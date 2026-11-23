"""The agent: runs the collectors and answers the phone's API with their latest readings."""
import asyncio
import logging
import os
import socket
import time

from .collectors.calendar import CalendarCollector
from .collectors.claude import StateStore, UsageReader
from .collectors.github import GithubCollector
from .collectors.media import MediaCollector
from .collectors.netprobe import NetCollector
from .collectors.shortcuts import Shortcuts
from .collectors.system import SystemCollector
from .config import HOME_DIR

log = logging.getLogger("hubuddy.agent")

CALENDAR_EVERY_S = 600
GITHUB_EVERY_S = 900
SPEEDTEST_MIN_GAP_S = 120


class Agent:
    def __init__(self, cfg: dict, home=HOME_DIR, runner=None, media=None):
        self.cfg = cfg
        self.started = time.time()
        self.hostname = socket.gethostname()
        self.system = SystemCollector()
        self.claude_usage = UsageReader() if cfg.get("claude", {}).get("enabled", True) else None
        self.claude_state = StateStore(home / "claude") if self.claude_usage else None
        self.media = media or MediaCollector()
        cal = cfg.get("calendar", {})
        self.calendar = CalendarCollector(cal.get("feeds", []), cal.get("days", 7))
        net = cfg.get("internet", {})
        self.net = NetCollector(net.get("targets"), home / "net.json") if net.get("enabled", True) else None
        gh = cfg.get("github", {})
        self.github = GithubCollector(os.environ.get(gh.get("token_env", "HUBUDDY_GITHUB_TOKEN")))
        self.shortcuts = Shortcuts(cfg.get("shortcuts", []), runner)
        self.latest = {"stats": None, "claude": None, "media": None}
        self._speedtest_at = -SPEEDTEST_MIN_GAP_S

    # ---- phone API ----------------------------------------------------------------
    def button_count(self) -> int:
        return len(self.shortcuts)

    def snapshot(self) -> dict:
        claude = alert = sessions = None
        if self.claude_usage:
            usage = self.claude_usage.summary()
            limits = self.claude_state.limits()
            if usage or limits:
                claude = {**(usage or {}), "limits": limits}
            alert = self.claude_state.alert()
            listed = self.claude_state.session_list()
            sessions = {"sessions": listed} if listed else None
        return {
            "server_time": int(time.time()),
            "pc": {"online": True, "hostname": self.hostname, "agent_uptime_s": int(time.time() - self.started)},
            "stats": self.latest["stats"],
            "claude": claude,
            "alert": alert,
            "sessions_info": sessions,
            "media": self.latest["media"],
            "calendar": self.calendar.message(),
            "network": self.net.message() if self.net else None,
            "shortcuts": {"buttons": self.shortcuts.listing()},
            "github": self.github.data,
        }

    async def command(self, kind: str, payload: dict):
        if kind == "media":
            if self.media.available:
                await self.media.command(payload["cmd"])
        elif kind == "shortcut":
            await asyncio.to_thread(self.shortcuts.run, int(payload["id"]))
        elif kind == "alert_action":
            if self.claude_state and payload.get("action") == "dismiss":
                self.claude_state.dismiss(str(payload["id"]))
        elif kind == "speedtest":
            now = time.monotonic()
            if self.net and not self.net.testing and now - self._speedtest_at >= SPEEDTEST_MIN_GAP_S:
                self._speedtest_at = now
                asyncio.create_task(asyncio.to_thread(self.net.run_speedtest))

    # ---- collection loops ---------------------------------------------------------------
    async def _loop(self, name: str, every: float, work):
        while True:
            try:
                await work()
            except Exception:
                log.exception("%s collector failed; retrying", name)
            await asyncio.sleep(every)

    async def run(self):
        async def stats():
            self.latest["stats"] = await asyncio.to_thread(self.system.sample)

        async def claude():
            await asyncio.to_thread(self.claude_usage.scan)

        async def media():
            self.latest["media"] = await self.media.snapshot()

        async def net():
            await asyncio.to_thread(self.net.tick)

        loops = [self._loop("stats", 1, stats)]
        if self.claude_usage:
            loops.append(self._loop("claude", 5, claude))
        if self.media.available:
            loops.append(self._loop("media", 1, media))
        if self.net:
            loops.append(self._loop("network", 2, net))
        if self.calendar.available:
            loops.append(self._loop("calendar", CALENDAR_EVERY_S, lambda: asyncio.to_thread(self.calendar.refresh)))
        if self.github.available:
            loops.append(self._loop("github", GITHUB_EVERY_S, lambda: asyncio.to_thread(self.github.refresh)))
        await asyncio.gather(*loops)
