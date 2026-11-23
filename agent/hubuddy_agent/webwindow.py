"""The Windows window: Hubuddy's own web page shown in a Microsoft Edge (or Chrome) "app window", plus a tray icon.

Tk's windows repaint badly on some laptop screens (grey strips, black flashes, a window that looks frozen). A browser-based window
draws smoothly and sharply at any display scaling, and the page is the same one every other system uses. This module only finds a
browser, opens it without browser chrome, and keeps a tray icon with Open and Quit.
"""
import os
import subprocess
import sys
import threading
import webbrowser

LOCAL_FALLBACK_PORT = 8766


def find_browser(env=None, exists=os.path.exists) -> str | None:
    """Edge first (it ships with Windows 10 and 11), then Chrome. None when neither is installed."""
    env = os.environ if env is None else env
    roots = [env.get("ProgramFiles(x86)"), env.get("ProgramFiles"), env.get("LOCALAPPDATA")]
    rel = [r"Microsoft\Edge\Application\msedge.exe", r"Google\Chrome\Application\chrome.exe"]
    for r in rel:
        for root in roots:
            if root and exists(os.path.join(root, r)):
                return os.path.join(root, r)
    return None


def app_command(browser: str, url: str, profile_dir: str, size=(600, 900)) -> list[str]:
    """A fixed argument list (never a shell). A private profile makes this its own window and process, so it behaves like an app."""
    return [browser, f"--app={url}", f"--user-data-dir={profile_dir}", f"--window-size={size[0]},{size[1]}",
            "--no-first-run", "--no-default-browser-check", "--disable-features=Translate", "--disable-session-crashed-bubble"]


def open_window(url: str, profile_dir: str, popen=subprocess.Popen, find=find_browser, fallback=webbrowser.open) -> bool:
    """Opens the app window. Falls back to the default browser when Edge and Chrome are both missing. True when something opened."""
    browser = find()
    if browser:
        try:
            popen(app_command(browser, url, profile_dir), shell=False, close_fds=True,
                  creationflags=0x00000008 if sys.platform == "win32" else 0)   # DETACHED_PROCESS: closing Hubuddy's window never kills it
            return True
        except (OSError, ValueError):
            pass
    try:
        return bool(fallback(url))
    except Exception:
        return False


def message_box(text: str) -> None:
    """A plain Windows message (no Tk): used only to explain a start-up failure."""
    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.user32.MessageBoxW(0, text, "Hubuddy", 0x10)
            return
        except Exception:
            pass
    print(text, file=sys.stderr)


def make_tray(logo_path: str, on_open, on_quit):
    """A tray icon with Open and Quit, or None when pystray (or Pillow) is not available."""
    try:
        import pystray
        from PIL import Image
        icon = pystray.Icon("Hubuddy", Image.open(logo_path), "Hubuddy",
                            pystray.Menu(pystray.MenuItem("Open Hubuddy", lambda *_: on_open(), default=True),
                                         pystray.MenuItem("Quit Hubuddy", lambda *_: on_quit())))
        return icon
    except Exception:
        return None


def run(argv, runtime_factory, home_dir, web_dir, opener=open_window, tray_factory=make_tray, notify=message_box, wait=None) -> int:
    """The whole Windows program: start the PC side, open the window (unless started minimized), sit in the tray until Quit."""
    minimized = "--minimized" in argv
    rt = runtime_factory()
    rt.ready.wait(30)
    profile = os.path.join(str(home_dir), "window-profile")
    if rt.error is not None or not rt.ready.is_set():
        if isinstance(rt.error, OSError):   # the page's port is taken: Hubuddy is already running, so just show its window
            opener(f"http://127.0.0.1:{LOCAL_FALLBACK_PORT}/", profile)
            return 0
        notify(f"Hubuddy could not start:\n\n{rt.error or 'it took too long to start'}")
        return 1
    url = f"http://127.0.0.1:{rt.server.local_port}/"
    quit_event = threading.Event()
    rt.server.on_quit = quit_event.set
    tray = tray_factory(os.path.join(str(web_dir), "logo.png"), lambda: opener(url, profile), quit_event.set)
    if tray is not None:
        tray.run_detached()
    if not minimized:
        opener(url, profile)
    (wait or quit_event.wait)()
    if tray is not None:
        try:
            tray.stop()
        except Exception:
            pass
    return 0
