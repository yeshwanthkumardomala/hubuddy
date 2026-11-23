"""LAN discovery: a phone broadcasts a small probe and the PC answers with where it is, so nobody types an IP.

The reply carries no secret: it says where this PC is and what it is called, not who may use it. Pairing and every
API call are still protected separately. Oversized or unknown datagrams are ignored and answers are rate-limited per
sender, so the socket cannot be used for amplification or reflection.
"""
import asyncio
import json
import logging
import time
from collections import defaultdict, deque

from .netinfo import is_private_peer

log = logging.getLogger("hubuddy.discovery")

PORT = 41236
PROBE = "hubuddy:discover:v1"
REPLY = "hubuddy:here:v1:"
MAX_PROBE = 64
MAX_REPLY = 400
PER_SENDER = 8  # answers per sender per second


def reply_for(data: bytes, info: dict) -> bytes | None:
    """The reply to one datagram, or None when it must be ignored."""
    if not isinstance(data, (bytes, bytearray)) or not 0 < len(data) <= MAX_PROBE:
        return None
    try:
        if data.decode("utf-8").strip() != PROBE:
            return None
    except UnicodeDecodeError:
        return None
    out = (REPLY + json.dumps({"app": "hubuddy", "hostId": info["hostId"], "name": info["name"][:40], "port": int(info["port"])},
                              separators=(",", ":"))).encode()
    return out if len(out) <= MAX_REPLY else None


class _Protocol(asyncio.DatagramProtocol):
    def __init__(self, info, clock):
        self.info, self.clock, self.transport = info, clock, None
        self.recent = defaultdict(deque)

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        host = addr[0]
        if not is_private_peer(host):
            return
        now = self.clock()
        q = self.recent[host]
        while q and now - q[0] > 1:
            q.popleft()
        if len(q) >= PER_SENDER:
            return
        q.append(now)
        if len(self.recent) > 512:
            self.recent.clear()
        out = reply_for(data, self.info())
        if out:
            self.transport.sendto(out, addr)


async def start(info, port: int = PORT, host: str = "0.0.0.0", clock=time.monotonic):
    """Start answering probes. `info` is a callable returning {hostId, name, port}. Returns the transport, or None
    when the port is taken (the PC is then found by typing its address instead)."""
    loop = asyncio.get_running_loop()
    try:
        transport, _ = await loop.create_datagram_endpoint(lambda: _Protocol(info, clock), local_addr=(host, port), allow_broadcast=True)
        return transport
    except OSError as e:
        log.warning("discovery port %s unavailable (%s); phones will need the address typed", port, e)
        return None
