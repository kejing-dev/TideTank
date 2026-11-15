"""Point-splat previews of GLBs (no Blender on kj-mbp-m5): side (X right, Y up), front (Z toward viewer: -X.. ) and top views,
colored by the base-colour texture at each face's UV. Used to find each Tripo model's head direction (D25)."""
import sys, os, numpy as np, trimesh
from PIL import Image

def views(path, out, size=300):
    m = trimesh.load(path, force='mesh', process=False)
    tex = m.visual.material.baseColorTexture if hasattr(m.visual.material, 'baseColorTexture') else None
    if tex is None:
        tex = getattr(m.visual.material, 'image', None)
    uv = m.visual.uv
    fc = m.triangles_center
    fuv = uv[m.faces].mean(axis=1)
    if tex is not None:
        t = np.asarray(tex.convert('RGB')); h, w = t.shape[:2]
        px = np.clip((fuv[:, 0] % 1) * (w - 1), 0, w - 1).astype(int); py = np.clip((1 - fuv[:, 1] % 1) * (h - 1), 0, h - 1).astype(int)
        col = t[py, px]
    else:
        col = np.full((len(fc), 3), 200)
    lo, hi = m.bounds; c = (lo + hi) / 2; s = (hi - lo).max()
    panels = []
    for (a, b, d, sgn) in [(0, 1, 2, 1), (2, 1, 0, -1), (0, 2, 1, -1)]:  # side: x,y depth z ; front: z,y depth -x ; top: x,z depth -y
        img = np.full((size, size, 3), 40, np.uint8); zb = np.full((size, size), -1e9)
        u = ((fc[:, a] - c[a]) / s * 0.9 + 0.5) * (size - 1); v = ((fc[:, b] - c[b]) / s * 0.9 + 0.5) * (size - 1)
        if (a, b) == (0, 2):
            v = ((fc[:, b] - c[b]) / s * 0.9 + 0.5) * (size - 1)  # top: +z down the image
        else:
            v = (size - 1) - v
        dep = fc[:, d] * sgn
        for i in np.argsort(dep):
            x, y = int(u[i]), int(v[i])
            img[max(0, y - 1):y + 2, max(0, x - 1):x + 2] = col[i]
        panels.append(img)
    Image.fromarray(np.concatenate(panels, axis=1)).save(out)

if __name__ == '__main__':
    for p in sys.argv[2:]:
        views(p, os.path.join(sys.argv[1], os.path.basename(p).replace('.glb', '.png')))
