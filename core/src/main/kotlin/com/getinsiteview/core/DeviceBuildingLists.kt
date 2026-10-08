package com.getinsiteview.core

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val storeJson = Json { ignoreUnknownKeys = true }

private inline fun <T> decodeOrNull(decode: () -> T): T? = try {
    decode()
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Buildings starred on this device (the Buildings tab's Favorites, A-02). Kept in the device's
 * [KeyValueStore] for the MVP, not synced to the account. Codes are stored in canonical form,
 * as a sorted JSON array of strings.
 */
class FavoriteBuildings(private val store: KeyValueStore) {
    suspend fun codes(): Set<String> {
        val stored = store.getString(KEY) ?: return emptySet()
        return decodeOrNull { storeJson.decodeFromString(ListSerializer(String.serializer()), stored) }?.toSet()
            ?: emptySet()
    }

    suspend fun contains(code: BuildingCode): Boolean = code.raw in codes()

    /** Stars or unstars a building; returns whether it's a favorite now. */
    suspend fun toggle(code: BuildingCode): Boolean {
        val codes = codes().toMutableSet()
        val isFavorite = code.raw !in codes
        if (isFavorite) codes.add(code.raw) else codes.remove(code.raw)
        store.putString(KEY, storeJson.encodeToString(ListSerializer(String.serializer()), codes.sorted()))
        return isFavorite
    }

    suspend fun removeAll() {
        store.remove(KEY)
    }

    companion object {
        const val KEY = "com.getinsiteview.favorites"
    }
}

/**
 * Buildings opened on this device, newest first (the Buildings tab's Recent, A-02). Stored as a
 * JSON array of `{"code": "8K29X7", "at": <epoch milliseconds>}`.
 */
class RecentBuildings(private val store: KeyValueStore) {
    data class Entry(val code: BuildingCode, val openedAt: Instant)

    @Serializable
    private data class Stored(val code: String, val at: Long)

    /** Newest first, at most [LIMIT]. */
    suspend fun entries(): List<Entry> {
        val stored = store.getString(KEY) ?: return emptyList()
        val items = decodeOrNull { storeJson.decodeFromString(ListSerializer(Stored.serializer()), stored) } ?: return emptyList()
        return items
            .mapNotNull { item -> BuildingCode.parse(item.code)?.let { Entry(it, Instant.ofEpochMilli(item.at)) } }
            .sortedByDescending { it.openedAt }
    }

    /** The building was opened: it moves to the top. */
    suspend fun record(code: BuildingCode, at: Instant = Instant.now()) {
        val list = listOf(Entry(code, at)) + entries().filter { it.code != code }
        val stored = list.take(LIMIT).map { Stored(it.code.raw, it.openedAt.toEpochMilli()) }
        store.putString(KEY, storeJson.encodeToString(ListSerializer(Stored.serializer()), stored))
    }

    suspend fun removeAll() {
        store.remove(KEY)
    }

    companion object {
        const val LIMIT = 20
        const val KEY = "com.getinsiteview.recents"
    }
}
