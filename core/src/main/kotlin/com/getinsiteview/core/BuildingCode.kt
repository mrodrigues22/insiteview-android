package com.getinsiteview.core

/**
 * A building's public code: 6 characters of Crockford base32 (digits and letters without
 * I, L, O and U), about 10⁹ combinations (PLAN §10).
 *
 * The API and URLs use the raw form (`8K29X7`); people see `IV-8K29-X7`.
 * Parsing ([parse]) is case-insensitive and accepts either form.
 */
@JvmInline
value class BuildingCode private constructor(
    /** Canonical form: 6 upper-case characters, e.g. `8K29X7`. */
    val raw: String,
) {
    /** Display form, e.g. `IV-8K29-X7`. */
    val formatted: String get() = "IV-${raw.take(4)}-${raw.takeLast(2)}"

    override fun toString(): String = raw

    companion object {
        /** The 32 symbols of Crockford base32. */
        val alphabet: Set<Char> = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toSet()
        const val LENGTH = 6

        /** Parses `8K29X7` or `IV-8K29-X7`, in any case. Surrounding spaces and tabs are ignored. */
        fun parse(string: CharSequence): BuildingCode? {
            val trimmed = string.trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
            // ASCII only, so no Unicode case mapping can turn a foreign letter into a valid one.
            if (!trimmed.all { it.code < 128 }) return null
            var candidate = trimmed.toString().uppercase()

            val parts = candidate.split('-')
            if (parts.size == 3 && parts[0] == "IV" && parts[1].length == 4 && parts[2].length == 2) {
                candidate = parts[1] + parts[2]
            }

            if (candidate.length != LENGTH || !candidate.all { it in alphabet }) return null
            return BuildingCode(candidate)
        }
    }
}
