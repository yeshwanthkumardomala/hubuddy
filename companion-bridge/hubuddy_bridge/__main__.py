"""Run the Hubuddy DeskHub bridge.

From the DeskHub companion folder (so the `deskhub` package and its config are found):

    uv run python -m hubuddy_bridge                 # USB mode: 127.0.0.1:8765 (adb reverse)
    uv run python -m hubuddy_bridge --lan           # also listen on the LAN (private clients only)
    uv run python -m hubuddy_bridge --list-devices
    uv run python -m hubuddy_bridge --revoke all
"""
import argparse
import asyncio
import logging
import pathlib
import sys
import tomllib

from . import VERSION
from .auth import DEFAULT_STORE, DeviceAuth
from .server import BridgeServer

log = logging.getLogger("hubuddy.bridge")
WEB_DIR = pathlib.Path(__file__).resolve().parent.parent / "web"


def find_config(arg):
    cands = [pathlib.Path(arg)] if arg else [pathlib.Path.cwd() / "config.toml"]
    for p in cands:
        if p.is_file():
            return p
    sys.exit("config.toml not found. Run from the DeskHub companion folder or pass --config.")


async def amain(args, auth: DeviceAuth):
    from .source import DeskhubSource

    cfg = tomllib.loads(find_config(args.config).read_text(encoding="utf-8"))
    source = DeskhubSource(cfg, also_board=args.also_board)
    host = "0.0.0.0" if args.lan else args.host
    server = BridgeServer(source, auth, host=host, port=args.port,
                          web_dir=None if args.no_web else WEB_DIR)
    await server.start()
    print(f"Hubuddy DeskHub bridge {VERSION} listening on {host}:{server.port}")
    if host in ("127.0.0.1", "localhost"):
        print("USB mode: connect the phone and run   adb reverse tcp:%d tcp:%d" % (server.port, server.port))
    else:
        print("LAN mode: requests are accepted only from private/local addresses. Traffic is NOT encrypted.")
    if args.pair or not auth.devices:
        code = auth.new_code(args.pair_minutes)
        print(f"\n  Pairing code: {code}   (valid {args.pair_minutes:g} min, single use)\n")
    else:
        print(f"{len(auth.devices)} paired device(s). Start with --pair to pair another.")
    await source.run()


def main():
    ap = argparse.ArgumentParser(description="Hubuddy DeskHub bridge")
    ap.add_argument("--config", help="DeskHub config.toml (default: ./config.toml)")
    ap.add_argument("--host", default="127.0.0.1", help="bind address (default 127.0.0.1)")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--lan", action="store_true", help="listen on all interfaces (private clients only)")
    ap.add_argument("--pair", action="store_true", help="print a new pairing code")
    ap.add_argument("--pair-minutes", type=float, default=10)
    ap.add_argument("--also-board", action="store_true", help="also stream to the ESP32 DeskHub board")
    ap.add_argument("--no-web", action="store_true", help="do not serve the optional web dashboard")
    ap.add_argument("--devices-file", default=str(DEFAULT_STORE))
    ap.add_argument("--list-devices", action="store_true")
    ap.add_argument("--revoke", metavar="ID|all")
    ap.add_argument("--verbose", "-v", action="store_true")
    args = ap.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    for noisy in ("websockets", "httpx"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    auth = DeviceAuth(pathlib.Path(args.devices_file))
    if args.list_devices:
        for d in auth.devices:
            print(f"{d['id']}  {d['name']}  created {d['created']}  last seen {d.get('last_seen', 0)}")
        return
    if args.revoke:
        print(f"revoked {auth.revoke(args.revoke)} device(s)")
        return
    try:
        asyncio.run(amain(args, auth))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
