"""A deliberately small asyncio HTTP/1.1 server for the phone API.

Security model
--------------
* Binds to 127.0.0.1 by default (USB mode through `adb reverse`). LAN mode must be
  asked for explicitly, and even then requests from non-private addresses are refused.
* Every endpoint except /api/ping and /api/pair needs `Authorization: Bearer <token>`.
* Commands are fixed verbs with validated arguments; the phone never sends a
  command line. Shortcut buttons are referenced by index, exactly like the board.
* Failed auth and pairing attempts are rate-limited per client address.
* Plain HTTP is NOT encrypted. Use USB mode or a trusted network.
"""
import asyncio
import ipaddress
import json
import logging
import mimetypes
import pathlib
import time
from collections import defaultdict, deque

from . import VERSION
from .auth import DeviceAuth

log = logging.getLogger("hubuddy.bridge")

MAX_HEADER = 16 * 1024
MAX_BODY = 16 * 1024
READ_TIMEOUT_S = 10
FAIL_WINDOW_S = 60
FAIL_LIMIT = 10

SECTIONS = {
    "/api/status": "pc", "/api/stats": "stats", "/api/claude": "claude", "/api/calendar": "calendar",
    "/api/media": "media", "/api/network": "network", "/api/shortcuts": "shortcuts",
    "/api/github": "github", "/api/alerts": "alert",
}
MEDIA_CMDS = {"toggle", "next", "prev"}
ALERT_ACTIONS = {"focus", "dismiss"}


def is_allowed_peer(host: str) -> bool:
    """Loopback, RFC 1918 private, link-local and unique-local IPv6 only."""
    try:
        ip = ipaddress.ip_address(host.split("%")[0])
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_loopback or ip.is_link_local or (ip.is_private and not ip.is_unspecified)


class HttpError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status, self.message = status, message


REASONS = {200: "OK", 204: "No Content", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden",
           404: "Not Found", 405: "Method Not Allowed", 408: "Request Timeout", 413: "Payload Too Large",
           429: "Too Many Requests", 500: "Internal Server Error", 503: "Service Unavailable"}


