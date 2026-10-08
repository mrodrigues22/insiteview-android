package com.getinsiteview.modelkit.scene

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The materials a GLB chunk declares (its JSON chunk), for fading it.
 *
 * iOS fades a chunk with an `OpacityComponent`, which works on any material. Filament can't blend
 * glTF's opaque materials, so a faded chunk gets transparent copies of its colours instead
 * (`:scene`'s `SceneMaterials`), looked up here by the material's name (gltfio names each material
 * instance after its glTF material).
 */
data class GlbMaterial(
    val name: String?,
    /** glTF `baseColorFactor`: linear RGBA. */
    val baseColor: List<Double>,
    val metallic: Double,
    val roughness: Double,
)

object GlbMaterials {
    private const val MAGIC = 0x46546C67 // "glTF"
    private const val JSON_CHUNK = 0x4E4F534A // "JSON"

    private val json = Json { ignoreUnknownKeys = true }

    /** The materials of a GLB file's bytes, in glTF order; empty when it isn't a readable GLB. */
    fun read(bytes: ByteArray): List<GlbMaterial> {
        if (bytes.size < 20) return emptyList()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != MAGIC) return emptyList()
        val length = buffer.getInt(12)
        val type = buffer.getInt(16)
        if (type != JSON_CHUNK || length <= 0 || 20 + length > bytes.size) return emptyList()
        val text = String(bytes, 20, length, Charsets.UTF_8)
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        val materials = root["materials"] as? JsonArray ?: return emptyList()
        return materials.map { element ->
            val material = element as? JsonObject
            val pbr = material?.get("pbrMetallicRoughness") as? JsonObject
            val factor = (pbr?.get("baseColorFactor") as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull }
            GlbMaterial(
                name = material?.get("name")?.jsonPrimitive?.contentOrNull,
                baseColor = if (factor != null && factor.size == 4) factor else listOf(1.0, 1.0, 1.0, 1.0),
                metallic = pbr?.get("metallicFactor")?.jsonPrimitive?.doubleOrNull ?: 1.0,
                roughness = pbr?.get("roughnessFactor")?.jsonPrimitive?.doubleOrNull ?: 1.0,
            )
        }
    }

    /**
     * The material a gltfio instance named [name] came from: by name; for an unnamed one, the only
     * material when there's just one. `null` when it can't be told.
     */
    fun lookup(materials: List<GlbMaterial>, name: String?): GlbMaterial? {
        if (!name.isNullOrEmpty()) {
            val named = materials.filter { it.name == name }
            if (named.isNotEmpty()) return named.first()
        }
        return materials.singleOrNull()
    }
}
