"""Remote control of this PC from a paired phone: mouse, keyboard, volume, power and a small task manager.

Every action is validated against a strict schema with hard limits before it reaches the backend, and no shell is used.
A backend is injectable: the real Windows one uses SendInput through ctypes, tests use a fake, other systems report
"unavailable" instead of pretending.
"""
import ctypes
import subprocess
import sys

MAX_EVENTS = 64
MAX_DELTA = 2048
MAX_TEXT = 500
BUTTONS = ("left", "right", "middle")
KEYS = ("enter", "backspace", "tab", "esc", "space", "delete", "up", "down", "left", "right", "home", "end", "pageup", "pagedown",
        "win", "volup", "voldown", "mute", "playpause", "next", "prev") + tuple(f"f{i}" for i in range(1, 13))
MODS = ("ctrl", "shift", "alt", "win")
POWER = ("sleep", "restart", "shutdown", "abort")
VOLUME = ("up", "down", "mute")
CRITICAL = {"system", "registry", "smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe", "services.exe", "lsass.exe", "svchost.exe",
            "explorer.exe", "dwm.exe", "fontdrvhost.exe", "system idle process", "memory compression", "hubuddy.exe", "python.exe"}


class Unavailable(RuntimeError):
    pass


class BadRequest(ValueError):
    pass


def _int(v, lo, hi, name):
    if isinstance(v, bool) or not isinstance(v, int) or not lo <= v <= hi:
        raise BadRequest(f"{name} must be a whole number from {lo} to {hi}")
    return v


def validate_events(events) -> list[dict]:
    """The cleaned list of input events, or BadRequest. Unknown fields and values are refused, not ignored."""
    if not isinstance(events, list) or not 1 <= len(events) <= MAX_EVENTS:
        raise BadRequest(f"events must be a list of 1 to {MAX_EVENTS}")
    out = []
    for e in events:
        if not isinstance(e, dict) or not isinstance(e.get("t"), str):
            raise BadRequest("each event needs a type")
        t = e["t"]
        if t == "move":
            out.append({"t": t, "dx": _int(e.get("dx"), -MAX_DELTA, MAX_DELTA, "dx"), "dy": _int(e.get("dy"), -MAX_DELTA, MAX_DELTA, "dy")})
        elif t == "click":
            if e.get("button", "left") not in BUTTONS:
                raise BadRequest("unknown button")
            out.append({"t": t, "button": e.get("button", "left"), "double": bool(e.get("double", False))})
        elif t == "scroll":
            out.append({"t": t, "dy": _int(e.get("dy"), -MAX_DELTA, MAX_DELTA, "dy")})
        elif t == "key":
            if e.get("key") not in KEYS:
                raise BadRequest("unknown key")
            mods = e.get("mods", [])
            if not isinstance(mods, list) or len(mods) > 4 or any(m not in MODS for m in mods):
                raise BadRequest("unknown modifier")
            out.append({"t": t, "key": e["key"], "mods": sorted(set(mods))})
        elif t == "text":
            txt = e.get("text")
            if not isinstance(txt, str) or not 1 <= len(txt) <= MAX_TEXT or any(ord(c) < 32 and c not in "\n\t" for c in txt):
                raise BadRequest(f"text must be 1 to {MAX_TEXT} printable characters")
            out.append({"t": t, "text": txt})
        else:
            raise BadRequest("unknown event type")
    return out


