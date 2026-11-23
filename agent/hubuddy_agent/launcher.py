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
        self.discovery_error = None
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
        try:
            self.svc.discovery_transport = await discovery.start(self.server.info)
        except OSError as e:   # another copy already answers discovery, or the network refused: pairing by QR still works
            self.discovery_error = str(e)
        self.loop.create_task(agent.run())

    def call(self, coro_or_fn):
        return self.loop.call_soon_threadsafe(coro_or_fn)


def open_page(port: int, fragment: str = "") -> None:
    webbrowser.open(f"http://127.0.0.1:{port}/{fragment}")


def enable_dpi_awareness() -> None:
    """Without this Windows scales the window as a bitmap, which looks blurry on high-resolution laptop screens."""
    if sys.platform == "win32":
        try:
            import ctypes
            try:
                ctypes.windll.shcore.SetProcessDpiAwareness(1)
            except (AttributeError, OSError):
                ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


class Window:
    """The Hubuddy window. Everything a person needs to pair phones is in it, so no browser is required."""

    def __init__(self, tk, ttk, messagebox, runtime: Runtime, minimized: bool = False):
        self.tk, self.ttk, self.mb, self.rt = tk, ttk, messagebox, runtime
        self.srv, self.svc = runtime.server, runtime.svc
        self.shown: list[str] = []
        self.pair_until = 0.0
        self._after = None
        self.invitation = None
        self.root = tk.Tk()
        self.root.title(f"Hubuddy {VERSION}")
        self.root.geometry("560x700")
        self.root.minsize(520, 620)
        self.build()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.refresh()
        self.poll()
        if minimized:
            self.root.iconify()

    # ---- layout -------------------------------------------------------------------------
    def build(self):
        tk, ttk = self.tk, self.ttk
        outer = ttk.Frame(self.root, padding=14)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="Hubuddy", font=("Segoe UI", 20, "bold")).pack(anchor="w")
        ttk.Label(outer, text=f"{self.svc.name} is ready. Phones on the same Wi-Fi or LAN can find it.", wraplength=500).pack(anchor="w")
        from .netinfo import interfaces
        addrs = ", ".join(i["address"] for i in interfaces()[:3]) or "no network"
        ttk.Label(outer, text=f"Address {addrs}   ·   port {self.srv.port}", foreground="#666666").pack(anchor="w", pady=(0, 6))
        if self.rt.discovery_error:
            ttk.Label(outer, text="Automatic discovery is off (another Hubuddy may be running). Pair with the QR code.", foreground="#b45309", wraplength=500).pack(anchor="w")

        # -- pairing
        box = ttk.LabelFrame(outer, text="Pair a phone", padding=10)
        box.pack(fill="x", pady=8)
        row = ttk.Frame(box)
        row.pack(fill="x")
        ttk.Button(row, text="Show QR code", command=self.show_qr).pack(side="left", padx=(0, 6))
        ttk.Button(row, text="Pair with a PIN", command=self.show_pin).pack(side="left", padx=(0, 6))
        ttk.Button(row, text="Copy invitation text", command=self.copy_invite).pack(side="left")
        self.qr = tk.Canvas(box, width=240, height=240, highlightthickness=0, background="white")
        self.pair_info = ttk.Label(box, text="Open Hubuddy on the phone: Settings → Add a PC or laptop.", wraplength=500, justify="left")
        self.pair_info.pack(anchor="w", pady=(8, 0))
        self.pin_big = ttk.Label(box, text="", font=("Consolas", 26, "bold"))
        self.code_big = ttk.Label(box, text="", font=("Consolas", 14))

        # -- phones
        ttk.Label(outer, text="Paired phones", font=("Segoe UI", 11, "bold")).pack(anchor="w", pady=(8, 2))
        lf = ttk.Frame(outer)
        lf.pack(fill="both", expand=True)
        self.box = tk.Listbox(lf, height=6, activestyle="none", exportselection=False)
        sb = ttk.Scrollbar(lf, orient="vertical", command=self.box.yview)
        self.box.configure(yscrollcommand=sb.set)
        self.box.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        self.box.bind("<<ListboxSelect>>", lambda e: self.sync_remote())
        r2 = ttk.Frame(outer)
        r2.pack(fill="x", pady=6)
        ttk.Button(r2, text="Forget selected", command=self.forget).pack(side="left", padx=(0, 6))
        ttk.Button(r2, text="Forget all phones", command=self.forget_all).pack(side="left")
        self.remote = tk.BooleanVar(value=False)
        self.remote_box = ttk.Checkbutton(outer, text="Let the selected phone control this PC (mouse, keyboard, volume, power)",
                                          variable=self.remote, command=self.toggle_remote)
        self.remote_box.pack(anchor="w")
        ttk.Label(outer, text="Off by default. Turn it on only for phones you trust.", foreground="#666666").pack(anchor="w", pady=(0, 6))

        # -- system
        self.auto = tk.BooleanVar(value=winsetup.autostart_enabled())
        ttk.Checkbutton(outer, text="Start Hubuddy with Windows", variable=self.auto, command=self.toggle_auto).pack(anchor="w")
        ttk.Button(outer, text="Allow through Windows Firewall (private networks)", command=self.firewall).pack(anchor="w", pady=6)
        ttk.Label(outer, text="Closing this window asks whether to keep Hubuddy running in the background.", foreground="#666666", wraplength=500).pack(anchor="w")
        bottom = ttk.Frame(outer)
        bottom.pack(fill="x", pady=(8, 0))
        ttk.Button(bottom, text="Open the Hubuddy page", command=lambda: open_page(self.srv.local_port)).pack(side="left")
        ttk.Button(bottom, text="Quit Hubuddy", command=self.root.destroy).pack(side="right")

    # ---- pairing ------------------------------------------------------------------------
    def _clear_pairing(self):
        self.qr.pack_forget(); self.pin_big.pack_forget(); self.code_big.pack_forget()

    def show_qr(self):
        import time
        self.svc.pairing.cancel()
        issued = self.svc.pairing.invites.issue(self.srv.invite_details())
        self.invitation = issued["invitation"]
        self._clear_pairing()
        modules, quiet = issued["modules"], issued["quiet"]
        n = len(modules) + 2 * quiet
        cell = max(2, 240 // n)
        size = cell * n
        self.qr.configure(width=size, height=size)
        self.qr.delete("all")
        self.qr.create_rectangle(0, 0, size, size, fill="white", outline="white")
        for y, line in enumerate(modules):
            for x, on in enumerate(line):
                if on:
                    self.qr.create_rectangle((x + quiet) * cell, (y + quiet) * cell, (x + quiet + 1) * cell, (y + quiet + 1) * cell, fill="black", outline="black")
        self.qr.pack(pady=(8, 0))
        self.pair_until = time.time() + 120
        self.tick_pair("Scan with Hubuddy: Settings → Add a PC or laptop → Scan QR code.")

    def show_pin(self):
        import time
        self.svc.pairing.cancel()
        started = self.svc.pairing.start_pin()
        self._clear_pairing()
        self.pin_big.configure(text=f"PIN  {started['pin']}")
        self.code_big.configure(text=f"Compare code  {compare_code(self.svc.tls.pin)}")
        self.pin_big.pack(pady=(8, 0)); self.code_big.pack()
        self.pair_until = time.time() + 120
        self.tick_pair("On the phone: Find PCs on this network → tap this PC → check the compare code matches → enter the PIN.")

    def tick_pair(self, text: str):
        import time
        if self._after is not None:      # only one countdown runs at a time
            self.root.after_cancel(self._after)
            self._after = None
        left = int(self.pair_until - time.time())
        if left <= 0:
            self._clear_pairing()
            self.pair_info.configure(text="Pairing closed. Choose Show QR code or Pair with a PIN to start again.")
            return
        self.pair_info.configure(text=f"{text}  Valid for {left} s.")
        self._after = self.root.after(1000, lambda: self.tick_pair(text))

    def copy_invite(self):
        if not self.invitation or self.pair_until <= __import__("time").time():
            self.show_qr()
        self.root.clipboard_clear()
        self.root.clipboard_append(self.invitation)
        self.pair_info.configure(text="Invitation copied. In the app: Settings → Add a PC or laptop → Paste invitation.")

    # ---- phones -------------------------------------------------------------------------
    def selected(self):
        sel = self.box.curselection()
        return self.shown[sel[0]] if sel and sel[0] < len(self.shown) else None

    def refresh(self):
        phones = self.srv.phones()
        keep = self.selected()
        self.box.delete(0, "end")
        self.shown = [p["id"] for p in phones]
        self._remote_flags = {p["id"]: bool(p.get("remote")) for p in phones}
        for p in phones:
            st = p.get("status") or {}
            extra = f"   battery {st['battery']}%" if "battery" in st else ""
            lock = "   [remote control ON]" if p.get("remote") else ""
            self.box.insert("end", f"{p['name']}{extra}{lock}")
        if not phones:
            self.box.insert("end", "No phones paired yet.")
        elif keep in self.shown:
            self.box.selection_set(self.shown.index(keep))
        self.sync_remote()

    def sync_remote(self):
        sid = self.selected()
        self.remote.set(bool(sid and self._remote_flags.get(sid)))
        self.remote_box.state(["!disabled"] if sid else ["disabled"])

    def toggle_remote(self):
        sid = self.selected()
        if not sid:
            return
        on = self.remote.get()
        if on and not self.mb.askyesno("Remote control", "Let this phone move the mouse, type, change the volume and shut down this PC?"):
            self.remote.set(False)
            return
        self.svc.sessions.set_remote(sid, on)
        if not on:
            self.srv._release(sid)
        self.svc.activity.record("remote_allowed" if on else "remote_blocked", id=sid)
        self.refresh()

    def forget(self):
        sid = self.selected()
        if sid and self.mb.askyesno("Forget phone", "This phone will need to pair again. Continue?"):
            self.srv._release(sid)
            self.svc.sessions.revoke(sid)
            self.svc.activity.record("phone_forgotten", id=sid)
            self.refresh()

    def forget_all(self):
        if self.mb.askyesno("Forget all phones", "Every phone will be signed out and must pair again. Continue?"):
            for k in list(self.srv._rc_held):
                self.srv._release(k)
            self.svc.sessions.revoke_all()
            self.svc.pairing.cancel()
            self.svc.activity.record("all_phones_forgotten")
            self.refresh()

    # ---- system -------------------------------------------------------------------------
    def toggle_auto(self):
        if not winsetup.set_autostart(self.auto.get()):
            self.auto.set(not self.auto.get())
            self.mb.showwarning("Hubuddy", "Could not change the Windows startup setting (this works on Windows only).")

    def firewall(self):
        ok = winsetup.allow_through_firewall(self.srv.port, self.svc.discovery_port)
        self.mb.showinfo("Firewall", "Hubuddy is allowed on private networks." if ok else
                         "Could not add the firewall rules. Allow Hubuddy when Windows asks, or use the installer.")

    def on_close(self):
        answer = self.mb.askyesnocancel("Hubuddy", "Quit Hubuddy?\n\nYes: quit (phones lose the connection)\nNo: keep it running, minimized\nCancel: stay here")
        if answer is True:
            self.root.destroy()
        elif answer is False:
            self.root.iconify()

    def poll(self):
        import queue as _q
        try:
            while True:
                ev = self.rt.events.get_nowait()
                if ev.get("event") == "phone_paired":
                    self.pair_info.configure(text=f"{ev.get('phone', 'A phone')} is now paired. Not you? Choose Forget all phones.")
                    self._clear_pairing(); self.pair_until = 0
                    try:
                        self.root.bell()
                    except Exception:
                        pass
                    self.refresh()
                elif ev.get("event") in ("phone_forgotten", "all_phones_forgotten", "remote_allowed", "remote_blocked"):
                    self.refresh()
        except _q.Empty:
            pass
        self.root.after(1000, self.poll)

    def run(self):
        self.root.mainloop()


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

    enable_dpi_awareness()
    try:
        rt = Runtime()
    except Exception as e:
        _fatal(tk, messagebox, f"Could not start:\n\n{e}")
        return 1
    rt.ready.wait(20)
    if rt.error is not None:
        if isinstance(rt.error, OSError):   # the page's port is taken: another Hubuddy is probably running
            open_page(LOCAL_PORT)
            _fatal(tk, messagebox, "Hubuddy is already running. Opening its page.", info=True)
        else:
            _fatal(tk, messagebox, f"Could not start:\n\n{rt.error}")
        return 1
    try:
        Window(tk, ttk, messagebox, rt, minimized).run()
    except Exception as e:   # never leave a half-drawn window: say what went wrong
        _fatal(tk, messagebox, f"Hubuddy's window failed:\n\n{type(e).__name__}: {e}\n\nThe program can still be used from http://127.0.0.1:{LOCAL_PORT}/")
        return 1
    return 0


def _fatal(tk, messagebox, text: str, info: bool = False) -> None:
    root = tk.Tk()
    root.withdraw()
    (messagebox.showinfo if info else messagebox.showerror)("Hubuddy", text)
    root.destroy()


if __name__ == "__main__":
    sys.exit(main())
