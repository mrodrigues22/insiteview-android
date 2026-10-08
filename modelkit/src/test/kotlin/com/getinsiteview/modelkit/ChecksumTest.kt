package com.getinsiteview.modelkit

import kotlin.test.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("Checksum")
class ChecksumTest {
    @ParameterizedTest
    @CsvSource(
        "'', e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "abc, ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
    )
    fun `SHA-256 as lower-case hex`(input: String?, expected: String) {
        assertEquals(expected, Checksum.sha256Hex((input ?: "").encodeToByteArray()))
    }
}
