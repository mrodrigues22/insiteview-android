#!/usr/bin/env python3
"""Builds `in.glb` (plain floats) and `expected.json` for the chunk fixture; see README.md.

Stdlib only. The hierarchy follows the converter's chunks (master PLAN §9): chunk root → storey
group → element nodes named `e` + 32 hex, with element transforms that aren't identity.

- `PIPE`: a 50 mm pipe running 3 m up at 45° (a convex-hull element), its mesh on the element node.
- `BOX`: a 0.6 × 0.4 × 0.8 box, its mesh on the element node (turned and scaled).
- `NESTED`: an element whose meshes hang on unnamed child nodes (a bar turned 90° and an instance
  of the box's mesh), with two primitives on the bar.

`expected.json` holds each element's vertex positions in the element node's own frame.
"""

import json
import math
import struct
import sys
from pathlib import Path

PIPE = "e" + "0123456789abcdef0123456789abcdef"
BOX = "e" + "fedcba9876543210fedcba9876543210"
NESTED = "e" + "00112233445566778899aabbccddeeff"


def quat_axis(axis, degrees):
    half = math.radians(degrees) / 2
    s = math.sin(half)
    return [axis[0] * s, axis[1] * s, axis[2] * s, math.cos(half)]


def matrix(t=(0, 0, 0), r=(0, 0, 0, 1), s=(1, 1, 1)):
    """Column-major 4×4 (glTF's layout) from TRS."""
    x, y, z, w = r
    m = [
        1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0,
        2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0,
        2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0,
        t[0], t[1], t[2], 1,
    ]
    for col in range(3):
        for row in range(3):
            m[col * 4 + row] *= s[col]
    return m


def apply(m, p):
    return [m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12],
            m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13],
            m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14]]


def cylinder(start, end, radius, segments=24, rings=20):
    """A capped tube from `start` to `end`: positions, normals, triangle indices."""
    axis = [e - s for s, e in zip(start, end)]
    length = math.sqrt(sum(a * a for a in axis))
    a = [c / length for c in axis]
    helper = [0, 0, 1] if abs(a[2]) < 0.9 else [1, 0, 0]
    u = [a[1] * helper[2] - a[2] * helper[1], a[2] * helper[0] - a[0] * helper[2], a[0] * helper[1] - a[1] * helper[0]]
    ul = math.sqrt(sum(c * c for c in u))
    u = [c / ul for c in u]
    v = [a[1] * u[2] - a[2] * u[1], a[2] * u[0] - a[0] * u[2], a[0] * u[1] - a[1] * u[0]]
    positions, normals, indices = [], [], []
    for ring in range(rings + 1):
        f = ring / rings
        centre = [s + c * f for s, c in zip(start, axis)]
        for k in range(segments):
            angle = 2 * math.pi * k / segments
            n = [math.cos(angle) * uu + math.sin(angle) * vv for uu, vv in zip(u, v)]
            positions.append([c + radius * nn for c, nn in zip(centre, n)])
            normals.append(n)
    for ring in range(rings):
        for k in range(segments):
            i0 = ring * segments + k
            i1 = ring * segments + (k + 1) % segments
            i2 = i0 + segments
            i3 = i1 + segments
            indices += [i0, i1, i2, i1, i3, i2]
    for cap, sign in ((0, -1), (rings, 1)):
        centre = len(positions)
        positions.append([s + c * cap / rings for s, c in zip(start, axis)])
        normals.append([sign * c for c in a])
        for k in range(segments):
            i0 = cap * segments + k
            i1 = cap * segments + (k + 1) % segments
            indices += [centre, i1, i0] if sign < 0 else [centre, i0, i1]
    return positions, normals, indices


def box(centre, size):
    """A box with flat faces (24 vertices)."""
    positions, normals, indices = [], [], []
    for axis in range(3):
        for sign in (-1, 1):
            n = [0, 0, 0]
            n[axis] = sign
            o1, o2 = (axis + 1) % 3, (axis + 2) % 3
            base = len(positions)
            for a, b in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                p = [0, 0, 0]
                p[axis] = sign * size[axis] / 2
                p[o1] = a * size[o1] / 2
                p[o2] = b * size[o2] / 2
                positions.append([c + q for c, q in zip(centre, p)])
                normals.append(list(n))
            quad = [0, 1, 2, 0, 2, 3] if sign > 0 else [0, 2, 1, 0, 3, 2]
            indices += [base + q for q in quad]
    return positions, normals, indices


