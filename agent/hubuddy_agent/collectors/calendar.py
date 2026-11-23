"""Upcoming events from calendar feeds (any ICS URL or local .ics file), including repeating events."""
import datetime as dt
import logging
import pathlib
import urllib.request

log = logging.getLogger("hubuddy.calendar")

try:
    import icalendar
    import recurring_ical_events
except ImportError:
    icalendar = recurring_ical_events = None

MAX_EVENTS = 16


def read_feed(source: str, opener=urllib.request.urlopen) -> bytes:
    if source.startswith(("http://", "https://", "webcal://")):
        url = "https://" + source[len("webcal://"):] if source.startswith("webcal://") else source
        with opener(urllib.request.Request(url, headers={"User-Agent": "Hubuddy/1.0"}), timeout=15) as r:
            return r.read(5_000_000)
    return pathlib.Path(source).expanduser().read_bytes()


def _epoch(v, tz) -> tuple[int, bool]:
    """(unix seconds, is_all_day) for an iCalendar date or datetime."""
    if isinstance(v, dt.datetime):
        if v.tzinfo is None:
            v = v.replace(tzinfo=tz)
        return int(v.timestamp()), False
    return int(dt.datetime.combine(v, dt.time.min, tzinfo=tz).timestamp()), True


def events_from_ics(data: bytes, name: str, color: str, start: dt.datetime, end: dt.datetime) -> list[dict]:
    cal = icalendar.Calendar.from_ical(data)
    out = []
    for ev in recurring_ical_events.of(cal).between(start, end):
        s, all_day = _epoch(ev.get("DTSTART").dt, start.tzinfo)
        raw_end = ev.get("DTEND")
        e = _epoch(raw_end.dt, start.tzinfo)[0] if raw_end else s
        out.append({"title": str(ev.get("SUMMARY") or "(untitled)")[:80], "loc": str(ev.get("LOCATION") or "")[:60],
                    "cal": name[:24], "start": s, "end": e, "all_day": all_day, "color": color})
    return out


class CalendarCollector:
    def __init__(self, feeds: list[dict], days: int = 7, reader=read_feed):
        self.feeds = [f for f in feeds if isinstance(f, dict) and f.get("url")]
        self.days, self.reader = max(1, min(int(days), 60)), reader
        self.events: list[dict] | None = None

    @property
    def available(self) -> bool:
        return bool(self.feeds) and icalendar is not None

    def refresh(self, now: dt.datetime | None = None) -> None:
        """Blocking fetch of every feed; a feed that fails is skipped and logged."""
        if not self.available:
            return
        now = now or dt.datetime.now().astimezone()
        end = now + dt.timedelta(days=self.days)
        found = []
        for f in self.feeds:
            try:
                found += events_from_ics(self.reader(str(f["url"])), str(f.get("name") or "Calendar"), str(f.get("color") or "#2EC4B6"), now, end)
            except Exception as e:
                log.warning("calendar feed %r failed: %s", f.get("name") or f["url"], e)
        found = [e for e in found if e["end"] >= now.timestamp()]
        self.events = sorted(found, key=lambda e: (e["start"], e["title"]))[:MAX_EVENTS]

    def message(self) -> dict | None:
        return None if self.events is None else {"events": self.events, "days": self.days}
