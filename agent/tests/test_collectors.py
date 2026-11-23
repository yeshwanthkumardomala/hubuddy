import asyncio
import datetime as dt
import json
import pathlib
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from types import SimpleNamespace

from hubuddy_agent import claude_hook, claude_setup, claude_statusline
from hubuddy_agent.collectors import calendar as cal
from hubuddy_agent.collectors import github, netprobe, shortcuts, system
from hubuddy_agent.collectors.claude import StateStore, UsageReader
from hubuddy_agent.collectors.media import MediaCollector


def tmp() -> pathlib.Path:
    d = tempfile.TemporaryDirectory()
    tmp.keep.append(d)
    return pathlib.Path(d.name)


tmp.keep = []


class SystemTests(unittest.TestCase):
    @unittest.skipUnless(system.psutil, "psutil not installed")
    def test_sample_has_real_readings(self):
        c = system.SystemCollector(gpu_reader=lambda: None)
        s = c.sample()
        self.assertTrue(0 <= s["cpu"] <= 100)
        self.assertGreater(s["ram_total"], 0)
        self.assertLessEqual(s["ram_used"], s["ram_total"] + 0.1)
        self.assertGreaterEqual(s["net_down"], 0)
        self.assertNotIn("gpu", s)  # no GPU reader result -> never invented

    def test_nvidia_parsing_and_absence(self):
        ok = lambda *a, **k: SimpleNamespace(stdout="NVIDIA GeForce RTX 3060, 22, 54, 1843, 6144\n")
        r = system.read_nvidia(ok)
        self.assertEqual((r["gpu"], r["gpu_temp"], r["vram_used"], r["vram_total"]), (22, 54, 1.8, 6.0))

        def missing(*a, **k):
            raise FileNotFoundError("nvidia-smi")
        self.assertIsNone(system.read_nvidia(missing))
        garbage = lambda *a, **k: SimpleNamespace(stdout="oops")
        self.assertIsNone(system.read_nvidia(garbage))

    @unittest.skipUnless(system.psutil, "psutil not installed")
    def test_gpu_is_not_polled_every_second(self):
        calls, t = [], [0.0]

        def reader():
            calls.append(1)
            return {"gpu": 5}
        c = system.SystemCollector(gpu_reader=reader, clock=lambda: t[0])
        for t[0] in (0, 1, 2, 3, 6):
            c.sample()
        self.assertEqual(len(calls), 2)


def write_log(root: pathlib.Path, rows):
    p = root / "proj"
    p.mkdir(parents=True, exist_ok=True)
    with open(p / "s.jsonl", "a", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r) + "\n")


