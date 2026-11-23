import unittest

from helpers import Agent, AgentCase
from hubuddy_agent import remote, wol


class FakeBackend(remote.Backend):
    def __init__(self):
        self.log = []
        self.killed = []

    def move(self, dx, dy): self.log.append(("move", dx, dy))
    def click(self, button, double): self.log.append(("click", button, double))
    def scroll(self, dx, dy): self.log.append(("scroll", dx, dy))
    def button(self, button, down): self.log.append(("button", button, down))
    def zoom(self, steps): self.log.append(("zoom", steps))
    def key(self, key, mods): self.log.append(("key", key, tuple(mods)))
    def text(self, text): self.log.append(("text", text))
    def power(self, action): self.log.append(("power", action))
    def battery(self): return {"percent": 80, "charging": False}
    def processes(self, limit=15): return [{"pid": 1234, "name": "chrome.exe", "mem_mb": 500}]

    def end(self, pid):
        if pid == 4:
            raise remote.BadRequest("that program is protected")
        self.killed.append(pid)
        return "chrome.exe"


class Schema(unittest.TestCase):
    def test_good_events_are_cleaned(self):
        out = remote.validate_events([{"t": "move", "dx": 5, "dy": -3}, {"t": "key", "key": "c", "mods": []} if False else {"t": "key", "key": "enter", "mods": ["ctrl", "ctrl"]},
                                      {"t": "text", "text": "héllo 😀"}, {"t": "click"}, {"t": "scroll", "dy": 3}, {"t": "button", "button": "left", "down": True}, {"t": "zoom", "steps": -2}, {"t": "release"}, {"t": "ping"}])
        self.assertEqual(out[1]["mods"], ["ctrl"])
        self.assertEqual(out[3], {"t": "click", "button": "left", "double": False})

    def test_everything_else_is_refused(self):
        bad = [None, [], "x", [1], [{"t": "move"}], [{"t": "move", "dx": 99999, "dy": 0}], [{"t": "move", "dx": True, "dy": 0}],
               [{"t": "move", "dx": 1.5, "dy": 0}], [{"t": "click", "button": "x"}], [{"t": "key", "key": "alt+f4"}], [{"t": "key", "key": "A"}], [{"t": "key", "key": "cc"}],
               [{"t": "key", "key": "enter", "mods": ["super"]}], [{"t": "text", "text": ""}], [{"t": "text", "text": "x" * 501}],
               [{"t": "text", "text": "a\x00b"}], [{"t": "rm -rf"}], [{"t": "move", "dx": 1, "dy": 1}] * 65,
               [{"t": "button", "button": "left"}], [{"t": "button", "button": "left", "down": "yes"}], [{"t": "button", "button": "x", "down": True}],
               [{"t": "zoom", "steps": 0}], [{"t": "zoom", "steps": 9}], [{"t": "zoom", "steps": 1.5}], [{"t": "scroll", "dx": "a", "dy": 0}]]
        for letter in ("c", "v", "7"):
            self.assertEqual(remote.validate_events([{"t": "key", "key": letter, "mods": ["ctrl"]}])[0]["key"], letter)
        for b in bad:
            with self.assertRaises(remote.BadRequest, msg=str(b)[:60]):
                remote.validate_events(b)


class Wol(unittest.TestCase):
    def test_magic_packet_bytes(self):
        pkt = wol.magic_packet("AA:BB:CC:DD:EE:FF")
        self.assertEqual(len(pkt), 102)
        self.assertEqual(pkt[:6], b"\xff" * 6)
        self.assertEqual(pkt[6:12], bytes.fromhex("aabbccddeeff"))
        self.assertEqual(pkt[-6:], bytes.fromhex("aabbccddeeff"))
        for bad in ("", "AA:BB", "GG:BB:CC:DD:EE:FF", None, "AABBCCDDEEFF"):
            with self.assertRaises(ValueError):
                wol.magic_packet(bad)