class BridgeServer:
    def __init__(self, source, auth: DeviceAuth, host: str = "127.0.0.1", port: int = 8765,
                 web_dir: pathlib.Path | None = None, clock=time.monotonic):
        self.source, self.auth, self.host, self.port = source, auth, host, port
        self.web_dir = pathlib.Path(web_dir).resolve() if web_dir else None
        self.clock = clock
        self._fails: dict[str, deque] = defaultdict(deque)
        self.server: asyncio.base_events.Server | None = None

    async def start(self):
        self.server = await asyncio.start_server(self._client, self.host, self.port)
        sock = self.server.sockets[0].getsockname()
        self.port = sock[1]
        return self.server

    async def close(self):
        if self.server:
            self.server.close()
            await self.server.wait_closed()

    # ---- rate limiting --------------------------------------------------------
    def _limited(self, peer: str) -> bool:
        q = self._fails[peer]
        now = self.clock()
        while q and now - q[0] > FAIL_WINDOW_S:
            q.popleft()
        return len(q) >= FAIL_LIMIT

    def _fail(self, peer: str):
        self._fails[peer].append(self.clock())

    # ---- connection handling ---------------------------------------------------
    async def _client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        peer = (writer.get_extra_info("peername") or ("?",))[0]
        status, body, ctype = 500, {"error": "internal error"}, None
        try:
            if not is_allowed_peer(peer):
                raise HttpError(403, "only local network clients are allowed")
            method, path, headers, raw = await asyncio.wait_for(self._read(reader), READ_TIMEOUT_S)
            status, body, ctype = await self.route(method, path, headers, raw, peer)
        except HttpError as e:
            status, body = e.status, {"error": e.message}
        except asyncio.TimeoutError:
            status, body = 408, {"error": "timeout"}
        except (ConnectionError, asyncio.IncompleteReadError):
            writer.close()
            return
        except Exception:
            log.exception("request failed")
            status, body = 500, {"error": "internal error"}
        try:
            await self._write(writer, status, body, ctype)
        except ConnectionError:
            pass
        finally:
            writer.close()

    async def _read(self, reader):
        try:
            head = await reader.readuntil(b"\r\n\r\n")
        except asyncio.LimitOverrunError:
            raise HttpError(413, "headers too large")
        if len(head) > MAX_HEADER:
            raise HttpError(413, "headers too large")
        lines = head.decode("latin-1").split("\r\n")
        parts = lines[0].split(" ")
        if len(parts) != 3 or not parts[2].startswith("HTTP/1."):
            raise HttpError(400, "bad request line")
        method, target = parts[0].upper(), parts[1]
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        if "transfer-encoding" in headers:
            raise HttpError(400, "chunked bodies are not supported")
        length = headers.get("content-length", "0")
        if not length.isdigit():
            raise HttpError(400, "bad content-length")
        if int(length) > MAX_BODY:
            raise HttpError(413, "body too large")
        raw = await reader.readexactly(int(length)) if int(length) else b""
        path = target.split("?", 1)[0]
        return method, path, headers, raw

    async def _write(self, writer, status, body, ctype=None):
        if isinstance(body, (bytes, bytearray)):
            data = bytes(body)
        else:
            data = json.dumps(body, separators=(",", ":")).encode("utf-8")
            ctype = "application/json; charset=utf-8"
        head = (f"HTTP/1.1 {status} {REASONS.get(status, 'Status')}\r\n"
                f"Content-Type: {ctype}\r\nContent-Length: {len(data)}\r\n"
                "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n"
                "X-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n"
                "Connection: close\r\n\r\n")
        writer.write(head.encode("latin-1") + data)
        await writer.drain()

    # ---- routing -------------------------------------------------------------
    def _json(self, raw: bytes) -> dict:
        if not raw:
            return {}
        try:
            obj = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise HttpError(400, "body must be JSON")
        if not isinstance(obj, dict):
            raise HttpError(400, "body must be a JSON object")
        return obj

    async def route(self, method: str, path: str, headers: dict, raw: bytes, peer: str):
        if path == "/api/ping":
            if method != "GET":
                raise HttpError(405, "use GET")
            return 200, {"ok": True, "service": "hubuddy-bridge", "version": VERSION}, None

        if path == "/api/pair":
            if method != "POST":
                raise HttpError(405, "use POST")
            if self._limited(peer):
                raise HttpError(429, "too many attempts")
            body = self._json(raw)
            code, device = body.get("code"), body.get("device", "device")
            if not isinstance(code, str) or len(code) != 6 or not code.isdigit():
                self._fail(peer)
                raise HttpError(400, "code must be 6 digits")
            if not self.auth.pairing_open:
                raise HttpError(403, "pairing is closed; restart the bridge with --pair to get a new code")
            token = self.auth.pair(code, str(device))
            if token is None:
                self._fail(peer)
                raise HttpError(401, "wrong or expired pairing code")
            log.info("paired a new device: %s", str(device)[:40])
            return 200, {"token": token}, None

        if path.startswith("/api/"):
            if self._limited(peer):
                raise HttpError(429, "too many failed attempts")
            auth = headers.get("authorization", "")
            token = auth[7:].strip() if auth.lower().startswith("bearer ") else None
            if self.auth.check(token) is None:
                self._fail(peer)
                raise HttpError(401, "missing or invalid device token")
            return await self._api(method, path, raw)

        if method == "GET" and self.web_dir is not None:
            return self._static(path)
        raise HttpError(404, "not found")

    async def _api(self, method: str, path: str, raw: bytes):
        if method == "GET":
            if path == "/api/snapshot":
                return 200, self.source.snapshot(), None
            if path == "/api/weather":
                return 200, {"available": False,
                             "reason": "Weather is fetched by the phone directly from Open-Meteo."}, None
            if path in SECTIONS:
                snap = self.source.snapshot()
                return 200, {"available": snap.get(SECTIONS[path]) is not None,
                             "data": snap.get(SECTIONS[path])}, None
            raise HttpError(404, "unknown endpoint")
        if method != "POST":
            raise HttpError(405, "method not allowed")
        body = self._json(raw)
        if path == "/api/media":
            cmd = body.get("cmd")
            if cmd not in MEDIA_CMDS:
                raise HttpError(400, "cmd must be toggle, next or prev")
            await self.source.command("media", {"cmd": cmd})
        elif path == "/api/shortcuts/run":
            i = body.get("id")
            if not isinstance(i, int) or isinstance(i, bool) or not 0 <= i < self.source.tile_count():
                raise HttpError(400, "unknown shortcut id")
            await self.source.command("shortcut", {"id": i})
        elif path == "/api/alerts/action":
            sid, action = body.get("id"), body.get("action")
            if not isinstance(sid, str) or not sid or len(sid) > 200 or action not in ALERT_ACTIONS:
                raise HttpError(400, "id and action (focus|dismiss) required")
            await self.source.command("alert_action", {"id": sid, "action": action})
        elif path == "/api/speedtest":
            await self.source.command("speedtest", {})
        else:
            raise HttpError(404, "unknown endpoint")
        return 200, {"ok": True}, None

    def _static(self, path: str):
        rel = "index.html" if path in ("/", "") else path.lstrip("/")
        target = (self.web_dir / rel).resolve()
        if self.web_dir not in target.parents and target != self.web_dir / "index.html":
            raise HttpError(404, "not found")
        if not target.is_file():
            raise HttpError(404, "not found")
        ctype = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        return 200, target.read_bytes(), ctype
