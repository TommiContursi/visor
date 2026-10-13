"""Build app/src/main/assets/glasses.glb from the Marmoset Viewer export of the Wayfarer (mview_extract.py output).

    python tools/glasses_glb.py <mw_extracted> app/src/main/assets/glasses.glb


One pair, in metres, front facing +Z, origin at the centre of the open pair.
Nodes: front (frame, camera glass), led, lens, temple_l, temple_r. Animations "fold_r" and "fold_l": 0 open, 1 closed. Material variants: matte|shiny x clear|graphite|green.
"""
import io
import json
import os
import struct
import sys

import numpy as np
from PIL import Image

SRC, OUT = sys.argv[1], sys.argv[2]
SCENE = json.load(open(f"{SRC}/raw/scene.json"))
# Marmoset bakes positions into scene space; each mesh's transform is its pose there, so two copies of a part line up
# through the product of their transforms.
M = {m["name"]: np.array(m["transform"]).reshape(4, 4).T for m in SCENE["meshes"]}


def apply(m, p):
    return p @ m[:3, :3].T + m[:3, 3]


def load_obj(name):
    v, vt, groups, cur = [], [], {}, None
    for line in open(f"{SRC}/meshes/{name}.obj"):
        if line.startswith("v "):
            v.append([float(x) for x in line.split()[1:4]])
        elif line.startswith("vt "):
            vt.append([float(x) for x in line.split()[1:3]])
        elif line.startswith("usemtl "):
            cur = line.split()[1]
            groups.setdefault(cur, [])
        elif line.startswith("f "):
            groups[cur].append([int(p.split("/")[0]) - 1 for p in line.split()[1:4]])
    return np.array(v), np.array(vt), {k: np.array(t) for k, t in groups.items()}

TEX = f"{SRC}/textures"
SCALE = 0.147 / 6.71  # the front is 147 mm wide
CANON = "frame_m_shiny_black_closed"


def to_canon(name_of_front):
    return M[CANON] @ np.linalg.inv(M[name_of_front])


F_B = to_canon("frame_shiny_black")


def weld_split(v, uv, tris):
    """Keep only the vertices the triangles use, reindexed."""
    used, inv = np.unique(tris.ravel(), return_inverse=True)
    return v[used], uv[used], inv.reshape(-1, 3)


def normals(v, tris):
    _, w = np.unique(np.round(v, 6), axis=0, return_inverse=True)
    w = w.ravel()
    acc = np.zeros((w.max() + 1, 3))
    a, b, c = v[tris[:, 0]], v[tris[:, 1]], v[tris[:, 2]]
    fn = np.cross(b - a, c - a)
    for k in range(3):
        np.add.at(acc, w[tris[:, k]], fn)
    n = acc[w]
    return n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)


def rigid_decompose(h):
    """Axis, angle, a point on the axis and the slide along it, for a rigid 4x4."""
    r, t = h[:3, :3], h[:3, 3]
    angle = np.arccos(np.clip((np.trace(r) - 1) / 2, -1, 1))
    axis = np.array([r[2, 1] - r[1, 2], r[0, 2] - r[2, 0], r[1, 0] - r[0, 1]])
    axis /= np.linalg.norm(axis)
    slide = t @ axis
    p, *_ = np.linalg.lstsq(np.eye(3) - r, t - slide * axis, rcond=None)
    return axis, angle, p, slide


def quat(axis, angle):
    s = np.sin(angle / 2)
    return [*(axis * s), np.cos(angle / 2)]


# Canonical geometry: the shiny pair (B), moved onto the closed pair's frame.
front_v, front_uv, front_g = load_obj("frame_shiny_black")
front_v = apply(F_B, front_v)
tl_v, tl_uv, tl_g = load_obj("frame_l_shiny_black")
tl_v = apply(F_B, tl_v)
tr_v, tr_uv, tr_g = load_obj("frame_r_shiny_black")
tr_v = apply(F_B, tr_v)
lens_v, lens_uv, lens_g = load_obj("lens_green")
lens_v = apply(F_B, lens_v)

# Hinges: open temple (canonical) -> closed temple.
hinge = {}
for side in ("l", "r"):
    h = M[f"frame_{side}_shiny_black_closed"] @ np.linalg.inv(M[f"frame_{side}_shiny_black"]) @ np.linalg.inv(F_B)
    hinge[side] = rigid_decompose(h)
    a, ang, p, s = hinge[side]
    print(side, "axis", a.round(3), "angle", np.degrees(ang).round(1), "pivot", p.round(3), "slide", round(s, 4))

# World placement: centre of the open pair at the origin, metres.
allv = np.vstack([front_v, tl_v, tr_v, lens_v])
centre = (allv.min(0) + allv.max(0)) / 2


def place(v):
    return (v - centre) * SCALE


print("size m", (place(allv).max(0) - place(allv).min(0)).round(4))

# Glass on the front: two components; the camera sits on the wearer's left (+X), the capture LED on the right.
glass = front_g.pop("Glass_Colored")
gc = front_v[glass].mean(1)
camera_tris, led_tris = glass[gc[:, 0] > 0], glass[gc[:, 0] < 0]

