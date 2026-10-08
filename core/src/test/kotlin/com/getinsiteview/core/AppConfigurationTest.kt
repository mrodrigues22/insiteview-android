package com.getinsiteview.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AppConfigurationTest {
    @Test
    fun `Reads the base URLs from BuildConfig`() {
        val configuration = AppConfiguration.from(
            apiBaseURL = "https://api.staging.getinsiteview.com",
            webBaseURL = "https://staging.getinsiteview.com",
        )
        assertEquals("https://api.staging.getinsiteview.com", configuration.apiBaseURL.toString())
        assertEquals("https://staging.getinsiteview.com", configuration.webBaseURL.toString())
        assertNull(configuration.appVersion)
        assertNull(configuration.playStore)
    }

    @Test
    fun `App version and Play Store package, when the build has them`() {
        val configuration = AppConfiguration.from(
            apiBaseURL = "https://api.getinsiteview.com",
            webBaseURL = "https://getinsiteview.com",
            appVersion = "1.2.0",
            playStorePackage = "com.getinsiteview.android",
        )
        assertEquals("1.2.0", configuration.appVersion)
        assertEquals("com.getinsiteview.android", configuration.playStore?.packageName)
        // The placeholder: an empty PLAY_STORE_PACKAGE until the app is on Google Play.
        val placeholder = AppConfiguration.from(
            apiBaseURL = "https://api.getinsiteview.com",
            webBaseURL = "https://getinsiteview.com",
            appVersion = " ",
            playStorePackage = "",
        )
        assertNull(placeholder.appVersion)
        assertNull(placeholder.playStore)
    }

    @Test
    fun `Missing or empty keys`() {
        assertEquals(
            AppConfiguration.ConfigurationError.Missing(AppConfiguration.Key.API_BASE_URL),
            assertFailsWith<AppConfiguration.ConfigurationError> {
                AppConfiguration.from(apiBaseURL = null, webBaseURL = "https://getinsiteview.com")
            },
        )
        assertEquals(
            AppConfiguration.ConfigurationError.Missing(AppConfiguration.Key.WEB_BASE_URL),
            assertFailsWith<AppConfiguration.ConfigurationError> {
                AppConfiguration.from(apiBaseURL = "https://api.getinsiteview.com", webBaseURL = "")
            },
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["https:", "api.getinsiteview.com", "ftp://x.y"])
    fun `A URL that isn't an absolute http(s) URL is rejected`(value: String) {
        assertEquals(
            AppConfiguration.ConfigurationError.InvalidURL(AppConfiguration.Key.API_BASE_URL, value),
            assertFailsWith<AppConfiguration.ConfigurationError> {
                AppConfiguration.from(apiBaseURL = value, webBaseURL = "https://getinsiteview.com")
            },
        )
    }
}
