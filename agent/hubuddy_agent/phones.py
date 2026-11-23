"""What the PC knows about each paired phone beyond its token: the status the phone last reported."""
import time

KEYS = {"battery": int, "charging": bool, "network": str, "model": str}


class PhoneStatus:
    def __init__(self, clock=time.time):
        self.clock = clock
        self._by_session: dict[str, dict] = {}

    def update(self, sid: str, data: dict) -> dict:
        clean = {}
        for key, typ in KEYS.items():
            v = data.get(key)
            if v is None:
                continue
            if typ is int:
                if isinstance(v, bool) or not isinstance(v, (int, float)):
                    continue
                v = max(0, min(100, int(v)))
            elif typ is bool:
                if not isinstance(v, bool):
                    continue
            else:
                v = "".join(c for c in str(v) if c.isprintable())[:40]
            clean[key] = v
        clean["at"] = int(self.clock())
        self._by_session[sid] = clean
        return clean

    def get(self, sid: str) -> dict:
        return dict(self._by_session.get(sid, {}))

    def forget(self, sid: str) -> None:
        self._by_session.pop(sid, None)
