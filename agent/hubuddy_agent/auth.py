"""Request authorization helpers (ported in spirit from a design with the same threat model).

* The Host header must be a name we recognise (or an IP literal). A web page whose domain resolves to this PC
  (DNS rebinding) sends its own domain as Host and is refused.
* Loopback is trusted only for the local pages, and only when the Host header is a loopback name too.
"""
import ipaddress
import re
import socket

from .netinfo import is_loopback

HOST_HEADER = re.compile(r"^(?:\[[0-9a-f:.]+\]|[a-z0-9._-]+)(?::\d{1,5})?$", re.I)
LOCAL_ACTION_HEADER = "x-hubuddy-local"


def host_name(header) -> str:
    """The name part of a Host header, without port or IPv6 brackets; "" when it does not parse."""
    if not isinstance(header, str) or not HOST_HEADER.match(header):
        return ""
    h = header.lower()
    if h.startswith("["):
        return h[1:h.index("]")]
    return h.rsplit(":", 1)[0].rstrip(".") if re.search(r":\d{1,5}$", h) else h.rstrip(".")


def is_loopback_host(header) -> bool:
    n = host_name(header)
    return n == "localhost" or is_loopback(n)


def _is_ip(name: str) -> bool:
    try:
        ipaddress.ip_address(name)
        return True
    except ValueError:
        return False


def make_host_check(machine: str | None = None, extra: list[str] | None = None, tls_name: str = ""):
    names = {"localhost"}
    base = host_name((machine or socket.gethostname() or "").strip())
    if base:
        names |= {base, f"{base}.local"}
    if tls_name:
        names.add(tls_name.lower())
    for item in extra or []:
        n = host_name(item.strip())
        if n:
            names.add(n)

    def allowed(header) -> bool:
        n = host_name(header)
        return bool(n) and (_is_ip(n) or n in names)
    return allowed


def local_request_ok(headers: dict, remote: str, port: int | None = None, mutating: bool = False) -> bool:
    """A request to the local (loopback-only) pages: loopback peer, loopback Host, same-origin or no Origin."""
    if not is_loopback(remote) or not is_loopback_host(headers.get("host")):
        return False
    origin = headers.get("origin")
    if origin:
        m = re.match(r"^http://([^/]+)$", origin)
        if not m or not is_loopback_host(m.group(1)):
            return False
    if headers.get("sec-fetch-site") not in (None, "same-origin", "none"):
        return False
    if mutating and headers.get(LOCAL_ACTION_HEADER) != "1":
        return False
    return True


def bearer(headers: dict) -> str | None:
    value = headers.get("authorization", "")
    return value[7:].strip() if value[:7].lower() == "bearer " else None
