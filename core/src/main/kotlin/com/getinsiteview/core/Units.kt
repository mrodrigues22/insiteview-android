package com.getinsiteview.core

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.truncate
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Metric or imperial, as stored on the user (`units` in `/v1/me`, JSON `Metric` / `Imperial`).
 * Defaults from the locale's region; Profile can override it (docs/PLAN.md §3).
 *
 * Unknown values decode as metric so a new API value never breaks decoding.
 */
@Serializable(with = UnitSystemSerializer::class)
enum class UnitSystem(val raw: String) {
    METRIC("Metric"),
    IMPERIAL("Imperial");

    companion object {
        /** Regions that use US customary units day to day. */
        private val imperialRegions = setOf("US", "LR", "MM")

        /** Imperial for the United States, Liberia and Myanmar; metric everywhere else. */
        fun default(region: String?): UnitSystem {
            if (region == null) return METRIC
            return if (region.uppercase(Locale.ROOT) in imperialRegions) IMPERIAL else METRIC
        }

        fun default(locale: Locale): UnitSystem = default(region = locale.country.ifEmpty { null })

        /** The exact raw value, else a case-insensitive match, else metric. */
        fun fromRaw(raw: String): UnitSystem =
            entries.firstOrNull { it.raw == raw } ?: entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: METRIC
    }
}

object UnitSystemSerializer : KSerializer<UnitSystem> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.getinsiteview.core.UnitSystem", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): UnitSystem = UnitSystem.fromRaw(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: UnitSystem) {
        encoder.encodeString(value.raw)
    }
}

/**
 * Formats lengths, distances and areas in the user's unit system.
 *
 * Written out by hand instead of with platform formatters so the rules are the same as on iOS
 * and tested on the JVM. Numbers use the locale's decimal and grouping separators.
 */
data class UnitFormatter(
    val system: UnitSystem,
    val decimalSeparator: String = ".",
    val groupingSeparator: String = ",",
) {
    constructor(system: UnitSystem, locale: Locale) : this(
        system,
        DecimalFormatSymbols.getInstance(locale).decimalSeparator.toString(),
        DecimalFormatSymbols.getInstance(locale).groupingSeparator.toString(),
    )

    /**
     * A size, e.g. a pipe diameter or a room height: `25 mm`, `42 cm`, `2.6 m`; `3/4 in`-style
     * fractions are not used: `1 in`, `5 ft 3 in`.
     */
    fun length(metres: Double): String {
        val sign = if (metres < 0) "-" else ""
        val value = abs(metres)
        return when (system) {
            UnitSystem.METRIC -> {
                if (value < 0.1) {
                    val millimetres = (value * 1000).roundedHalfAway()
                    if (millimetres < 100) return sign + number(millimetres, fractionDigits = 0) + " mm"
                }
                if (value < 1) {
                    val centimetres = (value * 100).roundedHalfAway()
                    if (centimetres < 100) return sign + number(centimetres, fractionDigits = 0) + " cm"
                }
                sign + number(value, fractionDigits = 2) + " m"
            }
            UnitSystem.IMPERIAL ->
                sign + feetAndInches(value, inchFractionDigits = if (value < METRES_PER_INCH * 12) 1 else 0)
        }
    }

    /** How far away something is, for "Locate in AR": `85 cm`, `3.2 m`; `2 ft 9 in`, `32 ft`. */
    fun distance(metres: Double): String {
        val value = max(0.0, metres)
        return when (system) {
            UnitSystem.METRIC -> {
                val centimetres = (value * 100).roundedHalfAway()
                if (centimetres < 100) return number(centimetres, fractionDigits = 0) + " cm"
                number(value, fractionDigits = if (value < 10) 1 else 0) + " m"
            }
            UnitSystem.IMPERIAL -> {
                val feet = value / (METRES_PER_INCH * 12)
                if (feet < 10) return feetAndInches(value, inchFractionDigits = 0)
                number(feet.roundedHalfAway(), fractionDigits = 0) + " ft"
            }
        }
    }

    /** A floor area: `186 m²`, `2,006 sq ft`. */
    fun area(squareMetres: Double): String = when (system) {
        UnitSystem.METRIC -> number(squareMetres.roundedHalfAway(), fractionDigits = 0, grouping = true) + " m²"
        UnitSystem.IMPERIAL ->
            number((squareMetres * SQUARE_FEET_PER_SQUARE_METRE).roundedHalfAway(), fractionDigits = 0, grouping = true) + " sq ft"
    }

    /** Size of one "Fine-tune" nudge in AR: `1 cm` or `0.4 in`. */
    fun nudge(metres: Double): String = when (system) {
        UnitSystem.METRIC -> number((metres * 100 * 10).roundedHalfAway() / 10, fractionDigits = 1) + " cm"
        UnitSystem.IMPERIAL -> number((metres / METRES_PER_INCH * 10).roundedHalfAway() / 10, fractionDigits = 1) + " in"
    }

    /** Degrees with at most one decimal: `0.5°`, `90°`. */
    fun angle(degrees: Double): String = number((degrees * 10).roundedHalfAway() / 10, fractionDigits = 1) + "°"

    private fun feetAndInches(metres: Double, inchFractionDigits: Int): String {
        val scale = 10.0.pow(inchFractionDigits)
        val totalInches = (metres / METRES_PER_INCH * scale).roundedHalfAway() / scale
        if (totalInches < 12) return number(totalInches, fractionDigits = inchFractionDigits) + " in"
        var feet = floor(totalInches / 12)
        var inches = (totalInches - feet * 12).roundedHalfAway()
        if (inches >= 12) {
            feet += 1
            inches -= 12
        }
        val feetText = number(feet, fractionDigits = 0, grouping = true) + " ft"
        return if (inches == 0.0) feetText else feetText + " " + number(inches, fractionDigits = 0) + " in"
    }

    /** Rounds to [fractionDigits] and drops trailing zeros: 2.50 → `2.5`, 3.00 → `3`. */
    internal fun number(value: Double, fractionDigits: Int, grouping: Boolean = false): String {
        val scale = 10.0.pow(fractionDigits)
        val rounded = (abs(value) * scale).roundedHalfAway()
        val integerPart = floor(rounded / scale)
        var fraction = (rounded - integerPart * scale).roundedHalfAway().toInt()

        var integerDigits = integerPart.toLong().toString()
        if (grouping && integerDigits.length > 3) {
            val groups = ArrayList<String>()
            var end = integerDigits.length
            while (end > 0) {
                val start = max(0, end - 3)
                groups.add(0, integerDigits.substring(start, end))
                end = start
            }
            integerDigits = groups.joinToString(groupingSeparator)
        }

        var fractionText = ""
        if (fractionDigits > 0 && fraction > 0) {
            val digits = fraction.toString().padStart(fractionDigits, '0').trimEnd('0')
            fraction = digits.toIntOrNull() ?: 0
            fractionText = decimalSeparator + digits
        }
        val negative = value < 0 && (integerPart > 0 || fraction > 0)
        return (if (negative) "-" else "") + integerDigits + fractionText
    }

    companion object {
        const val METRES_PER_INCH = 0.0254
        const val SQUARE_FEET_PER_SQUARE_METRE = 10.763_910_416_709_722
    }
}

/** Swift's `rounded()`: to the nearest integer, halves away from zero (not Kotlin's half-even `round`). */
internal fun Double.roundedHalfAway(): Double {
    if (isNaN() || isInfinite()) return this
    val whole = truncate(this)
    return if (abs(this - whole) >= 0.5) whole + sign(this) else whole
}
