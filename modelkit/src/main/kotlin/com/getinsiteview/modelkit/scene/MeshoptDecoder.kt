package com.getinsiteview.modelkit.scene

import kotlin.math.abs
import kotlin.math.sqrt

/** A meshopt-compressed buffer that can't be decoded (bad header, truncated or corrupt data). */
class MeshoptException(message: String) : IllegalArgumentException(message)

/**
 * meshoptimizer's decoders (zeux/meshoptimizer 1.3: `vertexcodec.cpp`, `indexcodec.cpp`,
 * `vertexfilter.cpp`; the scalar paths), for the chunks gltfpack writes with `-cc`
 * (`EXT_meshopt_compression`, or `KHR_meshopt_compression` with `-ce khr`).
 *
 * - Vertex buffers ("ATTRIBUTES"): codec versions 0 (`0xa0`, what EXT allows and gltfpack 1.3 writes by
 *   default) and 1 (`0xa1`: control modes and 16-bit / XOR channels, KHR only).
 * - Index buffers ("TRIANGLES", `0xe0`/`0xe1`) and index sequences ("INDICES", `0xd1`).
 * - Filters: [Filter.OCTAHEDRAL], [Filter.QUATERNION], [Filter.EXPONENTIAL] and [Filter.COLOR] (KHR).
 *
 * Bytes are little-endian. Every decoder checks the header and the stream's length the way the C++
 * one does and throws [MeshoptException] instead of returning an error code. Pure and allocation-light
 * (one block of deltas per call), so it runs on any thread.
 */
object MeshoptDecoder {
    enum class Mode { ATTRIBUTES, TRIANGLES, INDICES }

    enum class Filter { NONE, OCTAHEDRAL, QUATERNION, EXPONENTIAL, COLOR }

    private const val VERTEX_HEADER = 0xa0
    private const val INDEX_HEADER = 0xe0
    private const val SEQUENCE_HEADER = 0xd0
    private const val VERTEX_BLOCK_SIZE_BYTES = 8192
    private const val VERTEX_BLOCK_MAX_SIZE = 256
    private const val BYTE_GROUP_SIZE = 16
    private const val TAIL_MIN_SIZE_V0 = 32
    private const val TAIL_MIN_SIZE_V1 = 24

    /** Bits per delta for each 2-bit group header: v0, then v1 by its byte's control mode (0 or 1). */
    private val HEADER_BITS = arrayOf(intArrayOf(0, 2, 4, 8), intArrayOf(0, 1, 2, 4), intArrayOf(1, 2, 4, 8))

    /**
     * Decodes a glTF meshopt buffer view (the extension's `count`, `byteStride`, `mode` and `filter`)
     * into `count * size` bytes.
     */
    fun decodeGltfBuffer(
        count: Int,
        size: Int,
        source: ByteArray,
        mode: Mode,
        filter: Filter = Filter.NONE,
        sourceOffset: Int = 0,
        sourceLength: Int = source.size - sourceOffset,
    ): ByteArray {
        if (count < 0 || size <= 0) throw MeshoptException("Bad count or size")
        val target = ByteArray(Math.multiplyExact(count, size))
        when (mode) {
            Mode.ATTRIBUTES -> decodeVertexBuffer(target, count, size, source, sourceOffset, sourceLength, filter)
            Mode.TRIANGLES -> decodeIndexBuffer(target, count, size, source, sourceOffset, sourceLength)
            Mode.INDICES -> decodeIndexSequence(target, count, size, source, sourceOffset, sourceLength)
        }
        return target
    }

    // region Vertex codec

    /**
     * `meshopt_decodeVertexBuffer` then the filter: [count] vertices of [size] bytes (a multiple of 4,
     * at most 256) into [target].
     */
    fun decodeVertexBuffer(
        target: ByteArray,
        count: Int,
        size: Int,
        source: ByteArray,
        sourceOffset: Int = 0,
        sourceLength: Int = source.size - sourceOffset,
        filter: Filter = Filter.NONE,
    ) {
        if (size <= 0 || size > 256 || size % 4 != 0) throw MeshoptException("Vertex size $size")
        if (target.size < count.toLong() * size) throw MeshoptException("Target too small")
        if (sourceOffset < 0 || sourceLength < 0 || sourceOffset + sourceLength > source.size) throw MeshoptException("Source range")
        try {
            decodeVertices(target, count, size, source, sourceOffset, sourceOffset + sourceLength)
        } catch (e: IndexOutOfBoundsException) {
            throw MeshoptException("Truncated vertex data")
        }
        applyFilter(target, count, size, filter)
    }

