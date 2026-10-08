package com.getinsiteview.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BuildingCodeTest {
    @ParameterizedTest
    @ValueSource(strings = ["8K29X7", "8k29x7", "IV-8K29-X7", "iv-8k29-x7", "  8K29X7 ", "Iv-8K29-x7"])
    fun `Parses raw and display forms in any case`(input: String) {
        val code = assertNotNull(BuildingCode.parse(input))
        assertEquals("8K29X7", code.raw)
    }

    @Test
    fun `Formats as IV-XXXX-XX`() {
        assertEquals("IV-8K29-X7", assertNotNull(BuildingCode.parse("8k29x7")).formatted)
        assertEquals("IV-TEST-01", assertNotNull(BuildingCode.parse("TEST01")).formatted)
    }

    @Test
    fun `Accepts every Crockford base32 symbol`() {
        assertEquals(32, BuildingCode.alphabet.size)
        for (symbol in "0123456789ABCDEFGHJKMNPQRSTVWXYZ") {
            assertNotNull(BuildingCode.parse("00000$symbol"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["I", "L", "O", "U", "i", "l", "o", "u"])
    fun `Rejects I, L, O and U`(letter: String) {
        assertNull(BuildingCode.parse("8K29X$letter"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "8K29X", "8K29X7A", "8K29-X7", "IV8K29X7", "IV-8K2-9X7", "IV-8K29X7", "XX-8K29-X7", "UW-8K29-X7",
            "IV-8K29-X7-", "8K 29X7", "8K29X7\n9", "8K29X#", "８K29X7", "8K29Xſ",
        ],
    )
    fun `Rejects malformed input`(input: String) {
        assertNull(BuildingCode.parse(input))
    }

    @Test
    fun `Equality uses the canonical form`() {
        assertEquals(BuildingCode.parse("8K29X7"), BuildingCode.parse("iv-8k29-x7"))
    }
}
