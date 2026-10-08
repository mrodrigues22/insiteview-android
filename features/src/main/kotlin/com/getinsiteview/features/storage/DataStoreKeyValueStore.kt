package com.getinsiteview.features.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.getinsiteview.core.KeyValueStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * The app's one Preferences DataStore file (`insiteview.preferences_pb`). DataStore allows a single
 * instance per file in a process, so every store below shares this delegate.
 */
internal val Context.insiteViewDataStore: DataStore<Preferences> by preferencesDataStore(name = "insiteview")

/**
 * [KeyValueStore] in a Preferences DataStore: iOS's `UserDefaults` (device id, guest flags,
 * favourites, recents, units, the Install Referrer handoff and the encrypted tokens). A file that
 * can't be read reads as empty, as a missing `UserDefaults` value would.
 */
class DataStoreKeyValueStore(private val dataStore: DataStore<Preferences>) : KeyValueStore {
    override suspend fun getString(key: String): String? =
        dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()[stringPreferencesKey(key)]

    override suspend fun putString(key: String, value: String) {
        dataStore.edit { it[stringPreferencesKey(key)] = value }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    companion object {
        /** The app's store (application context, so it outlives activities). */
        fun of(context: Context): DataStoreKeyValueStore =
            DataStoreKeyValueStore(context.applicationContext.insiteViewDataStore)
    }
}
