"""Add the inner notification LED and the capture button to glasses.glb, in place.

    python tools/glasses_marks.py app/src/main/assets/glasses.glb

Run after tools/glasses_glb.py. "inner_led" is a small disc on the back of the front by the right hinge, facing the
wearer; "button" is a strip on top of the right temple, a child of temple_r so it folds with it. Both are dark with
an emissive of strength 0 that the app turns up.
"""
import json
import struct
import sys

import numpy as np

PATH = sys.argv[1]
raw = open(PATH, "rb").read()
js_len = struct.unpack_from("<I", raw, 12)[0]
gltf = json.loads(raw[20:20 + js_len])
bin_data = bytearray(raw[20 + js_len + 8:])


def accessor_array(i):
    a = gltf["accessors"][i]
    v = gltf["bufferViews"][a["bufferView"]]
    comp = {"VEC3": 3, "VEC2": 2, "SCALAR": 1, "VEC4": 4}[a["type"]]
    dt = {5126: np.float32, 5125: np.uint32, 5123: np.uint16}[a["componentType"]]
    off = v.get("byteOffset", 0) + a.get("byteOffset", 0)
    return np.frombuffer(bytes(bin_data), dtype=dt, count=a["count"] * comp, offset=off).reshape(a["count"], comp)


def node_index(name):
    return next(i for i, n in enumerate(gltf["nodes"]) if n.get("name") == name)


def positions(name):
    mesh = gltf["meshes"][gltf["nodes"][node_index(name)]["mesh"]]
    return np.vstack([accessor_array(p["attributes"]["POSITION"]) for p in mesh["primitives"]])


if any(n.get("name") in ("button", "inner_led") for n in gltf["nodes"]):
    sys.exit("the model already has its marks")


def add_view(data, target):
    while len(bin_data) % 4:
        bin_data.append(0)
    view = {"buffer": 0, "byteOffset": len(bin_data), "byteLength": len(data)}
    if target:
        view["target"] = target
    bin_data.extend(data)
    gltf["bufferViews"].append(view)
    return len(gltf["bufferViews"]) - 1


def add_accessor(arr, ctype, kind, target, bounds=False):
    a = {"bufferView": add_view(arr.tobytes(), target), "componentType": ctype, "count": len(arr), "type": kind}
    if bounds:
        a["min"] = arr.min(axis=0).tolist()
        a["max"] = arr.max(axis=0).tolist()
    gltf["accessors"].append(a)
    return len(gltf["accessors"]) - 1


def add_material(name):
    gltf["materials"].append({
        "name": name,
        "pbrMetallicRoughness": {"baseColorFactor": [0.02, 0.02, 0.025, 1], "metallicFactor": 0.0, "roughnessFactor": 0.25},
        "emissiveFactor": [1.0, 1.0, 1.0],
        "extensions": {"KHR_materials_emissive_strength": {"emissiveStrength": 0.0}},
    })
    return len(gltf["materials"]) - 1


def add_node(name, verts, tris, normal, material, parent=None):
    v = np.asarray(verts, np.float32)
    n = np.tile(np.asarray(normal, np.float32), (len(v), 1))
    idx = np.asarray(tris, np.uint16).ravel()
    prim = {"attributes": {"POSITION": add_accessor(v, 5126, "VEC3", 34962, True), "NORMAL": add_accessor(n, 5126, "VEC3", 34962)},
            "indices": add_accessor(idx, 5123, "SCALAR", 34963), "material": material}
    gltf["meshes"].append({"name": name, "primitives": [prim]})
    gltf["nodes"].append({"name": name, "mesh": len(gltf["meshes"]) - 1})
    i = len(gltf["nodes"]) - 1
    if parent is None:
        gltf["scenes"][0]["nodes"].append(i)
    else:
        gltf["nodes"][parent].setdefault("children", []).append(i)


def fan(centre, points):
    verts = [centre] + points
    return verts, [(0, 1 + k, 1 + (k + 1) % len(points)) for k in range(len(points))]


# The LED: 1.1 mm across, 0.15 mm behind the back face of the front, by the right hinge's top corner.
front = positions("front")
led_x, led_y = -0.0625, 0.0150
near = front[(np.abs(front[:, 0] - led_x) < 0.0015) & (np.abs(front[:, 1] - led_y) < 0.0015)]
led_z = float(near[:, 2].min()) - 0.00015
ring = [(led_x + 0.00055 * np.cos(a), led_y + 0.00055 * np.sin(a), led_z) for a in np.linspace(0, 2 * np.pi, 16, endpoint=False)]
# Wound to face -Z, towards the wearer.
verts, tris = fan((led_x, led_y, led_z), ring[::-1])
add_node("inner_led", verts, tris, (0, 0, -1), add_material("inner_led"))

# The button: a 9 x 2.6 mm stadium along the temple, 0.35 mm above its top, 13 mm behind the hinge, in temple_r's space.
temple = positions("temple_r")
bx, bz = -0.0058, -0.0135
top = temple[(np.abs(temple[:, 0] - bx) < 0.0012) & (np.abs(temple[:, 2] - bz) < 0.0045)]
by = float(top[:, 1].max()) + 0.00035
half_len, r = 0.0045 - 0.0013, 0.0013
outline = []
for a in np.linspace(0, np.pi, 9):
    outline.append((bx + r * np.cos(a), by, bz - half_len - r * np.sin(a)))
for a in np.linspace(np.pi, 2 * np.pi, 9):
    outline.append((bx + r * np.cos(a), by, bz + half_len - r * np.sin(a)))
verts, tris = fan((bx, by, bz), outline)
# Facing up: wind so the fan's normal is +Y.
p0, p1, p2 = (np.array(verts[i]) for i in tris[0])
if np.cross(p1 - p0, p2 - p0)[1] < 0:
    tris = [(a, c, b) for a, b, c in tris]
add_node("button", verts, tris, (0, 1, 0), add_material("button"), parent=node_index("temple_r"))

used = gltf.setdefault("extensionsUsed", [])
if "KHR_materials_emissive_strength" not in used:
    used.append("KHR_materials_emissive_strength")
gltf["buffers"] = [{"byteLength": len(bin_data)}]
js = json.dumps(gltf, separators=(",", ":")).encode()
js += b" " * (-len(js) % 4)
while len(bin_data) % 4:
    bin_data.append(0)
with open(PATH, "wb") as f:
    f.write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(bin_data)))
    f.write(struct.pack("<II", len(js), 0x4E4F534A) + js)
    f.write(struct.pack("<II", len(bin_data), 0x004E4942) + bytes(bin_data))
print("marks added: led at", (led_x, led_y, round(led_z, 4)), "button at", (bx, round(by, 4), bz))
