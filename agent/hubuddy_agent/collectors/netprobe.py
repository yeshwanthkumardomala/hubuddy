"""Internet health: TCP-connect latency probes, jitter, loss, data counters and a simple speed test."""
import json
import logging
import pathlib
import socket
import statistics
import time
import urllib.request
from collections import deque
from datetime import date

log = logging.getLogger("hubuddy.net")

try:
    import psutil
except ImportError:
    psutil = None

DEFAULT_TARGETS = [("1.1.1.1", 443), ("8.8.8.8", 443)]
HISTORY = 60
SAVE_EVERY_S = 60


def probe(host: str, port: int, timeout: float = 1.5) -> float | None:
    """Milliseconds to open a TCP connection, or None on failure."""
    t = time.perf_counter()
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return (time.perf_counter() - t) * 1000
    except OSError:
        return None


class NetCollector:
    def __init__(self, targets=None, state_path: pathlib.Path | None = None, today=date.today, probe_fn=probe,
                 speed_down_url: str = "https://speed.cloudflare.com/__down?bytes=8000000",
                 speed_up_url: str = "https://speed.cloudflare.com/__up", clock=time.monotonic):
        self.targets = [tuple(t) for t in (targets or DEFAULT_TARGETS)]
        self.state_path = state_path
        self.today = today
        self.probe_fn = probe_fn
        self.speed_down_url, self.speed_up_url = speed_down_url, speed_up_url
        self.clock = clock
        self.samples: deque = deque(maxlen=HISTORY)
        self.testing = False
        self.speed: dict | None = None
        self._n = 0
        self._last_save = 0.0
        self._counters = psutil.net_io_counters() if psutil else None
        self.day, self.month = date.today().isoformat(), date.today().strftime("%Y-%m")
        self.today_bytes = self.month_bytes = 0
        self._load()

    # ---- probing ----------------------------------------------------------------
    def tick(self):
        """One probe (alternating targets) plus data-counter bookkeeping."""
        host, port = self.targets[self._n % len(self.targets)]
        self._n += 1
        ms = self.probe_fn(host, port)
        self.samples.append(None if ms is None else round(ms, 1))
        self._count_bytes()

    @property
    def online(self) -> bool:
        return any(s is not None for s in list(self.samples)[-3:])

    def message(self) -> dict:
        ok = [s for s in self.samples if s is not None]
        latency = ok[-1] if ok else None
        jitter = None
        if len(ok) >= 2:
            recent = ok[-10:]
            jitter = round(statistics.fmean(abs(a - b) for a, b in zip(recent, recent[1:])), 1)
        loss = round(100 * (len(self.samples) - len(ok)) / len(self.samples)) if self.samples else None
        return {"online": self.online, "latency_ms": latency, "jitter_ms": jitter, "loss_pct": loss,
                "hist": [-1 if s is None else round(s) for s in self.samples],
                "today_bytes": self.today_bytes, "month_bytes": self.month_bytes,
                "speed": self.speed, "testing": self.testing}

    # ---- data counters (persisted so they survive restarts) -------------------------
    def _count_bytes(self):
        if psutil is None:
            return
        now = psutil.net_io_counters()
        delta = max(0, (now.bytes_recv + now.bytes_sent) - (self._counters.bytes_recv + self._counters.bytes_sent))
        self._counters = now
        d, m = self.today().isoformat(), self.today().strftime("%Y-%m")
        if d != self.day:
            self.day, self.today_bytes = d, 0
        if m != self.month:
            self.month, self.month_bytes = m, 0
        self.today_bytes += delta
        self.month_bytes += delta
        if self.clock() - self._last_save >= SAVE_EVERY_S:
            self.save()

    def _load(self):
        if not self.state_path:
            return
        try:
            d = json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        if d.get("day") == self.day:
            self.today_bytes = int(d.get("today", 0))
        if d.get("month") == self.month:
            self.month_bytes = int(d.get("month_bytes", 0))

    def save(self):
        self._last_save = self.clock()
        if not self.state_path:
            return
        try:
            self.state_path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.state_path.with_suffix(".tmp")
            tmp.write_text(json.dumps({"day": self.day, "month": self.month, "today": self.today_bytes,
                                       "month_bytes": self.month_bytes}), encoding="utf-8")
            tmp.replace(self.state_path)
        except OSError as e:
            log.debug("could not save data counters: %s", e)

    # ---- speed test ---------------------------------------------------------------------
    def run_speedtest(self, opener=urllib.request.urlopen):
        """Blocking: download then upload a few MB. Skips when one is already running."""
        if self.testing:
            return
        self.testing = True
        try:
            started = time.perf_counter()
            with opener(urllib.request.Request(self.speed_down_url, headers={"User-Agent": "Hubuddy/1.0"}), timeout=20) as r:
                nbytes = 0
                while chunk := r.read(65536):
                    nbytes += len(chunk)
            down_s = max(time.perf_counter() - started, 0.001)
            payload = b"\0" * 2_000_000
            started = time.perf_counter()
            req = urllib.request.Request(self.speed_up_url, data=payload, method="POST", headers={"User-Agent": "Hubuddy/1.0"})
            with opener(req, timeout=20) as r:
                r.read()
            up_s = max(time.perf_counter() - started, 0.001)
            ok = [s for s in self.samples if s is not None]
            self.speed = {"down_mbps": round(nbytes * 8 / down_s / 1e6, 1), "up_mbps": round(len(payload) * 8 / up_s / 1e6, 1),
                          "ping_ms": round(statistics.median(ok), 1) if ok else None, "at": int(time.time())}
        except Exception as e:  # network errors of any kind just mean "no result"
            log.warning("speed test failed: %s", e)
        finally:
            self.testing = False