    private fun decodeVertices(target: ByteArray, count: Int, size: Int, src: ByteArray, start: Int, end: Int) {
        if (end - start < 1 + size) throw MeshoptException("Vertex data too short")
        val header = src[start].toInt() and 0xff
        if (header and 0xf0 != VERTEX_HEADER) throw MeshoptException("Not a meshopt vertex buffer")
        val version = header and 0x0f
        if (version > 1) throw MeshoptException("Vertex codec version $version")

        val tailSize = size + if (version == 0) 0 else size / 4
        val tailSizePadded = maxOf(tailSize, if (version == 0) TAIL_MIN_SIZE_V0 else TAIL_MIN_SIZE_V1)
        if (end - start - 1 < tailSizePadded) throw MeshoptException("Vertex data too short")
        val tail = end - tailSize
        val dataEnd = end - tailSizePadded

        // The previous vertex, which deltas are relative to; starts as the tail's baseline.
        val last = src.copyOfRange(tail, tail + size)
        val channels = tail + size // v1: one channel byte per 4-byte group

        val blockSize = minOf((VERTEX_BLOCK_SIZE_BYTES / size) and (BYTE_GROUP_SIZE - 1).inv(), VERTEX_BLOCK_MAX_SIZE)
        // 16 bytes of slack: a block's last group can be partial, and its padding spills into the next
        // byte's deltas (overwritten next) or past the block.
        val deltas = ByteArray(blockSize * size + BYTE_GROUP_SIZE)
        var data = start + 1

        var base = 0
        while (base < count) {
            val blockCount = minOf(count - base, blockSize)
            val groupCount = (blockCount + BYTE_GROUP_SIZE - 1) / BYTE_GROUP_SIZE
            val headerSize = (groupCount + 3) / 4
            val control = data
            if (version != 0) data += size / 4
            for (k in 0 until size) {
                val deltaBase = k * blockCount
                val controlMode = if (version == 0) 0 else ((src[control + (k ushr 2)].toInt() and 0xff) ushr ((k and 3) shl 1)) and 3
                when (controlMode) {
                    2 -> { // all zero
                        deltas.fill(0, deltaBase, deltaBase + blockCount)
                        continue
                    }
                    3 -> { // verbatim, no header
                        if (data + blockCount > dataEnd) throw MeshoptException("Truncated vertex data")
                        System.arraycopy(src, data, deltas, deltaBase, blockCount)
                        data += blockCount
                        continue
                    }
                }
                val headerAt = data
                data += headerSize
                val modes = HEADER_BITS[if (version == 0) 0 else controlMode + 1]
                for (group in 0 until groupCount) {
                    val mode = ((src[headerAt + (group ushr 2)].toInt() and 0xff) ushr ((group and 3) shl 1)) and 3
                    data = decodeGroup(src, data, modes[mode], deltas, deltaBase + (group shl 4))
                    if (data > dataEnd) throw MeshoptException("Truncated vertex data")
                }
            }
            applyDeltas(target, base, blockCount, size, deltas, last, src, channels, version)
            base += blockCount
        }
        if (data != dataEnd) throw MeshoptException("Unexpected vertex data length")
    }

    /** One group of 16 byte deltas at [bits] per delta (0, 1, 2, 4 or 8), sentinel-escaped. Returns the new read position. */
    private fun decodeGroup(src: ByteArray, at: Int, bits: Int, dst: ByteArray, o: Int): Int {
        var data = at
        when (bits) {
            0 -> dst.fill(0, o, o + 16)
            1 -> {
                data += 2
                for (m in 0 until 16) {
                    var delta = ((src[at + (m ushr 3)].toInt() and 0xff) ushr (m and 7)) and 1
                    if (delta == 1) delta = src[data++].toInt() and 0xff
                    dst[o + m] = delta.toByte()
                }
            }
            2 -> {
                data += 4
                for (m in 0 until 16) {
                    var delta = ((src[at + (m ushr 2)].toInt() and 0xff) ushr (6 - ((m and 3) shl 1))) and 3
                    if (delta == 3) delta = src[data++].toInt() and 0xff
                    dst[o + m] = delta.toByte()
                }
            }
            4 -> {
                data += 8
                for (m in 0 until 16) {
                    var delta = ((src[at + (m ushr 1)].toInt() and 0xff) ushr (4 - ((m and 1) shl 2))) and 0x0f
                    if (delta == 0x0f) delta = src[data++].toInt() and 0xff
                    dst[o + m] = delta.toByte()
                }
            }
            else -> {
                System.arraycopy(src, data, dst, o, 16)
                data += 16
            }
        }
        return data
    }

