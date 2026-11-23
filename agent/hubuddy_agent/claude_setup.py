"""Add (or remove) Hubuddy's Claude Code hook and status line in ~/.claude/settings.json.

    python -m hubuddy_agent.claude_setup show     # print what would be added, change nothing
    python -m hubuddy_agent.claude_setup apply    # add it (a backup is written next to the file)
    python -m hubuddy_agent.claude_setup remove   # take only Hubuddy's entries out again

Existing hooks and an existing status line are kept: the old status line is chained, not replaced.
"""
import argparse
import json
import pathlib
import shlex
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
EVENTS = ["Notification", "Stop", "UserPromptSubmit", "PreToolUse", "PostToolUse", "SessionEnd"]
MARK = "hubuddy"


def _q(p) -> str:
    return f'"{p}"' if sys.platform == "win32" else shlex.quote(str(p))


def hook_command() -> str:
    return f"{_q(sys.executable)} {_q(HERE / 'claude_hook.py')}"


def statusline_command(previous: str | None) -> str:
    base = f"{_q(sys.executable)} {_q(HERE / 'claude_statusline.py')}"
    return f"{base} --then {_q(previous)}" if previous else base


def is_ours(command: str) -> bool:
    return MARK in command and ("claude_hook.py" in command or "claude_statusline.py" in command)


def merge(settings: dict) -> dict:
    s = json.loads(json.dumps(settings))  # deep copy
    hooks = s.setdefault("hooks", {})
    for ev in EVENTS:
        groups = hooks.setdefault(ev, [])
        if not any(is_ours(h.get("command", "")) for g in groups for h in g.get("hooks", [])):
            groups.append({"hooks": [{"type": "command", "command": hook_command()}]})
    current = (s.get("statusLine") or {}).get("command", "")
    if not is_ours(current):
        s["statusLine"] = {"type": "command", "command": statusline_command(current or None)}
    return s


def strip(settings: dict) -> dict:
    s = json.loads(json.dumps(settings))
    hooks = s.get("hooks", {})
    for ev in list(hooks):
        groups = []
        for g in hooks[ev]:
            kept = [h for h in g.get("hooks", []) if not is_ours(h.get("command", ""))]
            if kept:
                groups.append({**g, "hooks": kept})
        if groups:
            hooks[ev] = groups
        else:
            del hooks[ev]
    if not hooks:
        s.pop("hooks", None)
    cmd = (s.get("statusLine") or {}).get("command", "")
    if is_ours(cmd):
        previous = shlex.split(cmd, posix=sys.platform != "win32")
        if "--then" in previous and previous.index("--then") + 1 < len(previous):
            s["statusLine"] = {"type": "command", "command": previous[previous.index("--then") + 1].strip('"')}
        else:
            s.pop("statusLine", None)
    return s


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("action", choices=["show", "apply", "remove"])
    ap.add_argument("--settings", default=str(pathlib.Path.home() / ".claude" / "settings.json"))
    args = ap.parse_args(argv)
    path = pathlib.Path(args.settings)
    try:
        current = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else {}
    except (OSError, ValueError) as e:
        print(f"Cannot read {path}: {e}", file=sys.stderr)
        return 1
    new = merge(current) if args.action in ("show", "apply") else strip(current)
    if args.action == "show":
        print(json.dumps(new, indent=2))
        return 0
    if new == current:
        print("Nothing to change.")
        return 0
    if path.is_file():
        shutil.copy2(path, path.with_name(path.name + ".hubuddy-backup"))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(new, indent=2), encoding="utf-8")
    print(f"Updated {path} (backup: {path.name}.hubuddy-backup).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
