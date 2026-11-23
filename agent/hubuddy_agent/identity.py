"""The stable identity of this computer: a random UUID, separate from every credential."""
import pathlib
import re
import uuid

from .privatestore import write_private

VALID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


def host_id(home: pathlib.Path) -> str:
    """Read the saved id, or create and save one. A read-only home still works, with an id for this run."""
    path = pathlib.Path(home) / "host-id"
    try:
        existing = path.read_text(encoding="utf-8").strip()
        if VALID.match(existing):
            return existing
    except OSError:
        pass
    fresh = str(uuid.uuid4())
    try:
        write_private(path, fresh + "\n")
        saved = path.read_text(encoding="utf-8").strip()
        return saved if VALID.match(saved) else fresh
    except OSError:
        return fresh