class RemoteApi(AgentCase):
    def setUp(self):
        self.fake = FakeBackend()
        self.agent = Agent(remote=self.fake)
        self.addCleanup(self.agent.stop)
        self.phone = self.agent.pair_by_qr("Vivo")

    def allow(self, on=True):
        _, st = self.agent.local("GET", "/local/state")
        sid = st["phones"][0]["id"]
        return self.agent.local("POST", "/local/remote", {"id": sid, "on": on})

    def test_off_by_default_and_nothing_runs(self):
        self.assertEqual(self.phone.call("GET", "/api/remote")[1]["allowed"], False)
        for path, body in (("/api/input", {"events": [{"t": "move", "dx": 1, "dy": 1}]}), ("/api/volume", {"action": "up"}),
                           ("/api/power", {"action": "shutdown"}), ("/api/processes/end", {"pid": 1234})):
            self.assertEqual(self.phone.call("POST", path, body)[0], 403, path)
        self.assertEqual(self.phone.call("GET", "/api/processes")[0], 403)
        self.assertEqual(self.fake.log, [])

    def test_when_allowed_it_works_and_is_logged(self):
        self.assertEqual(self.allow()[0], 200)
        s, r = self.phone.call("POST", "/api/input", {"events": [{"t": "move", "dx": 4, "dy": 2}, {"t": "text", "text": "hi"}]})
        self.assertEqual((s, r["count"]), (200, 2))
        self.assertEqual(self.fake.log, [("move", 4, 2), ("text", "hi")])
        self.assertEqual(self.phone.call("POST", "/api/volume", {"action": "mute"})[0], 200)
        self.assertEqual(self.fake.log[-1], ("key", "mute", ()))
        s, r = self.phone.call("POST", "/api/power", {"action": "restart"})
        self.assertEqual((s, r["delaySeconds"]), (200, 15))
        self.assertEqual(self.phone.call("GET", "/api/processes")[1]["items"][0]["name"], "chrome.exe")
        self.assertEqual(self.phone.call("POST", "/api/processes/end", {"pid": 1234})[0], 200)
        self.assertEqual(self.phone.call("POST", "/api/processes/end", {"pid": 4})[0], 400)
        events = [e["event"] for e in self.agent.local("GET", "/local/activity")[1]["events"]]
        for want in ("remote_allowed", "remote_input", "remote_power", "remote_end_task"):
            self.assertIn(want, events)

    def test_bad_requests_are_400_and_turning_it_off_blocks_again(self):
        self.allow()
        for path, body in (("/api/input", {"events": [{"t": "move", "dx": 99999, "dy": 0}]}), ("/api/input", {}), ("/api/volume", {"action": "boom"}),
                           ("/api/power", {"action": "format"}), ("/api/processes/end", {"pid": "1"}), ("/api/processes/end", {"pid": True})):
            self.assertEqual(self.phone.call("POST", path, body)[0], 400, (path, body))
        self.allow(False)
        self.assertEqual(self.phone.call("POST", "/api/volume", {"action": "up"})[0], 403)

    def test_rate_limit(self):
        self.allow()
        self.agent.server.rc_limit = 5
        codes = [self.phone.call("POST", "/api/input", {"events": [{"t": "move", "dx": 1, "dy": 1}]})[0] for _ in range(30)]
        self.assertIn(429, codes)
        self.assertEqual(codes[0], 200)

    def test_buttons_press_and_hold_and_are_released_on_request(self):
        self.allow()
        post = lambda ev: self.phone.call("POST", "/api/input", {"events": ev})
        self.assertEqual(post([{"t": "button", "button": "left", "down": True}, {"t": "button", "button": "left", "down": True}])[0], 200)
        self.assertEqual(self.fake.log, [("button", "left", True)])      # a repeated press is not sent twice
        self.assertEqual(post([{"t": "move", "dx": 3, "dy": 0}, {"t": "zoom", "steps": 2}])[0], 200)
        self.assertNotIn(("zoom", 2), self.fake.log)                      # no zoom while a button is held, as in the reference design
        self.assertEqual(post([{"t": "release"}])[0], 200)
        self.assertEqual(self.fake.log[-1], ("button", "left", False))
        self.assertEqual(post([{"t": "zoom", "steps": 2}, {"t": "scroll", "dx": 1, "dy": 4}])[0], 200)
        self.assertEqual(self.fake.log[-2:], [("zoom", 2), ("scroll", 1, 4)])

    def test_a_stuck_button_is_released_when_the_phone_goes_quiet(self):
        import time
        self.allow()
        self.phone.call("POST", "/api/input", {"events": [{"t": "button", "button": "right", "down": True}]})
        deadline = time.time() + 6
        while time.time() < deadline and ("button", "right", False) not in self.fake.log:
            time.sleep(0.2)
        self.assertIn(("button", "right", False), self.fake.log)

    def test_turning_remote_off_or_forgetting_releases_held_buttons(self):
        self.allow()
        self.phone.call("POST", "/api/input", {"events": [{"t": "button", "button": "left", "down": True}]})
        self.allow(False)
        self.assertEqual(self.fake.log[-1], ("button", "left", False))
        self.allow()
        self.phone.call("POST", "/api/input", {"events": [{"t": "button", "button": "middle", "down": True}]})
        self.agent.local("POST", "/local/forget-all", {"confirm": "forget-all"})
        self.assertEqual(self.fake.log[-1], ("button", "middle", False))

    def test_unavailable_backend_is_501_not_a_crash(self):
        agent = Agent(remote=remote.Backend())  # a platform that cannot do any of it (the real Windows backend can)
        self.addCleanup(agent.stop)
        p = agent.pair_by_qr()
        _, st = agent.local("GET", "/local/state")
        agent.local("POST", "/local/remote", {"id": st["phones"][0]["id"], "on": True})
        self.assertEqual(p.call("POST", "/api/input", {"events": [{"t": "move", "dx": 1, "dy": 1}]})[0], 501)

    def test_switch_is_per_phone_and_survives_restart(self):
        other = self.agent.pair_by_qr("Pixel")
        self.allow()
        self.assertEqual(self.phone.call("POST", "/api/volume", {"action": "up"})[0], 200)
        self.assertEqual(other.call("POST", "/api/volume", {"action": "up"})[0], 403)
        from hubuddy_agent.sessions import SessionStore
        self.assertEqual(sum(1 for e in SessionStore(self.agent.svc.home / "sessions.json").entries if e.get("remote")), 1)

    def test_forget_all_removes_the_permission(self):
        self.allow()
        self.agent.local("POST", "/local/forget-all", {"confirm": "forget-all"})
        self.assertEqual(self.phone.call("POST", "/api/volume", {"action": "up"})[0], 401)


if __name__ == "__main__":
    unittest.main()
