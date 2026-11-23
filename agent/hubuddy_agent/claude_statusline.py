"""Claude Code status line: saves plan-limit data for the agent, then prints a short status line.

Claude Code runs the status-line command with a JSON description of the session on stdin; on plans with
usage limits it includes `rate_limits` with the 5-hour and weekly windows. This stores those numbers in
~/.hubuddy/claude/status.json. With `--then "<command>"` it also runs your previous status line command
with the same input and prints its output, so nothing you had is lost. Standard library only.
"""
import json
import os
import pathlib
import subprocess
import sys
import time

ROOT = pathlib.Path(os.environ.get("HUBUDDY_CLAUDE_DIR") or pathlib.Path.home() / ".hubuddy" / "claude")


def record(data: dict, root: pathlib.Path = ROOT, now=time.time) -> None:
    limits = data.get("rate_limits")
    if not isinstance(limits, dict):
        return
    keep = {k: {"used_percentage": v.get("used_percentage"), "resets_at": v.get("resets_at")}
            for k, v in limits.items() if k in ("five_hour", "seven_day") and isinstance(v, dict)}
    if not keep:
        return
    root.mkdir(parents=True, exist_ok=True)
    tmp = root / "status.tmp"
    tmp.write_text(json.dumps({"rate_limits": keep, "updated": int(now())}), encoding="utf-8")
    tmp.replace(root / "status.json")


def main(argv: list[str]) -> int:
    raw = sys.stdin.read()
    try:
        data = json.loads(raw or "{}")
        if isinstance(data, dict):
            record(data)
    except Exception:
        data = {}
    if "--then" in argv and argv.index("--then") + 1 < len(argv):
        previous = argv[argv.index("--then") + 1]
        try:
            out = subprocess.run(previous, input=raw, capture_output=True, text=True, shell=True, timeout=5).stdout
            sys.stdout.write(out)
            return 0
        except (OSError, subprocess.SubprocessError):
            pass
    model = (data.get("model") or {}).get("display_name") if isinstance(data.get("model"), dict) else ""
    sys.stdout.write(f"Hubuddy{' · ' + model if model else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
