#!/usr/bin/env python3
"""Generate Google Play store graphics for Ondes from the in-app icon design.

Outputs (docs/store-assets/):
  - icon-512.png            512x512 hi-res app icon
  - feature-graphic-1024x500.png

The mark mirrors the adaptive launcher icon: five rounded "sound-wave" bars in
white over a diagonal indigo gradient. Run: python3 scripts/generate-store-assets.py
"""
import os

from PIL import Image, ImageDraw, ImageFont

C0 = (61, 90, 254)    # #3D5AFE  indigo A200
C1 = (26, 35, 126)    # #1A237E  indigo 900
# Brand fonts bundled with the app (see docs/fonts/): Space Grotesk for the
# wordmark, Manrope for the tagline.
FONT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                        "app", "src", "main", "res", "font")
FONT_WORDMARK = os.path.join(FONT_DIR, "space_grotesk_bold.ttf")
FONT_TAGLINE = os.path.join(FONT_DIR, "manrope_medium.ttf")

# Bars in the 108x108 icon viewport: (center_x, top_y, bottom_y)
BARS = [(28, 42, 66), (41, 34, 74), (54, 26, 82), (67, 34, 74), (80, 42, 66)]
STROKE = 8  # bar width in viewport units


def diagonal_gradient(w, h, c0, c1):
    """Smooth top-left -> bottom-right linear gradient."""
    px = bytearray(w * h * 3)
    denom = max(1, (w - 1) + (h - 1))
    for y in range(h):
        for x in range(w):
            t = (x + y) / denom
            i = (y * w + x) * 3
            px[i] = int(c0[0] + (c1[0] - c0[0]) * t)
            px[i + 1] = int(c0[1] + (c1[1] - c0[1]) * t)
            px[i + 2] = int(c0[2] + (c1[2] - c0[2]) * t)
    return Image.frombytes("RGB", (w, h), bytes(px))


def wave_mark(size, scale_unit, offset=(0, 0), ss=4):
    """Antialiased white sound-wave bars on a transparent RGBA layer of `size`.

    scale_unit maps the 108-unit viewport onto the target; offset shifts the
    108x108 origin (in target px). Drawn supersampled then downscaled.
    """
    w, h = size
    big = Image.new("RGBA", (w * ss, h * ss), (0, 0, 0, 0))
    d = ImageDraw.Draw(big)
    s = scale_unit * ss
    ox, oy = offset[0] * ss, offset[1] * ss
    r = STROKE / 2 * s
    for cx, y0, y1 in BARS:
        x = cx * s + ox
        top = y0 * s + oy
        bot = y1 * s + oy
        d.rounded_rectangle([x - r, top - r, x + r, bot + r], radius=r,
                            fill=(255, 255, 255, 255))
    return big.resize((w, h), Image.LANCZOS)


def make_icon(path):
    n = 512
    img = diagonal_gradient(n, n, C0, C1).convert("RGBA")
    # Map the 108 viewport across the full square (mark fills the centre).
    mark = wave_mark((n, n), n / 108.0)
    img.alpha_composite(mark)
    img.convert("RGB").save(path)
    print("wrote", path)


def make_feature(path):
    w, h = 1024, 500
    img = diagonal_gradient(w, h, C0, C1).convert("RGBA")
    # Mark on the left third, vertically centred. Viewport ~300px tall.
    vp = 300.0
    unit = vp / 108.0
    off_x = 150
    off_y = (h - vp) / 2
    img.alpha_composite(wave_mark((w, h), unit, offset=(off_x, off_y)))
    # Wordmark + tagline on the right.
    d = ImageDraw.Draw(img)
    title = ImageFont.truetype(FONT_WORDMARK, 150)
    sub = ImageFont.truetype(FONT_TAGLINE, 44)
    tx = 470
    # Place by baseline ("ls" anchor) so the layout doesn't depend on each
    # font's ascent metrics.
    d.text((tx, 300), "Ondes", font=title, anchor="ls",
           fill=(255, 255, 255, 255))
    d.text((tx + 6, 358), "Lecteur de podcasts", font=sub, anchor="ls",
           fill=(255, 255, 255, 230))
    img.convert("RGB").save(path)
    print("wrote", path)


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(__file__), "..", "docs", "store-assets")
    out = os.path.abspath(out)
    os.makedirs(out, exist_ok=True)
    make_icon(os.path.join(out, "icon-512.png"))
    make_feature(os.path.join(out, "feature-graphic-1024x500.png"))
