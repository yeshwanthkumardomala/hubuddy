"""Builds every logo file the project needs.

    python tools/make_logo.py

If `assets/logo-source.png` exists (the owner's own logo: a dark mark on a light background is fine) it is used: the background
is turned transparent and the mark is placed on the app tile. Otherwise the built-in H-with-signal drawing is used.

Outputs: assets/logo-1024.png, docs/hubuddy/logo.png, agent/web/logo.png, agent/web/favicon.png, agent/hubuddy.ico,
and the Android launcher PNGs (android/hubuddy/app/src/main/res/mipmap-*/ic_launcher.png and drawable-nodpi/ic_launcher_fg.png).
"""
import pathlib

from PIL import Image, ImageChops, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "assets" / "logo-source.png"
RES = ROOT / "android/hubuddy/app/src/main/res"
BG_TOP, BG_BOTTOM, WHITE, TEAL = (13, 24, 40), (10, 56, 70), (236, 243, 250), (46, 196, 182)


def tile(size: int, rounded: bool = True) -> Image.Image:
    """The rounded app tile with its dark gradient."""
    s = 2
    n = size * s
    grad = Image.new("RGBA", (n, n))
    px = grad.load()
    for y in range(n):
        t = y / (n - 1)
        row = tuple(int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)) + (255,)
        for x in range(n):
            px[x, y] = row
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.225) if rounded else 0, fill=255)
    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


def mark_from_source() -> Image.Image:
    """The owner's logo as a white mark on transparency, cropped square."""
    src = Image.open(SOURCE).convert("RGBA")
    lum = src.convert("L")
    alpha = ImageChops.invert(lum)                      # dark ink -> opaque, white paper -> transparent
    alpha = alpha.point(lambda v: 0 if v < 12 else min(255, int((v - 12) * 1.08)))
    box = alpha.getbbox() or (0, 0, src.width, src.height)
    mark = Image.new("RGBA", src.size, WHITE + (0,))
    mark.putalpha(alpha)
    mark = mark.crop(box)
    side = max(mark.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(mark, ((side - mark.width) // 2, (side - mark.height) // 2))
    return square


def builtin(size: int) -> Image.Image:
    s = 4
    n = size * s
    img = tile(n)
    d = ImageDraw.Draw(img)
    u = n / 108.0
    bar = lambda x0, y0, x1, y1, c: d.rounded_rectangle([x0 * u, y0 * u, x1 * u, y1 * u], radius=4.2 * u, fill=c)
    bar(31, 28, 43, 80, WHITE); bar(65, 28, 77, 80, WHITE); bar(40, 48, 68, 60, TEAL)
    d.ellipse([(88 - 4.5) * u, (24 - 4.5) * u, (88 + 4.5) * u, (24 + 4.5) * u], fill=TEAL)
    d.arc([(88 - 11) * u, (24 - 11) * u, (88 + 11) * u, (24 + 11) * u], 180, 270, fill=TEAL, width=int(2.4 * u))
    return img.resize((size, size), Image.LANCZOS)


def with_source(size: int, padding: float = 0.22) -> Image.Image:
    base = tile(size)
    mark = mark_from_source()
    inner = int(size * (1 - 2 * padding))
    mark = mark.resize((inner, inner), Image.LANCZOS)
    base.alpha_composite(mark, ((size - inner) // 2, (size - inner) // 2))
    return base


def render(size: int) -> Image.Image:
    return with_source(size) if SOURCE.exists() else builtin(size)


if __name__ == "__main__":
    big = render(1024)
    for rel, size in (("assets/logo-1024.png", 1024), ("docs/hubuddy/logo.png", 512), ("agent/web/logo.png", 256), ("agent/web/favicon.png", 64)):
        p = ROOT / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        big.resize((size, size), Image.LANCZOS).save(p)
    big.save(ROOT / "agent/hubuddy.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    if SOURCE.exists():
        # Android: PNG launcher icons (the owner's mark is a picture, not the hand-written H vector) and an adaptive foreground
        for dens, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
            d = RES / f"mipmap-{dens}"
            d.mkdir(parents=True, exist_ok=True)
            render(px).save(d / "ic_launcher.png")
        fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
        m = mark_from_source().resize((190, 190), Image.LANCZOS)      # inside the adaptive-icon safe zone
        fg.alpha_composite(m, ((432 - 190) // 2, (432 - 190) // 2))
        (RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
        fg.save(RES / "drawable-nodpi" / "ic_launcher_fg.png")
        for stale in (RES / "mipmap" / "ic_launcher.xml", RES / "drawable" / "ic_launcher_fg.xml"):
            if stale.exists():
                stale.unlink()
        print("logo written from assets/logo-source.png (Android launcher PNGs included)")
    else:
        print("logo written (built-in drawing; add assets/logo-source.png to use your own)")
