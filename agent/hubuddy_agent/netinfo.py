"""Which network addresses this PC has, which peers count as "local", and which adapter is the real LAN."""
import ipaddress
import re
import socket

try:
    import psutil
except ImportError:
    psutil = None

VIRTUAL = re.compile(r"tailscale|zerotier|wireguard|vpn|^tun\d*|^tap\d*|vethernet|hyper-v|vmware|virtualbox|vbox|docker|^br-|^virbr|^veth|loopback", re.I)


def is_loopback(addr: str) -> bool:
    try:
        ip = ipaddress.ip_address(addr.split("%")[0])
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_loopback


def is_private_peer(addr: str) -> bool:
    """Loopback, RFC 1918 private, link-local and unique-local addresses only: never the public internet."""
    try:
        ip = ipaddress.ip_address(addr.split("%")[0])
    except ValueError:
        return False
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped:
        ip = ip.ipv4_mapped
    return ip.is_loopback or ip.is_link_local or (ip.is_private and not ip.is_unspecified)


def interfaces() -> list[dict]:
    """IPv4 interfaces that could carry a phone: [{name, address, netmask, mac, virtual}], best first."""
    found = []
    if psutil is not None:
        try:
            addrs = psutil.net_if_addrs()
            stats = psutil.net_if_stats()
        except OSError:
            addrs, stats = {}, {}
        for name, entries in addrs.items():
            if name in stats and not stats[name].isup:
                continue
            mac = next((e.address for e in entries if getattr(e.family, "name", "") in ("AF_LINK", "AF_PACKET")), "")
            for e in entries:
                if e.family != socket.AF_INET or not e.address:
                    continue
                ip = ipaddress.ip_address(e.address)
                if ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_unspecified:
                    continue
                found.append({"name": name, "address": e.address, "netmask": e.netmask or "255.255.255.0",
                              "mac": mac.replace("-", ":").lower(), "virtual": bool(VIRTUAL.search(name)), "private": ip.is_private})
    if not found:  # no psutil: ask the OS which address reaches the outside, without sending anything
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect(("192.0.2.1", 9))
                found.append({"name": "default", "address": s.getsockname()[0], "netmask": "255.255.255.0", "mac": "", "virtual": False,
                              "private": ipaddress.ip_address(s.getsockname()[0]).is_private})
        except OSError:
            pass
    found.sort(key=lambda i: (i["virtual"], not i["private"]))
    return found


def primary_address(items: list[dict] | None = None) -> str | None:
    items = interfaces() if items is None else items
    return items[0]["address"] if items else None


def mac_for(address: str, items: list[dict] | None = None) -> str:
    for i in (interfaces() if items is None else items):
        if i["address"] == address:
            return i["mac"]
    return ""