    private fun applyDeltas(
        target: ByteArray,
        base: Int,
        blockCount: Int,
        size: Int,
        deltas: ByteArray,
        last: ByteArray,
        src: ByteArray,
        channels: Int,
        version: Int,
    ) {
        var group = 0
        while (group < size) {
            val channel = if (version == 0) 0 else src[channels + (group ushr 2)].toInt() and 0xff
            when (channel and 3) {
                0 -> for (k in group until group + 4) { // byte deltas, zigzag
                    var p = last[k].toInt() and 0xff
                    val row = k * blockCount
                    var dst = base * size + k
                    for (i in 0 until blockCount) {
                        val v = deltas[row + i].toInt() and 0xff
                        p = (p + ((v ushr 1) xor -(v and 1))) and 0xff
                        target[dst] = p.toByte()
                        dst += size
                    }
                    last[k] = p.toByte()
                }
                1 -> for (k in group until group + 4 step 2) { // 16-bit deltas, zigzag
                    var p = (last[k].toInt() and 0xff) or ((last[k + 1].toInt() and 0xff) shl 8)
                    val row0 = k * blockCount
                    val row1 = (k + 1) * blockCount
                    var dst = base * size + k
                    for (i in 0 until blockCount) {
                        val v = (deltas[row0 + i].toInt() and 0xff) or ((deltas[row1 + i].toInt() and 0xff) shl 8)
                        p = (p + ((v ushr 1) xor -(v and 1))) and 0xffff
                        target[dst] = p.toByte()
                        target[dst + 1] = (p ushr 8).toByte()
                        dst += size
                    }
                    last[k] = p.toByte()
                    last[k + 1] = (p ushr 8).toByte()
                }
                2 -> { // 32-bit XOR deltas, rotated by the channel byte's high nibble
                    val rot = channel ushr 4
                    var p = (last[group].toInt() and 0xff) or ((last[group + 1].toInt() and 0xff) shl 8) or
                        ((last[group + 2].toInt() and 0xff) shl 16) or ((last[group + 3].toInt() and 0xff) shl 24)
                    val row = group * blockCount
                    var dst = base * size + group
                    for (i in 0 until blockCount) {
                        val v = (deltas[row + i].toInt() and 0xff) or ((deltas[row + blockCount + i].toInt() and 0xff) shl 8) or
                            ((deltas[row + 2 * blockCount + i].toInt() and 0xff) shl 16) or ((deltas[row + 3 * blockCount + i].toInt() and 0xff) shl 24)
                        p = p xor v.rotateRight(rot)
                        target[dst] = p.toByte()
                        target[dst + 1] = (p ushr 8).toByte()
                        target[dst + 2] = (p ushr 16).toByte()
                        target[dst + 3] = (p ushr 24).toByte()
                        dst += size
                    }
                    last[group] = p.toByte()
                    last[group + 1] = (p ushr 8).toByte()
                    last[group + 2] = (p ushr 16).toByte()
                    last[group + 3] = (p ushr 24).toByte()
                }
                else -> throw MeshoptException("Vertex channel mode 3")
            }
            group += 4
        }
    }

    // endregion

    // region Index codec

    /** `meshopt_decodeIndexBuffer`: [count] (a multiple of 3) indices of [size] bytes (2 or 4) into [target]. */
    fun decodeIndexBuffer(
        target: ByteArray,
        count: Int,
        size: Int,
        source: ByteArray,
        sourceOffset: Int = 0,
        sourceLength: Int = source.size - sourceOffset,
    ) {
        if (count % 3 != 0) throw MeshoptException("Index count $count isn't a multiple of 3")
        if (size != 2 && size != 4) throw MeshoptException("Index size $size")
        if (target.size < count.toLong() * size) throw MeshoptException("Target too small")
        if (sourceOffset < 0 || sourceLength < 0 || sourceOffset + sourceLength > source.size) throw MeshoptException("Source range")
        if (sourceLength < 1 + count / 3 + 16) throw MeshoptException("Index data too short")
        val header = source[sourceOffset].toInt() and 0xff
        if (header and 0xf0 != INDEX_HEADER) throw MeshoptException("Not a meshopt index buffer")
        val version = header and 0x0f
        if (version > 1) throw MeshoptException("Index codec version $version")
        try {
            decodeTriangles(target, count, size, source, sourceOffset, sourceOffset + sourceLength, version)
        } catch (e: IndexOutOfBoundsException) {
            throw MeshoptException("Truncated index data")
        }
    }

