# meshopt decoder fixtures (`MeshoptDecoderTest`)

`cases.json` holds buffers encoded by meshoptimizer 1.3.0 (the npm package, the same library as
gltfpack 1.3) and what its own decoder makes of them, base64: vertex buffers in codec versions 0
and 1 at several levels and strides, with each filter (octahedral, quaternion, exponential,
colour), index buffers (16- and 32-bit) and index sequences. Each case has `mode`, `filter`,
`count`, `stride` (bytes per element) and `version` (the codec version in the header).

Regenerate (Node 18+):

```bash
dir=$(mktemp -d) && (cd "$dir" && npm pack meshoptimizer@1.3.0 && tar xzf meshoptimizer-1.3.0.tgz)
node make_fixtures.mjs "$dir/package" .
```

The data comes from a fixed-seed generator, so the output only changes with the library.
