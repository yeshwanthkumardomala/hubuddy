"""Pick the TCP port: the saved one if free, else the default, else the next free one nearby (and remember it)."""
import pathlib
import socket

DEFAULT = 8765
SPAN = 35


def _free(port: int, host: str) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        try:
            s.bind((host, port))
            return True
        except OSError:
            return False


def choose(home: pathlib.Path, preferred: int | None = None, host: str = "0.0.0.0") -> int:
    saved = None
    try:
        saved = int((pathlib.Path(home) / "port").read_text().strip())
    except (OSError, ValueError):
        pass
    for candidate in [p for p in (preferred, saved) if p] + list(range(DEFAULT, DEFAULT + SPAN)):
        if 1024 <= candidate <= 65535 and _free(candidate, host):
            try:
                (pathlib.Path(home) / "port").write_text(str(candidate))
            except OSError:
                pass
            return candidate
    raise OSError("No free port found for Hubuddy near 8765. Close the program using it or choose a port.")
