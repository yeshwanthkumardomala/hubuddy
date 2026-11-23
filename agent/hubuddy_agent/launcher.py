"""The Hubuddy window for Windows: runs the agent, shows who is paired, and starts pairing with one click."""
import asyncio
import os
import queue
import sys
import threading
import webbrowser

from . import VERSION, discovery, ports, services, winsetup
from .config import HOME_DIR, find_config, load
from .pairing import compare_code
from .server import AgentServer

LOCAL_PORT = 8766


class Runtime:
    """The agent on a background thread so the window never blocks."""

    def __init__(self, home=None):
        self.svc = services.build(home or HOME_DIR)
        self.server = None
        self.loop = asyncio.new_event_loop()
        self.ready = threading.Event()
        self.error = None
        self.events: "queue.Queue[dict]" = queue.Queue()
        self.svc.activity.listeners.append(self.events.put)
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self._start())
        except Exception as e:
            self.error = e
            self.ready.set()
            return
        self.ready.set()
        self.loop.run_forever()

    async def _start(self):
        from .__main__ import web_dir
        from .source import Agent
        agent = Agent(load(find_config(None)), home=self.svc.home)
        port = ports.choose(self.svc.home, None, "0.0.0.0")
        self.server = AgentServer(agent, self.svc, lan_host="0.0.0.0", port=port, local_port=LOCAL_PORT, web_dir=web_dir())
        await self.server.start()
        self.svc.discovery_transport = await discovery.start(self.server.info)
        self.loop.create_task(agent.run())

    def call(self, coro_or_fn):
        return self.loop.call_soon_threadsafe(coro_or_fn)


