package com.getinsiteview.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class UnitsTest {
    private val metric = UnitFormatter(system = UnitSystem.METRIC)
    private val imperial = UnitFormatter(system = UnitSystem.IMPERIAL)

    @Test
    fun `Default unit system from the region`() {
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.default(region = "US"))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.default(region = "us"))
        assertEquals(UnitSystem.METRIC, UnitSystem.default(region = "BR"))
        assertEquals(UnitSystem.METRIC, UnitSystem.default(region = "GB"))
        assertEquals(UnitSystem.METRIC, UnitSystem.default(region = null))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.default(Locale.forLanguageTag("en-US")))
        assertEquals(UnitSystem.METRIC, UnitSystem.default(Locale.forLanguageTag("pt-BR")))
    }

    @Test
    fun `Decodes the API's values - unknown values are metric`() {
        assertEquals(
            listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL, UnitSystem.IMPERIAL, UnitSystem.METRIC),
            Json.decodeFromString<List<UnitSystem>>("""["Metric","Imperial","imperial","Nautical"]"""),
        )
        assertEquals("""["Imperial"]""", Json.encodeToString(listOf(UnitSystem.IMPERIAL)))
    }

    @ParameterizedTest
    @CsvSource(
        "0.025, 25 mm", "0.0999, 10 cm", "0.42, 42 cm", "0.999, 1 m", "1, 1 m", "2.6, 2.6 m",
        "12.346, 12.35 m", "-0.5, -50 cm", "0, 0 mm",
    )
    fun `Metric lengths`(metres: Double, expected: String) {
        assertEquals(expected, metric.length(metres))
    }

    @ParameterizedTest
    @CsvSource(
        "0.0254, 1 in", "0.1, 3.9 in", "0.3048, 1 ft", "1.6, 5 ft 3 in", "3.048, 10 ft",
        "0.3017, 11.9 in", "0.3040, 1 ft",
    )
    fun `Imperial lengths`(metres: Double, expected: String) {
        assertEquals(expected, imperial.length(metres))
    }

    @ParameterizedTest
    @CsvSource(
        "METRIC, 0.85, 85 cm", "METRIC, 3.24, 3.2 m", "METRIC, 12.6, 13 m",
        "IMPERIAL, 0.85, 2 ft 9 in", "IMPERIAL, 9.8, 32 ft", "METRIC, -1, 0 cm",
    )
    fun `Distances for Locate in AR`(system: UnitSystem, metres: Double, expected: String) {
        assertEquals(expected, UnitFormatter(system = system).distance(metres))
    }

    @Test
    fun `Areas with grouping`() {
        assertEquals("186 m²", metric.area(186.4))
        assertEquals("1,235 m²", metric.area(1234.5))
        assertEquals("2,006 sq ft", imperial.area(186.4))
        val brazil = UnitFormatter(system = UnitSystem.METRIC, decimalSeparator = ",", groupingSeparator = ".")
        assertEquals("12.345 m²", brazil.area(12_345.0))
        assertEquals("2,6 m", brazil.length(2.6))
    }

    @Test
    fun `Separators from the locale`() {
        val formatter = UnitFormatter(system = UnitSystem.METRIC, locale = Locale.forLanguageTag("de-DE"))
        assertEquals("1,25 m", formatter.length(1.25))
        assertEquals("1.25 m", UnitFormatter(system = UnitSystem.METRIC, locale = Locale.forLanguageTag("en-US")).length(1.25))
    }

    @Test
    fun `Nudge steps and angles`() {
        assertEquals("1 cm", metric.nudge(0.01))
        assertEquals("0.4 in", imperial.nudge(0.01))
        assertEquals("0.5°", metric.angle(degrees = 0.5))
        assertEquals("90°", metric.angle(degrees = 90.0))
    }
}
