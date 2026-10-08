package com.getinsiteview.modelkit

import java.security.MessageDigest

/**
 * SHA-256 for downloaded chunks. The manifest lists each file's `sha256` as lower-case hex, and
 * the chunk cache is keyed by it (docs/PLAN.md §3 "Loading a building").
 */
object Checksum {
    private val hexDigits = "0123456789abcdef".toCharArray()

    /** Lower-case hex SHA-256 of `data`. */
    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val hex = CharArray(digest.size * 2)
        for ((i, byte) in digest.withIndex()) {
            val value = byte.toInt() and 0xFF
            hex[i * 2] = hexDigits[value ushr 4]
            hex[i * 2 + 1] = hexDigits[value and 0x0F]
        }
        return String(hex)
    }
}
