"""Regenerate the CAS brand assets with Pillow (Inter is licensed in assets/fonts).

Run from anywhere: python3 tools/cas_brand.py

The mark is an aperture: a C-shaped ring with a dot, the C of CAS and a lens looking back. Monochrome, geometric,
and legible at launcher size.

- drawable-xhdpi/banner.png (320x180): the Fire TV / Android TV launcher tile. PNG, as Fire TV's launcher wants (a
  WebP banner was passed over and the square icon shown with grey bars). The whole tile is the brand: a lit charcoal
  surface with a warm glow behind the mark, the mark and the wordmark large across it, no border.
- logo_transparent.png (512x512): the bare mark; the adaptive icon's foreground and the Watch Next mark.
- drawable/ic_mark.xml: the same mark as a vector, for the wordmark on every screen (Screen.brand()).
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / 'app/src/main/assets/fonts/inter.ttf'
DEST = ROOT / 'app/src/main/res/drawable-nodpi'
TILE = ROOT / 'app/src/main/res/drawable-xhdpi'
VECTOR = ROOT / 'app/src/main/res/drawable/ic_mark.xml'

BASE = (8, 9, 11)        # #08090B, Screen.BASE
LIFT = (30, 32, 38)      # the top of the gradient: barely lighter, so the tile reads as a surface, not a hole
INK = (245, 245, 247)    # #F5F5F7, Screen.TEXT
WARM = (243, 231, 211)   # #F3E7D3, Screen.TUNGSTEN: the lens, lit
GLOW = (120, 96, 70)     # the warm light behind the mark, at its brightest before it fades
S = 4                    # supersampling: drawn large, then shrunk, so the curves are smooth
GAP_DEG = 35             # half the opening of the C, in degrees, either side of the right


def plate(size):
    """Vertical charcoal gradient, LIFT at the top to BASE at the bottom."""
    w, h = size
    im = Image.new('RGB', size, BASE)
    px = im.load()
    for y in range(h):
        t = (y / max(1, h - 1)) ** 2  # ease: most of the lift stays near the top
        row = tuple(round(LIFT[i] + (BASE[i] - LIFT[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = row
    return im.convert('RGBA')


def mark(draw, cx, cy, r, stroke, lens=INK):
    """The aperture: ring open on the right, the lens (dot) at the centre, lit warm where colour is allowed."""
    draw.arc([cx - r, cy - r, cx + r, cy + r], start=GAP_DEG, end=360 - GAP_DEG, fill=INK, width=stroke)
    dr = r * 0.24
    draw.ellipse([cx - dr, cy - dr, cx + dr, cy + dr], fill=lens)


def glow(im, cx, cy, radius):
    """A soft warm light behind the mark: the tile reads as a lit surface, as the big services' tiles do."""
    w, h = im.size
    layer = Image.new('RGBA', im.size, (0, 0, 0, 0))
    px = layer.load()
    for y in range(h):
        for x in range(w):
            d = math.hypot(x - cx, y - cy) / radius
            if d < 1:
                a = round(255 * (1 - d) ** 2 * 0.55)
                px[x, y] = (*GLOW, a)
    return Image.alpha_composite(im, layer)


def wordmark(draw, cx, cy, font_size, tracking=0.14, weight=650, fill=INK):
    """'CAS' set in Inter, letterspaced and optically centred on (cx, cy)."""
    font = ImageFont.truetype(str(FONT), font_size)
    font.set_variation_by_axes([32, weight])  # Inter's axes are [optical size, weight]; 32 is its display cut
    gap = font_size * tracking
    widths = [draw.textlength(c, font=font) for c in 'CAS']
    total = sum(widths) + gap * 2
    x = cx - total / 2
    box = draw.textbbox((0, 0), 'CAS', font=font)
    y = cy - (box[3] - box[1]) / 2 - box[1]
    for c, width in zip('CAS', widths):
        draw.text((x, y), c, fill=fill, font=font)
        x += width + gap


def shrink(im, size):
    return im.resize(size, Image.LANCZOS)


# Banner: the mark large at left with its warm light, the wordmark at right, both on one optical centre line,
# filling the tile the way the big services' tiles do.
G = 2  # the glow is drawn at a lower supersample: it is soft anyway, and the per-pixel loop is slow at S
im = plate((320 * G, 180 * G))
im = glow(im, 84 * G, 90 * G, 150 * G)
im = im.resize((320 * S, 180 * S), Image.BICUBIC)
d = ImageDraw.Draw(im)
mark(d, 84 * S, 90 * S, 42 * S, 10 * S, lens=WARM)
wordmark(d, 212 * S, 90 * S, 60 * S, tracking=0.12)
TILE.mkdir(exist_ok=True)
shrink(im, (320, 180)).convert('RGB').save(TILE / 'banner.png', optimize=True)
old = DEST / 'banner.webp'
if old.exists():
    old.unlink()

# The bare mark, for the adaptive icon (its inset keeps it inside the safe zone) and Watch Next.
im = Image.new('RGBA', (512 * S, 512 * S), (0, 0, 0, 0))
mark(ImageDraw.Draw(im), 256 * S, 256 * S, 168 * S, 40 * S, lens=WARM)
shrink(im, (512, 512)).save(DEST / 'logo_transparent.png')

# The vector: one arc path for the ring (as a thick stroke) and a circle for the dot, in a 24 dp box.
cx = cy = 12.0
r = 8.2
a0, a1 = math.radians(GAP_DEG), math.radians(360 - GAP_DEG)
x0, y0 = cx + r * math.cos(a0), cy + r * math.sin(a0)
x1, y1 = cx + r * math.cos(a1), cy + r * math.sin(a1)
ring = f"M{x0:.2f},{y0:.2f} A{r},{r} 0 1 1 {x1:.2f},{y1:.2f}"
dot_r = r * 0.22
dot = f"M{cx - dot_r:.2f},{cy} a{dot_r:.2f},{dot_r:.2f} 0 1 0 {2 * dot_r:.2f},0 a{dot_r:.2f},{dot_r:.2f} 0 1 0 {-2 * dot_r:.2f},0"
VECTOR.write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- The CAS mark: an aperture. Generated by tools/cas_brand.py; tint it like text. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="2.2" android:strokeLineCap="round" android:fillColor="#00000000" android:pathData="{ring}" />
    <path android:fillColor="#FFFFFFFF" android:pathData="{dot}" />
</vector>
''')
print('wrote', TILE / 'banner.png', DEST / 'logo_transparent.png', VECTOR.name)
