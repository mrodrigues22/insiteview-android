package com.getinsiteview.core

import java.net.URLDecoder
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Camera permission, as the app reads it (`checkSelfPermission` and `shouldShowRequestPermissionRationale`). */
enum class CameraAccess {
    NOT_DETERMINED,
    AUTHORIZED,
    DENIED,

    /** Device policy: the user can't allow it here. */
    RESTRICTED,
}

/** What "See in AR" shows before the camera (A-05 homeowner flow, IOS-M2-07). */
sealed interface ARPreflightStep {
    /** The first-time "Point · Align · Explore" explainer (skippable). */
    data object Explainer : ARPreflightStep

    /** "Insite View needs the camera for AR" → the system permission prompt. */
    data object CameraExplainer : ARPreflightStep

    /** Denied or restricted: a Settings link (restricted can't be changed there, so no link). */
    data class CameraDenied(val canOpenSettings: Boolean) : ARPreflightStep

    data object Ready : ARPreflightStep

    companion object {
        fun next(hasSeenExplainer: Boolean, camera: CameraAccess): ARPreflightStep {
            if (!hasSeenExplainer) return Explainer
            return when (camera) {
                CameraAccess.NOT_DETERMINED -> CameraExplainer
                CameraAccess.AUTHORIZED -> Ready
                CameraAccess.DENIED -> CameraDenied(canOpenSettings = true)
                CameraAccess.RESTRICTED -> CameraDenied(canOpenSettings = false)
            }
        }
    }
}

/** The explainer's three frames. */
enum class ARExplainerFrame {
    POINT,
    ALIGN,
    EXPLORE;

    val id: Int get() = ordinal

    val next: ARExplainerFrame? get() = entries.getOrNull(ordinal + 1)
}

/** Once-per-device guest flags, in the device's [KeyValueStore] (`"true"` when set). */
class GuestPreferences(private val store: KeyValueStore) {
    enum class Key(val raw: String) {
        SEEN_AR_EXPLAINER("com.getinsiteview.guest.seenARExplainer"),
        SEEN_SAFETY_NOTE("com.getinsiteview.guest.seenSafetyNote"),
    }

    /** The "Point · Align · Explore" explainer was shown (finished or skipped). */
    suspend fun hasSeenARExplainer(): Boolean = flag(Key.SEEN_AR_EXPLAINER)

    suspend fun setHasSeenARExplainer(value: Boolean) = setFlag(Key.SEEN_AR_EXPLAINER, value)

    /**
     * "The model shows the design. Always confirm before drilling or cutting." was shown once
     * (it stays reachable from the AR menu).
     */
    suspend fun hasSeenSafetyNote(): Boolean = flag(Key.SEEN_SAFETY_NOTE)

    suspend fun setHasSeenSafetyNote(value: Boolean) = setFlag(Key.SEEN_SAFETY_NOTE, value)

    private suspend fun flag(key: Key) = store.getString(key.raw) == "true"

    private suspend fun setFlag(key: Key, value: Boolean) = store.putString(key.raw, value.toString())
}

/**
 * The web → app handoff (docs/PLAN.md §3 "Guest entry", §6: Android has no App Clip). The web's
 * "Get the app" opens Google Play with `referrer=code=8K29X7&plate=2`; on its first launch the app
 * reads the Play Install Referrer once ([saveReferrer]) and opens that building ([take], once),
 * as the iOS app does with the App Clip's App Group handoff.
 *
 * [take] ignores handoffs older than [MAX_AGE] (the user moved on), measured from the time passed
 * to [save] / [saveReferrer].
 */
class BuildingHandoff(private val store: KeyValueStore) {
    @Serializable
    private data class Stored(val code: String, val plate: Int? = null, val savedAt: Long)

    /** Remember this building (and plate) for the next [take]. */
    suspend fun save(code: BuildingCode, plate: Int?, at: Instant = Instant.now()) {
        store.putString(KEY, json.encodeToString(Stored.serializer(), Stored(code.raw, plate, at.toEpochMilli())))
    }

    /** Whether [saveReferrer] already ran on this install. */
    suspend fun hasReadReferrer(): Boolean = store.getString(REFERRER_READ_KEY) == "true"

    /**
     * Saves the building the Install Referrer names, once per install: later calls (the Play API
     * keeps returning the same referrer for 90 days) do nothing and return `null`. Organic installs
     * and referrers without a valid code are marked read and save nothing.
     *
     * @param referrer `ReferrerDetails.installReferrer`.
     * @param at when the user asked for the app: `referrerClickTimestampSeconds` when it's set,
     *   else `installBeginTimestampSeconds`, else now.
     * @return the building saved, if any.
     */
    suspend fun saveReferrer(referrer: String?, at: Instant = Instant.now()): DeepLink.Building? {
        if (hasReadReferrer()) return null
        val link = parseReferrer(referrer)
        if (link != null) save(link.code, link.plate, at)
        store.putString(REFERRER_READ_KEY, "true")
        return link
    }

    /** The building to open, read once. `null` when there's none or it's too old. */
    suspend fun take(now: Instant = Instant.now()): DeepLink? {
        val raw = store.getString(KEY) ?: return null
        store.remove(KEY)
        val value = try {
            json.decodeFromString(Stored.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        val code = BuildingCode.parse(value.code) ?: return null
        if ((now.toEpochMilli() - value.savedAt).milliseconds > MAX_AGE) return null
        return DeepLink.Building(code, plate = value.plate?.takeIf { it > 0 })
    }

    companion object {
        /** Older handoffs are ignored: the user moved on. */
        val MAX_AGE: Duration = 7.days

        const val KEY = "com.getinsiteview.handoff"
        const val REFERRER_READ_KEY = "com.getinsiteview.handoff.referrerRead"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses an Install Referrer: a URL-encoded query such as `code=8K29X7&plate=2`. `code` is
         * required and must be a [BuildingCode] (raw or display form); `plate` is optional and
         * dropped unless it's a positive decimal integer; other parameters are ignored, and the
         * first occurrence of a parameter wins. A referrer encoded once more
         * (`code%3D8K29X7%26plate%3D2`) is accepted too.
         */
        fun parseReferrer(referrer: String?): DeepLink.Building? {
            var query = referrer?.trim()?.removePrefix("?") ?: return null
            if ('=' !in query && query.contains("%3D", ignoreCase = true)) {
                query = decode(query) ?: return null
            }
            val parameters = LinkedHashMap<String, String>()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val separator = pair.indexOf('=')
                val name = decode(if (separator < 0) pair else pair.substring(0, separator)) ?: continue
                val value = if (separator < 0) "" else decode(pair.substring(separator + 1)) ?: continue
                parameters.putIfAbsent(name, value)
            }
            val code = parameters["code"]?.let(BuildingCode::parse) ?: return null
            return DeepLink.Building(code, plate = parameters["plate"]?.let(Router::plateNumber))
        }

        private fun decode(string: String): String? = try {
            URLDecoder.decode(string, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
