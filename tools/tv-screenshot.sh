#!/bin/sh
# Takes a native 1920×1080 screenshot of the TV over adb, for the README (docs/screenshots).
# Usage: tools/tv-screenshot.sh <name>   → docs/screenshots/<name>.png
set -e
TV="${TV_IP:-192.168.1.125}:5555"
NAME="${1:?usage: tools/tv-screenshot.sh <name>}"
DIR="$(cd "$(dirname "$0")/.." && pwd)/docs/screenshots"
mkdir -p "$DIR"
adb -s "$TV" exec-out screencap -p > "$DIR/$NAME.png"
sips -g pixelWidth -g pixelHeight "$DIR/$NAME.png" | tail -2 | tr '\n' ' '; echo "→ $DIR/$NAME.png"
