package com.getinsiteview.modelkit.scene

import com.getinsiteview.modelkit.NodeNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** A GLB that can't be read for geometry. */
class GlbException(message: String) : IllegalArgumentException(message)

/**
 * Just enough of a GLB chunk's geometry to build picking hulls: the JSON and BIN chunks, buffer views
 * (meshopt-compressed ones decoded with [MeshoptDecoder]: `EXT_meshopt_compression` or
 * `KHR_meshopt_compression`), accessors (any component type, normalized or not, strided; quantized
 * as `KHR_mesh_quantization` allows), meshes' `POSITION` and indices, and the node tree (TRS or
 * matrix). Sparse accessors, external buffers and `EXT_mesh_gpu_instancing` (gltfpack writes them
 * only with flags the converter doesn't pass) are skipped.
 *
 * Element nodes are named `e` + 32 hex ([NodeNames.isElement]); as `:scene`'s `BuildingScene`
 * collects them, an element nested in another one belongs to neither.
 */
class GlbGeometry private constructor(private val root: JsonObject, private val bin: ByteArray?, private val binOffset: Int, private val binLength: Int) {
    private val nodes = root.array("nodes")
    private val meshes = root.array("meshes")
    private val accessors = root.array("accessors")
    private val bufferViews = root.array("bufferViews")
    private val buffers = root.array("buffers")
    private val views = HashMap<Int, View?>()

    /** Bytes of a buffer view: a slice of BIN, or a decoded copy. */
    private class View(val data: ByteArray, val offset: Int, val length: Int, val stride: Int?)

