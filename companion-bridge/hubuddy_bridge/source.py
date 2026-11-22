"""Data source backed by the original DeskHub companion.

Rather than re-implementing PC stats, media, Claude Code usage, alerts, calendar,
internet and GitHub, this subclasses DeskHub's own `Companion` and captures the
JSON messages it would send to the ESP32 board. Commands from the phone are fed
to the companion's own `handle()` exactly as if they had come from the board.
"""
import asyncio
import json
import logging
import socket
import time

log = logging.getLogger("hubuddy.source")


def _clean(msg: dict | None) -> dict | None:
    if msg is None:
        return None
    return {k: v for k, v in msg.items() if k != "type"}


class DeskhubSource:
    def __init__(self, cfg: dict, also_board: bool = False):
        try:
            from deskhub.__main__ import Companion  # the DeskHub companion package
        except ImportError as e:  # pragma: no cover - depends on the PC setup
            raise SystemExit(
                "The DeskHub companion package is not importable. Run the bridge from the DeskHub\n"
                "companion folder with:  uv run python -m hubuddy_bridge  (see docs/deskhub/API_SETUP.md)\n"
                f"({e})")

        outer = self
        self.latest: dict[str, dict] = {}
        self.started = time.time()
        self.hostname = socket.gethostname()

        class PhoneCompanion(Companion):
            async def send(inner, msg):
                if isinstance(msg, dict) and msg.get("type"):
                    outer.latest[msg["type"]] = msg
                if also_board:
                    await Companion.send(inner, msg)

            async def session(inner):
                if also_board:
                    return await Companion.session(inner)
                # No ESP32 board: just keep the producers running.
                await asyncio.Event().wait()

        self.c = PhoneCompanion(cfg)

    async def run(self):
        await self.c.run()

    def tile_count(self) -> int:
        return len(self.c.tiles)

    def snapshot(self) -> dict:
        L = self.latest
        alert = L.get("alert")
        if alert is not None and alert.get("clear"):
            alert = None
        sessions = None
        if self.c.alerts is not None:
            now = time.time()
            sessions = {"sessions": [
                {"state": str(d.get("state", "")), "project": str(d.get("project") or "")[:40],
                 "age_s": int(now - float(d.get("updated", now)))}
                for d in list(self.c.alerts.sessions.values())[:8]]}
        tiles = [{"id": i, "label": t["label"], "icon": t["icon"], "color": t["color"]}
                 for i, t in enumerate(self.c.tiles)]
        lit = (L.get("tile_state") or {}).get("on", [])
        events = L.get("events")
        return {
            "server_time": int(time.time()),
            "pc": {"online": True, "asleep": bool(self.c.pc_asleep), "hostname": self.hostname,
                   "bridge_uptime_s": int(time.time() - self.started)},
            "stats": _clean(L.get("stats")),
            "claude": _clean(L.get("ai") or L.get("claude")),
            "alert": _clean(alert),
            "sessions_info": sessions,
            "media": _clean(L.get("media")),
            "calendar": {"events": events.get("events", []), "days": events.get("days", 7)} if events else None,
            "network": _clean(L.get("net")),
            "shortcuts": {"tiles": tiles, "on": lit},
            "github": _clean(L.get("github")),
            "printer": _clean(L.get("printer")),
            "steam": _clean(L.get("steam")),
        }

    async def command(self, kind: str, payload: dict):
        await self.c.handle(json.dumps({"type": kind, **payload}))
