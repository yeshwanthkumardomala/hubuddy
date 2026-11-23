"""The agent's HTTP servers.

Two listeners:

* **LAN (HTTPS)** for phones: TLS with this PC's own certificate authority (phones pin it), private-network peers
  only, every route but /health and pairing needs the phone's Bearer token. Keep-alive, streaming file bodies.
* **Local (plain HTTP on 127.0.0.1)** for this PC's own pages (QR, PIN, phones, send box). Loopback only, with a
  loopback Host header and same-origin checks, so a web page on the internet cannot reach it.

Plain HTTP is never offered to the network. Commands from the phone are fixed verbs with validated arguments.
"""
import asyncio
import json
import logging
import pathlib
import time
import urllib.parse
from collections import defaultdict, deque

from . import VERSION
from .auth import bearer, local_request_ok, make_host_check
from . import remote as rc
from .files import FileError
from .netinfo import interfaces, is_loopback, is_private_peer, mac_for, primary_address
from .pairing import compare_code
from .sessions import StoreError

log = logging.getLogger("hubuddy.server")

MAX_HEADER = 16 * 1024
JSON_LIMIT = 256 * 1024
IDLE_S = 60
FAIL_WINDOW_S = 60
FAIL_LIMIT = 20
MAX_CONNECTIONS = 64

SECTIONS = {
    "/api/status": "pc", "/api/stats": "stats", "/api/claude": "claude", "/api/calendar": "calendar",
    "/api/media": "media", "/api/network": "network", "/api/shortcuts": "shortcuts",
    "/api/github": "github", "/api/alerts": "alert",
}
MEDIA_CMDS = {"toggle", "next", "prev"}
ALERT_ACTIONS = {"dismiss"}
WEB_FILES = {"index.html": "text/html; charset=utf-8", "app.js": "text/javascript; charset=utf-8", "style.css": "text/css; charset=utf-8"}

REASONS = {200: "OK", 204: "No Content", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden", 404: "Not Found",
           405: "Method Not Allowed", 408: "Request Timeout", 409: "Conflict", 413: "Payload Too Large", 429: "Too Many Requests",
           500: "Internal Server Error", 501: "Not Implemented", 503: "Service Unavailable", 507: "Insufficient Storage"}


class HttpError(Exception):
    def __init__(self, status: int, message: str, extra: dict | None = None):
        super().__init__(message)
        self.status, self.message, self.extra = status, message, extra or {}


class Request:
    def __init__(self, method, path, query, headers, reader, length, peer, tls):
        self.method, self.path, self.query, self.headers = method, path, query, headers
        self.reader, self.length, self.peer, self.tls = reader, length, peer, tls
        self.session: dict | None = None
        self._consumed = False

    async def body(self, limit: int = JSON_LIMIT) -> bytes:
        if self.length > limit:
            raise HttpError(413, "body too large")
        self._consumed = True
        return await asyncio.wait_for(self.reader.readexactly(self.length), 15) if self.length else b""

    async def json(self, limit: int = JSON_LIMIT) -> dict:
        raw = await self.body(limit)
        if not raw:
            return {}
        try:
            obj = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise HttpError(400, "body must be JSON")
        if not isinstance(obj, dict):
            raise HttpError(400, "body must be a JSON object")
        return obj


class Response:
    def __init__(self, status=200, body=None, ctype=None, headers=None, file=None):
        self.status, self.body, self.ctype, self.headers, self.file = status, body, ctype, headers or {}, file


def ok(**kw) -> Response:
    return Response(200, {"ok": True, **kw})


