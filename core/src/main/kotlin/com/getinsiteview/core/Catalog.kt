package com.getinsiteview.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * `GET /v1/catalog` (master PLAN §9 "Catalog"): system and subsystem keys and colours, element
 * kinds and key-property labels, each named in en / pt-BR / es.
 *
 * Clients never hard-code system colours or kind names; they come from here. Unknown keys (a
 * newer converter or catalog) fall back to the raw key and a neutral colour instead of failing.
 */
@Serializable
data class Catalog(
    val version: Int,
    val systems: List<System>,
    val kinds: List<Kind> = emptyList(),
    val properties: List<Property> = emptyList(),
) {
    @Transient private val systemIndex: Map<String, Int> = index(systems.map { it.key })
    @Transient private val kindIndex: Map<String, Int> = index(kinds.map { it.key })
    @Transient private val propertyIndex: Map<String, Int> = index(properties.map { it.key })

    // Lookups

    fun system(key: String): System? = systemIndex[key]?.let { systems[it] }

    fun kind(key: String): Kind? = kindIndex[key]?.let { kinds[it] }

    fun property(key: String): Property? = propertyIndex[key]?.let { properties[it] }

    fun subsystem(key: String, systemKey: String): Subsystem? = system(systemKey)?.subsystems?.firstOrNull { it.key == key }

    /** The subsystem colour when there is one, else the system colour. */
    fun color(system: String, subsystem: String? = null): CatalogColor {
        if (subsystem != null) this.subsystem(subsystem, system)?.let { return it.color }
        return this.system(system)?.color ?: CatalogColor.FALLBACK
    }

    /** Whether elements of this kind appear in equipment lists (panels, water heaters, …). */
    fun isEquipment(kind: String): Boolean = this.kind(kind)?.equipment ?: false

    /** Position of a system in the catalog, used to order chips and download priorities. */
    fun systemOrder(key: String): Int = systemIndex[key] ?: Int.MAX_VALUE

    /** Position of a key property in the catalog, used to order an element's key properties. */
    fun propertyOrder(key: String): Int = propertyIndex[key] ?: Int.MAX_VALUE

    // Names

    fun systemName(key: String, language: CatalogLanguage): String =
        system(key)?.names?.get(language) ?: humanizedKey(key)

    fun subsystemName(key: String, systemKey: String, language: CatalogLanguage): String =
        subsystem(key, systemKey)?.names?.get(language) ?: humanizedKey(key)

    fun kindName(key: String, language: CatalogLanguage): String =
        kind(key)?.names?.get(language) ?: humanizedKey(key)

    fun propertyName(key: String, language: CatalogLanguage): String =
        property(key)?.names?.get(language) ?: humanizedKey(key)

    // Types

    @Serializable
    data class System(
        val key: String,
        val color: CatalogColor,
        val names: LocalizedNames,
        val subsystems: List<Subsystem> = emptyList(),
    ) {
        val id: String get() = key
    }

    @Serializable
    data class Subsystem(val key: String, val color: CatalogColor, val names: LocalizedNames) {
        val id: String get() = key
    }

    @Serializable
    data class Kind(val key: String, val system: String, val equipment: Boolean, val names: LocalizedNames) {
        val id: String get() = key
    }

    @Serializable
    data class Property(val key: String, val names: LocalizedNames) {
        val id: String get() = key
    }

    /** Names in the three supported languages (JSON keys `en`, `pt-BR`, `es`). */
    @Serializable
    data class LocalizedNames(
        val en: String,
        @SerialName("pt-BR") val ptBR: String,
        val es: String,
    ) {
        operator fun get(language: CatalogLanguage): String = when (language) {
            CatalogLanguage.EN -> en
            CatalogLanguage.PT_BR -> ptBR
            CatalogLanguage.ES -> es
        }
    }

    companion object {
        private fun index(keys: List<String>): Map<String, Int> {
            val index = HashMap<String, Int>()
            keys.forEachIndexed { offset, key -> index.putIfAbsent(key, offset) }
            return index
        }

        /** `water_heater` → `Water heater`, for keys the catalog doesn't know yet. */
        fun humanizedKey(key: String): String =
            key.replace("_", " ").replaceFirstChar { it.uppercase() }
    }
}

/** The languages the catalog (and the API) supports. */
enum class CatalogLanguage(val raw: String) {
    EN("en"),
    PT_BR("pt-BR"),
    ES("es");

    companion object {
        /**
         * Maps a BCP 47 or ICU identifier (`pt-BR`, `pt_PT`, `es-MX`, `en-US`) to a supported
         * language by its language code; `null` when the language isn't supported.
         */
        fun fromIdentifier(identifier: String): CatalogLanguage? =
            when (identifier.split('-', '_').firstOrNull { it.isNotEmpty() }?.lowercase()) {
                "en" -> EN
                "pt" -> PT_BR
                "es" -> ES
                else -> null
            }

        /**
         * The first supported language in the user's preference list (e.g. the app's
         * `LocaleListCompat` as language tags), else English.
         */
        fun preferred(identifiers: Iterable<String>): CatalogLanguage =
            identifiers.firstNotNullOfOrNull(::fromIdentifier) ?: EN
    }
}

/** An sRGB colour from the catalog, written as `#RRGGBB`. */
@Serializable(with = CatalogColorSerializer::class)
data class CatalogColor(val red: Double, val green: Double, val blue: Double) {
    /** `#RRGGBB` in upper case. */
    val hex: String
        get() {
            fun byte(component: Double): String {
                val value = (component.coerceIn(0.0, 1.0) * 255).roundedHalfAway().toInt()
                return value.toString(16).uppercase().padStart(2, '0')
            }
            return "#" + byte(red) + byte(green) + byte(blue)
        }

    companion object {
        /** Neutral grey for systems the catalog doesn't know. */
        val FALLBACK = CatalogColor(red = 0.62, green = 0.65, blue = 0.66)

        /** Parses `#RRGGBB` or `RRGGBB` (any case). */
        fun fromHex(hex: String): CatalogColor? {
            val digits = hex.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }.removePrefix("#")
            if (digits.length != 6 || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val value = digits.toInt(16)
            return CatalogColor(
                red = ((value shr 16) and 0xFF) / 255.0,
                green = ((value shr 8) and 0xFF) / 255.0,
                blue = (value and 0xFF) / 255.0,
            )
        }
    }
}

object CatalogColorSerializer : KSerializer<CatalogColor> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.getinsiteview.core.CatalogColor", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): CatalogColor {
        val string = decoder.decodeString()
        return CatalogColor.fromHex(string) ?: throw SerializationException("Expected #RRGGBB, found \"$string\".")
    }

    override fun serialize(encoder: Encoder, value: CatalogColor) {
        encoder.encodeString(value.hex)
    }
}
