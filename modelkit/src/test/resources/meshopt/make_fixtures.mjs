// Writes cases.json: meshoptimizer 1.3.0 encodings and their decodings, for MeshoptDecoderTest.
// See README.md. Usage: node make_fixtures.mjs <path to the meshoptimizer@1.3.0 package> <output dir>
import fs from 'fs';
import path from 'path';
import { pathToFileURL } from 'url';

const [packageDir, outDir] = process.argv.slice(2);
const { MeshoptEncoder } = await import(pathToFileURL(path.join(packageDir, 'meshopt_encoder.js')));
const { MeshoptDecoder } = await import(pathToFileURL(path.join(packageDir, 'meshopt_decoder.mjs')));
await MeshoptEncoder.ready;
await MeshoptDecoder.ready;

// Deterministic pseudo-random numbers (xorshift32).
let seed = 0x9e3779b9;
function random() {
	seed ^= seed << 13;
	seed ^= seed >>> 17;
	seed ^= seed << 5;
	return (seed >>> 0) / 4294967296;
}

const b64 = (bytes) => Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength).toString('base64');
const cases = [];

function vertexCase(name, raw, count, stride, { level = 2, version = 0, filter = 'NONE' } = {}) {
	const encoded = MeshoptEncoder.encodeVertexBufferLevel(raw, count, stride, level, version);
	const decoded = new Uint8Array(count * stride);
	MeshoptDecoder.decodeVertexBuffer(decoded, count, stride, encoded, filter);
	if (filter === 'NONE' && Buffer.compare(Buffer.from(decoded), Buffer.from(raw)) !== 0) throw new Error(`${name}: round trip`);
	cases.push({ name, mode: 'ATTRIBUTES', filter, count, stride, version, encoded: b64(encoded), decoded: b64(decoded) });
}

function indexCase(name, indices, mode, stride) {
	const source = stride === 2 ? new Uint16Array(indices) : new Uint32Array(indices);
	const bytes = new Uint8Array(source.buffer);
	const encoded = mode === 'TRIANGLES' ? MeshoptEncoder.encodeIndexBuffer(bytes, indices.length, stride) : MeshoptEncoder.encodeIndexSequence(bytes, indices.length, stride);
	const decoded = new Uint8Array(indices.length * stride);
	MeshoptDecoder.decodeGltfBuffer(decoded, indices.length, stride, encoded, mode);
	// The index codec keeps each triangle but may rotate its corners.
	const back = stride === 2 ? new Uint16Array(decoded.buffer) : new Uint32Array(decoded.buffer);
	const key = (t) => [0, 1, 2].map((r) => [t[r], t[(r + 1) % 3], t[(r + 2) % 3]].join()).sort()[0];
	for (let i = 0; i < indices.length; i += mode === 'TRIANGLES' ? 3 : indices.length) {
		const same = mode === 'TRIANGLES' ? key(source.subarray(i, i + 3)) === key(back.subarray(i, i + 3)) : Buffer.compare(Buffer.from(decoded), Buffer.from(bytes)) === 0;
		if (!same) throw new Error(`${name}: round trip`);
	}
	cases.push({ name, mode, filter: 'NONE', count: indices.length, stride, version: encoded[0] & 15, encoded: b64(encoded), decoded: b64(decoded) });
}

// Float positions along a random walk (several 256-vertex blocks), then 16-bit quantized ones
// padded to 8 bytes as gltfpack writes them.
{
	const count = 300;
	const floats = new Float32Array(count * 3);
	let p = [0, 0, 0];
	for (let i = 0; i < count; i++) {
		p = p.map((c) => c + (random() - 0.5) * 0.2);
		floats.set(p, i * 3);
	}
	const raw = new Uint8Array(floats.buffer);
	vertexCase('float positions v0', raw, count, 12, { version: 0 });
	vertexCase('float positions v1 level 3', raw, count, 12, { level: 3, version: 1 });
	vertexCase('float positions v1 level 0', raw, count, 12, { level: 0, version: 1 });

	const quantized = new Uint16Array(count * 4);
	for (let i = 0; i < count; i++) for (let k = 0; k < 3; k++) quantized[i * 4 + k] = Math.round((floats[i * 3 + k] + 5) * 1600) & 0xffff;
	const q = new Uint8Array(quantized.buffer);
	vertexCase('u16 positions v0', q, count, 8, { version: 0 });
	vertexCase('u16 positions v1 level 1', q, count, 8, { level: 1, version: 1 });
	vertexCase('u16 positions v1 level 2', q, count, 8, { level: 2, version: 1 });
	vertexCase('u16 positions v1 level 3', q, count, 8, { level: 3, version: 1 });
}

