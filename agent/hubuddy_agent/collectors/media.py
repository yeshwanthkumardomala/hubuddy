"""What is playing on the PC, and play/pause/next/previous (Windows media sessions).

Uses the Windows "system media transport controls" through the `winrt` Python packages. On other systems,
or when those packages are missing, the collector reports "unavailable" and the phone shows that.
"""
import logging
import sys

log = logging.getLogger("hubuddy.media")

PLAYING = 4  # GlobalSystemMediaTransportControlsSessionPlaybackStatus.PLAYING


def _windows_manager():
    from winrt.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as Manager
    return Manager.request_async()


def app_name(aumid: str) -> str:
    """Friendly app name from a Windows app id: "Spotify.exe" -> "Spotify", "Microsoft.ZuneMusic_8we!App" -> "ZuneMusic"."""
    base = aumid.split("!")[0].split("_")[0]
    if base.lower().endswith(".exe"):
        base = base[:-4]
    return base.split(".")[-1][:30]


class MediaCollector:
    def __init__(self, manager_factory=None):
        """`manager_factory` returns an awaitable manager; injected in tests."""
        self._factory = manager_factory
        if self._factory is None and sys.platform == "win32":
            try:
                import winrt.windows.media.control  # noqa: F401  (only checking it can be imported)
                self._factory = _windows_manager
            except ImportError:
                log.info("media: install the 'winrt' packages (see requirements.txt) to show what is playing")
        self._manager = None

    @property
    def available(self) -> bool:
        return self._factory is not None

    async def _session(self):
        if self._manager is None:
            self._manager = await self._factory()
        return self._manager.get_current_session()

    async def snapshot(self) -> dict | None:
        if not self.available:
            return None
        try:
            s = await self._session()
            if s is None:
                return {"active": False}
            props = await s.try_get_media_properties_async()
            info = s.get_playback_info()
            tl = s.get_timeline_properties()
            end = tl.end_time.total_seconds() if tl and tl.end_time else 0
            pos = tl.position.total_seconds() if tl and tl.position else 0
            return {"active": True, "playing": int(info.playback_status) == PLAYING,
                    "title": (props.title or "")[:100], "artist": (props.artist or "")[:80], "album": (props.album_title or "")[:80],
                    "source": app_name(s.source_app_user_model_id or ""),
                    "pos": int(min(pos, end)) if end else 0, "dur": int(end)}
        except Exception as e:  # a vanished session or an API hiccup must not stop the agent
            log.debug("media read failed: %s", e)
            self._manager = None
            return {"active": False}

    async def command(self, cmd: str) -> None:
        s = await self._session()
        if s is None:
            return
        await {"toggle": s.try_toggle_play_pause_async, "next": s.try_skip_next_async, "prev": s.try_skip_previous_async}[cmd]()