class AgentServer:
    def __init__(self, source, services, lan_host: str = "0.0.0.0", port: int = 8765, local_port: int = 8766,
                 web_dir: pathlib.Path | None = None, clock=time.monotonic):
        self.source, self.svc = source, services
        self.lan_host, self.port, self.local_port = lan_host, port, local_port
        self._rc_rate: dict = {}
        self.web_dir = pathlib.Path(web_dir).resolve() if web_dir else None
        self.clock = clock
        self.host_ok = make_host_check(services.name, tls_name=services.tls.name)
        self._fails: dict[str, deque] = defaultdict(deque)
        self._servers: list[asyncio.base_events.Server] = []
        self._slots = asyncio.Semaphore(MAX_CONNECTIONS)

    # ---- lifecycle ----------------------------------------------------------------------------
    async def start(self):
        lan = await asyncio.start_server(lambda r, w: self._connection(r, w, local=False), self.lan_host, self.port,
                                         ssl=self.svc.tls.context, ssl_handshake_timeout=10)
        self.port = lan.sockets[0].getsockname()[1]
        local = await asyncio.start_server(lambda r, w: self._connection(r, w, local=True), "127.0.0.1", self.local_port)
        self.local_port = local.sockets[0].getsockname()[1]
        self._servers = [lan, local]

    async def close(self):
        for s in self._servers:
            s.close()
        for s in self._servers:
            await s.wait_closed()

    def info(self) -> dict:
        return {"hostId": self.svc.host_id, "name": self.svc.name, "port": self.port}

    # ---- limits ---------------------------------------------------------------------------------
    def _limited(self, peer: str) -> bool:
        q, now = self._fails[peer], self.clock()
        while q and now - q[0] > FAIL_WINDOW_S:
            q.popleft()
        return len(q) >= FAIL_LIMIT

    def _fail(self, peer: str):
        self._fails[peer].append(self.clock())

    # ---- connection loop --------------------------------------------------------------------------
    async def _connection(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter, local: bool):
        peer = (writer.get_extra_info("peername") or ("?",))[0]
        async with self._slots:
            try:
                if (local and not is_loopback(peer)) or (not local and not is_private_peer(peer)):
                    await self._send(writer, Response(403, {"error": "only local network clients are allowed"}), False)
                    return
                while True:
                    try:
                        req = await self._read_request(reader, peer, local, writer)
                    except (asyncio.IncompleteReadError, ConnectionError, asyncio.TimeoutError, asyncio.LimitOverrunError):
                        return
                    except HttpError as e:
                        await self._send(writer, Response(e.status, {"error": e.message}), False)
                        return
                    keep = req.headers.get("connection", "").lower() != "close"
                    try:
                        resp = await (self._route_local(req) if local else self._route_lan(req))
                    except HttpError as e:
                        resp = Response(e.status, {"error": e.message, **e.extra})
                    except FileError as e:
                        resp = Response(e.status, {"error": e.message})
                        keep = False
                    except StoreError as e:
                        resp = Response(409, {"error": str(e)})
                    except (ConnectionError, asyncio.IncompleteReadError, asyncio.TimeoutError):
                        return
                    except Exception:
                        log.exception("request failed")
                        resp, keep = Response(500, {"error": "internal error"}), False
                    unread = not req._consumed and req.length > 0
                    if unread and req.length <= JSON_LIMIT:
                        # Read a small unused body first: on Windows, closing a socket that still holds unread data resets
                        # the connection and the phone would never see the reply. Reading it also keeps the connection usable.
                        try:
                            await asyncio.wait_for(reader.readexactly(req.length), 5)
                            req._consumed = unread = False
                        except (asyncio.IncompleteReadError, asyncio.TimeoutError, ConnectionError):
                            return
                    if unread:
                        keep = False  # an unread large body would corrupt the next request on this connection
                    await self._send(writer, resp, keep)
                    if unread:
                        await self._discard(reader, req.length)
                    if not keep:
                        return
            except ConnectionError:
                pass
            finally:
                try:
                    writer.close()
                except Exception:
                    pass

    @staticmethod
    async def _discard(reader: asyncio.StreamReader, length: int, limit: int = 4_000_000, seconds: float = 2.0) -> None:
        """After replying to a request whose body was not used, swallow what the sender is still sending (briefly), so
        closing the connection does not reset it before the reply arrives."""
        left = min(length, limit)
        try:
            async with asyncio.timeout(seconds):
                while left > 0:
                    chunk = await reader.read(min(65536, left))
                    if not chunk:
                        return
                    left -= len(chunk)
        except (TimeoutError, ConnectionError):
            pass

    async def _read_request(self, reader, peer, local, writer) -> Request:
        head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), IDLE_S)
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
        u = urllib.parse.urlsplit(target)
        query = {k: v[0] for k, v in urllib.parse.parse_qs(u.query, keep_blank_values=True).items()}
        return Request(method, u.path, query, headers, reader, int(length), peer, tls=not local)

    async def _send(self, writer, resp: Response, keep: bool):
        file = resp.file
        if file is not None:
            path, size = file
            ctype, data = resp.ctype or "application/octet-stream", None
        elif isinstance(resp.body, (bytes, bytearray)):
            ctype, data, size = resp.ctype or "application/octet-stream", bytes(resp.body), len(resp.body)
        else:
            data = json.dumps(resp.body if resp.body is not None else {}, separators=(",", ":")).encode()
            ctype, size = "application/json; charset=utf-8", len(data)
        head = [f"HTTP/1.1 {resp.status} {REASONS.get(resp.status, 'Status')}", f"Content-Type: {ctype}", f"Content-Length: {size}",
                "Cache-Control: no-store", "X-Content-Type-Options: nosniff", "X-Frame-Options: DENY", "Referrer-Policy: no-referrer",
                f"Connection: {'keep-alive' if keep else 'close'}"]
        head += [f"{k}: {v}" for k, v in resp.headers.items()]
        writer.write(("\r\n".join(head) + "\r\n\r\n").encode("latin-1"))
        if file is not None:
            with open(file[0], "rb") as f:
                while chunk := f.read(64 * 1024):
                    writer.write(chunk)
                    await writer.drain()
        elif data:
            writer.write(data)
        await writer.drain()

    # ======================================================================================================
    #  LAN routes (HTTPS, phones)
    # ======================================================================================================
    async def _route_lan(self, req: Request) -> Response:
        if not self.host_ok(req.headers.get("host")):
            raise HttpError(403, "unrecognised Host header")
        svc, p, m = self.svc, req.path, req.method

        if p == "/health":
            self._method(req, "GET")
            return Response(200, {"ok": True, "app": "hubuddy", "version": VERSION, "hostId": svc.host_id, "name": svc.name,
                                  "port": self.port, "ca": svc.tls.public_ca})
        if p == "/api/pairing/status":
            self._method(req, "GET")
            return Response(200, {"hostId": svc.host_id, "name": svc.name, **svc.pairing.status()})
        if p == "/api/pairing/claim":
            self._method(req, "POST")
            return await self._claim(req, method="qr")
        if p == "/api/pairing/pin-claim":
            self._method(req, "POST")
            return await self._claim(req, method="pin")

        if not p.startswith("/api/"):
            raise HttpError(404, "not found")
        self._authenticate(req)
        sid = svc.sessions.session_id(req.session)

        if p == "/api/snapshot":
            self._method(req, "GET")
            return Response(200, self.source.snapshot())
        if p in SECTIONS:
            self._method(req, "GET")
            snap = self.source.snapshot()
            return Response(200, {"available": snap.get(SECTIONS[p]) is not None, "data": snap.get(SECTIONS[p])})
        if p == "/api/weather":
            return Response(200, {"available": False, "reason": "Weather is fetched by the phone directly from Open-Meteo."})

        if p == "/api/phone/status":
            self._method(req, "POST")
            return Response(200, {"ok": True, "status": svc.status.update(sid, await req.json())})
        if p == "/api/inbox":
            self._method(req, "GET")
            return Response(200, {"items": svc.inbox.for_phone(sid)})
        if p == "/api/inbox/ack":
            self._method(req, "POST")
            ids = (await req.json()).get("ids")
            if not isinstance(ids, list) or len(ids) > 100 or not all(isinstance(i, str) and len(i) <= 32 for i in ids):
                raise HttpError(400, "ids must be a list of item ids")
            svc.inbox.ack(sid, ids)
            svc.inbox.prune({svc.sessions.session_id(e) for e in svc.sessions.entries})
            return ok()
        if p == "/api/send":
            self._method(req, "POST")
            return await self._phone_sends(req, sid)

        if p in ("/api/remote", "/api/input", "/api/volume", "/api/power", "/api/processes", "/api/processes/end"):
            return await self._remote(req, p, sid)

        if p == "/api/files":
            self._method(req, "GET")
            return Response(200, {"toPhone": svc.files.to_phone_list(), "received": svc.files.received_list()[:50], "maxBytes": svc.files.max_bytes})
        if p == "/api/files/upload":
            self._method(req, "PUT")
            name = req.query.get("name", "")
            if not name or req.length <= 0:
                raise HttpError(400, "a file name and a non-empty body are required")
            path = await svc.files.receive(req.reader, req.length, name)
            req._consumed = True
            svc.activity.record("file_received", phone=self._phone_name(req), name=path.name, bytes=req.length)
            return ok(name=path.name, size=req.length)
        if p == "/api/files/download":
            self._method(req, "GET")
            path = svc.files.open_to_phone(req.query.get("name", ""))
            svc.activity.record("file_sent", phone=self._phone_name(req), name=path.name)
            return Response(200, ctype="application/octet-stream", file=(path, path.stat().st_size),
                            headers={"Content-Disposition": f'attachment; filename="{path.name}"'})

        if p == "/api/media":
            self._method(req, "POST")
            cmd = (await req.json()).get("cmd")
            if cmd not in MEDIA_CMDS:
                raise HttpError(400, "cmd must be toggle, next or prev")
            await self.source.command("media", {"cmd": cmd})
            return ok()
        if p == "/api/shortcuts/run":
            self._method(req, "POST")
            i = (await req.json()).get("id")
            if not isinstance(i, int) or isinstance(i, bool) or not 0 <= i < self.source.button_count():
                raise HttpError(400, "unknown shortcut id")
            svc.activity.record("shortcut", phone=self._phone_name(req), id=i)
            await self.source.command("shortcut", {"id": i})
            return ok()
        if p == "/api/alerts/action":
            self._method(req, "POST")
            body = await req.json()
            if not isinstance(body.get("id"), str) or not body["id"] or len(body["id"]) > 200 or body.get("action") not in ALERT_ACTIONS:
                raise HttpError(400, "id and action (dismiss) required")
            await self.source.command("alert_action", {"id": body["id"], "action": body["action"]})
            return ok()
        if p == "/api/speedtest":
            self._method(req, "POST")
            await self.source.command("speedtest", {})
            return ok()
        raise HttpError(404, "unknown endpoint")

    @staticmethod
    def _method(req: Request, *allowed: str):
        if req.method not in allowed:
            raise HttpError(405, f"use {allowed[0]}")

    def _authenticate(self, req: Request):
        if self._limited(req.peer):
            raise HttpError(429, "too many failed attempts")
        entry = self.svc.sessions.verify(bearer(req.headers))
        if entry is None:
            self._fail(req.peer)
            raise HttpError(401, "missing or invalid device token")
        req.session = entry

    def _phone_name(self, req: Request) -> str:
        return (req.session or {}).get("name", "phone")

    async def _claim(self, req: Request, method: str) -> Response:
        svc = self.svc
        before = svc.lockout.status(req.peer)
        if before["locked"]:
            raise HttpError(429, "locked", {k: before[k] for k in ("retryInMs", "global")})
        body = await req.json()
        after = svc.lockout.status(req.peer)
        if after["locked"]:
            raise HttpError(429, "locked", {k: after[k] for k in ("retryInMs", "global")})
        secret_ok = (svc.pairing.invites.consume(body.get("secret")) if method == "qr" else svc.pairing.consume_pin(body.get("pin")))
        if body.get("hostId") != svc.host_id or not secret_ok:
            svc.lockout.record_failure(req.peer)
            svc.activity.record("pairing_failed", method=method, peer=req.peer)
            raise HttpError(401, "invitation or PIN expired or invalid")
        svc.lockout.record_success(req.peer)
        device = str(body.get("device") or "phone")
        token = svc.sessions.issue(device)
        entry = svc.sessions.verify(token)
        svc.activity.record("phone_paired", phone=entry["name"], method=method, peer=req.peer)
        return Response(200, {"ok": True, "hostId": svc.host_id, "token": token})

    async def _remote(self, req: Request, p: str, sid: str) -> Response:
        """Mouse, keyboard, volume, power and task manager. Off for every phone until the PC's owner switches it on."""
        svc, be = self.svc, self.svc.remote
        if p == "/api/remote":
            self._method(req, "GET")
            return Response(200, {"allowed": bool(req.session.get("remote")), "battery": be.battery()})
        if not req.session.get("remote"):
            raise HttpError(403, "Remote control is off for this phone. Turn it on in Hubuddy on the PC.")
        now = self.clock()
        window = self._rc_rate.setdefault(sid, [])
        window[:] = [t for t in window if now - t < 1.0]
        if len(window) >= 20:
            raise HttpError(429, "too many remote actions per second")
        window.append(now)
        name = self._phone_name(req)
        try:
            if p == "/api/input":
                self._method(req, "POST")
                events = rc.validate_events((await req.json(32768)).get("events"))
                for e in events:
                    t = e["t"]
                    if t == "move": be.move(e["dx"], e["dy"])
                    elif t == "click": be.click(e["button"], e["double"])
                    elif t == "scroll": be.scroll(e["dy"])
                    elif t == "key": be.key(e["key"], e["mods"])
                    else: be.text(e["text"])
                if any(e["t"] in ("key", "text") for e in events):
                    svc.activity.record("remote_input", phone=name, events=len(events))
                return ok(count=len(events))
            if p == "/api/volume":
                self._method(req, "POST")
                action = (await req.json()).get("action")
                if action not in rc.VOLUME:
                    raise rc.BadRequest("action must be up, down or mute")
                be.key({"up": "volup", "down": "voldown", "mute": "mute"}[action], [])
                return ok()
            if p == "/api/power":
                self._method(req, "POST")
                action = (await req.json()).get("action")
                if action not in rc.POWER:
                    raise rc.BadRequest("action must be sleep, restart, shutdown or abort")
                be.power(action)
                svc.activity.record("remote_power", phone=name, action=action)
                return ok(action=action, delaySeconds=15 if action in ("restart", "shutdown") else 0)
            if p == "/api/processes":
                self._method(req, "GET")
                return Response(200, {"items": be.processes()})
            self._method(req, "POST")
            pid = (await req.json()).get("pid")
            if isinstance(pid, bool) or not isinstance(pid, int):
                raise rc.BadRequest("pid must be a number")
            ended = be.end(pid)
            svc.activity.record("remote_end_task", phone=name, program=ended)
            return ok()
        except rc.BadRequest as e:
            raise HttpError(400, str(e))
        except rc.Unavailable as e:
            raise HttpError(501, str(e) or "this PC cannot do that")

    async def _phone_sends(self, req: Request, sid: str) -> Response:
        body = await req.json()
        kind, text = body.get("kind"), body.get("text")
        if kind not in ("clipboard", "link") or not isinstance(text, str) or not text or len(text) > 20_000:
            raise HttpError(400, "kind must be clipboard or link, with text")
        if kind == "link":
            if not text.lower().startswith(("http://", "https://")) or len(text) > 2000 or any(c in text for c in " \r\n\t"):
                raise HttpError(400, "a link must start with http:// or https:// and contain no spaces")
            if not self.svc.open_link(text):
                raise HttpError(501, "this PC could not open the link")
            self.svc.activity.record("link_opened", phone=self._phone_name(req), host=urllib.parse.urlsplit(text).hostname or "")
        else:
            if not self.svc.set_clipboard(text):
                raise HttpError(501, "this PC could not set the clipboard (supported on Windows)")
            self.svc.activity.record("clipboard_set", phone=self._phone_name(req), chars=len(text))
        return ok()

    # ======================================================================================================
    #  Local routes (HTTP on loopback, this PC's own pages)
    # ======================================================================================================
    def invite_details(self) -> dict:
        items = interfaces()
        primary = primary_address(items)
        extras = [i["address"] for i in items if i["address"] != primary][:3]
        return {"hostId": self.svc.host_id, "name": self.svc.name, "ca": self.svc.tls.public_ca, "port": self.port,
                "address": primary or "127.0.0.1", "addresses": extras, "mac": mac_for(primary, items) if primary else ""}

    def phones(self) -> list[dict]:
        out = []
        for e in self.svc.sessions.entries:
            sid = self.svc.sessions.session_id(e)
            out.append({"id": sid, "name": e["name"], "created": e["created"], "last_seen": e.get("last_seen", 0), "status": self.svc.status.get(sid), "remote": bool(e.get("remote"))})
        return out

    async def _route_local(self, req: Request) -> Response:
        svc, p, m = self.svc, req.path, req.method
        mutating = m not in ("GET", "HEAD")
        if not local_request_ok(req.headers, req.peer, mutating=mutating):
            raise HttpError(403, "this page is only available on the PC itself")
        if m == "GET" and (p == "/" or p == "/pair" or p.lstrip("/") in WEB_FILES):
            name = "index.html" if p in ("/", "/pair") else p.lstrip("/")
            if not self.web_dir or not (self.web_dir / name).is_file():
                raise HttpError(404, "page not found")
            csp = ("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
                   "frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
            return Response(200, (self.web_dir / name).read_bytes(), WEB_FILES[name], {"Content-Security-Policy": csp})
        if not p.startswith("/local/"):
            raise HttpError(404, "not found")

        if p == "/local/state" and m == "GET":
            return Response(200, {"app": "hubuddy", "version": VERSION, "hostId": svc.host_id, "name": svc.name, "port": self.port,
                                  "addresses": [i["address"] for i in interfaces()], "pairing": svc.pairing.status(), "phones": self.phones(),
                                  "toPhone": svc.files.to_phone_list(), "received": svc.files.received_list()[:30],
                                  "root": str(svc.files.root), "discovery": svc.discovery_on()})
        if p == "/local/invite" and m == "POST":
            svc.pairing.cancel()
            return Response(200, svc.pairing.invites.issue(self.invite_details()))
        if p == "/local/pin" and m == "POST":
            svc.pairing.cancel()
            started = svc.pairing.start_pin()
            return Response(200, {**started, "compare": compare_code(svc.tls.pin), "hostId": svc.host_id})
        if p == "/local/pairing/cancel" and m == "POST":
            svc.pairing.cancel()
            return ok()
        if p == "/local/forget" and m == "POST":
            sid = (await req.json()).get("id")
            gone = svc.sessions.revoke(sid) if isinstance(sid, str) else False
            if gone:
                svc.status.forget(sid)
                svc.activity.record("phone_forgotten", id=sid)
            return ok(removed=gone)
        if p == "/local/remote" and m == "POST":
            body = await req.json()
            sid, on = body.get("id"), body.get("on")
            if not isinstance(sid, str) or not isinstance(on, bool):
                raise HttpError(400, "id and on (true or false) required")
            changed = svc.sessions.set_remote(sid, on)
            if changed:
                svc.activity.record("remote_allowed" if on else "remote_blocked", id=sid)
            return ok(changed=changed)
        if p == "/local/forget-all" and m == "POST":
            if (await req.json()).get("confirm") != "forget-all":
                raise HttpError(400, "confirm forgetting all phones")
            svc.sessions.revoke_all()
            svc.pairing.cancel()
            svc.activity.record("all_phones_forgotten")
            return ok()
        if p == "/local/send" and m == "POST":
            body = await req.json()
            try:
                item = svc.inbox.add(body.get("kind"), body.get("text"), body.get("to", "all"))
            except ValueError as e:
                raise HttpError(400, str(e))
            svc.activity.record("sent_to_phone", kind=item["kind"], to=item["to"])
            return ok(id=item["id"])
        if p == "/local/files/upload" and m == "PUT":
            name = req.query.get("name", "")
            if not name or req.length <= 0:
                raise HttpError(400, "a file name and a non-empty body are required")
            path = await svc.files.receive(req.reader, req.length, name, folder=svc.files.to_phone)
            req._consumed = True
            svc.activity.record("file_offered", name=path.name, bytes=req.length)
            return ok(name=path.name)
        if p == "/local/files/remove" and m == "POST":
            return ok(removed=svc.files.remove_to_phone((await req.json()).get("name", "")))
        if p == "/local/activity" and m == "GET":
            return Response(200, {"events": svc.activity.recent(100)})
        if p == "/local/diagnostics" and m == "GET":
            return Response(200, {**svc.tls.diagnostics, "port": self.port, "udpPort": svc.discovery_port, "addresses": interfaces(),
                                  "firewall": {"tcp": self.port, "udp": svc.discovery_port}})
        raise HttpError(404, "unknown endpoint")
