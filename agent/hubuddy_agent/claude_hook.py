"""Claude Code hook: records what each session is doing so the phone can show it.

Claude Code runs this for hook events and passes a JSON description on stdin. It writes one small file per
session under ~/.hubuddy/claude/sessions (state, project folder name, short message). It never prints
anything and never fails the hook. Standard library only.
"""
import json
import os
import pathlib
import re
import sys
import time

ROOT = pathlib.Path(os.environ.get("HUBUDDY_CLAUDE_DIR") or pathlib.Path.home() / ".hubuddy" / "claude")


def state_for(event: str, data: dict) -> tuple[str | None, str]:
    """(state, message) for a hook event; state None means "ignore", "end" means forget the session."""
    if event == "Notification":
        kind = str(data.get("notification_type") or "")
        message = " ".join(str(data.get("message") or "").split())[:200]
        text = message.lower()
        if kind == "permission_prompt" or "permission" in text:
            return "permission", message
        if kind == "idle_prompt" or "waiting for your input" in text or "waiting for input" in text:
            return "input", message
        return None, ""
    if event == "Stop":
        return "idle", ""
    if event in ("UserPromptSubmit", "PreToolUse", "PostToolUse", "SubagentStop"):
        return "running", ""
    if event == "SessionEnd":
        return "end", ""
    return None, ""


def handle(data: dict, root: pathlib.Path = ROOT, now=time.time) -> None:
    sid = "".join(c for c in str(data.get("session_id") or "") if c.isalnum() or c in "-_")[:64]
    if not sid:
        return
    state, message = state_for(str(data.get("hook_event_name") or ""), data)
    folder = root / "sessions"
    path = folder / f"{sid}.json"
    if state is None:
        return
    if state == "end":
        path.unlink(missing_ok=True)
        return
    folder.mkdir(parents=True, exist_ok=True)
    project = (re.split(r"[\\/]", str(data.get("cwd") or "").rstrip("\\/")) or [""])[-1]  # works for both path styles
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps({"state": state, "project": project, "message": message, "updated": now()}), encoding="utf-8")
    tmp.replace(path)


def main() -> int:
    try:
        data = json.loads(sys.stdin.read() or "{}")
        if isinstance(data, dict):
            handle(data)
    except Exception:  # a hook must never break Claude Code
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
