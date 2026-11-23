"""Contribution calendar for a GitHub account (needs a personal access token, read from an env variable)."""
import datetime as dt
import json
import logging
import urllib.request

log = logging.getLogger("hubuddy.github")

QUERY = ("query { viewer { login contributionsCollection { contributionCalendar { totalContributions "
         "weeks { contributionDays { date contributionCount contributionLevel weekday } } } } } }")
LEVELS = {"NONE": 0, "FIRST_QUARTILE": 1, "SECOND_QUARTILE": 2, "THIRD_QUARTILE": 3, "FOURTH_QUARTILE": 4}


def post(token: str, opener=urllib.request.urlopen) -> dict:
    req = urllib.request.Request("https://api.github.com/graphql", data=json.dumps({"query": QUERY}).encode(), method="POST",
                                 headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json", "User-Agent": "Hubuddy/1.0"})
    with opener(req, timeout=20) as r:
        return json.loads(r.read(2_000_000))


def streaks(counts: list[int]) -> tuple[int, int]:
    """(current streak up to today or yesterday, longest streak)."""
    longest = run = 0
    for c in counts:
        run = run + 1 if c > 0 else 0
        longest = max(longest, run)
    end = len(counts) - 1 if counts and counts[-1] else len(counts) - 2
    cur = 0
    while end - cur >= 0 and counts[end - cur] > 0:
        cur += 1
    return cur, longest


def summarize(payload: dict) -> dict:
    viewer = payload["data"]["viewer"]
    cal = viewer["contributionsCollection"]["contributionCalendar"]
    days = [d for w in cal["weeks"] for d in w["contributionDays"]]
    counts = [int(d["contributionCount"]) for d in days]
    start_wday = int(days[0]["weekday"]) if days else 0
    today_wday = (len(days) - 1 + start_wday) % 7
    this_week = counts[len(counts) - 1 - today_wday:]
    last_week = counts[max(0, len(counts) - 8 - today_wday):len(counts) - 1 - today_wday]
    cur, longest = streaks(counts)
    return {"login": viewer["login"], "total": int(cal["totalContributions"]), "today": counts[-1] if counts else 0,
            "week": sum(this_week), "last_week": sum(last_week), "streak": cur, "longest": longest,
            "levels": "".join(str(LEVELS.get(d["contributionLevel"], 0)) for d in days), "start_wday": start_wday}


class GithubCollector:
    def __init__(self, token: str | None, poster=post):
        self.token, self.poster = token, poster
        self.data: dict | None = None

    @property
    def available(self) -> bool:
        return bool(self.token)

    def refresh(self):
        if not self.token:
            return
        try:
            self.data = summarize(self.poster(self.token))
        except Exception as e:
            log.warning("GitHub contributions unavailable: %s", e)