# ---------------------------------------------------------------- textures


def jpeg(img, q=90):
    b = io.BytesIO()
    img.save(b, "JPEG", quality=q)
    return b.getvalue()


def png(img):
    b = io.BytesIO()
    img.save(b, "PNG", optimize=True)
    return b.getvalue()


def load(name, size=1024, mode="RGB"):
    return Image.open(f"{TEX}/{name}.jpg").convert(mode).resize((size, size), Image.LANCZOS)


ao_full = Image.open(f"{TEX}/mat6_s.jpg").convert("L")
# Marmoset keeps the AO in a sub-rectangle of the extras texture: uv * 0.96875 + 0.015625.
w = ao_full.size[0]
ao = ao_full.transform((w, w), Image.AFFINE, (0.96875, 0, 0.015625 * w, 0, 0.96875, 0.015625 * w), Image.BILINEAR)
ao = ao.resize((1024, 1024), Image.LANCZOS)


def orm(gloss_name):
    g = np.asarray(load(gloss_name, mode="L")).astype(np.float32)
    rough = 255 - g
    out = np.stack([np.asarray(ao).astype(np.float32), rough, np.zeros_like(rough)], -1)
    return Image.fromarray(out.clip(0, 255).astype(np.uint8))


images = {
    "frame_c": jpeg(load("mat7_c")),
    "frame_n": png(load("mat6_n")),
    "orm_shiny": jpeg(orm("mat7_g")),
    "orm_matte": jpeg(orm("mat6_g")),
    "lens_graphite": jpeg(load("mat1_c")),
    "lens_green": jpeg(load("mat5_c")),
}

# ---------------------------------------------------------------- glTF assembly

bin_data = bytearray()
gltf = {
    "asset": {"version": "2.0", "generator": "visor build.py"},
    "scene": 0, "scenes": [{"nodes": []}], "nodes": [], "meshes": [], "materials": [], "textures": [],
    "images": [], "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}],
    "accessors": [], "bufferViews": [], "buffers": [],
    "extensionsUsed": ["KHR_materials_variants", "KHR_materials_emissive_strength"],
    "extensions": {"KHR_materials_variants": {"variants": []}},
}


def view(data, target=None):
    while len(bin_data) % 4:
        bin_data.append(0)
    off = len(bin_data)
    bin_data.extend(data)
    bv = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
    if target:
        bv["target"] = target
    gltf["bufferViews"].append(bv)
    return len(gltf["bufferViews"]) - 1


def accessor(arr, ctype, typ, target, minmax=False):
    a = {"bufferView": view(arr.tobytes(), target), "componentType": ctype, "count": len(arr), "type": typ}
    if minmax:
        a["min"] = np.atleast_1d(arr.min(0)).tolist()
        a["max"] = np.atleast_1d(arr.max(0)).tolist()
    gltf["accessors"].append(a)
    return len(gltf["accessors"]) - 1


tex_index = {}
for name, data in images.items():
    gltf["images"].append({"bufferView": view(data), "mimeType": "image/png" if data[:4] == b"\x89PNG" else "image/jpeg", "name": name})
    gltf["textures"].append({"sampler": 0, "source": len(gltf["images"]) - 1})
    tex_index[name] = len(gltf["textures"]) - 1


def material(name, **m):
    m["name"] = name
    gltf["materials"].append(m)
    return len(gltf["materials"]) - 1


def frame_mat(name, orm_name):
    return material(name, pbrMetallicRoughness={
        "baseColorTexture": {"index": tex_index["frame_c"]},
        "metallicRoughnessTexture": {"index": tex_index[orm_name]},
        "metallicFactor": 0.0, "roughnessFactor": 1.0},
        normalTexture={"index": tex_index["frame_n"]},
        occlusionTexture={"index": tex_index[orm_name]})


MAT = {
    "shiny": frame_mat("frame_shiny", "orm_shiny"),
    "matte": frame_mat("frame_matte", "orm_matte"),
    "camera": material("camera_glass", pbrMetallicRoughness={"baseColorFactor": [0.01, 0.01, 0.012, 1], "metallicFactor": 0.0, "roughnessFactor": 0.05}),
    "led": material("led", pbrMetallicRoughness={"baseColorFactor": [0.03, 0.03, 0.03, 1], "metallicFactor": 0.0, "roughnessFactor": 0.1},
                    emissiveFactor=[1.0, 1.0, 1.0], extensions={"KHR_materials_emissive_strength": {"emissiveStrength": 0.0}}),
    "clear": material("lens_clear", alphaMode="BLEND", doubleSided=True, pbrMetallicRoughness={"baseColorFactor": [0.92, 0.93, 0.95, 0.05], "metallicFactor": 0.0, "roughnessFactor": 0.02}),
    "graphite": material("lens_graphite", alphaMode="BLEND", doubleSided=True, pbrMetallicRoughness={"baseColorTexture": {"index": tex_index["lens_graphite"]}, "baseColorFactor": [1, 1, 1, 0.66], "metallicFactor": 0.0, "roughnessFactor": 0.03}),
    "green": material("lens_green", alphaMode="BLEND", doubleSided=True, pbrMetallicRoughness={"baseColorTexture": {"index": tex_index["lens_green"]}, "baseColorFactor": [1, 1, 1, 0.62], "metallicFactor": 0.0, "roughnessFactor": 0.03}),
}