    private fun decodeTriangles(target: ByteArray, count: Int, size: Int, src: ByteArray, start: Int, end: Int, version: Int) {
        val edgeA = IntArray(16)
        val edgeB = IntArray(16)
        var edgeOffset = 0
        val vertices = IntArray(16)
        var vertexOffset = 0
        var next = 0
        var last = 0
        val fecMax = if (version >= 1) 13 else 15

        var code = start + 1
        val reader = VByteReader(src, start + 1 + count / 3)
        val dataSafeEnd = end - 16
        val codeaux = end - 16

        fun pushEdge(a: Int, b: Int) {
            edgeA[edgeOffset] = a
            edgeB[edgeOffset] = b
            edgeOffset = (edgeOffset + 1) and 15
        }

        fun pushVertex(v: Int, condition: Boolean = true) {
            vertices[vertexOffset] = v
            if (condition) vertexOffset = (vertexOffset + 1) and 15
        }

        fun decodeIndex(): Int {
            val v = reader.read()
            return last + ((v ushr 1) xor -(v and 1))
        }

        var i = 0
        while (i < count) {
            if (reader.at > dataSafeEnd) throw MeshoptException("Truncated index data")
            val codetri = src[code++].toInt() and 0xff
            if (codetri < 0xf0) {
                val fe = codetri ushr 4
                val a = edgeA[(edgeOffset - 1 - fe) and 15]
                val b = edgeB[(edgeOffset - 1 - fe) and 15]
                val fec = codetri and 15
                val c: Int
                if (fec < fecMax) {
                    val cf = vertices[(vertexOffset - 1 - fec) and 15]
                    c = if (fec == 0) next else cf
                    val fec0 = fec == 0
                    if (fec0) next++
                    pushVertex(c, fec0)
                } else {
                    // 13 and 14 are last - 1 and last + 1 (v1); 15 is a free index.
                    c = if (fec != 15) last + (fec - (fec xor 3)) else decodeIndex()
                    last = c
                    pushVertex(c)
                }
                pushEdge(c, b)
                pushEdge(a, c)
                writeTriangle(target, i, size, a, b, c)
            } else if (codetri < 0xfe) {
                val aux = src[codeaux + (codetri and 15)].toInt() and 0xff
                val feb = aux ushr 4
                val fec = aux and 15
                val a = next++
                val bf = vertices[(vertexOffset - feb) and 15]
                val b = if (feb == 0) next else bf
                val feb0 = feb == 0
                if (feb0) next++
                val cf = vertices[(vertexOffset - fec) and 15]
                val c = if (fec == 0) next else cf
                val fec0 = fec == 0
                if (fec0) next++
                pushVertex(a)
                pushVertex(b, feb0)
                pushVertex(c, fec0)
                pushEdge(b, a)
                pushEdge(c, b)
                pushEdge(a, c)
                writeTriangle(target, i, size, a, b, c)
            } else {
                val aux = src[reader.at++].toInt() and 0xff
                val fea = if (codetri == 0xfe) 0 else 15
                val feb = aux ushr 4
                val fec = aux and 15
                if (aux == 0) next = 0
                var a = if (fea == 0) next++ else 0
                var b = if (feb == 0) next++ else vertices[(vertexOffset - feb) and 15]
                var c = if (fec == 0) next++ else vertices[(vertexOffset - fec) and 15]
                if (fea == 15) {
                    a = decodeIndex()
                    last = a
                }
                if (feb == 15) {
                    b = decodeIndex()
                    last = b
                }
                if (fec == 15) {
                    c = decodeIndex()
                    last = c
                }
                pushVertex(a)
                pushVertex(b, feb == 0 || feb == 15)
                pushVertex(c, fec == 0 || fec == 15)
                pushEdge(b, a)
                pushEdge(c, b)
                pushEdge(a, c)
                writeTriangle(target, i, size, a, b, c)
            }
            i += 3
        }
        if (reader.at != dataSafeEnd) throw MeshoptException("Unexpected index data length")
    }

    private fun writeTriangle(target: ByteArray, i: Int, size: Int, a: Int, b: Int, c: Int) {
        writeIndex(target, i, size, a)
        writeIndex(target, i + 1, size, b)
        writeIndex(target, i + 2, size, c)
    }

