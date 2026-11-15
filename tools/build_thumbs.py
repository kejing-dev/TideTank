"""Guide card pictures: the gate-B Tripo renders of the six creatures (apps/aquarium/design/tripo-<id>-A.jpg, the same
models the app ships) cut out of their grey studio background -> res/drawable-nodpi/guide_<kind>.png (256 px, transparent).
The silhouette for 「还没见到」 is the same picture tinted in the app (SrcIn), so the shape is the real one.

  ~/.factory-venv/bin/python tools/build_thumbs.py
"""
import os
import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DESIGN = os.path.expanduser("~/Desktop/Spatial Apps/spatial-app-factory/apps/aquarium/design")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
MAP = {"chromis": "chromis", "clown": "clownfish", "tang": "bluetang", "crab": "hermitcrab", "seahorse": "seahorse", "jelly": "moonjelly"}

for src, kind in MAP.items():
    im = np.asarray(Image.open(os.path.join(DESIGN, f"tripo-{src}-A.jpg")).convert("RGB")).astype(float)
    h, w, _ = im.shape
    im = im[40:h - 70, 40:w - 140]   # drop the viewer's top-right label and bottom toolbar
    mx = im.max(axis=2); mn = im.min(axis=2)
    sat = mx - mn
    mask = (sat > 22) | (mx > 105)
    from scipy.ndimage import binary_fill_holes, binary_closing
    mask = binary_fill_holes(binary_closing(mask, iterations=4))
    m = Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.MedianFilter(5)).filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.GaussianBlur(1.2))
    a = np.asarray(m)
    ys, xs = np.nonzero(a > 128)
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    rgba = np.dstack([im, a.astype(float)]).astype(np.uint8)
    crop = Image.fromarray(rgba, "RGBA").crop((x0, y0, x1 + 1, y1 + 1))
    s = max(crop.size)
    canvas = Image.new("RGBA", (int(s * 1.08), int(s * 1.08)), (0, 0, 0, 0))
    canvas.paste(crop, ((canvas.width - crop.width) // 2, (canvas.height - crop.height) // 2), crop)
    canvas.resize((256, 256), Image.LANCZOS).save(os.path.join(OUT, f"guide_{kind}.png"))
    print(kind, crop.size)
