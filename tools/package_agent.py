"""Build the laptop download: releases/Hubuddy-Agent-<version>.zip (+ .sha256).

    python tools/package_agent.py

The zip is a ready-to-run folder: unzip it anywhere on a Windows PC and double-click start-agent.bat.
Timestamps are fixed so building the same sources gives the same zip.
"""
import hashlib
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
AGENT = ROOT / "agent"
OUT = ROOT / "releases"
STAMP = (2026, 1, 1, 0, 0, 0)

READ_ME = """Hubuddy PC program {version} (Python source)
==============================================

Most people should use Hubuddy.exe or HubuddySetup.exe from the Releases page. This zip runs the same program from
Python source.

1. Install Python 3.11 or newer from python.org ("Add python.exe to PATH" ticked), if you do not have it.
2. Double-click  start-agent.bat  (first start installs what it needs, which takes a minute).
3. Open http://127.0.0.1:8766/ on this PC and choose "Pair a phone". In the Hubuddy app: Settings > Add a PC or
   laptop > Scan QR code. No camera? Use "Pair with a PIN" and "Find PCs on this network" in the app.

Same Wi-Fi or LAN is needed (or switch on USB tethering on the phone). No developer options. Allow Python through
Windows Firewall for Private networks only.

Optional: copy config.example.toml to config.toml to add calendar links and shortcut buttons.
Claude Code limits and alerts: python -m hubuddy_agent.claude_setup show
"""


def files():
    for p in sorted(AGENT.rglob("*")):
        rel = p.relative_to(AGENT)
        if p.is_dir() or "__pycache__" in rel.parts or "tests" in rel.parts or ".venv" in rel.parts:
            continue
        if rel.name in ("config.toml", "agent_devices.json") or rel.suffix in (".pyc",):
            continue
        yield p, rel.as_posix()


def build() -> pathlib.Path:
    version = re.search(r'VERSION = "([^"]+)"', (AGENT / "hubuddy_agent" / "__init__.py").read_text()).group(1)
    top = f"Hubuddy-Agent-{version}"
    out = OUT / f"{top}.zip"
    OUT.mkdir(exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        def add(name: str, data: bytes):
            info = zipfile.ZipInfo(f"{top}/{name}", STAMP)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, data)

        for p, rel in files():
            data = p.read_bytes()
            if rel.endswith((".ps1", ".bat")):  # Windows scripts keep CRLF line endings
                data = data.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
            add(rel, data)
        add("docs/SETUP.md", (ROOT / "docs" / "hubuddy" / "API_SETUP.md").read_bytes())
        add("READ-ME-FIRST.txt", READ_ME.format(version=version).replace("\n", "\r\n").encode())
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    (OUT / f"{top}.zip.sha256").write_text(f"{digest}  {out.name}\n")
    return out


if __name__ == "__main__":
    z = build()
    print(f"{z.relative_to(ROOT)}  {z.stat().st_size:,} bytes")
