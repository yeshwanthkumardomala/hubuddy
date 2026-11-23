"""Test harness: a real agent server in a background thread, and clients that behave like a phone and like the PC's browser."""
import asyncio
import base64
import http.client
import json
import pathlib
import socket
import tempfile
import threading
import unittest

from hubuddy_agent import services, tls
from hubuddy_agent.server import AgentServer

WEB = pathlib.Path(__file__).resolve().parent.parent / "web"
_keep = []


def tmpdir() -> pathlib.Path:
    d = tempfile.TemporaryDirectory()
    _keep.append(d)
    return pathlib.Path(d.name)


class FakeSource:
    def __init__(self):
        self.commands = []

    def snapshot(self):
        return {"stats": {"cpu": 12.5}, "pc": {"online": True}}

    def button_count(self):
        return 2

    async def command(self, kind, payload):
        self.commands.append((kind, payload))


def decode_invite(text: str) -> dict:
    assert text.startswith("hubuddy-pair:")
    raw = text[len("hubuddy-pair:"):]
    return json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))


class PinnedConnection(http.client.HTTPSConnection):
    """Connects to an IP address but verifies the certificate against the pinned CA and the signed name."""

    def __init__(self, port, ca_b64, host_id, timeout=10):
        super().__init__("127.0.0.1", port, timeout=timeout)
        self._ctx, self._name = tls.client_context(ca_b64), tls.tls_name(host_id)

    def connect(self):
        raw = socket.create_connection((self.host, self.port), self.timeout)
        self.sock = self._ctx.wrap_socket(raw, server_hostname=self._name)


_open = []


class Phone:
    """What the Android app does, over a pinned TLS connection."""

    def __init__(self, port, ca_b64, host_id, token=None):
        self.port, self.ca, self.host_id, self.token = port, ca_b64, host_id, token
        self.conn = PinnedConnection(port, ca_b64, host_id)
        _open.append(self)

    def call(self, method, path, body=None, headers=None, raw=None, token=True):
        h = {"Content-Type": "application/json", **(headers or {})}
        if token and self.token:
            h["Authorization"] = "Bearer " + self.token
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        self.conn.request(method, path, data, h)
        r = self.conn.getresponse()
        payload = r.read()
        try:
            return r.status, json.loads(payload)
        except ValueError:
            return r.status, payload

    def reconnect(self):
        self.conn.close()
        self.conn = PinnedConnection(self.port, self.ca, self.host_id)

    def close(self):
        self.conn.close()


class Agent:
    """A running server plus handles to its services."""

    def __init__(self, **overrides):
        self.home = tmpdir()
        self.clip, self.links = [], []
        self.svc = services.build(self.home / "home", name="TEST-PC", files_root=self.home / "files",
                                  set_clipboard=lambda t: (self.clip.append(t) or True), open_link=lambda u: (self.links.append(u) or True), **overrides)
        self.source = FakeSource()
        self.server = AgentServer(self.source, self.svc, lan_host="127.0.0.1", port=0, local_port=0, web_dir=WEB)
        self.loop = asyncio.new_event_loop()
        self._ready = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        assert self._ready.wait(15), "server did not start"

    def _run(self):
        asyncio.set_event_loop(self.loop)
        self.loop.run_until_complete(self.server.start())
        self._ready.set()
        self.loop.run_forever()

    def stop(self):
        while _open:
            _open.pop().close()
        asyncio.run_coroutine_threadsafe(self.server.close(), self.loop).result(10)
        self.loop.call_soon_threadsafe(self.loop.stop)
        self._thread.join(5)
        self.loop.close()

    # -- the PC's own page --
    def local(self, method, path, body=None, headers=None, host=None):
        c = http.client.HTTPConnection("127.0.0.1", self.server.local_port, timeout=10)
        h = {"X-Hubuddy-Local": "1", "Content-Type": "application/json", **(headers or {})}
        if host:
            h["Host"] = host
        c.request(method, path, json.dumps(body).encode() if body is not None else None, h)
        r = c.getresponse()
        payload = r.read()
        c.close()
        try:
            return r.status, json.loads(payload)
        except ValueError:
            return r.status, payload

    def phone(self, token=None) -> Phone:
        return Phone(self.server.port, self.svc.tls.public_ca, self.svc.host_id, token)

    def pair_by_qr(self, device="Test phone") -> Phone:
        _, inv = self.local("POST", "/local/invite")
        d = decode_invite(inv["invitation"])
        p = Phone(self.server.port, d["ca"], d["hostId"])
        status, body = p.call("POST", "/api/pairing/claim", {"hostId": d["hostId"], "secret": d["secret"], "device": device}, token=False)
        assert status == 200, (status, body)
        p.token = body["token"]
        return p


class AgentCase(unittest.TestCase):
    def setUp(self):
        self.agent = Agent()
        self.addCleanup(self.agent.stop)