FRAMES, LENSES = ["matte", "shiny"], ["clear", "graphite", "green"]
VARIANTS = [f"{f}_{l}" for f in FRAMES for l in LENSES]
gltf["extensions"]["KHR_materials_variants"]["variants"] = [{"name": n} for n in VARIANTS]


def frame_mappings():
    return {"KHR_materials_variants": {"mappings": [
        {"material": MAT[f], "variants": [i for i, n in enumerate(VARIANTS) if n.startswith(f + "_")]} for f in FRAMES]}}


def lens_mappings():
    return {"KHR_materials_variants": {"mappings": [
        {"material": MAT[l], "variants": [i for i, n in enumerate(VARIANTS) if n.endswith("_" + l)]} for l in LENSES]}}


def primitive(v, uv, tris, mat, offset, ext=None):
    v, uv, tris = weld_split(v, uv, tris)
    p = (place(v) - offset).astype(np.float32)
    n = normals(p.astype(np.float64), tris).astype(np.float32)
    t = np.column_stack([uv[:, 0], 1 - uv[:, 1]]).astype(np.float32)
    idx = tris.astype(np.uint16 if len(p) < 65536 else np.uint32).ravel()
    prim = {"attributes": {"POSITION": accessor(p, 5126, "VEC3", 34962, True), "NORMAL": accessor(n, 5126, "VEC3", 34962),
                           "TEXCOORD_0": accessor(t, 5126, "VEC2", 34962)},
            "indices": accessor(idx, 5123 if idx.dtype == np.uint16 else 5125, "SCALAR", 34963), "material": mat}
    if ext:
        prim["extensions"] = ext
    return prim


def node(name, prims, translation=(0, 0, 0)):
    gltf["meshes"].append({"name": name, "primitives": prims})
    gltf["nodes"].append({"name": name, "mesh": len(gltf["meshes"]) - 1, "translation": [float(x) for x in translation]})
    i = len(gltf["nodes"]) - 1
    gltf["scenes"][0]["nodes"].append(i)
    return i


zero = np.zeros(3)
frame_tris = np.vstack(list(front_g.values()))
n_front = node("front", [
    primitive(front_v, front_uv, frame_tris, MAT["shiny"], zero, frame_mappings()),
    primitive(front_v, front_uv, camera_tris, MAT["camera"], zero),
])
n_led = node("led", [primitive(front_v, front_uv, led_tris, MAT["led"], zero)])
# Both layers of the lens take the variant; each is about 0.6 opaque, so the pair passes about 15 per cent like a real G15.
n_lens = node("lens", [primitive(lens_v, lens_uv, np.vstack(list(lens_g.values())), MAT["green"], zero, lens_mappings())])

temple_nodes = {}
for side, (v, uv, g) in {"l": (tl_v, tl_uv, tl_g), "r": (tr_v, tr_uv, tr_g)}.items():
    axis, ang, pivot, slide = hinge[side]
    piv = place(pivot[None])[0]
    temple_nodes[side] = node(f"temple_{side}", [primitive(v, uv, np.vstack(list(g.values())), MAT["shiny"], piv, frame_mappings())], piv)

# One clip per temple, "fold_r" and "fold_l", each 0 open to 1 closed; the app eases and staggers them.
gltf["animations"] = []
for side in ("r", "l"):
    axis, ang, pivot, slide = hinge[side]
    piv = place(pivot[None])[0]
    times = accessor(np.array([0.0, 1.0], np.float32), 5126, "SCALAR", None, True)
    rots = accessor(np.array([[0, 0, 0, 1], quat(axis, ang)], np.float32), 5126, "VEC4", None)
    trans = accessor(np.array([piv, piv + axis * slide * SCALE], np.float32), 5126, "VEC3", None)
    gltf["animations"].append({
        "name": f"fold_{side}",
        "samplers": [{"input": times, "output": rots, "interpolation": "LINEAR"}, {"input": times, "output": trans, "interpolation": "LINEAR"}],
        "channels": [{"sampler": 0, "target": {"node": temple_nodes[side], "path": "rotation"}},
                     {"sampler": 1, "target": {"node": temple_nodes[side], "path": "translation"}}],
    })

for bv in gltf["bufferViews"]:
    if bv.get("target") is None:
        bv.pop("target", None)
gltf["buffers"] = [{"byteLength": len(bin_data)}]
js = json.dumps(gltf, separators=(",", ":")).encode()
js += b" " * (-len(js) % 4)
while len(bin_data) % 4:
    bin_data.append(0)
with open(OUT, "wb") as f:
    f.write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(bin_data)))
    f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
    f.write(struct.pack("<II", len(bin_data), 0x004E4942) + bytes(bin_data))
print("wrote", OUT, os.path.getsize(OUT), "bytes; variants", VARIANTS)