class Builder:
    def __init__(self):
        self.blob = bytearray()
        self.views, self.accessors, self.meshes = [], [], []

    def _view(self, data, target):
        while len(self.blob) % 4:
            self.blob.append(0)
        self.views.append({"buffer": 0, "byteOffset": len(self.blob), "byteLength": len(data), "target": target})
        self.blob += data
        return len(self.views) - 1

    def _floats(self, rows):
        data = b"".join(struct.pack("<3f", *r) for r in rows)
        view = self._view(data, 34962)
        lo = [min(r[i] for r in rows) for i in range(3)]
        hi = [max(r[i] for r in rows) for i in range(3)]
        # Bounds of the float32 values, as glTF requires.
        lo = [struct.unpack("<f", struct.pack("<f", c))[0] for c in lo]
        hi = [struct.unpack("<f", struct.pack("<f", c))[0] for c in hi]
        self.accessors.append({"bufferView": view, "componentType": 5126, "count": len(rows), "type": "VEC3", "min": lo, "max": hi})
        return len(self.accessors) - 1

    def primitive(self, geometry, material):
        positions, normals, indices = geometry
        data = b"".join(struct.pack("<I", i) for i in indices)
        view = self._view(data, 34963)
        self.accessors.append({"bufferView": view, "componentType": 5125, "count": len(indices), "type": "SCALAR"})
        index = len(self.accessors) - 1
        return {"attributes": {"POSITION": self._floats(positions), "NORMAL": self._floats(normals)}, "indices": index, "material": material}

    def mesh(self, name, primitives):
        self.meshes.append({"name": name, "primitives": primitives})
        return len(self.meshes) - 1


def main(out_dir: Path):
    b = Builder()
    pipe_geometry = cylinder([0, 0, 0], [3, 3, 0], 0.025)
    box_geometry = box([0, 0.2, 0], [0.6, 0.4, 0.8])
    bar_geometry = box([0.5, 0, 0], [1.0, 0.1, 0.1])
    stub_geometry = box([1.05, 0, 0], [0.1, 0.2, 0.2])
    pipe_mesh = b.mesh("pipe", [b.primitive(pipe_geometry, 0)])
    box_mesh = b.mesh("box", [b.primitive(box_geometry, 1)])
    bar_mesh = b.mesh("bar", [b.primitive(bar_geometry, 0), b.primitive(stub_geometry, 1)])

    pipe_trs = {"translation": [1, 0.5, 2], "rotation": quat_axis([0, 1, 0], 20)}
    box_trs = {"translation": [-2, 0, 1], "rotation": quat_axis([1, 0, 0], 10), "scale": [1.5, 1, 0.5]}
    bar_trs = {"translation": [0.3, 0, 0], "rotation": quat_axis([0, 0, 1], 90)}
    copy_trs = {"translation": [0, 0, 1.5]}
    nodes = [
        {"name": "chunk_plumbing", "children": [1], "translation": [10, 0, -5]},
        {"name": "s0", "children": [2, 3, 4], "rotation": quat_axis([0, 1, 0], 30)},
        {"name": PIPE, "mesh": pipe_mesh, **pipe_trs},
        {"name": BOX, "mesh": box_mesh, **box_trs},
        {"name": NESTED, "children": [5, 6], "translation": [0, 1, -3]},
        {"mesh": bar_mesh, **bar_trs},
        {"mesh": box_mesh, **copy_trs},
    ]
    gltf = {
        "asset": {"version": "2.0", "generator": "make_chunk.py"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": nodes,
        "meshes": b.meshes,
        "materials": [
            {"name": "Pipe", "pbrMetallicRoughness": {"baseColorFactor": [0.2, 0.4, 0.8, 1], "metallicFactor": 0, "roughnessFactor": 0.5}},
            {"name": "Box", "pbrMetallicRoughness": {"baseColorFactor": [0.8, 0.8, 0.8, 1], "metallicFactor": 0, "roughnessFactor": 0.9}},
        ],
        "accessors": b.accessors,
        "bufferViews": b.views,
        "buffers": [{"byteLength": len(b.blob)}],
    }

    def positions(geometry, m=None):
        return [apply(m, p) if m else list(p) for p in geometry[0]]

    def trs(d):
        return matrix(d.get("translation", (0, 0, 0)), d.get("rotation", (0, 0, 0, 1)), d.get("scale", (1, 1, 1)))

    expected = {
        PIPE: positions(pipe_geometry),
        BOX: positions(box_geometry),
        NESTED: positions(bar_geometry, trs(bar_trs)) + positions(stub_geometry, trs(bar_trs)) + positions(box_geometry, trs(copy_trs)),
    }

    content = json.dumps(gltf, separators=(",", ":")).encode()
    content += b" " * (-len(content) % 4)
    blob = bytes(b.blob) + b"\0" * (-len(b.blob) % 4)
    chunks = struct.pack("<I4s", len(content), b"JSON") + content + struct.pack("<I4s", len(blob), b"BIN\0") + blob
    (out_dir / "in.glb").write_bytes(struct.pack("<4sII", b"glTF", 2, 12 + len(chunks)) + chunks)
    (out_dir / "expected.json").write_text(json.dumps({k: [[round(c, 6) for c in p] for p in v] for k, v in expected.items()}) + "\n")


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else Path.cwd())
