# Chunk GLB fixture (`ElementHullsTest`)

A small chunk shaped like the converter's (master PLAN §9: chunk root → storey group → element
nodes `e` + 32 hex), packed with the converter's gltfpack flags (`GLTFPACK_FLAGS` in
`insiteview-api/converter/insiteview_converter/glb.py`):

- `PIPE` `e0123…cdef`: a 50 mm pipe 3 m up at 45° (a convex-hull element), mesh on the element node.
- `BOX` `efedc…3210`: a box, mesh on the element node, element turned and scaled.
- `NESTED` `e0011…eeff`: meshes on unnamed child nodes (two primitives on one, an instance of the
  box mesh on the other).

Files:

- `make_chunk.py` writes `in.glb` (float positions, not committed) and `expected.json` (each
  element's positions in the element node's frame).
- `chunk.glb`: `gltfpack -cc -kn -km -ke` (production): `EXT_meshopt_compression`, vertex codec v0
  (`0xa0`), index codec v1 (`0xe1`), `KHR_mesh_quantization` (uint16 positions, the dequantizing
  scale and offset on unnamed child nodes of the elements), octahedral normals.
- `chunk-khr.glb`: the same with `-ce khr`: `KHR_meshopt_compression`, vertex codec v1 (`0xa1`).

Regenerate (Python 3 and Node 18+; the npm package `gltfpack@1.3.0` is the version the converter uses):

```bash
cd modelkit/src/test/resources/glb
python3 make_chunk.py .
npx --yes gltfpack@1.3.0 -i in.glb -o chunk.glb -cc -kn -km -ke
npx --yes gltfpack@1.3.0 -i in.glb -o chunk-khr.glb -cc -kn -km -ke -ce khr
rm in.glb
```
