"""Run the Hubuddy agent on a PC or laptop:

    python -m hubuddy_agent                    # start; open http://127.0.0.1:8766/ on this PC to pair a phone
    python -m hubuddy_agent --show-invite      # also print the pairing invitation (QR) in this window
    python -m hubuddy_agent --pin              # also print a PIN to pair without a camera
    python -m hubuddy_agent --list-phones      # or: --forget <id|all>
"""
import argparse
import asyncio
import logging
import pathlib
import sys

from . import VERSION, discovery, ports, services
from .config import HOME_DIR, find_config, load
from .server import AgentServer

DEFAULT_LOCAL_PORT = 8766


def web_dir() -> pathlib.Path:
    base = pathlib.Path(getattr(sys, "_MEIPASS", pathlib.Path(__file__).resolve().parent.parent))
    return base / "web"


def print_invite(server: AgentServer, svc) -> None:
    import segno
    issued = svc.pairing.invites.issue(server.invite_details())
    print("\nScan this with Hubuddy on the phone (Settings > PCs and laptops > Scan QR), within 2 minutes:\n")
    segno.make(issued["invitation"], error="m").terminal(compact=True, border=2)
    print("\nNo camera? Paste this invitation into the app instead:\n" + issued["invitation"] + "\n")


async def amain(args, svc, cfg: dict):
    from .source import Agent

    agent = Agent(cfg, home=svc.home)
    port = ports.choose(svc.home, args.port, args.host)
    server = AgentServer(agent, svc, lan_host=args.host, port=port, local_port=args.local_port, web_dir=web_dir())
    await server.start()
    if not args.no_discovery:
        svc.discovery_transport = await discovery.start(server.info)
    print(f"Hubuddy agent {VERSION} on {svc.name}")
    print(f"  Phones connect to  https://<this PC>:{server.port}  (found automatically on the same network)")
    print(f"  Pair a phone at    http://127.0.0.1:{server.local_port}/   (open this on this PC)")
    if not svc.discovery_on():
        print("  Note: automatic discovery is off; type this PC's address on the phone.")
    if args.show_invite:
        print_invite(server, svc)
    if args.pin:
        started = svc.pairing.start_pin()
        from .pairing import compare_code
        print(f"\n  PIN: {started['pin']}    compare code (must match the phone): {compare_code(svc.tls.pin)}    (valid 2 minutes)\n")
    if not agent.system.available:
        print("Note: install psutil (pip install -r requirements.txt) to report CPU, memory and disk.")
    await agent.run()


def main(argv=None):
    ap = argparse.ArgumentParser(description="Hubuddy agent")
    ap.add_argument("--config", help="config.toml (default: ./config.toml, then ~/.hubuddy/config.toml)")
    ap.add_argument("--home", default=str(HOME_DIR), help="where identity, pairings and logs are kept")
    ap.add_argument("--host", default="0.0.0.0", help="address for phones to connect to (default: all, private clients only)")
    ap.add_argument("--port", type=int, default=None, help="phone port (default 8765; another free one is chosen if taken)")
    ap.add_argument("--local-port", type=int, default=DEFAULT_LOCAL_PORT, help="port of this PC's own pages (loopback only)")
    ap.add_argument("--no-discovery", action="store_true", help="do not answer LAN discovery probes")
    ap.add_argument("--show-invite", action="store_true", help="print a pairing QR code in this window")
    ap.add_argument("--pin", action="store_true", help="print a PIN for pairing without a camera")
    ap.add_argument("--list-phones", action="store_true")
    ap.add_argument("--forget", metavar="ID|all")
    ap.add_argument("--selftest", action="store_true", help="run the full pairing and API flow against this program and exit")
    ap.add_argument("--selftest-output", help="write the self-test result (JSON) to this file")
    ap.add_argument("--verbose", "-v", action="store_true")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    if args.selftest:
        from .selftest import run
        return run(args.selftest_output)
    try:
        svc = services.build(args.home)
    except Exception as e:  # identity problems come with a recovery message
        sys.exit(str(e))
    if args.list_phones:
        for p in svc.sessions.listing():
            print(f"{p['id']}  {p['name']}  paired {p['created']}  last seen {p['last_seen']}")
        return
    if args.forget:
        n = len(svc.sessions) if args.forget == "all" else int(svc.sessions.revoke(args.forget))
        if args.forget == "all":
            svc.sessions.revoke_all()
        print(f"forgot {n} phone(s)")
        return
    try:
        cfg = load(find_config(args.config))
    except ValueError as e:
        sys.exit(str(e))
    try:
        asyncio.run(amain(args, svc, cfg))
    except KeyboardInterrupt:
        pass
    except (OSError, ValueError) as e:
        sys.exit(f"Could not start: {e}")


if __name__ == "__main__":
    sys.exit(main())
