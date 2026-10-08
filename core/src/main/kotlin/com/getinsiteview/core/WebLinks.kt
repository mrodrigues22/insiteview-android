package com.getinsiteview.core

import java.net.URI

/**
 * Web pages the app opens in a Custom Tab (routes kept by the web app, master PLAN §7 "Web pages
 * the API links to" and the web plan's route list). None of them sells anything: billing stays on
 * the web and the app has no purchase links (master PLAN §11).
 *
 * @param baseURL `WEB_BASE_URL`. The web picks the language from `Accept-Language`, so the paths
 *   carry no locale prefix.
 */
data class WebLinks(val baseURL: URI) {
    /**
     * "Forgot password?": the web asks for the email and sends the reset link, which also opens
     * on the web (`/reset-password`).
     */
    val forgotPassword: URI get() = page("forgot-password")

    /** "+ Add building": the web upload flow (IOS-M3-02). */
    val addBuilding: URI get() = page("app/buildings/new")

    val terms: URI get() = page("terms")

    val privacy: URI get() = page("privacy")

    /** Help: the FAQ. */
    val help: URI get() = page("faq")

    private fun page(path: String): URI {
        val basePath = baseURL.path.orEmpty().removeSuffix("/")
        return URI(baseURL.scheme, baseURL.authority, "$basePath/$path", null, null)
    }
}

/**
 * The app's Google Play page, for "Update" when the API turns this version away (`client.outdated`,
 * master PLAN §9 "Schema and client compatibility"). iOS's `AppStoreLink` (docs/PLAN.md §6).
 * `PLAY_STORE_PACKAGE` is the app's applicationId; without one there is no link and screens hide
 * the button.
 */
@ConsistentCopyVisibility
data class PlayStoreLink private constructor(val packageName: String) {
    /** Opens the Play Store app directly. */
    val appURL: URI get() = URI("market://details?id=$packageName")

    /** The same page on the web, for when no app handles `market://`. */
    val webURL: URI get() = URI("https://play.google.com/store/apps/details?id=$packageName")

    companion object {
        private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        /**
         * `null` for a missing, empty or malformed package name (Android's applicationId rules: at
         * least two dot-separated segments, each starting with a letter, then letters, digits or `_`).
         */
        fun of(packageName: String?): PlayStoreLink? {
            val trimmed = packageName?.trim(' ', '\t')
            if (trimmed.isNullOrEmpty() || !packagePattern.matches(trimmed)) return null
            return PlayStoreLink(trimmed)
        }
    }
}

/**
 * The terms version accepted when an account is created (register, or a first Apple, Google or
 * Microsoft sign-in). Same value as the web's `TERMS_VERSION`; the API stores it with the date.
 */
object TermsVersion {
    const val CURRENT = "2026-09"
}
