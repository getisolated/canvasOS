#!/usr/bin/env python3
"""Generate the web image set for the canvasOS website.

Sources of truth (never replaced by generated pictures):
  ../docs/screenshots/*.png   real captures, 1920x1080, Thomson Streaming Box 240G
  ../DA/                      brand assets (social preview, launcher icon)

Outputs in public/img/:
  {name}-1280.webp, {name}-640.webp   one pair per capture (also the video posters)
  og.png                              Open Graph card (DA/social-preview-1280x640.png)
  apple-touch-icon.png                180x180, from DA/ic_launcher-512.png

Run from anywhere (needs Pillow):  python3 scripts/build-assets.py
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
SHOTS = REPO / "docs" / "screenshots"
DA = REPO / "DA"
OUT = ROOT / "public" / "img"

# web name -> capture file in docs/screenshots
CAPTURES = {
    "home": "accueil.png",
    "intro": "animation.png",
    "frame": "cadre-photo.png",
    "cc": "centre-de-controle.png",
    "settings": "reglages.png",
    "shelf": "top-shelf.png",
}


def build_screenshots() -> None:
    for name, file in CAPTURES.items():
        src = SHOTS / file
        if not src.exists():
            sys.exit(f"missing capture: {src}")
        im = Image.open(src).convert("RGB")
        w, h = im.size
        if w * 9 != h * 16:
            sys.exit(f"{src} is {w}x{h}, expected 16:9")
        im.resize((1280, 720), Image.LANCZOS).save(OUT / f"{name}-1280.webp", "WEBP", quality=84, method=6)
        im.resize((640, 360), Image.LANCZOS).save(OUT / f"{name}-640.webp", "WEBP", quality=80, method=6)


def build_brand() -> None:
    og = Image.open(DA / "social-preview-1280x640.png").convert("RGB")
    og.save(OUT / "og.png", "PNG", optimize=True)
    icon = Image.open(DA / "ic_launcher-512.png").convert("RGB")
    icon.resize((180, 180), Image.LANCZOS).save(OUT / "apple-touch-icon.png", "PNG", optimize=True)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    build_screenshots()
    build_brand()
    print("wrote", OUT)
    for f in sorted(OUT.iterdir()):
        print(f"  {f.name:28s} {f.stat().st_size / 1024:7.1f} KB")
