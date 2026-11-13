"""Draw the profile pictures: twelve animal faces, CAS's own drawings, as Android vector drawables.

Run from anywhere: python3 tools/cas_animals.py [preview.png]

Each animal is a few plain shapes (circles, ellipses, triangles) in a 96 x 96 box, drawn to sit on a profile's
coloured disc. The same shapes are written as res/drawable/ic_animal_<name>.xml and, when a path is given, drawn
with Pillow into one sheet to look at.
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res/drawable'
INK = '#2A2C31'; WHITE = '#F7F5F0'; PINK = '#F2A7B5'

def eyes(y=47, dx=12, r=3.4, c=INK): return [('c', 48 - dx, y, r, c), ('c', 48 + dx, y, r, c)]
def tri(a, b, c, col): return ('t', *a, *b, *c, col)
def both(shape):
    """A shape and its mirror image about the middle."""
    k = shape[0]
    if k == 'c': return [shape, ('c', 96 - shape[1], *shape[2:])]
    if k == 'e': return [shape, ('e', 96 - shape[1], *shape[2:])]
    x = shape[1:7]; return [shape, ('t', 96 - x[0], x[1], 96 - x[2], x[3], 96 - x[4], x[5], shape[7])]

ANIMALS = {
    'cat': both(tri((20, 40), (24, 10), (44, 26), '#8E9AA8')) + both(tri((25, 34), (27, 19), (37, 28), PINK)) +
           [('c', 48, 54, 30, '#8E9AA8')] + eyes() + [tri((44.5, 57), (51.5, 57), (48, 62), PINK)],
    'dog': [('c', 48, 52, 29, '#C99A6B')] + both(('e', 20, 50, 9, 19, '#7A5236')) +
           [('e', 48, 63, 14, 11, '#F1E3CF'), ('e', 48, 57.5, 5.2, 3.8, INK)] + eyes(45),
    'fox': both(tri((16, 44), (22, 8), (46, 28), '#E8833A')) + both(tri((23, 36), (25, 18), (37, 30), INK)) +
           [('c', 48, 54, 29, '#E8833A'), ('e', 48, 70, 21, 13, WHITE), ('c', 48, 64, 3.6, INK)] + eyes(48),
    'bear': both(('c', 23, 28, 11, '#8B5E3C')) + both(('c', 23, 28, 5.5, '#C99A6B')) +
            [('c', 48, 54, 30, '#8B5E3C'), ('e', 48, 63, 13, 10, '#E9D2B0'), ('e', 48, 58.5, 5, 3.6, INK)] + eyes(46),
    'rabbit': both(('e', 35, 22, 8, 21, '#DAD5CC')) + both(('e', 35, 23, 3.6, 15, PINK)) +
              [('c', 48, 59, 27, '#DAD5CC')] + eyes(54) + [('e', 48, 63, 3.6, 2.8, PINK)],
    'owl': both(tri((20, 30), (24, 12), (40, 26), '#7A5C46')) + [('c', 48, 54, 30, '#7A5C46')] +
           both(('c', 35, 49, 11.5, WHITE)) + both(('c', 35, 49, 4.8, INK)) + [tri((43.5, 57), (52.5, 57), (48, 66), '#F0A63A')],
    'panda': both(('c', 22, 28, 11, INK)) + [('c', 48, 54, 30, '#E7E3DB')] + both(('e', 34.5, 49, 8, 10, INK)) +
             both(('c', 35.5, 48, 3, WHITE)) + [('e', 48, 62, 5, 3.6, INK)],
    'lion': [('c', 48, 52, 38, '#C7772C'), ('c', 48, 54, 25, '#F0B55A')] + eyes(49, 10) +
            [tri((44, 58), (52, 58), (48, 63.5), '#8B4A1C')],
    'penguin': [('c', 48, 52, 30, '#2E3440'), ('e', 48, 59, 20, 21, WHITE)] + eyes(50, 9) +
               [tri((43, 58), (53, 58), (48, 66), '#F0A63A')],
    'frog': both(('c', 31, 34, 12, '#6DB65B')) + [('e', 48, 58, 31, 24, '#6DB65B')] + both(('c', 31, 34, 7.5, WHITE)) +
            both(('c', 31, 34, 3.6, INK)) + both(('c', 44, 58, 1.5, '#3F7A36')),
    'pig': both(tri((18, 40), (22, 14), (42, 28), '#E58FA1')) + [('c', 48, 54, 29, PINK), ('e', 48, 61, 13, 9.5, '#E58FA1')] +
           both(('e', 43.5, 61, 2.2, 3.2, '#B4566C')) + eyes(46),
    'koala': both(('c', 20, 38, 15, '#A7ADB5')) + both(('c', 20, 38, 8, '#DDE0E4')) +
             [('c', 48, 54, 28, '#A7ADB5'), ('e', 48, 59, 7, 10, INK)] + eyes(48, 14),
}

def path(shape):
    k = shape[0]
    if k == 'c': _, cx, cy, r, col = shape; rx = ry = r
    elif k == 'e': _, cx, cy, rx, ry, col = shape
    else:
        _, x1, y1, x2, y2, x3, y3, col = shape
        return col, f"M{x1},{y1}L{x2},{y2}L{x3},{y3}z"
    return col, f"M{cx - rx},{cy}a{rx},{ry} 0 1,0 {2 * rx},0a{rx},{ry} 0 1,0 {-2 * rx},0z"

for name, shapes in ANIMALS.items():
    body = '\n'.join(f'    <path android:fillColor="#FF{c[1:]}" android:pathData="{d}" />' for c, d in map(path, shapes))
    (RES / f'ic_animal_{name}.xml').write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- A profile picture: the {name}. Drawn by tools/cas_animals.py; do not tint. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="96dp" android:height="96dp" android:viewportWidth="96" android:viewportHeight="96">
{body}
</vector>
''')
print('wrote', len(ANIMALS), 'animals')

if len(sys.argv) > 1:
    from PIL import Image, ImageDraw
    S = 3; discs = ['#F7F5F0', '#C98E6A', '#8FA39A', '#A6A2BD', '#D3A66B', '#2A2C31']
    sheet = Image.new('RGB', (6 * 150 * S, 2 * 150 * S), '#F3F1EC'); d = ImageDraw.Draw(sheet)
    for i, (name, shapes) in enumerate(ANIMALS.items()):
        ox, oy = (i % 6) * 150 * S + 15 * S, (i // 6) * 150 * S + 15 * S
        d.ellipse([ox, oy, ox + 120 * S, oy + 120 * S], fill=discs[i % 6])
        k = 120 * S / 96 * 0.78; off = 120 * S * 0.11
        for sh in shapes:
            if sh[0] == 'c': _, cx, cy, r, col = sh; d.ellipse([ox + off + (cx - r) * k, oy + off + (cy - r) * k, ox + off + (cx + r) * k, oy + off + (cy + r) * k], fill=col)
            elif sh[0] == 'e': _, cx, cy, rx, ry, col = sh; d.ellipse([ox + off + (cx - rx) * k, oy + off + (cy - ry) * k, ox + off + (cx + rx) * k, oy + off + (cy + ry) * k], fill=col)
            else: p = sh[1:7]; d.polygon([(ox + off + p[j] * k, oy + off + p[j + 1] * k) for j in (0, 2, 4)], fill=sh[7])
    sheet.resize((900, 300), Image.LANCZOS).save(sys.argv[1])
