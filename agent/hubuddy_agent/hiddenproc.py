"""Start helper programs without ever showing a console window.

On Windows, a program without a console of its own (Hubuddy's window or tray) that starts a console program such as `nvidia-smi`
makes Windows open a new console window for it, for a split second, every time. The GPU reader does that every few seconds, which
shows up as a terminal flashing on screen and stealing focus from the real window. These helpers add the flags that prevent it.
"""
import subprocess
import sys

CREATE_NO_WINDOW = 0x08000000


def no_window() -> dict:
    """Extra keyword arguments for subprocess.run/Popen: nothing on other systems, 'hidden' on Windows."""
    if not sys.platform.startswith("win"):
        return {}
    kw = {"creationflags": CREATE_NO_WINDOW}
    info = getattr(subprocess, "STARTUPINFO", None)
    if info is not None:
        si = info()
        si.dwFlags |= getattr(subprocess, "STARTF_USESHOWWINDOW", 1)
        si.wShowWindow = 0   # SW_HIDE
        kw["startupinfo"] = si
    return kw


def run(*args, **kwargs):
    return subprocess.run(*args, **{**no_window(), **kwargs})


def popen(*args, **kwargs):
    return subprocess.Popen(*args, **{**no_window(), **kwargs})
