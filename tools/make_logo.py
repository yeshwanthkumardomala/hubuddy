"""Draws the Hubuddy logo (an H whose crossbar is a signal pulse) and writes every size the project needs.

    python tools/make_logo.py

Outputs: assets/logo-1024.png, docs/hubuddy/logo.png, agent/web/logo.png, agent/web/favicon.png, agent/hubuddy.ico.
The Android launcher icon is the same drawing written by hand as a vector (android/.../drawable/ic_launcher_fg.xml).
"""
import pathlib

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
BG_TOP, BG_BOTTOM, WHITE, TEAL = (13, 24, 40), (10, 56, 70), (236, 243, 250), (46, 196, 182)


def draw(size: int = 1024, rounded: bool = True) -> Image.Image:
    s = 4                       # supersample for smooth edges
    n = size * s
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    grad = Image.new("RGBA", (n, n))
    px = grad.load()
    for y in range(n):
        t = y / (n - 1)
        row = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)) + (255,)
        for x in range(n):
            px[x, y] = row
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.225) if rounded else 0, fill=255)
    img.paste(grad, (0, 0), mask)
    d = ImageDraw.Draw(img)
    u = n / 108.0               # the same 108-unit grid the Android vector uses
    bar = lambda x0, y0, x1, y1, c: d.rounded_rectangle([x0 * u, y0 * u, x1 * u, y1 * u], radius=4.2 * u, fill=c)
    bar(31, 28, 43, 80, WHITE)          # left stem
    bar(65, 28, 77, 80, WHITE)          # right stem
    bar(40, 48, 68, 60, TEAL)           # crossbar
    d.ellipse([(88 - 4.5) * u, (24 - 4.5) * u, (88 + 4.5) * u, (24 + 4.5) * u], fill=TEAL)   # signal dot
    d.arc([(88 - 11) * u, (24 - 11) * u, (88 + 11) * u, (24 + 11) * u], 180, 270, fill=TEAL, width=int(2.4 * u))
    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    big = draw(1024)
    for rel, size in (("assets/logo-1024.png", 1024), ("docs/hubuddy/logo.png", 512), ("agent/web/logo.png", 256), ("agent/web/favicon.png", 64)):
        p = ROOT / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        big.resize((size, size), Image.LANCZOS).save(p)
    big.save(ROOT / "agent/hubuddy.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    print("logo written")
