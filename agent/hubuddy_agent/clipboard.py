"""Put text on this PC's clipboard (Windows). Other systems report "not supported" instead of pretending."""
import ctypes
import sys

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


def set_text(text: str) -> bool:
    if sys.platform != "win32":
        return False
    try:
        return set_text_windows(text)
    except OSError:
        return False
