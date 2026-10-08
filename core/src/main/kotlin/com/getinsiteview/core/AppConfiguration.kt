package com.getinsiteview.core

import java.net.URI
import java.net.URISyntaxException

/**
 * Per-environment settings from the Gradle build (`BuildConfig` fields per build type / flavor,
 * docs/PLAN.md §1). The app holds no secrets, so nothing here is sensitive.
 *
 * iOS reads these from Info.plist; on Android the app builds the configuration with [from] (which
 * validates the values) or the constructor (already-parsed values).
 */
data class AppConfiguration(
    val apiBaseURL: URI,
    val webBaseURL: URI,
    /** `versionName`, e.g. `1.2.0`, sent as `X-Client-Version`; `null` when there's none (tests). */
    val appVersion: String? = null,
    /** The Google Play page for "Update"; `null` when `PLAY_STORE_PACKAGE` isn't set. */
    val playStore: PlayStoreLink? = null,
) {
    /** The `BuildConfig` field each value comes from. */
    enum class Key(val raw: String) {
        API_BASE_URL("API_BASE_URL"),
        WEB_BASE_URL("WEB_BASE_URL"),

        /** The app's package on Google Play (its applicationId); empty until it's published. */
        PLAY_STORE_PACKAGE("PLAY_STORE_PACKAGE"),

        /** The marketing version (`versionName`), sent as `X-Client-Version`. */
        APP_VERSION("VERSION_NAME"),
    }

    sealed class ConfigurationError(message: String) : Exception(message) {
        data class Missing(val key: Key) : ConfigurationError("${key.raw} is missing from BuildConfig.")

        data class InvalidURL(val key: Key, val value: String) :
            ConfigurationError("${key.raw} is not an http(s) URL: \"$value\".")
    }

    companion object {
        /**
         * Builds the configuration from raw `BuildConfig` strings.
         *
         * @throws ConfigurationError.Missing when a base URL is null or empty.
         * @throws ConfigurationError.InvalidURL when a base URL isn't an absolute http(s) URL with a host.
         */
        fun from(
            apiBaseURL: String?,
            webBaseURL: String?,
            appVersion: String? = null,
            playStorePackage: String? = null,
        ): AppConfiguration = AppConfiguration(
            apiBaseURL = url(Key.API_BASE_URL, apiBaseURL),
            webBaseURL = url(Key.WEB_BASE_URL, webBaseURL),
            appVersion = appVersion?.trim(' ', '\t')?.ifEmpty { null },
            playStore = PlayStoreLink.of(playStorePackage),
        )

        private fun url(key: Key, value: String?): URI {
            if (value.isNullOrEmpty()) throw ConfigurationError.Missing(key)
            val uri = try {
                URI(value)
            } catch (_: URISyntaxException) {
                throw ConfigurationError.InvalidURL(key, value)
            }
            val scheme = uri.scheme?.lowercase()
            if ((scheme != "https" && scheme != "http") || uri.host.isNullOrEmpty()) {
                throw ConfigurationError.InvalidURL(key, value)
            }
            return uri
        }
    }
}
