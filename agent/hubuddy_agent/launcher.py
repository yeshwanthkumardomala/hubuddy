"""The Hubuddy window for Windows (and any desktop with Tk): runs the PC side, pairs phones, shows who is connected.

The window opens at once with the logo and a short "Starting" message; the PC side starts on a background thread and the
main window replaces the splash when it is ready. The window thread never waits for anything, so it cannot freeze.
"""
import asyncio
import os
import queue
import sys
import threading
import time
import webbrowser

from . import VERSION, discovery, ports, services, winsetup
from .config import HOME_DIR, find_config, load
from .pairing import compare_code
from .server import AgentServer

LOCAL_PORT = 8766

DARK = {"bg": "#0b1220", "card": "#131c2e", "card2": "#1b2740", "line": "#25324d", "text": "#e8eef7", "dim": "#8fa0b8",
        "accent": "#2ec4b6", "on_accent": "#06201d", "bad": "#ef6a6a", "warn": "#f0b04a", "ok": "#3ddc97"}
LIGHT = {"bg": "#f3f6fa", "card": "#ffffff", "card2": "#e9eff7", "line": "#d5dde9", "text": "#0f1a2b", "dim": "#5a6b82",
         "accent": "#0f9d8f", "on_accent": "#ffffff", "bad": "#c62828", "warn": "#b45309", "ok": "#177a4c"}


def windows_prefers_dark() -> bool:
    """Follows the Windows light/dark setting; dark when it cannot be read."""
    if sys.platform == "win32":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize") as k:
                return winreg.QueryValueEx(k, "AppsUseLightTheme")[0] == 0
        except OSError:
            pass
    return True


class Runtime:
    """The PC side, started on a background thread so the window never blocks."""

    def __init__(self, home=None, local_port=None):
        self.home, self.local_port = home, LOCAL_PORT if local_port is None else local_port
        self.svc = None
        self.server = None
        self.loop = asyncio.new_event_loop()
        self.ready = threading.Event()
        self.error = None
        self.discovery_error = None
        self.events: "queue.Queue[dict]" = queue.Queue()
        threading.Thread(target=self._run, daemon=True, name="hubuddy-server").start()

    def _run(self):
        asyncio.set_event_loop(self.loop)
        try:
            self.svc = services.build(self.home or HOME_DIR)
            self.svc.activity.listeners.append(self.events.put)
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
        self.server = AgentServer(agent, self.svc, lan_host="0.0.0.0", port=port, local_port=self.local_port, web_dir=web_dir())
        await self.server.start()
        try:
            self.svc.discovery_transport = await discovery.start(self.server.info)
        except OSError as e:   # another copy already answers discovery, or the network refused: pairing by QR still works
            self.discovery_error = str(e)
        self.loop.create_task(agent.run())


def open_page(port: int) -> None:
    webbrowser.open(f"http://127.0.0.1:{port}/")


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


