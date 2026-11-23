"""Everything the servers need, built once from a home folder."""
import pathlib
import socket
import webbrowser
from dataclasses import dataclass, field
from typing import Callable

from . import clipboard, discovery, remote as remote_mod
from .activity import ActivityLog
from .config import HOME_DIR
from .files import Files
from .identity import host_id as load_host_id
from .inbox import Inbox
from .lockout import Lockout
from .pairing import PairingManager
from .phones import PhoneStatus
from .privatestore import private_dir
from .sessions import SessionStore
from .tls import ManagedTls


def open_link(url: str) -> bool:
    if not url.lower().startswith(("http://", "https://")):
        return False
    try:
        return bool(webbrowser.open(url))
    except Exception:
        return False


@dataclass
class Services:
    home: pathlib.Path
    host_id: str
    name: str
    tls: ManagedTls
    sessions: SessionStore
    pairing: PairingManager
    lockout: Lockout
    inbox: Inbox
    status: PhoneStatus
    files: Files
    activity: ActivityLog
    set_clipboard: Callable[[str], bool] = clipboard.set_text
    open_link: Callable[[str], bool] = open_link
    remote: object = None
    discovery_port: int = discovery.PORT
    discovery_transport: object = field(default=None, repr=False)

    def discovery_on(self) -> bool:
        return self.discovery_transport is not None


def default_files_root() -> pathlib.Path:
    docs = pathlib.Path.home() / "Documents"
    return (docs if docs.is_dir() else pathlib.Path.home()) / "Hubuddy"


def build(home: pathlib.Path | None = None, name: str | None = None, files_root: pathlib.Path | None = None, **overrides) -> Services:
    home = private_dir(pathlib.Path(home) if home else HOME_DIR)
    hid = load_host_id(home)
    svc = Services(
        home=home, host_id=hid, name=(name or socket.gethostname() or "PC")[:40], tls=ManagedTls(home, hid),
        sessions=SessionStore(home / "sessions.json"), pairing=PairingManager(), lockout=Lockout(),
        inbox=Inbox(home / "inbox.json"), status=PhoneStatus(), files=Files(files_root or default_files_root()),
        activity=ActivityLog(home / "activity.log"))
    svc.remote = remote_mod.default_backend()
    for k, v in overrides.items():
        setattr(svc, k, v)
    return svc
