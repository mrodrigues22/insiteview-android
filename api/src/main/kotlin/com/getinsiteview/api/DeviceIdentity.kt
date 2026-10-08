package com.getinsiteview.api

import com.getinsiteview.core.KeyValueStore
import java.util.UUID

/**
 * Where the device id lives: a Preferences DataStore in the app (iOS: the Keychain in the app,
 * `UserDefaults` in the App Clip), memory in tests (docs/PLAN.md §3 "Device ID").
 */
interface DeviceIdStore {
    suspend fun read(): String?

    suspend fun write(id: String)
}

/**
 * The random per-install id sent as `X-Device-Id` and as `deviceId` when starting a visit. Not an
 * advertising id (master PLAN §10).
 */
object DeviceIdentity {
    /** What the API accepts: 8–64 ASCII letters, digits, `-` or `_`. */
    fun isValid(id: String): Boolean =
        id.length in 8..64 && id.all { it.code < 128 && (it.isLetterOrDigit() || it == '-' || it == '_') }

    /** The stored id, or a new random UUID that is stored first. */
    suspend fun current(from: DeviceIdStore, makeId: () -> String = { UUID.randomUUID().toString().lowercase() }): String {
        val stored = from.read()
        if (stored != null && isValid(stored)) return stored
        val id = makeId()
        from.write(id)
        return id
    }
}

/**
 * [DeviceIdStore] in a [KeyValueStore] (iOS `UserDefaultsDeviceIDStore`): the app passes its
 * DataStore-backed store.
 */
class KeyValueDeviceIdStore(
    private val store: KeyValueStore,
    private val key: String = "com.getinsiteview.deviceId",
) : DeviceIdStore {
    override suspend fun read(): String? = store.getString(key)

    override suspend fun write(id: String) {
        store.putString(key, id)
    }
}

/** [DeviceIdStore] in memory, for tests and previews. */
class InMemoryDeviceIdStore(id: String? = null) : DeviceIdStore {
    @Volatile
    private var id: String? = id

    override suspend fun read(): String? = id

    override suspend fun write(id: String) {
        this.id = id
    }
}
