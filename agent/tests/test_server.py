import asyncio
import json
import pathlib
import tempfile
import unittest
import urllib.error
import urllib.request

from hubuddy_agent.auth import CODE_ATTEMPTS, DeviceAuth
from hubuddy_agent.server import FAIL_LIMIT, AgentServer, is_allowed_peer


class FakeSource:
    def __init__(self):
        self.commands = []

    def button_count(self):
        return 2

    def snapshot(self):
        return {"pc": {"online": True}, "stats": {"cpu": 12.5, "ram_used": 4.0, "ram_total": 16.0, "disk": 50.0},
                "claude": None, "media": {"active": False}}

    async def command(self, kind, payload):
        self.commands.append((kind, payload))


def call(port, path, method="GET", body=None, token=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


class AgentTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.auth = DeviceAuth(pathlib.Path(self.tmp.name) / "devices.json")
        self.source = FakeSource()
        self.server = AgentServer(self.source, self.auth, port=0)
        await self.server.start()
        self.port = self.server.port

    async def asyncTearDown(self):
        await self.server.close()
        self.tmp.cleanup()

    async def http(self, *a, **kw):
        return await asyncio.to_thread(call, self.port, *a, **kw)

    async def pair(self):
        code = self.auth.new_code()
        status, body = await self.http("/api/pair", "POST", {"code": code, "device": "Vivo 1902"})
        self.assertEqual(status, 200)
        return body["token"]

    async def test_ping_needs_no_auth_and_leaks_nothing(self):
        status, body = await self.http("/api/ping")
        self.assertEqual(status, 200)
        self.assertEqual(set(body), {"ok", "service", "version"})

    async def test_endpoints_require_token(self):
        for path in ("/api/snapshot", "/api/stats", "/api/claude", "/api/status"):
            status, _ = await self.http(path)
            self.assertEqual(status, 401, path)
        status, _ = await self.http("/api/media", "POST", {"cmd": "toggle"})
        self.assertEqual(status, 401)
        self.assertEqual(self.source.commands, [])

    async def test_pair_then_snapshot(self):
        token = await self.pair()
        status, body = await self.http("/api/snapshot", token=token)
        self.assertEqual(status, 200)
        self.assertEqual(body["stats"]["cpu"], 12.5)
        status, body = await self.http("/api/stats", token=token)
        self.assertEqual((status, body["available"]), (200, True))
        status, body = await self.http("/api/claude", token=token)
        self.assertEqual((status, body["available"]), (200, False))

    async def test_token_stored_only_as_hash(self):
        token = await self.pair()
        text = (pathlib.Path(self.tmp.name) / "devices.json").read_text()
        self.assertNotIn(token, text)
        self.assertIn("token_sha256", text)

    async def test_pair_code_single_use_and_expiring(self):
        code = self.auth.new_code()
        s1, _ = await self.http("/api/pair", "POST", {"code": code})
        s2, _ = await self.http("/api/pair", "POST", {"code": code})
        self.assertEqual((s1, s2), (200, 403))

    async def test_pair_code_invalidated_after_wrong_guesses(self):
        code = self.auth.new_code()
        wrong = "000000" if code != "000000" else "111111"
        for _ in range(CODE_ATTEMPTS):
            s, _ = await self.http("/api/pair", "POST", {"code": wrong})
            self.assertIn(s, (401, 403))
        s, _ = await self.http("/api/pair", "POST", {"code": code})
        self.assertEqual(s, 403)  # closed: the right code no longer works

    async def test_bad_token_rate_limited(self):
        for _ in range(FAIL_LIMIT):
            s, _ = await self.http("/api/snapshot", token="nope")
            self.assertEqual(s, 401)
        s, _ = await self.http("/api/snapshot", token="nope")
        self.assertEqual(s, 429)

    async def test_commands_validated(self):
        token = await self.pair()
        cases = [
            ("/api/media", {"cmd": "rm -rf"}, 400),
            ("/api/media", {"cmd": "next"}, 200),
            ("/api/shortcuts/run", {"id": 5}, 400),
            ("/api/shortcuts/run", {"id": "1"}, 400),
            ("/api/shortcuts/run", {"id": True}, 400),
            ("/api/shortcuts/run", {"id": 1}, 200),
            ("/api/alerts/action", {"id": "abc", "action": "explode"}, 400),
            ("/api/alerts/action", {"id": "abc", "action": "dismiss"}, 200),
            ("/api/speedtest", {}, 200),
        ]
        for path, body, want in cases:
            s, _ = await self.http(path, "POST", body, token=token)
            self.assertEqual(s, want, (path, body))
        self.assertEqual(self.source.commands, [
            ("media", {"cmd": "next"}), ("shortcut", {"id": 1}),
            ("alert_action", {"id": "abc", "action": "dismiss"}), ("speedtest", {})])

    async def test_malformed_json_and_oversize(self):
        token = await self.pair()
        s, _ = await self.http("/api/media", "POST", raw=b"{not json", token=token)
        self.assertEqual(s, 400)
        s, _ = await self.http("/api/media", "POST", raw=b"[1,2]", token=token)
        self.assertEqual(s, 400)
        s, _ = await self.http("/api/media", "POST", raw=b"x" * 20000, token=token)
        self.assertEqual(s, 413)

    async def test_revoke(self):
        token = await self.pair()
        self.auth.revoke("all")
        s, _ = await self.http("/api/snapshot", token=token)
        self.assertEqual(s, 401)


class StaticTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        web = pathlib.Path(__file__).resolve().parent.parent / "web"
        self.server = AgentServer(FakeSource(), DeviceAuth(pathlib.Path(self.tmp.name) / "d.json"), port=0, web_dir=web)
        await self.server.start()

    async def asyncTearDown(self):
        await self.server.close()
        self.tmp.cleanup()

    async def raw(self, line: bytes) -> bytes:
        r, w = await asyncio.open_connection("127.0.0.1", self.server.port)
        w.write(line + b"\r\nHost: x\r\n\r\n")
        await w.drain()
        data = await r.read()
        w.close()
        return data

    async def test_serves_dashboard_and_blocks_traversal(self):
        ok = await self.raw(b"GET / HTTP/1.1")
        self.assertTrue(ok.startswith(b"HTTP/1.1 200"))
        self.assertIn(b"Hubuddy", ok)
        self.assertIn(b"X-Frame-Options: DENY", ok)
        for evil in (b"GET /../hubuddy_agent/auth.py HTTP/1.1", b"GET /%2e%2e/tests/test_agent.py HTTP/1.1",
                     b"GET /../../../../etc/passwd HTTP/1.1"):
            self.assertTrue((await self.raw(evil)).startswith(b"HTTP/1.1 404"), evil)

    async def test_garbage_request_line(self):
        self.assertTrue((await self.raw(b"HELLO")).startswith(b"HTTP/1.1 400"))


class PeerTest(unittest.TestCase):
    def test_peers(self):
        for ok in ("127.0.0.1", "::1", "192.168.1.20", "10.0.0.5", "172.16.3.4", "169.254.1.1", "fe80::1", "::ffff:192.168.0.2"):
            self.assertTrue(is_allowed_peer(ok), ok)
        for bad in ("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "0.0.0.0", "garbage"):
            self.assertFalse(is_allowed_peer(bad), bad)


if __name__ == "__main__":
    unittest.main()