// Noise (verbatim groups), constant data (zero deltas), one element and a wide stride.
{
	const noise = new Uint8Array(270 * 16).map(() => Math.floor(random() * 256));
	vertexCase('noise v0', noise, 270, 16, { version: 0 });
	vertexCase('noise v1', noise, 270, 16, { level: 3, version: 1 });
	const constant = new Uint8Array(100 * 4).fill(7);
	vertexCase('constant v0', constant, 100, 4, { version: 0 });
	vertexCase('constant v1', constant, 100, 4, { version: 1 });
	const one = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]);
	vertexCase('one element v0', one, 1, 8, { version: 0 });
	vertexCase('one element v1', one, 1, 8, { version: 1 });
	// Floats whose bits change all over: the v1 encoder picks XOR channels, with rotations.
	const scattered = new Float32Array(300 * 4).map((_, i) => ((i >> 2) % 17) * 0.25 + (i & 3) * 0.5);
	vertexCase('xor channels v1', new Uint8Array(scattered.buffer), 300, 16, { level: 3, version: 1 });
	const randomFloats = new Float32Array(300 * 4).map(() => (random() - 0.5) * 1000);
	vertexCase('random floats v1', new Uint8Array(randomFloats.buffer), 300, 16, { level: 3, version: 1 });
	const wide = new Uint8Array(40 * 64).map((_, i) => (i * 37 + (i >> 6)) & 0xff);
	vertexCase('stride 64 v1', wide, 40, 64, { level: 3, version: 1 });
}

// Filters.
{
	const count = 130;
	const normals = new Float32Array(count * 4);
	for (let i = 0; i < count; i++) {
		const v = [random() * 2 - 1, random() * 2 - 1, random() * 2 - 1];
		const l = Math.hypot(...v) || 1;
		normals.set([v[0] / l, v[1] / l, v[2] / l, i % 2 ? 1 : -1], i * 4);
	}
	vertexCase('octahedral 8-bit', MeshoptEncoder.encodeFilterOct(normals, count, 4, 8), count, 4, { filter: 'OCTAHEDRAL' });
	vertexCase('octahedral 12-bit', MeshoptEncoder.encodeFilterOct(normals, count, 8, 12), count, 8, { filter: 'OCTAHEDRAL', version: 1 });

	const quats = new Float32Array(count * 4);
	for (let i = 0; i < count; i++) {
		const v = [random() * 2 - 1, random() * 2 - 1, random() * 2 - 1, random() * 2 - 1];
		const l = Math.hypot(...v) || 1;
		quats.set(v.map((c) => c / l), i * 4);
	}
	vertexCase('quaternion 12-bit', MeshoptEncoder.encodeFilterQuat(quats, count, 8, 12), count, 8, { filter: 'QUATERNION' });

	const values = new Float32Array(count * 3);
	for (let i = 0; i < values.length; i++) values[i] = (random() - 0.5) * Math.pow(10, Math.floor(random() * 6) - 2);
	vertexCase('exponential separate', MeshoptEncoder.encodeFilterExp(values, count, 12, 15, 'Separate'), count, 12, { filter: 'EXPONENTIAL' });
	vertexCase('exponential shared vector', MeshoptEncoder.encodeFilterExp(values, count, 12, 18, 'SharedVector'), count, 12, { filter: 'EXPONENTIAL', version: 1 });

	const colors = new Float32Array(count * 4).map(() => random());
	vertexCase('color 8-bit', MeshoptEncoder.encodeFilterColor(colors, count, 4, 8), count, 4, { filter: 'COLOR', version: 1 });
	vertexCase('color 12-bit', MeshoptEncoder.encodeFilterColor(colors, count, 8, 12), count, 8, { filter: 'COLOR', version: 1 });
}

// Indices: a grid in cache order (edge and vertex FIFO hits), random triangles (free indices),
// and the same again in 32-bit; then index sequences.
{
	const n = 14;
	const grid = [];
	for (let y = 0; y < n; y++)
		for (let x = 0; x < n; x++) {
			const i = y * (n + 1) + x;
			grid.push(i, i + 1, i + n + 1, i + 1, i + n + 2, i + n + 1);
		}
	const [remap] = MeshoptEncoder.reorderMesh(new Uint32Array(grid), true, false);
	const ordered = grid.map((i) => remap[i]);
	const scattered = [];
	for (let i = 0; i < 120; i++) {
		const a = Math.floor(random() * 70000);
		scattered.push(a, (a + 1 + Math.floor(random() * 3)) % 70000, Math.floor(random() * 70000));
	}
	indexCase('grid triangles 16-bit', ordered, 'TRIANGLES', 2);
	indexCase('grid triangles 32-bit', ordered, 'TRIANGLES', 4);
	indexCase('raw grid triangles 16-bit', grid, 'TRIANGLES', 2);
	indexCase('scattered triangles 32-bit', scattered, 'TRIANGLES', 4);
	indexCase('mixed triangles 32-bit', [...ordered, ...scattered, ...ordered], 'TRIANGLES', 4);
	const lines = [];
	for (let i = 0; i < 150; i++) lines.push(i, i + 1, Math.floor(random() * 100000));
	indexCase('sequence 32-bit', lines, 'INDICES', 4);
	indexCase('sequence 16-bit', lines.map((i) => i & 0xffff), 'INDICES', 2);
}

fs.writeFileSync(path.join(outDir, 'cases.json'), JSON.stringify({ generator: 'meshoptimizer 1.3.0 (npm)', cases }, null, '\t') + '\n');
console.log(`${cases.length} cases`);
