"""Wake-on-LAN: the 102-byte magic packet. The phone sends it on its own network; this module serves tests and the PC side."""
import re
import socket

MAC = re.compile(r"^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")


def magic_packet(mac: str) -> bytes:
    if not isinstance(mac, str) or not MAC.match(mac):
        raise ValueError("not a MAC address")
    raw = bytes.fromhex(re.sub(r"[:-]", "", mac))
    return b"\xff" * 6 + raw * 16


def send(mac: str, broadcast: str = "255.255.255.255", port: int = 9) -> None:
    pkt = magic_packet(mac)
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.sendto(pkt, (broadcast, port))
