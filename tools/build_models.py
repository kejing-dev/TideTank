"""Tide Tank models: the Tripo GLBs chosen at gate B (design assetFiles), decimated by the factory's bin/glb_decimate.py,
then calibrated here (profile D25): origin at the geometric centre (swimmers) or the bottom centre (things that sit on the
sand / the base), head toward +X, scaled to the design's real size. Smooth vertex normals are rebuilt (decimated Tripo
meshes come back unwelded = flat shaded). All OPAQUE except the moon jelly (design budget.transparent: the only
see-through creature, BLEND at 0.55).

  ~/.factory-venv/bin/python bin/glb_decimate.py <tripo>.glb ~/Downloads/aquarium-tripo/dec/<id>.glb --faces N   # once
  ~/.factory-venv/bin/python tools/build_models.py

Writes app/src/main/assets/models/*.glb and prints a calibration table (bbox after calibration, triangles) that goes
into ui-audit.md. The numbers here are the single source of the calibration (D25: one place, every instance shares it).
"""
import json, os, sys
import numpy as np
import trimesh
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.expanduser("~/Downloads/aquarium-tripo/dec")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "models")

# id -> (source, origin 'centre' | 'bottom', scale spec, yaw degrees applied first)
# scale spec: ('len', axis, metres) uniform | ('box', (x, y, z)) non-uniform to an exact box
MODELS = {
    "chromis":  ("chromis-A",  "centre", ("len", 0, 0.050), 0),
    "clown":    ("clown-A",    "centre", ("len", 0, 0.070), 0),
    "tang":     ("tang-A",     "centre", ("len", 0, 0.100), 0),
    "crab":     ("crab-A",     "bottom", ("len", 0, 0.045), 0),
    "seahorse": ("seahorse-A", "centre", ("len", 1, 0.080), 0),
    "jelly":    ("jelly-A",    "centre", ("len", 0, 0.080), 0),
    # the slope rises toward the left wall: the Tripo rock rises toward +X, so turn it 180 deg
    "rock":     ("rock-A",     "bottom", ("box", (0.232, 0.300, 0.284)), 180),
    "anemone":  ("anemone-A",  "bottom", ("box", (0.090, 0.070, 0.090)), 0),
    "grass":    ("grass-B",    "bottom", ("box", (0.080, 0.110, 0.070)), 0),
    "jar":      ("jar-A",      "bottom", ("len", 1, 0.070), 0),
    "post":     ("lever-A",    "bottom", ("len", 1, 0.380), 0),
}


def smooth_normals(m):
    """Vertex normals averaged over every face that shares the position (decimated meshes are unwelded)."""
    key = np.round(m.vertices, 6)
    _, inv = np.unique(key, axis=0, return_inverse=True)
    inv = inv.reshape(-1)
    fn = m.face_normals * m.area_faces[:, None]
    acc = np.zeros((inv.max() + 1, 3))
    for k in range(3):
        np.add.at(acc, inv[m.faces[:, k]], fn)
    n = acc[inv]
    n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-9)
    return n


def material(m, alpha=None, plain=None):
    mat = m.visual.material
    tex = getattr(mat, "baseColorTexture", None)
    if plain is not None:
        return trimesh.visual.material.PBRMaterial(baseColorFactor=plain, metallicFactor=0.0, roughnessFactor=0.55, alphaMode="OPAQUE", doubleSided=False)
    if alpha is not None:
        return trimesh.visual.material.PBRMaterial(baseColorTexture=tex, baseColorFactor=[1.0, 1.0, 1.0, alpha], metallicFactor=0.0,
                                                   roughnessFactor=0.35, alphaMode="BLEND", doubleSided=True)
    return trimesh.visual.material.PBRMaterial(baseColorTexture=tex, metallicFactor=0.0, roughnessFactor=0.75, alphaMode="OPAQUE", doubleSided=False)


def submesh(m, keep):
    faces = m.faces[keep]
    used = np.unique(faces)
    remap = -np.ones(len(m.vertices), dtype=np.int64); remap[used] = np.arange(len(used))
    uv = m.visual.uv[used]
    out = trimesh.Trimesh(m.vertices[used], remap[faces], visual=trimesh.visual.TextureVisuals(uv=uv, material=m.visual.material), process=False)
    return out


def calibrate(m, origin, spec, yaw):
    if yaw:
        R = trimesh.transformations.rotation_matrix(np.radians(yaw), [0, 1, 0])
        m.apply_transform(R)
    lo, hi = m.bounds
    c = (lo + hi) / 2
    if origin == "bottom":
        c = np.array([c[0], lo[1], c[2]])
    m.apply_translation(-c)
    ext = m.extents
    if spec[0] == "len":
        s = spec[2] / ext[spec[1]]
        m.apply_scale(s)
    else:
        S = np.eye(4); S[0, 0], S[1, 1], S[2, 2] = (np.array(spec[1]) / ext)
        m.apply_transform(S)
    return m


def export(m, name, mat):
    n = smooth_normals(m)
    out = trimesh.Trimesh(m.vertices, m.faces, vertex_normals=n,
                          visual=trimesh.visual.TextureVisuals(uv=m.visual.uv, material=mat), process=False)
    path = os.path.join(OUT, name + ".glb")
    out.export(path)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    rows = []
    for mid, (src, origin, spec, yaw) in MODELS.items():
        m = trimesh.load(os.path.join(SRC, src + ".glb"), force="mesh", process=False)
        if mid == "post":
            # lever-A is one mesh: post + scale + gauge rod + crossbar + two hanging rings. The app draws its own draggable brass
            # handle (true torus), so cut the crossbar and both rings (y 0.66..0.87 in model units, outside the post column).
            c = m.triangles_center
            band = (c[:, 1] > 0.655) & (c[:, 1] < 0.875)
            outside = (c[:, 0] < -0.058) | (c[:, 0] > 0.112) | (c[:, 2] > 0.052)
            m = submesh(m, ~(band & outside))
        m = calibrate(m, origin, spec, yaw)
        if mid == "jar":
            # the lid (top 15 % of the height) becomes its own node so its colour can show the current food
            h = m.extents[1]
            c = m.triangles_center
            lid = c[:, 1] > m.bounds[1][1] - 0.15 * h
            body = submesh(m, ~lid); cap = submesh(m, lid)
            export(body, "jar_body", material(body))
            export(cap, "jar_lid", material(cap, plain=[1.0, 1.0, 1.0, 1.0]))
            rows.append(("jar_body", body)); rows.append(("jar_lid", cap))
            continue
        mat = material(m, alpha=0.55) if mid == "jelly" else material(m)
        export(m, mid, mat)
        rows.append((mid, m))
    print("| model | triangles | bbox after calibration (m) | lo y | hi y |")
    print("|---|---|---|---|---|")
    table = {}
    for name, m in rows:
        lo, hi = m.bounds
        print(f"| {name} | {len(m.faces)} | {m.extents[0]:.3f} x {m.extents[1]:.3f} x {m.extents[2]:.3f} | {lo[1]:+.3f} | {hi[1]:+.3f} |")
        table[name] = {"tris": int(len(m.faces)), "lo": [round(float(v), 4) for v in lo], "hi": [round(float(v), 4) for v in hi]}
    with open(os.path.join(ROOT, "tools", "model_bounds.json"), "w") as f:
        json.dump(table, f, indent=1)


if __name__ == "__main__":
    sys.exit(main())
