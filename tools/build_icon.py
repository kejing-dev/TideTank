"""Tide Tank icon (design appIcon): a clownfish side-on over two white tide lines, three food grains falling top right,
on deep sea teal #0A4F6A (design asked #0E5E6F; nudged deeper because spatial-office's icon #0F6E78 is 18 apart). Flat,
no text. Three PICO layers: sea / tide lines + grains / fish.

  ~/.factory-venv/bin/python tools/build_icon.py [--icon512 out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same shapes).
"""
import math, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageChops

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
BG = (0x0A, 0x4F, 0x6A)
ORANGE = (0xF2, 0x7A, 0x1A)
WHITE = (0xFF, 0xFF, 0xFF)
INK = (0x1B, 0x1B, 0x1B)
FOOD = (0xE9, 0xC0, 0x6A)
C = 54.0
# fish: body ellipse centre / radii, tail triangle, two stripes (x centre, half width)
BX, BY, BRX, BRY = 50.0, 47.0, 20.0, 12.5
TAIL = [(31.0, 47.0), (20.0, 37.0), (20.0, 57.0)]
STRIPES = [(41.0, 2.6), (56.0, 3.0)]
EYE = (64.0, 44.0, 2.0)
WAVES = [66.0, 74.0]
GRAINS = [(76.0, 26.0, 2.2), (82.0, 33.0, 2.0), (74.0, 36.0, 1.8)]


def big(fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def ell(d, k, cx, cy, rx, ry, **kw):
    d.ellipse(((cx - rx) * k, (cy - ry) * k, (cx + rx) * k, (cy + ry) * k), **kw)


def layer_bg():
    return big(lambda d, k: ell(d, k, C, C, 54, 54, fill=BG + (255,)))


def wave_pts(y, x0=26.0, x1=82.0, amp=2.4, n=48):
    return [(x0 + (x1 - x0) * i / n, y + amp * math.sin(i / n * 4 * math.pi)) for i in range(n + 1)]


def layer_tide():
    def f(d, k):
        for y in WAVES:
            d.line([(x * k, yy * k) for x, yy in wave_pts(y)], fill=WHITE + (255,), width=int(3.2 * k), joint="curve")
        for gx, gy, r in GRAINS:
            ell(d, k, gx, gy, r, r, fill=FOOD + (255,))
    return big(f)


def layer_fish():
    def body(d, k, col):
        ell(d, k, BX, BY, BRX, BRY, fill=col + (255,))
        d.polygon([(x * k, y * k) for x, y in TAIL], fill=col + (255,))
    outline = big(lambda d, k: (ell(d, k, BX, BY, BRX + 1.4, BRY + 1.4, fill=INK + (255,)), d.polygon([(x * k, y * k) for x, y in [(33.0, 47.0), (18.5, 35.0), (18.5, 59.0)]], fill=INK + (255,))))
    fish = big(lambda d, k: body(d, k, ORANGE))
    stripes = big(lambda d, k: [d.rectangle(((x - w) * k, (BY - 20) * k, (x + w) * k, (BY + 20) * k), fill=WHITE + (255,)) for x, w in STRIPES])
    stripes.putalpha(ImageChops.multiply(stripes.getchannel("A"), fish.getchannel("A")))
    out = outline
    out.alpha_composite(fish); out.alpha_composite(stripes)
    eye = big(lambda d, k: ell(d, k, EYE[0], EYE[1], EYE[2], EYE[2], fill=INK + (255,)))
    out.alpha_composite(eye)
    return out


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def ell_path(cx, cy, rx, ry):
    return "M%.2f,%.2fa%.2f,%.2f 0 1,0 %.2f,0a%.2f,%.2f 0 1,0 %.2f,0Z" % (cx - rx, cy, rx, ry, 2 * rx, rx, ry, -2 * rx)


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    poly = lambda pts: "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Tide Tank launcher foreground: clownfish over two tide lines, three falling food grains (tools/build_icon.py draws the same for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
    ]
    for y in WAVES:
        lines.append('    <path android:strokeColor="#FFFFFFFF" android:strokeWidth="3.2" android:strokeLineCap="round" android:pathData="%s" />' % ("M" + "L".join("%.2f,%.2f" % p for p in wave_pts(y))))
    for gx, gy, r in GRAINS:
        lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(FOOD), ell_path(gx, gy, r, r)))
    lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(INK), ell_path(BX, BY, BRX + 1.4, BRY + 1.4)))
    lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(INK), poly([(33.0, 47.0), (18.5, 35.0), (18.5, 59.0)])))
    lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(ORANGE), ell_path(BX, BY, BRX, BRY)))
    lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(ORANGE), poly(TAIL)))
    for x, w in STRIPES:
        hh = BRY * math.sqrt(max(0.0, 1 - ((x - BX) / BRX) ** 2))
        lines.append('    <path android:fillColor="#FFFFFFFF" android:pathData="%s" />' % poly([(x - w, BY - hh), (x + w, BY - hh), (x + w, BY + hh), (x - w, BY + hh)]))
    lines.append('    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(INK), ell_path(EYE[0], EYE[1], EYE[2], EYE[2])))
    lines.append('</vector>')
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_tide(), layer_fish()]
    for i, l in enumerate(layers):
        l.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(l).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    print("ICON ok")


main()