class Backend:
    """What a platform must provide. Methods raise Unavailable when the platform cannot do them."""
    def move(self, dx, dy): raise Unavailable()
    def click(self, button, double): raise Unavailable()
    def scroll(self, dy): raise Unavailable()
    def key(self, key, mods): raise Unavailable()
    def text(self, text): raise Unavailable()
    def power(self, action): raise Unavailable()
    def battery(self): return None

    def processes(self, limit=15):
        try:
            import psutil
        except ImportError:
            raise Unavailable()
        rows = []
        for p in psutil.process_iter(["pid", "name", "memory_info"]):
            try:
                rows.append({"pid": p.info["pid"], "name": p.info["name"] or "?", "mem_mb": round((p.info["memory_info"].rss if p.info["memory_info"] else 0) / 1e6)})
            except Exception:
                continue
        rows.sort(key=lambda r: -r["mem_mb"])
        return rows[:limit]

    def end(self, pid):
        import os
        import psutil
        if pid == os.getpid():
            raise BadRequest("that is this program")
        try:
            p = psutil.Process(pid)
            name = (p.name() or "").lower()
            if name in CRITICAL or pid <= 4:
                raise BadRequest("that program is protected")
            p.terminate()
            return name
        except psutil.NoSuchProcess:
            raise BadRequest("no such program")
        except psutil.AccessDenied:
            raise BadRequest("Windows would not allow ending that program")


