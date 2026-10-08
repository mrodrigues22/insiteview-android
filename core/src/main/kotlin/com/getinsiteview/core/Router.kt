package com.getinsiteview.core

import java.net.URI
import java.net.URISyntaxException

/** What an Insite View URL points at. */
sealed interface DeepLink {
    /** `/b/{code}` or `/b/{code}/{plate}`: a Building QR, optionally printed on a numbered plate. */
    data class Building(val code: BuildingCode, override val plate: Int?) : DeepLink

    /** `/a/{token}`: a temporary access link. */
    data class AccessLink(val token: String) : DeepLink

    /** The plate number when the link came from a numbered plate. */
    val plate: Int?
        get() = null
}

/**
 * Turns QR, App Link and scanned URLs into a [DeepLink].
 *
 * The app and the in-app scanner share this one parser (docs/PLAN.md §2).
 * Only our hosts and path patterns are accepted; anything else returns `null`.
 *
 * @param additionalHost accepted besides production and staging, normally the host of
 *   `WEB_BASE_URL` (e.g. `localhost` or a tunnel host when running locally).
 */
class Router(additionalHost: String? = null) {
    /** Lower-cased hosts this router accepts. */
    val hosts: Set<String> = buildSet {
        add(PRODUCTION_HOST)
        add(STAGING_HOST)
        if (!additionalHost.isNullOrEmpty()) add(additionalHost.lowercase())
    }

    /** Accepts the production and staging hosts plus the host of [webBaseURL]. */
    constructor(webBaseURL: URI) : this(additionalHost = webBaseURL.host)

    fun deepLink(url: URI): DeepLink? {
        val scheme = url.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = url.host?.lowercase() ?: return null
        if (host !in hosts) return null

        val segments = (url.path ?: return null).split('/').filter { it.isNotEmpty() }
        return when {
            segments.size == 2 && segments[0] == "b" ->
                BuildingCode.parse(segments[1])?.let { DeepLink.Building(it, plate = null) }
            segments.size == 3 && segments[0] == "b" -> {
                val code = BuildingCode.parse(segments[1]) ?: return null
                val plate = plateNumber(segments[2]) ?: return null
                DeepLink.Building(code, plate)
            }
            segments.size == 2 && segments[0] == "a" -> {
                val token = segments[1]
                if (!token.all(::isTokenCharacter)) return null
                DeepLink.AccessLink(token)
            }
            else -> null
        }
    }

    /**
     * The payload of a scanned QR code. Only our URLs are accepted: anything else (other sites,
     * plain text, a bare building code) returns `null`, which the scanner shows as
     * "This isn't an Insite View code".
     */
    fun deepLinkForScannedText(text: String): DeepLink? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val url = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return null
        }
        return deepLink(url)
    }

    companion object {
        const val PRODUCTION_HOST = "getinsiteview.com"
        const val STAGING_HOST = "staging.getinsiteview.com"

        /** Plate numbers are positive decimal integers (`2`, not `+2` or `02x`). */
        internal fun plateNumber(segment: String): Int? {
            if (segment.isEmpty() || !segment.all { it in '0'..'9' }) return null
            val number = segment.toIntOrNull() ?: return null
            return if (number > 0) number else null
        }

        /** Tokens are opaque; only URL-unreserved characters (RFC 3986) are allowed. */
        private fun isTokenCharacter(character: Char): Boolean =
            character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-._~"
    }
}
