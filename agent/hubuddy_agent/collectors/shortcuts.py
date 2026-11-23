"""PC shortcut buttons. The phone sends only a button number; what it does is fixed by the config file.

    [[shortcuts]]
    label = "Lock PC"      action = "lock"
    [[shortcuts]]
    label = "Docs"         action = "open"   target = "https://example.org"
    [[shortcuts]]
    label = "Notepad"      action = "run"    target = "notepad.exe"
    [[shortcuts]]
    label = "Mute"         action = "volume" target = "mute"        # up | down | mute
    [[shortcuts]]
    label = "Screenshot"   action = "keys"   target = "win+shift+s"

Commands are started without a shell, so nothing the phone sends can ever become a command line.
"""
import ctypes
import logging
import os
import shlex
import subprocess
import sys
import webbrowser

log = logging.getLogger("hubuddy.shortcuts")

ACTIONS = {"open", "run", "keys", "volume", "lock"}
MAX_BUTTONS = 16
VK = {"ctrl": 0x11, "shift": 0x10, "alt": 0x12, "win": 0x5B, "enter": 0x0D, "esc": 0x1B, "tab": 0x09, "space": 0x20,
      "up": 0x26, "down": 0x28, "left": 0x25, "right": 0x27, "home": 0x24, "end": 0x23, "delete": 0x2E,
      "mute": 0xAD, "volume_down": 0xAE, "volume_up": 0xAF, "play_pause": 0xB3, "next": 0xB0, "prev": 0xB1,
      **{f"f{i}": 0x6F + i for i in range(1, 13)}, **{c: ord(c.upper()) for c in "abcdefghijklmnopqrstuvwxyz0123456789"}}
VOLUME = {"up": "volume_up", "down": "volume_down", "mute": "mute"}


class ConfigError(ValueError):
    pass


def parse_keys(spec: str) -> list[int]:
    codes = []
    for part in spec.lower().replace(" ", "").split("+"):
        if part not in VK:
            raise ConfigError(f"unknown key {part!r} in {spec!r}")
        codes.append(VK[part])
    if not codes:
        raise ConfigError("empty key combination")
    return codes


def load(items: list[dict]) -> list[dict]:
    """Validate the [[shortcuts]] entries. Raises ConfigError naming the first bad one."""
    out = []
    for i, it in enumerate(items[:MAX_BUTTONS]):
        label, action, target = str(it.get("label", "")).strip()[:30], it.get("action"), str(it.get("target", "")).strip()
        if not label:
            raise ConfigError(f"shortcut {i + 1}: a label is required")
        if action not in ACTIONS:
            raise ConfigError(f"shortcut {label!r}: action must be one of {sorted(ACTIONS)}")
        if action in ("open", "run") and not target:
            raise ConfigError(f"shortcut {label!r}: a target is required")
        if action == "keys":
            parse_keys(target)
        if action == "volume" and target not in VOLUME:
            raise ConfigError(f"shortcut {label!r}: volume target must be up, down or mute")
        out.append({"label": label, "action": action, "target": target, "color": str(it.get("color", "#24272B"))})
    return out


class Runner:
    """Does the real work. Windows gets keyboard/lock support; every system can open links and run programs."""

    def open_target(self, target: str):
        if target.startswith(("http://", "https://")):
            webbrowser.open(target)
        elif sys.platform == "win32":
            os.startfile(os.path.expandvars(os.path.expanduser(target)))  # noqa: S606 - config-defined path
        else:
            subprocess.Popen(["xdg-open", os.path.expanduser(target)])

    def run_command(self, target: str):
        subprocess.Popen(shlex.split(target, posix=sys.platform != "win32"), shell=False)

    def press_keys(self, codes: list[int]):
        if sys.platform != "win32":
            raise ConfigError("key presses are only supported on Windows")
        user32 = ctypes.windll.user32
        for c in codes:
            user32.keybd_event(c, 0, 0, 0)
        for c in reversed(codes):
            user32.keybd_event(c, 0, 2, 0)

    def lock(self):
        if sys.platform != "win32":
            raise ConfigError("locking is only supported on Windows")
        ctypes.windll.user32.LockWorkStation()


class Shortcuts:
    def __init__(self, items: list[dict], runner: Runner | None = None):
        self.items = load(items)
        self.runner = runner or Runner()

    def __len__(self) -> int:
        return len(self.items)

    def listing(self) -> list[dict]:
        return [{"id": i, "label": s["label"], "color": s["color"], "action": s["action"]} for i, s in enumerate(self.items)]

    def run(self, i: int) -> None:
        s = self.items[i]
        log.info("shortcut %r: %s", s["label"], s["action"])
        a, t = s["action"], s["target"]
        if a == "open":
            self.runner.open_target(t)
        elif a == "run":
            self.runner.run_command(t)
        elif a == "keys":
            self.runner.press_keys(parse_keys(t))
        elif a == "volume":
            self.runner.press_keys([VK[VOLUME[t]]])
        elif a == "lock":
            self.runner.lock()
