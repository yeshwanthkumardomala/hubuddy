"""Run the Hubuddy agent on a PC:

    python -m hubuddy_agent                  # USB mode: 127.0.0.1:8765 (use `adb reverse`)
    python -m hubuddy_agent --lan            # also reachable on the local network (private clients only)
    python -m hubuddy_agent --pair           # print a new pairing code
    python -m hubuddy_agent --list-devices   # or: --revoke <id|all>
"""
import argparse
import asyncio
import logging
import pathlib
import sys

from . import VERSION
from .auth import DEFAULT_STORE, DeviceAuth
from .config import find_config, load
from .server import AgentServer

WEB_DIR = pathlib.Path(__file__).resolve().parent.parent / "web"


async def amain(args, auth: DeviceAuth, cfg: dict):
    from .source import Agent

    agent = Agent(cfg)
    host = "0.0.0.0" if args.lan else args.host
    server = AgentServer(agent, auth, host=host, port=args.port, web_dir=None if args.no_web else WEB_DIR)
    await server.start()
    print(f"Hubuddy agent {VERSION} listening on {host}:{server.port}")
    if host in ("127.0.0.1", "localhost"):
        print(f"USB mode: connect the phone and run   adb reverse tcp:{server.port} tcp:{server.port}")
    else:
        print("Wi-Fi mode: only private-network clients are accepted. Traffic is NOT encrypted; use a network you trust.")
    if args.pair or not auth.devices:
        print(f"\n  Pairing code: {auth.new_code(args.pair_minutes)}   (valid {args.pair_minutes:g} min, single use)\n")
    else:
        print(f"{len(auth.devices)} paired device(s). Start with --pair to pair another.")
    if not agent.system.available:
        print("Note: install psutil (pip install -r requirements.txt) to report CPU, memory and disk.")
    await agent.run()


def main():
    ap = argparse.ArgumentParser(description="Hubuddy agent")
    ap.add_argument("--config", help="config.toml (default: ./config.toml, then ~/.hubuddy/config.toml)")
    ap.add_argument("--host", default="127.0.0.1", help="bind address (default 127.0.0.1)")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--lan", action="store_true", help="listen on all interfaces (private clients only)")
    ap.add_argument("--pair", action="store_true", help="print a new pairing code")
    ap.add_argument("--pair-minutes", type=float, default=10)
    ap.add_argument("--no-web", action="store_true", help="do not serve the optional web dashboard")
    ap.add_argument("--devices-file", default=str(DEFAULT_STORE))
    ap.add_argument("--list-devices", action="store_true")
    ap.add_argument("--revoke", metavar="ID|all")
    ap.add_argument("--verbose", "-v", action="store_true")
    args = ap.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    auth = DeviceAuth(pathlib.Path(args.devices_file))
    if args.list_devices:
        for d in auth.devices:
            print(f"{d['id']}  {d['name']}  created {d['created']}  last seen {d.get('last_seen', 0)}")
        return
    if args.revoke:
        print(f"revoked {auth.revoke(args.revoke)} device(s)")
        return
    try:
        cfg = load(find_config(args.config))
    except ValueError as e:
        sys.exit(str(e))
    try:
        asyncio.run(amain(args, auth, cfg))
    except KeyboardInterrupt:
        pass
    except ValueError as e:  # e.g. a bad [[shortcuts]] entry
        sys.exit(f"Config problem: {e}")


if __name__ == "__main__":
    main()