class App:
    """Splash first, then the main window. [tk], [ttk] and [mb] are the tkinter modules (swapped for a stand-in in tests)."""

    def __init__(self, tk, ttk, mb, runtime: Runtime, minimized: bool = False, smoke: str | None = None, dark: bool | None = None):
        self.tk, self.ttk, self.mb, self.rt = tk, ttk, mb, runtime
        self.minimized, self.smoke = minimized, smoke
        self.pal = DARK if (windows_prefers_dark() if dark is None else dark) else LIGHT
        self.srv = self.svc = None
        self.shown_sig = None
        self.pair_until = 0.0
        self._after = None
        self._rows = {}
        self.invitation = None
        self.root = tk.Tk()
        self.root.withdraw()                       # build off-screen, then show once: no half-drawn flashes
        self.root.title("Hubuddy")
        self.root.configure(background=self.pal["bg"])
        self.style()
        self.set_icon()
        self.logo_image = self.load_logo(4)
        self.splash()
        self.center(420, 300, minsize=(420, 300))
        self.root.deiconify()
        self.root.protocol("WM_DELETE_WINDOW", self.on_close)
        self.root.after(60, self.wait_ready)
        if smoke:
            self.root.after(30000, lambda: self.root.destroy())   # never hang a CI run

    # ---- look -----------------------------------------------------------------------------
    def style(self):
        p, s = self.pal, self.ttk.Style()
        try:
            s.theme_use("clam")
        except Exception:
            pass
        font, bold = ("Segoe UI", 10), ("Segoe UI Semibold", 10)
        s.configure(".", background=p["bg"], foreground=p["text"], fieldbackground=p["card"], font=font, borderwidth=0, focuscolor=p["bg"])
        s.configure("TFrame", background=p["bg"])
        s.configure("Card.TFrame", background=p["card"])
        s.configure("TLabel", background=p["bg"], foreground=p["text"])
        s.configure("Card.TLabel", background=p["card"], foreground=p["text"])
        s.configure("Dim.TLabel", background=p["card"], foreground=p["dim"])
        s.configure("DimBg.TLabel", background=p["bg"], foreground=p["dim"])
        s.configure("Title.TLabel", background=p["bg"], foreground=p["text"], font=("Segoe UI Semibold", 20))
        s.configure("CardTitle.TLabel", background=p["card"], foreground=p["dim"], font=("Segoe UI Semibold", 9))
        s.configure("Name.TLabel", background=p["card"], foreground=p["text"], font=bold)
        s.configure("Pin.TLabel", background=p["card"], foreground=p["accent"], font=("Consolas", 28, "bold"))
        s.configure("Code.TLabel", background=p["card"], foreground=p["text"], font=("Consolas", 14))
        s.configure("TButton", background=p["card2"], foreground=p["text"], padding=(14, 8), relief="flat")
        s.map("TButton", background=[("active", p["line"]), ("pressed", p["line"])])
        s.configure("Accent.TButton", background=p["accent"], foreground=p["on_accent"], font=bold)
        s.map("Accent.TButton", background=[("active", p["accent"]), ("pressed", p["accent"])])
        s.configure("Danger.TButton", background=p["card2"], foreground=p["bad"])
        s.map("Danger.TButton", background=[("active", p["line"])])
        s.configure("TCheckbutton", background=p["card"], foreground=p["text"])
        s.map("TCheckbutton", background=[("active", p["card"])], indicatorcolor=[("selected", p["accent"]), ("!selected", p["card2"])])
        s.configure("TProgressbar", troughcolor=p["card2"], background=p["accent"], borderwidth=0, thickness=6)
        s.configure("TScrollbar", background=p["card2"], troughcolor=p["card"], borderwidth=0, arrowsize=12)

    def set_icon(self):
        try:
            from .__main__ import web_dir
            ico = web_dir().parent / "hubuddy.ico"
            if sys.platform == "win32" and ico.is_file():
                self.root.iconbitmap(default=str(ico))
                return
            png = web_dir() / "logo.png"
            if png.is_file():
                self.icon_image = self.tk.PhotoImage(file=str(png))
                self.root.iconphoto(True, self.icon_image)
        except Exception:
            pass   # a missing icon must never stop the window

    def load_logo(self, shrink: int):
        try:
            from .__main__ import web_dir
            img = self.tk.PhotoImage(file=str(web_dir() / "logo.png"))
            return img.subsample(shrink, shrink)
        except Exception:
            return None

    def center(self, w: int, h: int, minsize=None):
        self.root.update_idletasks()
        sw, sh = self.root.winfo_screenwidth(), self.root.winfo_screenheight()
        w, h = min(w, sw - 80), min(h, sh - 120)
        self.root.geometry(f"{w}x{h}+{max(0, (sw - w) // 2)}+{max(0, (sh - h) // 3)}")
        if minsize:
            self.root.minsize(*minsize)

    def card(self, parent, title: str):
        outer = self.tk.Frame(parent, background=self.pal["line"])           # 1 px border
        outer.pack(fill="x", pady=(0, 10))
        inner = self.ttk.Frame(outer, style="Card.TFrame", padding=(14, 10, 14, 12))
        inner.pack(fill="x", padx=1, pady=1)
        self.ttk.Label(inner, text=title.upper(), style="CardTitle.TLabel").pack(anchor="w", pady=(0, 6))
        return inner

    # ---- splash ---------------------------------------------------------------------------
    def splash(self):
        self.splash_frame = self.ttk.Frame(self.root, padding=30)
        self.splash_frame.pack(fill="both", expand=True)
        if self.logo_image:
            self.tk.Label(self.splash_frame, image=self.logo_image, background=self.pal["bg"]).pack(pady=(8, 8))
        self.ttk.Label(self.splash_frame, text="Hubuddy", style="Title.TLabel").pack()
        self.ttk.Label(self.splash_frame, text="Starting…", style="DimBg.TLabel").pack(pady=(2, 14))
        bar = self.ttk.Progressbar(self.splash_frame, mode="indeterminate", length=220)
        bar.pack()
        bar.start(14)

    def wait_ready(self):
        if not self.rt.ready.is_set():
            self.root.after(60, self.wait_ready)
            return
        if self.rt.error is not None:
            err = self.rt.error
            if isinstance(err, OSError):   # the page's port is taken: another Hubuddy is probably running
                open_page(self.rt.local_port)
                self.mb.showinfo("Hubuddy", "Hubuddy is already running. Opening its page.")
            else:
                self.mb.showerror("Hubuddy", f"Could not start:\n\n{err}")
            self.root.destroy()
            return
        self.srv, self.svc = self.rt.server, self.rt.svc
        self.splash_frame.destroy()
        self.build()
        if self.minimized:
            self.root.iconify()
        self.refresh()
        self.poll()
        if self.smoke:
            self.root.after(900, self.finish_smoke)

    # ---- main window ----------------------------------------------------------------------
    def build(self):
        tk, ttk, p = self.tk, self.ttk, self.pal
        self.root.title(f"Hubuddy {VERSION}")
        self.center(600, 760, minsize=(560, 640))
        outer = ttk.Frame(self.root, padding=(18, 14, 18, 12))
        outer.pack(fill="both", expand=True)

        head = ttk.Frame(outer)
        head.pack(fill="x", pady=(0, 12))
        if self.logo_image:
            tk.Label(head, image=self.logo_image, background=p["bg"]).pack(side="left", padx=(0, 12))
        titles = ttk.Frame(head)
        titles.pack(side="left")
        ttk.Label(titles, text="Hubuddy", style="Title.TLabel").pack(anchor="w")
        ttk.Label(titles, text=f"{self.svc.name}  ·  version {VERSION}", style="DimBg.TLabel").pack(anchor="w")
        self.pill = tk.Label(head, text="", background=p["card2"], foreground=p["ok"], font=("Segoe UI Semibold", 9), padx=10, pady=4)
        self.pill.pack(side="right")
        self.set_pill()

        # -- pairing
        card = self.card(outer, "Connect a phone")
        row = ttk.Frame(card, style="Card.TFrame")
        row.pack(fill="x")
        ttk.Button(row, text="Show QR code", style="Accent.TButton", command=self.show_qr).pack(side="left", padx=(0, 8))
        ttk.Button(row, text="Pair with a PIN", command=self.show_pin).pack(side="left", padx=(0, 8))
        ttk.Button(row, text="Copy invitation", command=self.copy_invite).pack(side="left")
        self.qr = tk.Canvas(card, width=240, height=240, highlightthickness=0, background="#ffffff")
        self.pair_info = ttk.Label(card, text="On the phone open Hubuddy: Settings → Add a PC or laptop. Same Wi-Fi or LAN (or USB tethering).",
                                   style="Dim.TLabel", wraplength=520, justify="left")
        self.pair_info.pack(anchor="w", pady=(10, 0))
        self.pin_big = ttk.Label(card, text="", style="Pin.TLabel")
        self.code_big = ttk.Label(card, text="", style="Code.TLabel")

        # -- phones
        card = self.card(outer, "Paired phones")
        self.rows_host = ttk.Frame(card, style="Card.TFrame")
        self.rows_host.pack(fill="x")
        foot = ttk.Frame(card, style="Card.TFrame")
        foot.pack(fill="x", pady=(8, 0))
        ttk.Button(foot, text="Forget all phones", style="Danger.TButton", command=self.forget_all).pack(side="left")
        ttk.Label(foot, text="Remote control is off for every phone until you allow it.", style="Dim.TLabel").pack(side="right")

        # -- this PC
        card = self.card(outer, "This PC")
        from .netinfo import interfaces
        addrs = ", ".join(i["address"] for i in interfaces()[:3]) or "no network"
        ttk.Label(card, text=f"Address  {addrs}      Port  {self.srv.port}", style="Card.TLabel").pack(anchor="w")
        if self.rt.discovery_error:
            ttk.Label(card, text="Automatic discovery is off (another Hubuddy may be running). Pair with the QR code.", style="Dim.TLabel",
                      wraplength=520).pack(anchor="w", pady=(4, 0))
        self.auto = tk.BooleanVar(value=winsetup.autostart_enabled())
        ttk.Checkbutton(card, text="Start Hubuddy with Windows", variable=self.auto, command=self.toggle_auto).pack(anchor="w", pady=(8, 0))
        ttk.Button(card, text="Allow through Windows Firewall (private networks)", command=self.firewall).pack(anchor="w", pady=(8, 0))

        bottom = ttk.Frame(outer)
        bottom.pack(fill="x", side="bottom", pady=(2, 0))
        ttk.Button(bottom, text="Open the Hubuddy page", command=lambda: open_page(self.srv.local_port)).pack(side="left")
        ttk.Button(bottom, text="Quit Hubuddy", style="Danger.TButton", command=self.root.destroy).pack(side="right")

    def set_pill(self):
        p = self.pal
        if self.rt.discovery_error:
            self.pill.configure(text="●  Ready (no auto-discovery)", foreground=p["warn"])
        else:
            self.pill.configure(text="●  Ready", foreground=p["ok"])

    # ---- pairing --------------------------------------------------------------------------
    def _clear_pairing(self):
        self.qr.pack_forget(); self.pin_big.pack_forget(); self.code_big.pack_forget()

    def show_qr(self):
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
                    self.qr.create_rectangle((x + quiet) * cell, (y + quiet) * cell, (x + quiet + 1) * cell, (y + quiet + 1) * cell,
                                             fill="black", outline="black")
        self.qr.pack(pady=(12, 0))
        self.pair_until = time.time() + 120
        self.tick_pair("Scan with Hubuddy: Settings → Add a PC or laptop → Scan QR code.")

    def show_pin(self):
        self.svc.pairing.cancel()
        started = self.svc.pairing.start_pin()
        self._clear_pairing()
        self.pin_big.configure(text=f"PIN  {started['pin']}")
        self.code_big.configure(text=f"Compare code  {compare_code(self.svc.tls.pin)}")
        self.pin_big.pack(pady=(12, 0)); self.code_big.pack()
        self.pair_until = time.time() + 120
        self.tick_pair("On the phone: Find PCs on this network → tap this PC → check the compare code matches → enter the PIN.")

    def tick_pair(self, text: str):
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
        if not self.invitation or self.pair_until <= time.time():
            self.show_qr()
        self.root.clipboard_clear()
        self.root.clipboard_append(self.invitation)
        self.pair_info.configure(text="Invitation copied. In the app: Settings → Add a PC or laptop → Paste invitation.")

    # ---- phones ---------------------------------------------------------------------------
    def refresh(self):
        """Redraws the phone list only when something changed, so the window never flickers."""
        phones = self.srv.phones()
        sig = tuple((p["id"], p["name"], bool(p.get("remote")), (p.get("status") or {}).get("battery"), (p.get("status") or {}).get("network"))
                    for p in phones)
        if sig == self.shown_sig:
            return
        self.shown_sig = sig
        tk, ttk = self.tk, self.ttk
        for child in list(self.rows_host.winfo_children()):
            child.destroy()
        self._rows = {}
        if not phones:
            ttk.Label(self.rows_host, text="No phones paired yet. Choose Show QR code above.", style="Dim.TLabel").pack(anchor="w", pady=4)
            return
        for ph in phones:
            sid, st = ph["id"], ph.get("status") or {}
            row = ttk.Frame(self.rows_host, style="Card.TFrame")
            row.pack(fill="x", pady=4)
            text = ttk.Frame(row, style="Card.TFrame")
            text.pack(side="left", fill="x", expand=True)
            ttk.Label(text, text=ph["name"], style="Name.TLabel").pack(anchor="w")
            bits = []
            if "battery" in st:
                bits.append(f"battery {st['battery']}%")
            if st.get("network"):
                bits.append(str(st["network"]))
            ttk.Label(text, text=" · ".join(bits) or "connected", style="Dim.TLabel").pack(anchor="w")
            var = tk.BooleanVar(value=bool(ph.get("remote")))
            self._rows[sid] = var
            ttk.Checkbutton(row, text="Remote control", variable=var, command=lambda s=sid: self.toggle_remote(s)).pack(side="left", padx=10)
            ttk.Button(row, text="Forget", style="Danger.TButton", command=lambda s=sid: self.forget(s)).pack(side="right")

    def toggle_remote(self, sid: str):
        var = self._rows.get(sid)
        if var is None:
            return
        on = bool(var.get())
        if on and not self.mb.askyesno("Remote control", "Let this phone move the mouse, type, change the volume and shut down this PC?"):
            var.set(False)
            return
        self.svc.sessions.set_remote(sid, on)
        if not on:
            self.srv._release(sid)
        self.svc.activity.record("remote_allowed" if on else "remote_blocked", id=sid)
        self.shown_sig = None
        self.refresh()

    def forget(self, sid: str):
        if self.mb.askyesno("Forget phone", "This phone will need to pair again. Continue?"):
            self.srv._release(sid)
            self.svc.sessions.revoke(sid)
            self.svc.activity.record("phone_forgotten", id=sid)
            self.shown_sig = None
            self.refresh()

    def forget_all(self):
        if self.mb.askyesno("Forget all phones", "Every phone will be signed out and must pair again. Continue?"):
            for k in list(self.srv._rc_held):
                self.srv._release(k)
            self.svc.sessions.revoke_all()
            self.svc.pairing.cancel()
            self.svc.activity.record("all_phones_forgotten")
            self.shown_sig = None
            self.refresh()

    # ---- system ---------------------------------------------------------------------------
    def toggle_auto(self):
        if not winsetup.set_autostart(self.auto.get()):
            self.auto.set(not self.auto.get())
            self.mb.showwarning("Hubuddy", "Could not change the Windows startup setting (this works on Windows only).")

    def firewall(self):
        ok = winsetup.allow_through_firewall(self.srv.port, self.svc.discovery_port)
        self.mb.showinfo("Firewall", "Hubuddy is allowed on private networks." if ok else
                         "Could not add the firewall rules. Allow Hubuddy when Windows asks, or use the installer.")

    def on_close(self):
        if self.srv is None:          # still starting: just close
            self.root.destroy()
            return
        answer = self.mb.askyesnocancel("Hubuddy", "Quit Hubuddy?\n\nYes: quit (phones lose the connection)\nNo: keep it running, minimized\nCancel: stay here")
        if answer is True:
            self.root.destroy()
        elif answer is False:
            self.root.iconify()

    def poll(self):
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
        except queue.Empty:
            pass
        self.refresh()
        self.root.after(1500, self.poll)

    def finish_smoke(self):
        """CI check: draw the window, save a picture of it, and exit."""
        try:
            self.root.update()
            from PIL import ImageGrab
            x, y = self.root.winfo_rootx(), self.root.winfo_rooty()
            ImageGrab.grab(bbox=(x, y, x + self.root.winfo_width(), y + self.root.winfo_height())).save(self.smoke)
        except Exception as e:
            print(f"smoke: no picture ({type(e).__name__}: {e})")
        self.root.destroy()

    def run(self):
        self.root.mainloop()


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--selftest" in argv:
        from .selftest import run
        out = argv[argv.index("--selftest-output") + 1] if "--selftest-output" in argv else None
        return run(out)
    if sys.platform == "win32" and "--classic-window" not in argv:
        # Windows: Hubuddy's page in an Edge app window with a tray icon (the old Tk window repainted badly on some laptops)
        from . import webwindow
        from .__main__ import web_dir
        return webwindow.run(argv, Runtime, HOME_DIR, web_dir())
    minimized = "--minimized" in argv
    smoke = argv[argv.index("--smoke") + 1] if "--smoke" in argv else None
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
        # The server starts on its own thread: the window opens at once and never waits for it.
        App(tk, ttk, messagebox, Runtime(), minimized, smoke).run()
    except Exception as e:   # say what went wrong instead of dying silently
        try:
            root = tk.Tk(); root.withdraw()
            messagebox.showerror("Hubuddy", f"Hubuddy's window failed:\n\n{type(e).__name__}: {e}\n\nIt may still work from http://127.0.0.1:{LOCAL_PORT}/")
            root.destroy()
        except Exception:
            pass
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
