"""A log of what happened on this PC: pairings, forgets, files, remote actions. JSON lines, rotated, never holds secrets."""
import json
import logging
import pathlib
import threading
import time

from .privatestore import private_dir

log = logging.getLogger("hubuddy.activity")
MAX_BYTES = 512 * 1024
KEEP = 3


class ActivityLog:
    def __init__(self, path: pathlib.Path, clock=time.time):
        self.path, self.clock = pathlib.Path(path), clock
        self._lock = threading.Lock()
        self.listeners = []
        private_dir(self.path.parent)

    def record(self, event: str, **fields) -> dict:
        entry = {"t": round(self.clock(), 3), "event": str(event)[:40]}
        for k, v in fields.items():
            if v is not None:
                entry[k] = str(v)[:200] if not isinstance(v, (int, float, bool)) else v
        with self._lock:
            try:
                if self.path.exists() and self.path.stat().st_size > MAX_BYTES:
                    for i in range(KEEP - 1, 0, -1):
                        older = self.path.with_name(f"{self.path.name}.{i}")
                        if older.exists():
                            older.replace(self.path.with_name(f"{self.path.name}.{i + 1}"))
                    self.path.replace(self.path.with_name(self.path.name + ".1"))
                with open(self.path, "a", encoding="utf-8") as f:
                    f.write(json.dumps(entry, separators=(",", ":")) + "\n")
            except OSError as e:
                log.debug("could not write the activity log: %s", e)
        for cb in list(self.listeners):
            try:
                cb(entry)
            except Exception:
                log.exception("activity listener failed")
        return entry

    def recent(self, n: int = 100) -> list[dict]:
        rows: list[dict] = []
        for p in [self.path] + [self.path.with_name(f"{self.path.name}.{i}") for i in range(1, KEEP)]:
            try:
                for line in reversed(p.read_text(encoding="utf-8").splitlines()):
                    try:
                        rows.append(json.loads(line))
                    except ValueError:
                        continue
                    if len(rows) >= n:
                        return rows
            except OSError:
                continue
        return rows