    private fun writeIndex(target: ByteArray, i: Int, size: Int, value: Int) {
        val at = i * size
        target[at] = value.toByte()
        target[at + 1] = (value ushr 8).toByte()
        if (size == 4) {
            target[at + 2] = (value ushr 16).toByte()
            target[at + 3] = (value ushr 24).toByte()
        }
    }

    /** `meshopt_decodeIndexSequence`: [count] indices of [size] bytes (2 or 4) into [target]. */
    fun decodeIndexSequence(
        target: ByteArray,
        count: Int,
        size: Int,
        source: ByteArray,
        sourceOffset: Int = 0,
        sourceLength: Int = source.size - sourceOffset,
    ) {
        if (size != 2 && size != 4) throw MeshoptException("Index size $size")
        if (target.size < count.toLong() * size) throw MeshoptException("Target too small")
        if (sourceOffset < 0 || sourceLength < 0 || sourceOffset + sourceLength > source.size) throw MeshoptException("Source range")
        if (sourceLength < 1 + count + 4) throw MeshoptException("Index sequence too short")
        val header = source[sourceOffset].toInt() and 0xff
        if (header and 0xf0 != SEQUENCE_HEADER) throw MeshoptException("Not a meshopt index sequence")
        val version = header and 0x0f
        if (version > 1) throw MeshoptException("Index sequence version $version")
        val dataSafeEnd = sourceOffset + sourceLength - 4
        val reader = VByteReader(source, sourceOffset + 1)
        val last = IntArray(2)
        try {
            for (i in 0 until count) {
                if (reader.at > dataSafeEnd) throw MeshoptException("Truncated index sequence")
                val v = reader.read()
                // The low bit picks one of two baselines; the rest is a zigzag delta.
                val current = v and 1
                val d = v ushr 1
                val index = last[current] + ((d ushr 1) xor -(d and 1))
                last[current] = index
                writeIndex(target, i, size, index)
            }
        } catch (e: IndexOutOfBoundsException) {
            throw MeshoptException("Truncated index sequence")
        }
        if (reader.at != dataSafeEnd) throw MeshoptException("Unexpected index sequence length")
    }

    /** LEB128-style variable-length integers (meshoptimizer's `decodeVByte`: at most 5 bytes). */
    private class VByteReader(private val src: ByteArray, var at: Int) {
        fun read(): Int {
            val lead = src[at++].toInt() and 0xff
            if (lead < 128) return lead
            var result = lead and 127
            var shift = 7
            for (i in 0 until 4) {
                val group = src[at++].toInt() and 0xff
                result = result or ((group and 127) shl shift)
                shift += 7
                if (group < 128) break
            }
            return result
        }
    }

    // endregion

    // region Filters

    /** Applies a filter in place to [count] decoded vertices of [size] bytes. */
    fun applyFilter(data: ByteArray, count: Int, size: Int, filter: Filter) {
        when (filter) {
            Filter.NONE -> Unit
            Filter.OCTAHEDRAL -> {
                if (size != 4 && size != 8) throw MeshoptException("Octahedral filter on $size-byte vertices")
                decodeOct(data, count, size == 8)
            }
            Filter.QUATERNION -> {
                if (size != 8) throw MeshoptException("Quaternion filter on $size-byte vertices")
                decodeQuat(data, count)
            }
            Filter.EXPONENTIAL -> {
                if (size % 4 != 0) throw MeshoptException("Exponential filter on $size-byte vertices")
                decodeExp(data, count * (size / 4))
            }
            Filter.COLOR -> {
                if (size != 4 && size != 8) throw MeshoptException("Color filter on $size-byte vertices")
                decodeColor(data, count, size == 8)
            }
        }
    }

    private fun getSigned(data: ByteArray, index: Int, wide: Boolean): Int =
        if (wide) ((data[index * 2].toInt() and 0xff) or (data[index * 2 + 1].toInt() shl 8)) else data[index].toInt()

    private fun getUnsigned(data: ByteArray, index: Int, wide: Boolean): Int =
        if (wide) ((data[index * 2].toInt() and 0xff) or ((data[index * 2 + 1].toInt() and 0xff) shl 8)) else data[index].toInt() and 0xff

    private fun put(data: ByteArray, index: Int, wide: Boolean, value: Int) {
        if (wide) {
            data[index * 2] = value.toByte()
            data[index * 2 + 1] = (value ushr 8).toByte()
        } else {
            data[index] = value.toByte()
        }
    }

