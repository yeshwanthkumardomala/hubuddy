"""Put text on this PC's clipboard (Windows). Other systems report "not supported" instead of pretending."""
import ctypes
import os
import shutil
import subprocess
import sys

from . import hiddenproc

CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002


def set_text_windows(text: str) -> bool:
    user32, kernel32 = ctypes.windll.user32, ctypes.windll.kernel32
    kernel32.GlobalAlloc.restype = ctypes.c_void_p
    kernel32.GlobalLock.restype = ctypes.c_void_p
    user32.GetClipboardData.restype = ctypes.c_void_p
    data = (text + "\0").encode("utf-16-le")
    if not user32.OpenClipboard(None):
        return False
    try:
        user32.EmptyClipboard()
        handle = kernel32.GlobalAlloc(GMEM_MOVEABLE, len(data))
        if not handle:
            return False
        ptr = kernel32.GlobalLock(handle)
        ctypes.memmove(ptr, data, len(data))
        kernel32.GlobalUnlock(handle)
        return bool(user32.SetClipboardData(CF_UNICODETEXT, ctypes.c_void_p(handle)))
    finally:
        user32.CloseClipboard()


# Linux: first tool that exists wins. Fixed argv, no shell; the text goes in on stdin.
LINUX_TOOLS = (["wl-copy"], ["xclip", "-selection", "clipboard"], ["xsel", "--clipboard", "--input"])


def set_text_linux(text: str, which=shutil.which, run=hiddenproc.run) -> bool:
    wayland = bool(os.environ.get("WAYLAND_DISPLAY"))
    tools = sorted(LINUX_TOOLS, key=lambda t: 0 if (t[0] == "wl-copy") == wayland else 1)
    for argv in tools:
        if which(argv[0]):
            try:
                return run(argv, input=text.encode("utf-8"), timeout=5, capture_output=True).returncode == 0
            except (OSError, subprocess.SubprocessError):
                continue
    return False


def set_text(text: str) -> bool:
    try:
        if sys.platform == "win32":
            return set_text_windows(text)
        if sys.platform.startswith("linux"):
            return set_text_linux(text)
    except OSError:
        pass
    return False
