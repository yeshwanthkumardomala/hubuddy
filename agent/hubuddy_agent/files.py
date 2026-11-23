"""File exchange. Phones upload into Received; the PC offers files from ToPhone for phones to download.

Names are sanitised, nothing outside these two folders is ever read or written, and uploads are streamed to a
temporary file and only published once complete and the right size.
"""
import asyncio
import os
import pathlib
import re
import shutil
import time

MAX_BYTES = 2 * 1024**3
RESERVE = 100 * 1024**2
CHUNK = 64 * 1024
RESERVED = {"con", "prn", "aux", "nul", *(f"com{i}" for i in range(1, 10)), *(f"lpt{i}" for i in range(1, 10))}


class FileError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status, self.message = status, message


def safe_name(name: str) -> str:
    """A file name that is safe to create on Windows and Linux: no path, no control or reserved characters."""
    name = re.split(r"[\\/]", str(name))[-1]
    name = re.sub(r'[<>:"|?*\x00-\x1f]', "_", name).strip(" .")
    stem = name.split(".")[0].lower()
    if stem in RESERVED:
        name = "_" + name
    if len(name) > 120:
        base, dot, ext = name.rpartition(".")
        name = (base[: 120 - len(ext) - 1] + "." + ext) if dot and len(ext) < 20 else name[:120]
    return name or "file"


def unique_path(folder: pathlib.Path, name: str) -> pathlib.Path:
    p = folder / name
    if not p.exists():
        return p
    stem, dot, ext = name.rpartition(".") if "." in name else (name, "", "")
    for n in range(1, 10_000):
        q = folder / (f"{stem} ({n}).{ext}" if dot else f"{name} ({n})")
        if not q.exists():
            return q
    raise FileError(409, "too many files with this name")


class Files:
    def __init__(self, root: pathlib.Path, max_bytes: int = MAX_BYTES):
        self.root, self.max_bytes = pathlib.Path(root), max_bytes
        self.received = self.root / "Received"
        self.to_phone = self.root / "ToPhone"
        for d in (self.received, self.to_phone):
            d.mkdir(parents=True, exist_ok=True)

    def _check_space(self, need: int):
        if need > self.max_bytes:
            raise FileError(413, f"file is larger than the {self.max_bytes // 1024**2} MB limit")
        if shutil.disk_usage(self.root).free < need + RESERVE:
            raise FileError(507, "not enough free disk space on the PC")

    async def receive(self, reader: asyncio.StreamReader, length: int, name: str, folder: pathlib.Path | None = None) -> pathlib.Path:
        """Stream `length` bytes from the connection into a new file named like `name`. Returns its final path."""
        folder = folder or self.received
        self._check_space(length)
        final = unique_path(folder, safe_name(name))
        part = final.with_name(final.name + ".part")
        got = 0
        try:
            with open(part, "wb") as f:
                while got < length:
                    chunk = await asyncio.wait_for(reader.read(min(CHUNK, length - got)), 30)
                    if not chunk:
                        raise FileError(400, "connection ended before the whole file arrived")
                    f.write(chunk)
                    got += len(chunk)
            part.replace(final)
        except BaseException:
            try:
                part.unlink()
            except OSError:
                pass
            raise
        return final

    def listing(self, folder: pathlib.Path, limit: int = 200) -> list[dict]:
        rows = []
        for p in folder.iterdir():
            if p.is_file() and not p.name.endswith(".part") and not p.is_symlink():
                st = p.stat()
                rows.append({"name": p.name, "size": st.st_size, "modified": int(st.st_mtime)})
        return sorted(rows, key=lambda r: -r["modified"])[:limit]

    def to_phone_list(self) -> list[dict]:
        return self.listing(self.to_phone)

    def received_list(self) -> list[dict]:
        return self.listing(self.received)

    def open_to_phone(self, name: str) -> pathlib.Path:
        """The file to send, only if `name` is exactly a regular file directly inside ToPhone."""
        if not isinstance(name, str) or name != safe_name(name) or not name or name.endswith(".part"):
            raise FileError(404, "no such file")
        p = self.to_phone / name
        if p.is_symlink() or not p.is_file() or p.resolve().parent != self.to_phone.resolve():
            raise FileError(404, "no such file")
        return p

    def remove_to_phone(self, name: str) -> bool:
        try:
            self.open_to_phone(name).unlink()
            return True
        except (FileError, OSError):
            return False