class WindowsBackend(Backend):
    power_delay_s = 15
    VK = {"enter": 0x0D, "backspace": 0x08, "tab": 0x09, "esc": 0x1B, "space": 0x20, "delete": 0x2E, "up": 0x26, "down": 0x28, "left": 0x25,
          "right": 0x27, "home": 0x24, "end": 0x23, "pageup": 0x21, "pagedown": 0x22, "win": 0x5B, "volup": 0xAF, "voldown": 0xAE,
          "mute": 0xAD, "playpause": 0xB3, "next": 0xB0, "prev": 0xB1, **{f"f{i}": 0x6F + i for i in range(1, 13)}}
    MOD = {"ctrl": 0x11, "shift": 0x10, "alt": 0x12, "win": 0x5B}

    def __init__(self):
        from ctypes import wintypes
        self.u = ctypes.windll.user32
        ULONG_PTR = ctypes.c_size_t

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
                        ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
                        ("dwExtraInfo", ULONG_PTR)]

        class _U(ctypes.Union):
            _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]

        class INPUT(ctypes.Structure):
            _fields_ = [("type", wintypes.DWORD), ("u", _U)]
        self.INPUT, self.MI, self.KI = INPUT, MOUSEINPUT, KEYBDINPUT

    def _send(self, *inputs):
        arr = (self.INPUT * len(inputs))(*inputs)
        if self.u.SendInput(len(inputs), arr, ctypes.sizeof(self.INPUT)) != len(inputs):
            raise Unavailable("Windows did not accept the input (an elevated window or the lock screen is in front)")

    def _mouse(self, flags, data=0, dx=0, dy=0):
        i = self.INPUT(); i.type = 0; i.u.mi = self.MI(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
        return i

    def _key(self, vk=0, up=False, unicode=None):
        i = self.INPUT(); i.type = 1
        flags = (0x0002 if up else 0) | (0x0004 if unicode is not None else 0)
        i.u.ki = self.KI(0 if unicode is not None else vk, unicode or 0, flags, 0, 0)
        return i

    def move(self, dx, dy): self._send(self._mouse(0x0001, 0, dx, dy))

    def click(self, button, double):
        down, up = {"left": (0x0002, 0x0004), "right": (0x0008, 0x0010), "middle": (0x0020, 0x0040)}[button]
        for _ in range(2 if double else 1):
            self._send(self._mouse(down), self._mouse(up))

    def scroll(self, dy): self._send(self._mouse(0x0800, -dy * 120 // 10 if abs(dy) < 10 else -dy * 12))

    def key(self, key, mods):
        seq = [self._key(self.MOD[m]) for m in mods] + [self._key(self.VK[key]), self._key(self.VK[key], up=True)] + \
              [self._key(self.MOD[m], up=True) for m in reversed(mods)]
        self._send(*seq)

    def text(self, text):
        seq = []
        for ch in text.replace("\n", "\r"):
            code = ord(ch)
            units = [code] if code < 0x10000 else [0xD800 + ((code - 0x10000) >> 10), 0xDC00 + ((code - 0x10000) & 0x3FF)]
            for u16 in units:
                seq += [self._key(unicode=u16), self._key(unicode=u16, up=True)]
        self._send(*seq)

    def power(self, action):
        argv = {"sleep": ["rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0"], "restart": ["shutdown", "/r", "/t", "15"],
                "shutdown": ["shutdown", "/s", "/t", "15"], "abort": ["shutdown", "/a"]}[action]
        subprocess.Popen(argv, shell=False, creationflags=0x08000000)

    def battery(self):
        try:
            import psutil
            b = psutil.sensors_battery()
            return None if b is None else {"percent": round(b.percent), "charging": bool(b.power_plugged)}
        except Exception:
            return None


class LinuxBackend(Backend):
    """X11 through `xdotool` (or Wayland through `ydotool`), volume through `pactl` or `amixer`, power through `systemctl`.
    Every command is a fixed argument list started without a shell; a missing tool is reported as unavailable."""
    XKEY = {"enter": "Return", "backspace": "BackSpace", "tab": "Tab", "esc": "Escape", "space": "space", "delete": "Delete", "up": "Up",
            "down": "Down", "left": "Left", "right": "Right", "home": "Home", "end": "End", "pageup": "Prior", "pagedown": "Next",
            "win": "Super_L", "volup": "XF86AudioRaiseVolume", "voldown": "XF86AudioLowerVolume", "mute": "XF86AudioMute",
            "playpause": "XF86AudioPlay", "next": "XF86AudioNext", "prev": "XF86AudioPrev", **{f"f{i}": f"F{i}" for i in range(1, 13)}}
    XMOD = {"ctrl": "ctrl", "shift": "shift", "alt": "alt", "win": "super"}
    power_delay_s = 60  # `shutdown` counts in whole minutes
    XBTN = {"left": "1", "middle": "2", "right": "3"}

    def __init__(self, which=None, run=None):
        import shutil
        self.which = which or shutil.which
        self.run_cmd = run or (lambda argv: subprocess.run(argv, shell=False, timeout=5, capture_output=True))

    def _tool(self, *names):
        for n in names:
            if self.which(n):
                return n
        raise Unavailable(f"install {' or '.join(names)} on this PC to enable this")

    def _run(self, argv):
        r = self.run_cmd(argv)
        if getattr(r, "returncode", 0) != 0:
            raise Unavailable("the PC could not do that (is a desktop session running?)")

    def move(self, dx, dy): self._run([self._tool("xdotool"), "mousemove_relative", "--", str(dx), str(dy)])

    def click(self, button, double):
        self._run([self._tool("xdotool"), "click", *(["--repeat", "2", "--delay", "60"] if double else []), self.XBTN[button]])

    def scroll(self, dy):
        n = max(1, min(abs(dy) // 8 + 1, 20))
        self._run([self._tool("xdotool"), "click", "--repeat", str(n), "5" if dy > 0 else "4"])

    def key(self, key, mods):
        combo = "+".join([self.XMOD[m] for m in mods] + [self.XKEY[key]])
        self._run([self._tool("xdotool"), "key", "--", combo])

    def text(self, text): self._run([self._tool("xdotool"), "type", "--delay", "8", "--", text])

    def power(self, action):
        if action == "abort":
            return self._run([self._tool("shutdown"), "-c"])
        argv = {"sleep": [self._tool("systemctl"), "suspend"], "restart": [self._tool("shutdown"), "-r", "+1"],
                "shutdown": [self._tool("shutdown"), "-h", "+1"]}[action]
        self._run(argv)

    def battery(self):
        try:
            import psutil
            b = psutil.sensors_battery()
            return None if b is None else {"percent": round(b.percent), "charging": bool(b.power_plugged)}
        except Exception:
            return None


def default_backend() -> Backend:
    if sys.platform.startswith("linux"):
        return LinuxBackend()
    if sys.platform == "win32":
        try:
            return WindowsBackend()
        except Exception:
            pass
    b = Backend()
    return b