def open_page(port: int, fragment: str = "") -> None:
    webbrowser.open(f"http://127.0.0.1:{port}/{fragment}")


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--selftest" in argv:
        from .selftest import run
        out = argv[argv.index("--selftest-output") + 1] if "--selftest-output" in argv else None
        return run(out)
    minimized = "--minimized" in argv
    try:
        import tkinter as tk
        from tkinter import messagebox, ttk
        if sys.platform.startswith("linux") and not (os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY")):
            raise ImportError("no display")
    except ImportError as e:
        # Minimal Linux installs and servers have no Tk or no desktop: run the same program without a window.
        print(f"No desktop window available ({e}); running without one. Pair a phone with the QR code below "
              "or open http://127.0.0.1:8766/ in a browser on this PC.", file=sys.stderr)
        from .__main__ import main as cli
        return cli(["--show-invite"] + [a for a in argv if a not in ("--minimized",)])

    root = tk.Tk()
    root.title(f"Hubuddy {VERSION}")
    root.geometry("460x520")
    root.minsize(420, 460)
    try:
        rt = Runtime()
    except Exception as e:
        messagebox.showerror("Hubuddy", f"Could not start:\n\n{e}")
        return 1
    rt.ready.wait(20)
    if rt.error is not None:
        if isinstance(rt.error, OSError):  # the local page port is taken: another Hubuddy is probably running
            open_page(LOCAL_PORT)
            messagebox.showinfo("Hubuddy", "Hubuddy is already running. Opening its page.")
        else:
            messagebox.showerror("Hubuddy", f"Could not start:\n\n{rt.error}")
        return 1

    srv, svc = rt.server, rt.svc
    frame = ttk.Frame(root, padding=14)
    frame.pack(fill="both", expand=True)
    ttk.Label(frame, text="Hubuddy", font=("Segoe UI", 18, "bold")).pack(anchor="w")
    ttk.Label(frame, text=f"{svc.name} is ready. Phones on the same Wi-Fi or LAN can find it.").pack(anchor="w", pady=(0, 8))
    addrs = ", ".join(a["address"] if isinstance(a, dict) else a for a in __import__("hubuddy_agent.netinfo", fromlist=["x"]).interfaces()[:3]) or "no network"
    ttk.Label(frame, text=f"Address: {addrs}   Port: {srv.port}", foreground="#555").pack(anchor="w")

    row = ttk.Frame(frame)
    row.pack(fill="x", pady=10)

    def pair_qr():
        open_page(srv.local_port)

    def pair_pin():
        svc.pairing.cancel()
        s = svc.pairing.start_pin()
        messagebox.showinfo("Pair with a PIN",
                            f"On the phone: Hubuddy > Find PCs > {svc.name}.\n\nCompare code (must match the phone):\n    {compare_code(svc.tls.pin)}\n\nPIN:\n    {s['pin']}\n\nValid for 2 minutes. A QR code is safer if a camera is available.")

    ttk.Button(row, text="Pair a phone (QR)", command=pair_qr).pack(side="left", padx=(0, 6))
    ttk.Button(row, text="Pair with a PIN", command=pair_pin).pack(side="left", padx=(0, 6))
    ttk.Button(row, text="Send & files", command=pair_qr).pack(side="left")

    ttk.Label(frame, text="Paired phones", font=("Segoe UI", 11, "bold")).pack(anchor="w", pady=(8, 2))
    box = tk.Listbox(frame, height=7, activestyle="none")
    box.pack(fill="both", expand=True)
    shown: list[str] = []

    def refresh():
        phones = srv.phones()
        box.delete(0, "end")
        shown[:] = [p["id"] for p in phones]
        for p in phones:
            st = p.get("status") or {}
            extra = f"  battery {st['battery']}%" if "battery" in st else ""
            box.insert("end", f"{p['name']}{extra}")
        if not phones:
            box.insert("end", "No phones paired yet.")

    def forget():
        sel = box.curselection()
        if sel and sel[0] < len(shown) and messagebox.askyesno("Forget phone", "This phone will need to pair again. Continue?"):
            svc.sessions.revoke(shown[sel[0]])
            svc.activity.record("phone_forgotten", id=shown[sel[0]])
            refresh()

    def forget_all():
        if messagebox.askyesno("Forget all phones", "Every phone will be signed out and must pair again. Continue?"):
            svc.sessions.revoke_all()
            svc.pairing.cancel()
            svc.activity.record("all_phones_forgotten")
            refresh()

    r2 = ttk.Frame(frame)
    r2.pack(fill="x", pady=6)
    ttk.Button(r2, text="Forget selected", command=forget).pack(side="left", padx=(0, 6))
    ttk.Button(r2, text="Forget all phones", command=forget_all).pack(side="left")

    auto = tk.BooleanVar(value=winsetup.autostart_enabled())

    def toggle_auto():
        if not winsetup.set_autostart(auto.get()):
            auto.set(not auto.get())
            messagebox.showwarning("Hubuddy", "Could not change the Windows startup setting (Windows only).")
    ttk.Checkbutton(frame, text="Start Hubuddy with Windows", variable=auto, command=toggle_auto).pack(anchor="w", pady=(6, 0))

    def firewall():
        ok = winsetup.allow_through_firewall(srv.port, svc.discovery_port)
        messagebox.showinfo("Firewall", "Hubuddy is allowed on private networks." if ok else
                            "Could not add the firewall rules. Allow Hubuddy when Windows asks, or run the installer.")
    ttk.Button(frame, text="Allow through Windows Firewall (private networks)", command=firewall).pack(anchor="w", pady=6)
    ttk.Label(frame, text="Closing this window keeps Hubuddy running in the background; use Quit to stop it.", foreground="#777", wraplength=420).pack(anchor="w")
    ttk.Button(frame, text="Quit Hubuddy", command=root.destroy).pack(anchor="e", pady=(8, 0))

    def poll():
        try:
            while True:
                ev = rt.events.get_nowait()
                if ev.get("event") == "phone_paired":
                    messagebox.showinfo("New phone paired", f"{ev.get('phone', 'A phone')} can now see this PC.\nNot you? Use Forget all phones.")
                    refresh()
        except queue.Empty:
            pass
        root.after(1000, poll)

    root.protocol("WM_DELETE_WINDOW", root.iconify)
    refresh()
    poll()
    if minimized:
        root.iconify()
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
