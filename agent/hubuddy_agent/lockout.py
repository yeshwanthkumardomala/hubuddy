"""Failure tracking for pairing: a lock per client, plus a host-wide budget so hopping addresses does not help."""
import time

MAX_FAILS = 5
LOCK_MS = 60_000
GLOBAL_MAX_FAILS = 30
GLOBAL_WINDOW_MS = 10 * 60_000
GLOBAL_LOCK_MS = 10 * 60_000


class Lockout:
    def __init__(self, now=lambda: int(time.time() * 1000), max_fails=MAX_FAILS, lock_ms=LOCK_MS, global_max_fails=GLOBAL_MAX_FAILS,
                 global_window_ms=GLOBAL_WINDOW_MS, global_lock_ms=GLOBAL_LOCK_MS):
        self.now, self.max_fails, self.lock_ms = now, max_fails, lock_ms
        self.global_max, self.global_window, self.global_lock = global_max_fails, global_window_ms, global_lock_ms
        self._entries: dict[str, dict] = {}
        self._recent: list[int] = []
        self._global_until = 0

    def _prune(self, at):
        for k in list(self._entries):
            e = self._entries[k]
            if (e["until"] and e["until"] <= at) or (not e["until"] and at - e["last"] > self.lock_ms):
                del self._entries[k]

    def status(self, key: str) -> dict:
        at = self.now()
        e = self._entries.get(key)
        if e and e["until"] > at:
            return {"locked": True, "retryInMs": e["until"] - at, "global": False}
        if e and e["until"]:
            del self._entries[key]
        if self._global_until > at:
            return {"locked": True, "retryInMs": self._global_until - at, "global": True}
        return {"locked": False, "retryInMs": 0, "global": False}

    def record_failure(self, key: str) -> dict:
        at = self.now()
        self._prune(at)
        e = self._entries.setdefault(key, {"fails": 0, "last": at, "until": 0})
        e["fails"] += 1
        e["last"] = at
        if e["fails"] >= self.max_fails:
            e["until"] = at + self.lock_ms
        self._recent = [t for t in self._recent if at - t < self.global_window] + [at]
        if len(self._recent) >= self.global_max:
            self._global_until = at + self.global_lock
            self._recent = []
        return {"locked": bool(e["until"]) or self._global_until > at, "fails": e["fails"]}

    def reset_global(self) -> None:
        """PC-local recovery clears only the host-wide pause, never a client's own penalty."""
        self._recent = []
        self._global_until = 0

    def record_success(self, key: str) -> None:
        self._entries.pop(key, None)
