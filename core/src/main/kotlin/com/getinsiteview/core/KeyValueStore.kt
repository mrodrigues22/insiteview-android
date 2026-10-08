package com.getinsiteview.core

import java.util.concurrent.ConcurrentHashMap

/**
 * Small on-device key-value storage, the stand-in for iOS's `UserDefaults` (Android-only file:
 * iOS has no counterpart). The app implements it with a Preferences DataStore
 * (`stringPreferencesKey(key)`); tests and previews use [InMemoryKeyValueStore].
 *
 * Values are strings; callers that store structured values write JSON.
 */
interface KeyValueStore {
    suspend fun getString(key: String): String?

    suspend fun putString(key: String, value: String)

    suspend fun remove(key: String)
}

/** A [KeyValueStore] kept in memory, for tests and previews. Thread-safe. */
class InMemoryKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    private val values = ConcurrentHashMap(initial)

    /** A copy of everything stored. */
    val snapshot: Map<String, String> get() = HashMap(values)

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
