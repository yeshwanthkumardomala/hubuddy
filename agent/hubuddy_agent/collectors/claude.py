"""Claude Code readings: token usage from its session logs, plan limits and session state from our hooks.

Everything here reads files that Claude Code itself writes (~/.claude/projects/**/*.jsonl) or that the
small scripts in claude_hook.py / claude_statusline.py write under ~/.hubuddy/claude. Nothing is scraped
from any account, and nothing is invented: a missing file simply means "not available".
"""
import datetime as dt
import json
import logging
import pathlib
import time

log = logging.getLogger("hubuddy.claude")

STATE_DIR = pathlib.Path.home() / ".hubuddy" / "claude"
PROJECTS = pathlib.Path.home() / ".claude" / "projects"
WINDOW = dt.timedelta(hours=5)
HISTORY_DAYS = 7
ALERT_FRESH_S = 15 * 60
SESSION_STALE_S = 6 * 3600


def family(model: str | None) -> str:
    m = (model or "").lower()
    for name in ("opus", "sonnet", "haiku", "fable"):
        if name in m:
            return name.title()
    return "Other"


class UsageReader:
    """Incrementally reads assistant-message token counts from Claude Code's JSONL session logs."""

    def __init__(self, root: pathlib.Path = PROJECTS):
        self.root = pathlib.Path(root)
        self._offsets: dict[pathlib.Path, int] = {}
        self._seen: set[str] = set()
        self.entries: list[tuple[dt.datetime, str, int]] = []  # (time, model family, total tokens)

    def scan(self):
        if not self.root.is_dir():
            return
        for path in self.root.glob("**/*.jsonl"):
            try:
                size = path.stat().st_size
            except OSError:
                continue
            start = self._offsets.get(path, 0)
            if size < start:
                start = 0  # file was replaced
            if size == start:
                continue
            try:
                with open(path, "rb") as f:
                    f.seek(start)
                    data = f.read()
            except OSError:
                continue
            cut = data.rfind(b"\n") + 1  # only whole lines; a half-written one is read next time
            self._offsets[path] = start + cut
            for line in data[:cut].splitlines():
                self._line(line)

    def _line(self, raw: bytes):
        if b'"usage"' not in raw:
            return
        try:
            d = json.loads(raw)
            msg = d["message"]
            u = msg["usage"]
            if d.get("type") != "assistant" or not isinstance(u, dict):
                return
            key = f"{msg.get('id')}:{d.get('requestId')}"
            if key in self._seen:
                return
            self._seen.add(key)
            ts = dt.datetime.fromisoformat(d["timestamp"].replace("Z", "+00:00"))
            total = sum(int(u.get(k) or 0) for k in ("input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"))
            self.entries.append((ts, family(msg.get("model")), total))
        except (ValueError, KeyError, TypeError, AttributeError):
            return

    @staticmethod
    def blocks(entries, now: dt.datetime):
        """Five-hour usage windows: one starts at the hour of the first message after the previous ends."""
        out, start, end, tokens, msgs = [], None, None, 0, 0
        for ts, _, tot in sorted(entries):
            if start is None or ts >= end:
                if start is not None:
                    out.append((start, end, tokens, msgs))
                start = ts.replace(minute=0, second=0, microsecond=0)
                end, tokens, msgs = start + WINDOW, 0, 0
            tokens += tot
            msgs += 1
        if start is not None:
            out.append((start, end, tokens, msgs))
        return out

    def summary(self, now: dt.datetime | None = None) -> dict | None:
        if not self.entries:
            return None
        now = now or dt.datetime.now(dt.timezone.utc)
        today = now.astimezone().date()
        days = [today - dt.timedelta(days=i) for i in range(HISTORY_DAYS - 1, -1, -1)]
        per_day = {d: 0 for d in days}
        tokens_today = msgs_today = last_hour = 0
        models: dict[str, int] = {}
        for ts, fam, tot in self.entries:
            day = ts.astimezone().date()
            if day in per_day:
                per_day[day] += tot
            if day == today:
                tokens_today += tot
                msgs_today += 1
                models[fam] = models.get(fam, 0) + tot
            if now - ts <= dt.timedelta(hours=1):
                last_hour += tot
        block = {"active": False}
        blocks = self.blocks(self.entries, now)
        if blocks and now < blocks[-1][1]:
            start, end, tokens, msgs = blocks[-1]
            block = {"active": True, "start": int(start.timestamp()), "reset": int(end.timestamp()), "tokens": tokens, "messages": msgs}
        last = max(ts for ts, _, _ in self.entries)
        return {"today": {"tokens": tokens_today, "messages": msgs_today}, "last_hour": last_hour, "block": block,
                "days": [per_day[d] for d in days],
                "models": [{"name": k, "tokens": v} for k, v in sorted(models.items(), key=lambda kv: -kv[1]) if v][:4],
                "last_active": int(last.timestamp())}


class StateStore:
    """Plan limits (written by the status-line script) and per-session state (written by the hook script)."""

    def __init__(self, root: pathlib.Path = STATE_DIR, clock=time.time):
        self.root, self.clock = pathlib.Path(root), clock
        self.dismissed: dict[str, float] = {}

    def limits(self) -> dict:
        try:
            data = json.loads((self.root / "status.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}
        out, now = {}, self.clock()
        for name in ("five_hour", "seven_day"):
            w = (data.get("rate_limits") or {}).get(name) or {}
            pct, reset = w.get("used_percentage"), w.get("resets_at")
            if isinstance(pct, (int, float)) and isinstance(reset, (int, float)) and reset > now:
                out[name] = {"pct": round(float(pct), 1), "reset": int(reset)}
        if out:
            out["updated"] = int(data.get("updated", 0))
        return out

    def sessions(self) -> dict[str, dict]:
        found, now = {}, self.clock()
        folder = self.root / "sessions"
        if not folder.is_dir():
            return found
        for p in folder.glob("*.json"):
            try:
                d = json.loads(p.read_text(encoding="utf-8"))
                age = now - float(d["updated"])
            except (OSError, ValueError, KeyError, TypeError):
                continue
            if age > SESSION_STALE_S:
                try:
                    p.unlink()
                except OSError:
                    pass
            elif isinstance(d, dict):
                found[p.stem] = d
        return found

    def alert(self) -> dict | None:
        """The newest session waiting for the user (permission or a reply) that was not dismissed."""
        best = None
        now = self.clock()
        for sid, d in self.sessions().items():
            if d.get("state") not in ("permission", "input") or now - float(d["updated"]) > ALERT_FRESH_S:
                continue
            if self.dismissed.get(sid) == d["updated"]:
                continue
            if best is None or d["updated"] > best[1]["updated"]:
                best = (sid, d)
        if best is None:
            return None
        sid, d = best
        kind = d["state"]
        title = {"permission": "Claude needs your permission", "input": "Claude is waiting for you"}[kind]
        return {"id": sid, "kind": kind, "title": title, "detail": str(d.get("message") or "")[:120], "project": str(d.get("project") or "")[:40]}

    def dismiss(self, sid: str):
        d = self.sessions().get(sid)
        if d:
            self.dismissed[sid] = d["updated"]

    def session_list(self) -> list[dict]:
        now = self.clock()
        return [{"state": str(d.get("state", "")), "project": str(d.get("project") or "")[:40], "age_s": int(now - float(d["updated"]))}
                for d in sorted(self.sessions().values(), key=lambda x: -float(x["updated"]))[:8]]
