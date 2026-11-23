"""Things the PC sends to a phone: text and links. Phones fetch them while the app is open and acknowledge them."""
import json
import pathlib
import re
import secrets
import time

from .privatestore import write_private_json

MAX_ITEMS = 50
MAX_TEXT = 20_000
TTL_S = 7 * 24 * 3600
LINK = re.compile(r"^https?://[^\s]{3,2000}$", re.I)


class Inbox:
    def __init__(self, path: pathlib.Path, clock=time.time):
        self.path, self.clock = pathlib.Path(path), clock
        self.items: list[dict] = []
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
            self.items = [i for i in data.get("items", []) if isinstance(i, dict) and "id" in i][-MAX_ITEMS:]
        except (OSError, ValueError, AttributeError):
            pass

    def _save(self):
        write_private_json(self.path, {"items": self.items})

    def add(self, kind: str, text: str, to: str = "all") -> dict:
        text = str(text).strip()
        if kind not in ("text", "link"):
            raise ValueError("kind must be text or link")
        if not text or len(text) > MAX_TEXT:
            raise ValueError("text is empty or too long")
        if kind == "link" and not LINK.match(text):
            raise ValueError("a link must start with http:// or https://")
        item = {"id": secrets.token_hex(8), "kind": kind, "text": text, "created": int(self.clock()), "to": str(to)[:16], "done": []}
        self.items = (self.items + [item])[-MAX_ITEMS:]
        self._save()
        return item

    def for_phone(self, sid: str) -> list[dict]:
        now = self.clock()
        return [{k: i[k] for k in ("id", "kind", "text", "created")} for i in self.items
                if i["to"] in ("all", sid) and sid not in i["done"] and now - i["created"] < TTL_S]

    def ack(self, sid: str, ids: list) -> int:
        n = 0
        for i in self.items:
            if i["id"] in ids and sid not in i["done"]:
                i["done"].append(sid)
                n += 1
        if n:
            self._save()
        return n

    def prune(self, live_sessions: set[str]) -> None:
        """Drop items every current phone has seen, and old ones."""
        now = self.clock()
        keep = []
        for i in self.items:
            if now - i["created"] > TTL_S:
                continue
            targets = live_sessions if i["to"] == "all" else {i["to"]} & live_sessions
            if targets and targets <= set(i["done"]):
                continue
            keep.append(i)
        if len(keep) != len(self.items):
            self.items = keep
            self._save()
