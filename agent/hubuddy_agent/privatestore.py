"""Small helpers for files that hold secrets: private directory, atomic write, owner-only permissions."""
import json
import os
import pathlib


def private_dir(path: pathlib.Path) -> pathlib.Path:
    path = pathlib.Path(path)
    path.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path, 0o700)  # no-op on Windows, where the profile folder is already per-user
    except OSError:
        pass
    return path


def write_private(path: pathlib.Path, data: bytes | str) -> None:
    """Write atomically (temp file, then rename) with owner-only permissions."""
    path = pathlib.Path(path)
    private_dir(path.parent)
    tmp = path.with_name(path.name + ".tmp")
    raw = data.encode("utf-8") if isinstance(data, str) else data
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(raw)
    os.replace(tmp, path)


def write_private_json(path: pathlib.Path, obj) -> None:
    write_private(path, json.dumps(obj, indent=2))
