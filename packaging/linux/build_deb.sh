#!/usr/bin/env bash
# Builds dist/hubuddy_<version>_all.deb: pure Python, so one package serves x86, x86-64, ARM and other CPUs on Debian 12+
# (Python 3.11). Needs: dpkg-deb and pip (to vendor the small pure-Python QR library segno).
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
ver="$(sed -n 's/^VERSION = "\(.*\)"/\1/p' "$root/agent/hubuddy_agent/__init__.py")"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
lib="$stage/usr/lib/hubuddy"
mkdir -p "$lib" "$stage/usr/bin" "$stage/usr/lib/systemd/user" "$stage/usr/share/applications" "$stage/etc/ufw/applications.d" \
         "$stage/usr/share/doc/hubuddy" "$stage/DEBIAN"

cp -r "$root/agent/hubuddy_agent" "$root/agent/web" "$lib/"
cp "$root/agent/config.example.toml" "$lib/"
find "$lib" -name __pycache__ -prune -exec rm -rf {} +
"${PYTHON:-python3}" -m pip install --quiet --no-compile --no-deps --target "$lib/vendor" "segno>=1.5"
rm -rf "$lib/vendor/bin" "$lib/vendor"/*.dist-info

cat > "$stage/usr/bin/hubuddy" <<'SH'
#!/bin/sh
# Hubuddy: runs without a window. `hubuddy --show-invite` prints a pairing QR code, `hubuddy --pin` a PIN,
# `hubuddy --firewall-help` the firewall commands, `hubuddy --selftest` checks the program against itself.
lib="${HUBUDDY_LIB:-/usr/lib/hubuddy}"
PYTHONPATH="$lib:$lib/vendor${PYTHONPATH:+:$PYTHONPATH}" exec "${HUBUDDY_PYTHON:-/usr/bin/python3}" -s -m hubuddy_agent "$@"
SH
cat > "$stage/usr/bin/hubuddy-window" <<'SH'
#!/bin/sh
# The optional Hubuddy window (needs python3-tk and a desktop); without them it falls back to the terminal.
lib="${HUBUDDY_LIB:-/usr/lib/hubuddy}"
PYTHONPATH="$lib:$lib/vendor${PYTHONPATH:+:$PYTHONPATH}" exec "${HUBUDDY_PYTHON:-/usr/bin/python3}" -s -m hubuddy_agent.launcher "$@"
SH
chmod 755 "$stage/usr/bin/hubuddy" "$stage/usr/bin/hubuddy-window"

cat > "$stage/usr/lib/systemd/user/hubuddy.service" <<'UNIT'
[Unit]
Description=Hubuddy: phones see and control this PC
After=network-online.target

[Service]
ExecStart=/usr/bin/hubuddy
Restart=on-failure
RestartSec=5
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=full
Nice=10

[Install]
WantedBy=default.target
UNIT

cat > "$stage/usr/share/applications/hubuddy.desktop" <<'DESK'
[Desktop Entry]
Type=Application
Name=Hubuddy
Comment=Pair phones with this PC
Exec=hubuddy-window
Terminal=false
Categories=Utility;Network;
DESK

cat > "$stage/etc/ufw/applications.d/hubuddy" <<'UFW'
[Hubuddy]
title=Hubuddy
description=Lets phones on your network find and talk to this PC
ports=8765/tcp|41236/udp
UFW

cp "$root/NOTICE" "$stage/usr/share/doc/hubuddy/copyright"
cat > "$stage/DEBIAN/control" <<CTL
Package: hubuddy
Version: ${ver}
Architecture: all
Section: utils
Priority: optional
Depends: python3 (>= 3.11), python3-psutil, python3-cryptography (>= 38)
Recommends: xdotool, xclip | wl-clipboard, playerctl, python3-tk
Suggests: python3-icalendar, ufw
Maintainer: Hubuddy <noreply@users.noreply.github.com>
Installed-Size: $(du -sk "$stage/usr" | cut -f1)
Homepage: https://github.com/yeshwanthkumardomala/hubuddy
Description: Phone dashboard and remote control for this PC
 Hubuddy shows this PC's live readings on an Android phone, and lets paired phones
 send text, links and files, and (when you allow it per phone) use the PC remotely.
 Phones pair with a QR code or PIN and find the PC on the same network. Works on
 small systems: no desktop is needed, the program is plain Python.
CTL
cat > "$stage/DEBIAN/postinst" <<'POST'
#!/bin/sh
set -e
echo "Hubuddy installed. Pair a phone:  hubuddy --show-invite    Start at login:  systemctl --user enable --now hubuddy"
echo "Firewall help:  hubuddy --firewall-help"
POST
chmod 755 "$stage/DEBIAN/postinst"

mkdir -p "$root/dist"
out="$root/dist/hubuddy_${ver}_all.deb"
dpkg-deb --build --root-owner-group "$stage" "$out" >/dev/null
( cd "$root/dist" && sha256sum "$(basename "$out")" > "$(basename "$out").sha256" )
echo "$out"