    /** Rounded signed float → int, as the C++ scalar path does it. */
    private fun roundSigned(v: Float): Int = (v + if (v >= 0f) 0.5f else -0.5f).toInt()

    /** Octahedral-encoded unit vectors (normals, tangents); the fourth component is kept. */
    private fun decodeOct(data: ByteArray, count: Int, wide: Boolean) {
        val max = (if (wide) 32767 else 127).toFloat()
        for (i in 0 until count) {
            val o = i * 4
            var x = getSigned(data, o, wide).toFloat()
            var y = getSigned(data, o + 1, wide).toFloat()
            // z is reconstructed in the same fixed point as the stored "one"
            val z = getSigned(data, o + 2, wide).toFloat() - abs(x) - abs(y)
            val t = if (z >= 0f) 0f else z
            x += if (x >= 0f) t else -t
            y += if (y >= 0f) t else -t
            val l = sqrt(x * x + y * y + z * z)
            val s = max / l
            put(data, o, wide, roundSigned(x * s))
            put(data, o + 1, wide, roundSigned(y * s))
            put(data, o + 2, wide, roundSigned(z * s))
        }
    }

    /** Quaternions with the largest component dropped (its index in the low 2 bits of the fourth). */
    private fun decodeQuat(data: ByteArray, count: Int) {
        val scale = 32767f / sqrt(2f)
        for (i in 0 until count) {
            val o = i * 4
            val stored = getSigned(data, o + 3, true)
            val s = (stored or 3).toFloat()
            val x = getSigned(data, o, true).toFloat()
            val y = getSigned(data, o + 1, true).toFloat()
            val z = getSigned(data, o + 2, true).toFloat()
            val ww = s * s * 2f - x * x - y * y - z * z
            val w = sqrt(if (ww >= 0f) ww else 0f)
            val ss = scale / s
            val xf = roundSigned(x * ss)
            val yf = roundSigned(y * ss)
            val zf = roundSigned(z * ss)
            val wf = (w * ss + 0.5f).toInt()
            val qc = stored and 3
            put(data, o + ((qc + 1) and 3), true, xf)
            put(data, o + ((qc + 2) and 3), true, yf)
            put(data, o + ((qc + 3) and 3), true, zf)
            put(data, o + ((qc + 0) and 3), true, wf)
        }
    }

    /** 32-bit floats as a signed 24-bit mantissa and an 8-bit exponent. */
    private fun decodeExp(data: ByteArray, count: Int) {
        for (i in 0 until count) {
            val o = i * 4
            val v = (data[o].toInt() and 0xff) or ((data[o + 1].toInt() and 0xff) shl 8) or
                ((data[o + 2].toInt() and 0xff) shl 16) or (data[o + 3].toInt() shl 24)
            val m = (v shl 8) shr 8
            val e = v shr 24
            val f = Float.fromBits((e + 127) shl 23) * m.toFloat()
            val bits = f.toRawBits()
            data[o] = bits.toByte()
            data[o + 1] = (bits ushr 8).toByte()
            data[o + 2] = (bits ushr 16).toByte()
            data[o + 3] = (bits ushr 24).toByte()
        }
    }

    /** YCoCg colours with alpha, scaled by the alpha's top bit (KHR_meshopt_compression). */
    private fun decodeColor(data: ByteArray, count: Int, wide: Boolean) {
        val max = ((1 shl (if (wide) 16 else 8)) - 1).toFloat()
        for (i in 0 until count) {
            val o = i * 4
            val alpha = getUnsigned(data, o + 3, wide)
            var scale = alpha
            scale = scale or (scale shr 1)
            scale = scale or (scale shr 2)
            scale = scale or (scale shr 4)
            scale = scale or (scale shr 8)
            val y = getUnsigned(data, o, wide)
            val co = getSigned(data, o + 1, wide)
            val cg = getSigned(data, o + 2, wide)
            val r = y + co - cg
            val g = y + cg
            val b = y - co - cg
            val a = ((alpha shl 1) and scale) or (alpha and 1)
            val ss = max / scale.toFloat()
            put(data, o, wide, (r.toFloat() * ss + 0.5f).toInt())
            put(data, o + 1, wide, (g.toFloat() * ss + 0.5f).toInt())
            put(data, o + 2, wide, (b.toFloat() * ss + 0.5f).toInt())
            put(data, o + 3, wide, (a.toFloat() * ss + 0.5f).toInt())
        }
    }

    // endregion
}