    /**
     * Each requested element's vertex positions in the element node's own frame (x y z for each):
     * the meshes on the element node and on its descendants, through the transforms between them
     * (gltfpack's dequantizing child nodes included), so they line up with the frame
     * `ScenePicking.Target` uses. Only vertices the primitives' indices use are kept (all of them
     * without indices). Elements without geometry are left out.
     */
    fun elementPositions(elementIds: Set<String>): Map<String, DoubleArray> {
        if (elementIds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, DoubleArray>()
        val sceneRoots = sceneRoots()
        val visited = HashSet<Int>()
        fun walk(node: Int) {
            if (!visited.add(node)) return // a cycle, or a node shared by two parents
            val json = nodes.getOrNull(node) as? JsonObject ?: return
            val name = json.string("name")
            if (name != null && NodeNames.isElement(name)) {
                if (name in elementIds && name !in result) {
                    val out = DoubleList()
                    collect(node, Matrix4.identity, out, HashSet(), top = true)
                    if (out.size > 0) result[name] = out.toArray()
                }
                return // elements nested in this one belong to no element
            }
            for (child in json.ints("children")) walk(child)
        }
        for (node in sceneRoots) walk(node)
        return result
    }

    private fun sceneRoots(): List<Int> {
        val scenes = root.array("scenes")
        val sceneIndex = root.int("scene") ?: 0
        val scene = scenes.getOrNull(sceneIndex) as? JsonObject
        if (scene != null) return scene.ints("nodes")
        // No scene: every node that isn't a child.
        val children = HashSet<Int>()
        for (n in nodes) (n as? JsonObject)?.ints("children")?.let(children::addAll)
        return nodes.indices.filter { it !in children }
    }

    /** Positions under [node] (inclusive), through [frame] (the element's frame ← this node's parent). */
    private fun collect(node: Int, frame: DoubleArray, out: DoubleList, visited: MutableSet<Int>, top: Boolean) {
        if (!visited.add(node)) return
        val json = nodes.getOrNull(node) as? JsonObject ?: return
        val name = json.string("name")
        if (!top && name != null && NodeNames.isElement(name)) return // a nested element
        val local = if (top) Matrix4.identity else Matrix4.multiply(frame, localMatrix(json))
        json.int("mesh")?.let { appendMesh(it, local, out) }
        for (child in json.ints("children")) collect(child, local, out, visited, top = false)
    }

    private fun appendMesh(mesh: Int, m: DoubleArray, out: DoubleList) {
        val primitives = (meshes.getOrNull(mesh) as? JsonObject)?.array("primitives") ?: return
        for (primitive in primitives) {
            val p = primitive as? JsonObject ?: continue
            val position = (p["attributes"] as? JsonObject)?.int("POSITION") ?: continue
            val values = readAccessor(position, components = 3) ?: continue
            val count = values.size / 3
            val used: BooleanArray? = p.int("indices")?.let { indices ->
                val idx = readIndices(indices) ?: return@let null
                val flags = BooleanArray(count)
                for (i in idx) if (i in 0 until count) flags[i] = true
                flags
            }
            for (i in 0 until count) {
                if (used != null && !used[i]) continue
                val x = values[i * 3]
                val y = values[i * 3 + 1]
                val z = values[i * 3 + 2]
                out.add(m[0] * x + m[4] * y + m[8] * z + m[12])
                out.add(m[1] * x + m[5] * y + m[9] * z + m[13])
                out.add(m[2] * x + m[6] * y + m[10] * z + m[14])
            }
        }
    }

    /** A node's own transform: `matrix`, or translation · rotation · scale. */
    private fun localMatrix(node: JsonObject): DoubleArray {
        (node["matrix"] as? JsonArray)?.let { values ->
            val m = values.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            if (m.size == 16) return m.toDoubleArray()
        }
        val t = node.doubles("translation", 3) ?: doubleArrayOf(0.0, 0.0, 0.0)
        val r = node.doubles("rotation", 4) ?: doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        val s = node.doubles("scale", 3) ?: doubleArrayOf(1.0, 1.0, 1.0)
        val (x, y, z, w) = r.toList()
        return doubleArrayOf(
            (1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0.0,
            (2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0.0,
            (2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0.0,
            t[0], t[1], t[2], 1.0,
        )
    }

    // region Accessors

    /** An accessor's first [components] components per element as doubles (normalized as glTF says). */
    internal fun readAccessor(index: Int, components: Int): DoubleArray? {
        val accessor = accessors.getOrNull(index) as? JsonObject ?: return null
        if (accessor["sparse"] != null) return null
        val count = accessor.int("count") ?: return null
        val type = accessor.string("type") ?: return null
        val width = when (type) {
            "SCALAR" -> 1
            "VEC2" -> 2
            "VEC3" -> 3
            "VEC4" -> 4
            else -> return null
        }
        if (width < components) return null
        val componentType = accessor.int("componentType") ?: return null
        val componentSize = componentSize(componentType) ?: return null
        val normalized = (accessor["normalized"] as? JsonPrimitive)?.booleanOrNull ?: false
        val view = view(accessor.int("bufferView") ?: return null) ?: return null
        val stride = view.stride ?: (componentSize * width)
        val start = accessor.int("byteOffset") ?: 0
        if (count < 0 || start < 0) return null
        if (count > 0 && start.toLong() + (count - 1).toLong() * stride + componentSize * width > view.length) {
            throw GlbException("Accessor $index runs past its buffer view")
        }
        val out = DoubleArray(count * components)
        val data = view.data
        for (i in 0 until count) {
            val at = view.offset + start + i * stride
            for (c in 0 until components) {
                out[i * components + c] = component(data, at + c * componentSize, componentType, normalized)
            }
        }
        return out
    }

    private fun readIndices(index: Int): IntArray? {
        val accessor = accessors.getOrNull(index) as? JsonObject ?: return null
        if (accessor["sparse"] != null) return null
        val count = accessor.int("count") ?: return null
        val componentType = accessor.int("componentType") ?: return null
        val size = when (componentType) {
            UNSIGNED_BYTE -> 1
            UNSIGNED_SHORT -> 2
            UNSIGNED_INT -> 4
            else -> return null
        }
        val view = view(accessor.int("bufferView") ?: return null) ?: return null
        val start = accessor.int("byteOffset") ?: 0
        if (count < 0 || start < 0 || start.toLong() + count.toLong() * size > view.length) throw GlbException("Accessor $index runs past its buffer view")
        val data = view.data
        val base = view.offset + start
        return IntArray(count) { i ->
            val at = base + i * size
            when (size) {
                1 -> data[at].toInt() and 0xff
                2 -> (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8)
                else -> (data[at].toInt() and 0xff) or ((data[at + 1].toInt() and 0xff) shl 8) or
                    ((data[at + 2].toInt() and 0xff) shl 16) or (data[at + 3].toInt() shl 24)
            }
        }
    }

    private fun view(index: Int): View? = views.getOrPut(index) { loadView(index) }

    private fun loadView(index: Int): View? {
        val view = bufferViews.getOrNull(index) as? JsonObject ?: return null
        val stride = view.int("byteStride")
        val extensions = view["extensions"] as? JsonObject
        val meshopt = (extensions?.get("KHR_meshopt_compression") ?: extensions?.get("EXT_meshopt_compression")) as? JsonObject
        if (meshopt != null) {
            val (data, offset) = buffer(meshopt.int("buffer") ?: return null) ?: return null
            val start = meshopt.int("byteOffset") ?: 0
            val length = meshopt.int("byteLength") ?: return null
            val count = meshopt.int("count") ?: return null
            val size = meshopt.int("byteStride") ?: return null
            if (start < 0 || length < 0 || start.toLong() + length > bufferLength(meshopt.int("buffer")!!)) throw GlbException("Buffer view $index runs past its buffer")
            val mode = when (meshopt.string("mode")) {
                "ATTRIBUTES" -> MeshoptDecoder.Mode.ATTRIBUTES
                "TRIANGLES" -> MeshoptDecoder.Mode.TRIANGLES
                "INDICES" -> MeshoptDecoder.Mode.INDICES
                else -> return null
            }
            val filter = when (meshopt.string("filter") ?: "NONE") {
                "NONE" -> MeshoptDecoder.Filter.NONE
                "OCTAHEDRAL" -> MeshoptDecoder.Filter.OCTAHEDRAL
                "QUATERNION" -> MeshoptDecoder.Filter.QUATERNION
                "EXPONENTIAL" -> MeshoptDecoder.Filter.EXPONENTIAL
                "COLOR" -> MeshoptDecoder.Filter.COLOR
                else -> return null
            }
            val decoded = MeshoptDecoder.decodeGltfBuffer(count, size, data, mode, filter, sourceOffset = offset + start, sourceLength = length)
            return View(decoded, 0, decoded.size, stride ?: size)
        }
        val bufferIndex = view.int("buffer") ?: return null
        val (data, offset) = buffer(bufferIndex) ?: return null
        val start = view.int("byteOffset") ?: 0
        val length = view.int("byteLength") ?: return null
        if (start < 0 || length < 0 || start.toLong() + length > bufferLength(bufferIndex)) throw GlbException("Buffer view $index runs past its buffer")
        return View(data, offset + start, length, stride)
    }

    /** The GLB-stored buffer (index 0, no `uri`) as bytes and their offset; others aren't loaded. */
    private fun buffer(index: Int): Pair<ByteArray, Int>? {
        val buffer = buffers.getOrNull(index) as? JsonObject ?: return null
        if (index != 0 || buffer["uri"] != null || bin == null) return null
        return bin to binOffset
    }

    private fun bufferLength(index: Int): Long = if (index == 0) binLength.toLong() else 0L

    // endregion

    companion object {
        private const val MAGIC = 0x46546C67 // "glTF"
        private const val JSON_CHUNK = 0x4E4F534A // "JSON"
        private const val BIN_CHUNK = 0x004E4942 // "BIN\0"
        private const val BYTE = 5120
        private const val UNSIGNED_BYTE = 5121
        private const val SHORT = 5122
        private const val UNSIGNED_SHORT = 5123
        private const val UNSIGNED_INT = 5125
        private const val FLOAT = 5126

        private val json = Json { ignoreUnknownKeys = true }

        /** Parses a GLB file's bytes (kept, not copied). Throws [GlbException] when it isn't a GLB 2.0. */
        fun read(bytes: ByteArray): GlbGeometry {
            if (bytes.size < 20 || int32(bytes, 0) != MAGIC) throw GlbException("Not a GLB file")
            if (int32(bytes, 4) != 2) throw GlbException("GLB version ${int32(bytes, 4)}")
            val total = minOf(int32(bytes, 8).toLong() and 0xffffffffL, bytes.size.toLong()).toInt()
            var at = 12
            var root: JsonObject? = null
            var binOffset = -1
            var binLength = 0
            while (at + 8 <= total) {
                val length = int32(bytes, at)
                val type = int32(bytes, at + 4)
                val start = at + 8
                if (length < 0 || start.toLong() + length > total) throw GlbException("Truncated GLB chunk")
                when {
                    type == JSON_CHUNK && root == null -> {
                        val text = String(bytes, start, length, Charsets.UTF_8)
                        root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: throw GlbException("Bad GLB JSON")
                    }
                    type == BIN_CHUNK && binOffset < 0 -> {
                        binOffset = start
                        binLength = length
                    }
                }
                at = start + ((length + 3) and 3.inv())
            }
            return GlbGeometry(root ?: throw GlbException("No JSON chunk"), if (binOffset >= 0) bytes else null, maxOf(binOffset, 0), binLength)
        }

        private fun int32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or (b[at + 3].toInt() shl 24)

        private fun componentSize(type: Int): Int? = when (type) {
            BYTE, UNSIGNED_BYTE -> 1
            SHORT, UNSIGNED_SHORT -> 2
            UNSIGNED_INT, FLOAT -> 4
            else -> null
        }

        private fun component(d: ByteArray, at: Int, type: Int, normalized: Boolean): Double = when (type) {
            BYTE -> d[at].toDouble().let { if (normalized) maxOf(it / 127.0, -1.0) else it }
            UNSIGNED_BYTE -> (d[at].toInt() and 0xff).toDouble().let { if (normalized) it / 255.0 else it }
            SHORT -> ((d[at].toInt() and 0xff) or (d[at + 1].toInt() shl 8)).toDouble().let { if (normalized) maxOf(it / 32767.0, -1.0) else it }
            UNSIGNED_SHORT -> ((d[at].toInt() and 0xff) or ((d[at + 1].toInt() and 0xff) shl 8)).toDouble().let { if (normalized) it / 65535.0 else it }
            UNSIGNED_INT -> (int32(d, at).toLong() and 0xffffffffL).toDouble()
            else -> Float.fromBits(int32(d, at)).toDouble()
        }

        private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
        private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.ints(key: String): List<Int> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        private fun JsonObject.doubles(key: String, size: Int): DoubleArray? {
            val values = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return null
            return if (values.size == size) values.toDoubleArray() else null
        }
    }
}
