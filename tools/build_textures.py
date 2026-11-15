"""Procedural textures for the parts the design makes procedural (design assets: sand, the sea-gate backing board).
walnut.jpg comes from Aura Garden's tools/build_textures.py (procedural, no third-party image).

  ~/.factory-venv/bin/python tools/build_textures.py
"""
import os
import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "textures")
rng = np.random.default_rng(5)


def sand(n=512):
    base = np.array([214, 196, 160], float)
    g = rng.normal(0, 1, (n, n))
    fine = np.array(Image.fromarray(((g - g.min()) / (g.max() - g.min()) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.7))).astype(float) / 255
    lo = rng.normal(0, 1, (n // 16, n // 16))
    lo = np.array(Image.fromarray(((lo - lo.min()) / (lo.max() - lo.min()) * 255).astype(np.uint8)).resize((n, n), Image.BICUBIC)).astype(float) / 255
    k = 0.80 + 0.22 * fine + 0.10 * (lo - 0.5)
    img = np.clip(base[None, None, :] * k[..., None], 0, 255)
    # a few darker grains and shell bits
    for _ in range(1600):
        x, y = rng.integers(0, n, 2); img[y, x] = img[y, x] * rng.uniform(0.55, 0.8)
    for _ in range(500):
        x, y = rng.integers(0, n, 2); img[y, x] = [245, 238, 225]
    Image.fromarray(img.astype(np.uint8)).save(os.path.join(OUT, "sand.jpg"), quality=90)


def deep(n=256):
    # the open sea behind the gate: deep blue, lighter toward the top
    y = np.linspace(0, 1, n)[:, None]
    top = np.array([40, 110, 160]); bot = np.array([8, 30, 70])
    img = (top * (1 - y) + bot * y)[:, None, :].repeat(n, axis=1)
    img += rng.normal(0, 3, img.shape)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(os.path.join(OUT, "deep.jpg"), quality=90)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    sand(); deep()
    print("sand.jpg deep.jpg")
