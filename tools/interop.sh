#!/usr/bin/env bash
# Runs the Android app's real client code against the real PC program (QR + PIN pairing, API, inbox, files).
# Needs: Python 3.11+ with the agent's requirements, JDK 17+, and the Android SDK (see android/hubuddy).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
home="$(mktemp -d)"
files="$(mktemp -d)"
cleanup() { [[ -n "${pid:-}" ]] && kill "$pid" 2>/dev/null || true; rm -rf "$home" "$files"; }
trap cleanup EXIT
cd "$root/agent"
HOME="$files" "${PYTHON:-python3}" -m hubuddy_agent --home "$home/h" --host 127.0.0.1 --port 18765 --local-port 18766 --no-discovery >"$home/agent.log" 2>&1 &
pid=$!
for _ in $(seq 1 50); do curl -s -o /dev/null "http://127.0.0.1:18766/" && break; sleep 0.2; done
cd "$root/android/hubuddy"
./gradlew testDebugUnitTest --tests '*InteropTest*' -PinteropLan=18765 -PinteropLocal=18766 -q
echo "interop: passed"
