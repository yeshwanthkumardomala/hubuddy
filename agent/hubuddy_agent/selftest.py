"""`Hubuddy.exe --selftest`: pair a pretend phone with this very program and exercise the API.

Runs entirely in a temporary folder on loopback, so it never touches real pairings. Exit code 0 means every check passed.
"""
import asyncio
import base64
import http.client
import json
import socket
import sys
import tempfile
import time
import pathlib

from . import VERSION, discovery, services, tls
from .config import load
from .pairing import compare_code
from .server import AgentServer


class _Conn(http.client.HTTPSConnection):
    def __init__(self, port, ca, host_id):
        super().__init__("127.0.0.1", port, timeout=10)
        self._ctx, self._name = tls.client_context(ca), tls.tls_name(host_id)

    def connect(self):
        self.sock = self._ctx.wrap_socket(socket.create_connection((self.host, self.port), self.timeout), server_hostname=self._name)


class _Phone:
    def __init__(self, port, ca, host_id, token=None):
        self.conn, self.token = _Conn(port, ca, host_id), token

    def call(self, method, path, body=None, raw=None, token=True):
        h = {"Content-Type": "application/json"}
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


def _local(port, method, path, body=None):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    c.request(method, path, json.dumps(body).encode() if body is not None else None, {"X-Hubuddy-Local": "1", "Content-Type": "application/json"})
    r = c.getresponse()
    data = r.read()
    c.close()
    try:
        return r.status, json.loads(data)
    except ValueError:
        return r.status, data


def _decode(invitation: str) -> dict:
    raw = invitation[len("hubuddy-pair:"):]
    return json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))


async def _probe(port: int) -> bytes | None:
    loop, got = asyncio.get_running_loop(), asyncio.Future()

    class P(asyncio.DatagramProtocol):
        def datagram_received(self, data, addr):
            if not got.done():
                got.set_result(data)
    t, _ = await loop.create_datagram_endpoint(P, remote_addr=("127.0.0.1", port))
    t.sendto(b"hubuddy:discover:v1")
    try:
        return await asyncio.wait_for(got, 3)
    except asyncio.TimeoutError:
        return None
    finally:
        t.close()


def _checks(server: AgentServer, svc, udp_reply: bytes | None) -> list[tuple[str, bool, str]]:
    out = []

    def check(name, ok, detail=""):
        out.append((name, bool(ok), detail))

    port, lport = server.port, server.local_port
    check("page on this PC", _local(lport, "GET", "/")[0] == 200)
    check("discovery answers", bool(udp_reply) and udp_reply.startswith(b"hubuddy:here:v1:"))

    status, inv = _local(lport, "POST", "/local/invite")
    d = _decode(inv["invitation"])
    anon = _Phone(port, d["ca"], d["hostId"])
    status, health = anon.call("GET", "/health", token=False)
    check("health over pinned TLS", status == 200 and health.get("hostId") == d["hostId"])
    status, claim = anon.call("POST", "/api/pairing/claim", {"hostId": d["hostId"], "secret": d["secret"], "device": "Selftest phone"}, token=False)
    check("pair by QR", status == 200 and "token" in claim, str(status))
    qr = _Phone(port, d["ca"], d["hostId"], claim.get("token"))
    check("QR invitation is single use", anon.call("POST", "/api/pairing/claim", {"hostId": d["hostId"], "secret": d["secret"]}, token=False)[0] == 401)
    check("API refuses no token", anon.call("GET", "/api/snapshot")[0] == 401)
    status, snap = qr.call("GET", "/api/snapshot")
    check("snapshot with token", status == 200 and isinstance(snap, dict))

    status, pin = _local(lport, "POST", "/local/pin")
    check("compare code matches certificate", pin.get("compare") == compare_code(svc.tls.pin))
    status, c2 = anon.call("POST", "/api/pairing/pin-claim", {"hostId": d["hostId"], "pin": pin["pin"], "device": "Selftest phone 2"}, token=False)
    check("pair by PIN", status == 200 and "token" in c2, str(status))
    pinned = _Phone(port, d["ca"], d["hostId"], c2.get("token"))
    check("PIN is single use", anon.call("POST", "/api/pairing/pin-claim", {"hostId": d["hostId"], "pin": pin["pin"]}, token=False)[0] == 401)

    check("phone to PC clipboard", qr.call("POST", "/api/send", {"kind": "clipboard", "text": "selftest"})[0] in (200, 501))
    check("unsafe link refused", qr.call("POST", "/api/send", {"kind": "link", "text": "javascript:alert(1)"})[0] == 400)
    check("PC to phone message", _local(lport, "POST", "/local/send", {"kind": "text", "text": "hello"})[0] == 200)
    status, box = pinned.call("GET", "/api/inbox")
    check("phone receives message", status == 200 and len(box.get("items", [])) == 1)
    check("phone status", qr.call("POST", "/api/phone/status", {"battery": 80, "charging": False, "network": "Wi-Fi"})[0] == 200)

    status, up = qr.call("PUT", "/api/files/upload?name=selftest.txt", raw=b"hello file", token=True)
    check("file phone to PC", status == 200 and (svc.files.received / "selftest.txt").exists())
    (svc.files.to_phone / "back.txt").write_text("back")
    status, data = qr.call("GET", "/api/files/download?name=back.txt")
    check("file PC to phone", status == 200 and data == b"back")
    check("file traversal refused", qr.call("GET", "/api/files/download?name=..%2Fhost-id")[0] >= 400)

    check("two phones listed on the PC", len(_local(lport, "GET", "/local/state")[1]["phones"]) == 2)
    check("forget all signs phones out",
          _local(lport, "POST", "/local/forget-all", {"confirm": "forget-all"})[0] == 200 and qr.call("GET", "/api/snapshot")[0] == 401)
    return out


async def _run() -> list[tuple[str, bool, str]]:
    with tempfile.TemporaryDirectory(prefix="hubuddy-selftest-") as tmp:
        root = pathlib.Path(tmp)
        svc = services.build(root / "home", name="SELFTEST", files_root=root / "files", set_clipboard=lambda t: True, open_link=lambda u: True)
        from .source import Agent
        agent = Agent(load(None), home=svc.home)
        from .__main__ import web_dir
        server = AgentServer(agent, svc, lan_host="127.0.0.1", port=0, local_port=0, web_dir=web_dir())
        await server.start()
        transport = await discovery.start(server.info, port=0, host="127.0.0.1")
        try:
            reply = await _probe(transport.get_extra_info("sockname")[1])
            return await asyncio.get_running_loop().run_in_executor(None, _checks, server, svc, reply)
        finally:
            transport.close()
            await server.close()


def run(output: str | None = None) -> int:
    started = time.time()
    try:
        results = asyncio.run(_run())
    except Exception as e:  # report instead of a traceback so CI logs stay readable
        results = [("self-test could not run", False, f"{type(e).__name__}: {e}")]
    failed = [r for r in results if not r[1]]
    for name, ok, detail in results:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f"  ({detail})" if detail and not ok else ""))
    print(f"\nHubuddy {VERSION} self-test: {len(results) - len(failed)}/{len(results)} passed in {time.time() - started:.1f}s")
    if output:
        pathlib.Path(output).write_text(json.dumps({"version": VERSION, "passed": not failed, "checks": [{"name": n, "ok": o, "detail": d} for n, o, d in results]}, indent=1))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(run())