def row(ts, tokens_in, tokens_out, model="claude-opus-4", mid="m1", req="r1"):
    return {"type": "assistant", "timestamp": ts.strftime("%Y-%m-%dT%H:%M:%S.000Z"), "requestId": req,
            "message": {"id": mid, "model": model, "usage": {"input_tokens": tokens_in, "output_tokens": tokens_out,
                                                              "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0}}}


class ClaudeTests(unittest.TestCase):
    def test_usage_totals_dedupe_models_and_blocks(self):
        root = tmp()
        now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
        write_log(root, [row(now - dt.timedelta(minutes=30), 100, 50, mid="a"), row(now - dt.timedelta(minutes=10), 200, 100, "claude-sonnet-4", mid="b"),
                         row(now - dt.timedelta(minutes=10), 200, 100, "claude-sonnet-4", mid="b"),  # duplicate line
                         {"type": "user", "message": {}}, row(now - dt.timedelta(days=2), 10, 10, mid="old", req="r9")])
        u = UsageReader(root)
        u.scan()
        s = u.summary(now)
        self.assertEqual(s["last_hour"], 450)
        self.assertEqual(s["models"][0]["name"], "Opus" if 150 > 300 else "Sonnet")  # sonnet 300 > opus 150
        self.assertEqual(len(s["days"]), 7)
        self.assertTrue(s["block"]["active"])
        self.assertEqual(s["block"]["tokens"], 450)
        self.assertEqual(s["block"]["reset"] - s["block"]["start"], 5 * 3600)
        self.assertGreaterEqual(s["today"]["tokens"], 0)
        u.scan()  # nothing new is read twice
        self.assertEqual(u.summary(now)["last_hour"], 450)

    def test_incremental_read_and_half_written_line(self):
        root = tmp()
        now = dt.datetime.now(dt.timezone.utc)
        write_log(root, [row(now, 1, 1, mid="a")])
        u = UsageReader(root)
        u.scan()
        with open(root / "proj" / "s.jsonl", "a") as f:
            f.write(json.dumps(row(now, 5, 5, mid="b"))[:30])  # a line still being written
        u.scan()
        self.assertEqual(len(u.entries), 1)
        with open(root / "proj" / "s.jsonl", "a") as f:
            f.write(json.dumps(row(now, 5, 5, mid="b"))[30:] + "\n")
        u.scan()
        self.assertEqual(len(u.entries), 2)

    def test_nothing_when_no_logs(self):
        u = UsageReader(tmp() / "missing")
        u.scan()
        self.assertIsNone(u.summary())

    def test_limits_and_expired_windows(self):
        root = tmp()
        store = StateStore(root, clock=lambda: 1000.0)
        self.assertEqual(store.limits(), {})
        (root / "status.json").write_text(json.dumps({"updated": 990, "rate_limits": {
            "five_hour": {"used_percentage": 46.0, "resets_at": 5000}, "seven_day": {"used_percentage": 20, "resets_at": 500}}}))
        lim = store.limits()
        self.assertEqual(lim["five_hour"], {"pct": 46.0, "reset": 5000})
        self.assertNotIn("seven_day", lim)  # already reset: dropped, not shown stale

    def test_hook_states_alert_and_dismiss(self):
        root = tmp()
        now = [1000.0]
        store = StateStore(root, clock=lambda: now[0])
        h = lambda ev, **kw: claude_hook.handle({"session_id": "s-1", "cwd": "C:\\work\\hubuddy", "hook_event_name": ev, **kw}, root, lambda: now[0])
        h("UserPromptSubmit")
        self.assertIsNone(store.alert())
        self.assertEqual(store.session_list()[0]["state"], "running")
        h("Notification", notification_type="permission_prompt", message="Claude needs your permission to use Bash")
        a = store.alert()
        self.assertEqual((a["kind"], a["project"], a["id"]), ("permission", "hubuddy", "s-1"))
        self.assertIn("Bash", a["detail"])
        store.dismiss("s-1")
        self.assertIsNone(store.alert())
        now[0] += 5
        h("Notification", message="Claude is waiting for your input")
        self.assertEqual(store.alert()["kind"], "input")  # a new event shows up again after a dismissal
        h("Stop")
        self.assertIsNone(store.alert())
        h("SessionEnd")
        self.assertEqual(store.session_list(), [])

    def test_hook_ignores_unknown_and_junk(self):
        root = tmp()
        claude_hook.handle({"session_id": "../../evil", "hook_event_name": "Stop"}, root)
        self.assertEqual([p.name for p in (root / "sessions").glob("*.json")], ["evil.json"])  # path characters stripped
        claude_hook.handle({"hook_event_name": "Stop"}, root)  # no session id
        claude_hook.handle({"session_id": "x", "hook_event_name": "SomethingNew"}, root)
        self.assertEqual(len(list((root / "sessions").glob("*.json"))), 1)

    def test_old_alert_expires(self):
        root = tmp()
        now = [1000.0]
        store = StateStore(root, clock=lambda: now[0])
        claude_hook.handle({"session_id": "s", "hook_event_name": "Notification", "message": "needs permission"}, root, lambda: now[0])
        self.assertIsNotNone(store.alert())
        now[0] += 16 * 60
        self.assertIsNone(store.alert())

    def test_statusline_records_limits_and_chains(self):
        root = tmp()
        claude_statusline.record({"rate_limits": {"five_hour": {"used_percentage": 12, "resets_at": 9999999999, "junk": 1}, "other": {}}}, root, lambda: 5)
        data = json.loads((root / "status.json").read_text())
        self.assertEqual(list(data["rate_limits"]), ["five_hour"])
        self.assertEqual(data["updated"], 5)
        claude_statusline.record({"no": "limits"}, root)  # leaves the file alone
        # The script itself: records, then chains the previous command with the same stdin.
        env = {**__import__("os").environ, "HUBUDDY_CLAUDE_DIR": str(root)}
        script = pathlib.Path(claude_statusline.__file__)
        out = subprocess.run([sys.executable, str(script), "--then", f'"{sys.executable}" -c "import sys;print(\'old:\'+sys.stdin.read()[:8])"'],
                             input='{"rate_limits": {}, "x": 1}', capture_output=True, text=True, env=env, timeout=20).stdout
        self.assertIn("old:", out)
        plain = subprocess.run([sys.executable, str(script)], input='{"model": {"display_name": "Opus"}}', capture_output=True, text=True, env=env, timeout=20).stdout
        self.assertEqual(plain, "Hubuddy · Opus")

    def test_setup_apply_is_safe_and_reversible(self):
        d = tmp()
        path = d / "settings.json"
        original = {"theme": "dark", "hooks": {"Stop": [{"hooks": [{"type": "command", "command": "echo mine"}]}]},
                    "statusLine": {"type": "command", "command": "my-status --fancy"}}
        path.write_text(json.dumps(original))
        self.assertEqual(claude_setup.main(["show", "--settings", str(path)]), 0)
        self.assertEqual(json.loads(path.read_text()), original)  # show changes nothing
        self.assertEqual(claude_setup.main(["apply", "--settings", str(path)]), 0)
        new = json.loads(path.read_text())
        self.assertTrue((d / "settings.json.hubuddy-backup").is_file())
        self.assertEqual(new["theme"], "dark")
        self.assertEqual(new["hooks"]["Stop"][0]["hooks"][0]["command"], "echo mine")  # your hook is kept
        self.assertEqual(len(new["hooks"]["Stop"]), 2)
        self.assertIn("claude_statusline.py", new["statusLine"]["command"])
        self.assertIn("my-status --fancy", new["statusLine"]["command"])  # old status line is chained
        for ev in ("Notification", "Stop", "UserPromptSubmit", "PreToolUse", "PostToolUse", "SessionEnd"):
            self.assertIn(ev, new["hooks"])
        before = path.read_text()
        claude_setup.main(["apply", "--settings", str(path)])
        self.assertEqual(path.read_text(), before)  # applying twice does not duplicate
        claude_setup.main(["remove", "--settings", str(path)])
        self.assertEqual(json.loads(path.read_text()), original)  # back to exactly what you had

    def test_setup_on_missing_file_and_bad_json(self):
        d = tmp()
        p = d / "new" / "settings.json"
        self.assertEqual(claude_setup.main(["apply", "--settings", str(p)]), 0)
        self.assertIn("hooks", json.loads(p.read_text()))
        bad = d / "bad.json"
        bad.write_text("{not json")
        self.assertEqual(claude_setup.main(["apply", "--settings", str(bad)]), 1)
        self.assertEqual(bad.read_text(), "{not json")


ICS = b"""BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//test//EN
BEGIN:VEVENT
UID:1
DTSTART:20260105T090000Z
DTEND:20260105T093000Z
RRULE:FREQ=WEEKLY;BYDAY=MO
SUMMARY:Stand-up
LOCATION:Room 4
END:VEVENT
BEGIN:VEVENT
UID:2
DTSTART;VALUE=DATE:20260112
DTEND;VALUE=DATE:20260113
SUMMARY:Holiday
END:VEVENT
END:VCALENDAR
"""


@unittest.skipUnless(cal.icalendar, "icalendar not installed")
class CalendarTests(unittest.TestCase):
    def test_repeating_and_all_day_events(self):
        now = dt.datetime(2026, 1, 10, 8, 0, tzinfo=dt.timezone.utc)
        c = cal.CalendarCollector([{"name": "Work", "url": "x", "color": "#123456"}], days=14, reader=lambda u: ICS)
        c.refresh(now)
        titles = [(e["title"], e["all_day"]) for e in c.events]
        self.assertEqual(titles, [("Holiday", True), ("Stand-up", False), ("Stand-up", False)])  # Jan 12, Jan 12 09:00, Jan 19
        self.assertEqual(c.events[1]["loc"], "Room 4")
        self.assertEqual(c.events[0]["color"], "#123456")
        self.assertEqual(c.message()["days"], 14)

    def test_failing_feed_is_skipped(self):
        def reader(u):
            if u == "bad":
                raise OSError("down")
            return ICS
        c = cal.CalendarCollector([{"url": "bad"}, {"url": "good", "name": "G"}], reader=reader)
        c.refresh(dt.datetime(2026, 1, 10, tzinfo=dt.timezone.utc))
        self.assertTrue(c.events)

    def test_no_feeds_means_unavailable(self):
        c = cal.CalendarCollector([])
        c.refresh()
        self.assertIsNone(c.message())
        self.assertFalse(c.available)

    def test_local_file_feed(self):
        f = tmp() / "a.ics"
        f.write_bytes(ICS)
        self.assertEqual(cal.read_feed(str(f)), ICS)


class NetTests(unittest.TestCase):
    def test_latency_jitter_loss_and_online(self):
        seq = iter([10.0, 14.0, None, 12.0, None, None, None])
        n = netprobe.NetCollector(probe_fn=lambda h, p: next(seq), state_path=None)
        for _ in range(4):
            n.tick()
        m = n.message()
        self.assertEqual((m["latency_ms"], m["loss_pct"], m["online"]), (12.0, 25, True))
        self.assertEqual(m["hist"], [10, 14, -1, 12])
        self.assertAlmostEqual(m["jitter_ms"], 3.0, places=1)
        for _ in range(3):
            n.tick()
        self.assertFalse(n.message()["online"])

    def test_real_tcp_probe(self):
        import socket
        srv = socket.socket()
        srv.bind(("127.0.0.1", 0))
        srv.listen(1)
        ms = netprobe.probe("127.0.0.1", srv.getsockname()[1])
        self.assertIsNotNone(ms)
        port = srv.getsockname()[1]
        srv.close()
        self.assertIsNone(netprobe.probe("127.0.0.1", port, timeout=0.5))

    def test_counters_persist(self):
        p = tmp() / "net.json"
        n = netprobe.NetCollector(probe_fn=lambda h, pt: 1.0, state_path=p)
        n.today_bytes, n.month_bytes = 1234, 5678
        n.save()
        again = netprobe.NetCollector(probe_fn=lambda h, pt: 1.0, state_path=p)
        self.assertEqual((again.today_bytes, again.month_bytes), (1234, 5678))
        p.write_text("garbage")
        netprobe.NetCollector(state_path=p)  # corrupt state never crashes

    def test_speedtest_against_local_server(self):
        class H(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200); self.send_header("Content-Length", "500000"); self.end_headers(); self.wfile.write(b"x" * 500000)

            def do_POST(self):
                self.rfile.read(int(self.headers["Content-Length"])); self.send_response(200); self.send_header("Content-Length", "0"); self.end_headers()

            def log_message(self, *a):
                pass
        srv = HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{srv.server_port}"
        n = netprobe.NetCollector(probe_fn=lambda h, p: 5.0, speed_down_url=base + "/d", speed_up_url=base + "/u")
        n.tick()
        n.run_speedtest()
        srv.shutdown()
        self.assertGreater(n.speed["down_mbps"], 0)
        self.assertGreater(n.speed["up_mbps"], 0)
        self.assertFalse(n.testing)


class GithubTests(unittest.TestCase):
    def payload(self, counts, start_wday=2):
        levels = ["NONE", "FIRST_QUARTILE", "SECOND_QUARTILE", "THIRD_QUARTILE", "FOURTH_QUARTILE"]
        days = [{"date": f"2026-01-{i + 1:02d}", "contributionCount": c, "contributionLevel": levels[min(c, 4)], "weekday": (start_wday + i) % 7}
                for i, c in enumerate(counts)]
        weeks = [{"contributionDays": days[i:i + 7]} for i in range(0, len(days), 7)]
        return {"data": {"viewer": {"login": "me", "contributionsCollection": {"contributionCalendar": {"totalContributions": sum(counts), "weeks": weeks}}}}}

    def test_summary(self):
        s = github.summarize(self.payload([0, 1, 2, 0, 3, 4, 5, 0, 1, 1, 2]))
        self.assertEqual((s["login"], s["total"], s["today"], s["streak"], s["longest"]), ("me", 19, 2, 3, 3))
        self.assertEqual(s["levels"], "01203440112"); self.assertEqual(s["start_wday"], 2)

    def test_streak_through_empty_today_and_no_data(self):
        self.assertEqual(github.streaks([1, 1, 0]), (2, 2))
        self.assertEqual(github.streaks([]), (0, 0))

    def test_collector_needs_token_and_survives_errors(self):
        self.assertFalse(github.GithubCollector(None).available)
        c = github.GithubCollector("t", poster=lambda t: (_ for _ in ()).throw(OSError("offline")))
        c.refresh()
        self.assertIsNone(c.data)
        c2 = github.GithubCollector("t", poster=lambda t: self.payload([1, 2, 3]))
        c2.refresh()
        self.assertEqual(c2.data["total"], 6)


class FakeRunner:
    def __init__(self):
        self.calls = []

    def open_target(self, t): self.calls.append(("open", t))
    def run_command(self, t): self.calls.append(("run", t))
    def press_keys(self, c): self.calls.append(("keys", c))
    def lock(self): self.calls.append(("lock",))


class ShortcutTests(unittest.TestCase):
    CFG = [{"label": "Lock", "action": "lock"}, {"label": "Docs", "action": "open", "target": "https://example.org"},
           {"label": "Run", "action": "run", "target": "notepad.exe"}, {"label": "Mute", "action": "volume", "target": "mute"},
           {"label": "Shot", "action": "keys", "target": "Win+Shift+S"}]

    def test_listing_and_dispatch(self):
        r = FakeRunner()
        s = shortcuts.Shortcuts(self.CFG, r)
        self.assertEqual([b["label"] for b in s.listing()], ["Lock", "Docs", "Run", "Mute", "Shot"])
        for i in range(5):
            s.run(i)
        self.assertEqual(r.calls, [("lock",), ("open", "https://example.org"), ("run", "notepad.exe"), ("keys", [0xAD]), ("keys", [0x5B, 0x10, ord("S")])])

    def test_config_is_validated(self):
        bad = [{"label": "x", "action": "format-disk"}, {"label": "", "action": "lock"}, {"label": "x", "action": "run"},
               {"label": "x", "action": "keys", "target": "ctrl+banana"}, {"label": "x", "action": "volume", "target": "max"}]
        for b in bad:
            with self.assertRaises(shortcuts.ConfigError, msg=str(b)):
                shortcuts.load([b])

    def test_commands_never_use_a_shell(self):
        import inspect
        src = inspect.getsource(shortcuts.Runner.run_command)
        self.assertIn("shell=False", src)

    def test_button_limit(self):
        many = [{"label": f"b{i}", "action": "lock"} for i in range(40)]
        self.assertEqual(len(shortcuts.Shortcuts(many, FakeRunner())), shortcuts.MAX_BUTTONS)


class MediaTests(unittest.TestCase):
    def test_unavailable_without_platform_support(self):
        m = MediaCollector()
        if sys.platform != "win32":
            self.assertFalse(m.available)
            self.assertIsNone(asyncio.run(m.snapshot()))

    def test_maps_a_session_and_sends_commands(self):
        class Span:
            def __init__(self, s): self.s = s
            def total_seconds(self): return self.s

        class Session:
            source_app_user_model_id = "Spotify.exe"
            sent = []

            async def try_get_media_properties_async(self):
                return SimpleNamespace(title="Song", artist="Band", album_title="LP")

            def get_playback_info(self): return SimpleNamespace(playback_status=4)
            def get_timeline_properties(self): return SimpleNamespace(position=Span(30), end_time=Span(200))
            async def try_toggle_play_pause_async(self): self.sent.append("toggle")
            async def try_skip_next_async(self): self.sent.append("next")
            async def try_skip_previous_async(self): self.sent.append("prev")

        sess = Session()

        async def factory():
            return SimpleNamespace(get_current_session=lambda: sess)

        m = MediaCollector(factory)
        snap = asyncio.run(m.snapshot())
        self.assertEqual((snap["title"], snap["playing"], snap["pos"], snap["dur"], snap["source"]), ("Song", True, 30, 200, "Spotify"))

        async def go():
            for c in ("toggle", "next", "prev"):
                await m.command(c)
        asyncio.run(go())
        self.assertEqual(sess.sent, ["toggle", "next", "prev"])

    def test_app_names(self):
        from hubuddy_agent.collectors.media import app_name
        for raw, want in [("Spotify.exe", "Spotify"), ("Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic", "ZuneMusic"), ("Chrome", "Chrome"), ("", "")]:
            self.assertEqual(app_name(raw), want)

    def test_no_session_and_failures_are_quiet(self):
        async def none_factory():
            return SimpleNamespace(get_current_session=lambda: None)

        self.assertEqual(asyncio.run(MediaCollector(none_factory).snapshot()), {"active": False})

        async def broken():
            raise RuntimeError("boom")
        self.assertEqual(asyncio.run(MediaCollector(broken).snapshot()), {"active": False})


class AgentTests(unittest.TestCase):
    def test_runs_without_psutil(self):
        from unittest import mock
        from hubuddy_agent.source import Agent
        with mock.patch.object(system, "psutil", None):
            agent = Agent({"internet": {"enabled": False}}, home=tmp())
            self.assertFalse(agent.system.available)
            self.assertIsNone(agent.system.sample())
            self.assertIsNone(agent.snapshot()["stats"])  # reported as unavailable, never invented

    @unittest.skipUnless(system.psutil, "psutil not installed")
    def test_snapshot_shape_and_commands_end_to_end(self):
        from hubuddy_agent.source import Agent
        home = tmp()
        r = FakeRunner()
        agent = Agent({"shortcuts": [{"label": "Lock", "action": "lock"}], "internet": {"enabled": False}}, home=home, runner=r)
        agent.latest["stats"] = agent.system.sample()
        claude_hook.handle({"session_id": "a", "cwd": "/x/proj", "hook_event_name": "Notification", "message": "needs permission"}, home / "claude")
        snap = agent.snapshot()
        self.assertTrue(snap["stats"]["ram_total"] > 0)
        self.assertEqual(snap["alert"]["project"], "proj")
        self.assertEqual(snap["shortcuts"]["buttons"][0]["label"], "Lock")
        self.assertIsNone(snap["network"]); self.assertIsNone(snap["media"] if sys.platform != "win32" else None)
        json.dumps(snap)  # must be JSON-serialisable
        self.assertEqual(agent.button_count(), 1)

        async def go():
            await agent.command("shortcut", {"id": 0})
            await agent.command("alert_action", {"id": "a", "action": "dismiss"})
            await agent.command("speedtest", {})
        asyncio.run(go())
        self.assertEqual(r.calls, [("lock",)])
        self.assertIsNone(agent.snapshot()["alert"])

    def test_bad_shortcut_config_is_reported(self):
        from hubuddy_agent.source import Agent
        with self.assertRaises(ValueError):
            Agent({"shortcuts": [{"label": "x", "action": "nope"}]}, home=tmp())


if __name__ == "__main__":
    unittest.main()
