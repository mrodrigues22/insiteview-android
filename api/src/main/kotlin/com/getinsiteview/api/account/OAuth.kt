package com.getinsiteview.api.account

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * A social sign-in provider for the browser round trip (`GET /v1/auth/oauth/providers`). On
 * Android every provider, Apple included, signs in through a Custom Tab (docs/PLAN.md §6: native
 * Sign in with Apple exists only on Apple platforms).
 */
@JvmInline
value class OAuthProvider private constructor(val raw: String) {
    val id: String get() = raw

    override fun toString(): String = raw

    companion object {
        fun of(raw: String): OAuthProvider = OAuthProvider(raw.lowercase())

        val GOOGLE = OAuthProvider("google")
        val MICROSOFT = OAuthProvider("microsoft")
        val APPLE = OAuthProvider("apple")

        /**
         * The providers the app offers, in button order, from what the server has keys for. Names
         * the app doesn't know are left out. (iOS leaves Apple out too: it signs in natively.)
         */
        fun offered(names: List<String>): List<OAuthProvider> {
            val available = names.map { it.lowercase() }.toSet()
            return listOf(GOOGLE, MICROSOFT, APPLE).filter { it.raw in available }
        }
    }
}

/**
 * The browser sign-in round trip (master PLAN §10): open the API's start URL in a Custom Tab; the
 * provider comes back to `insiteview://auth/callback` with a one-time `code` for
 * `POST /v1/auth/oauth/exchange`, or an `error`.
 */
object OAuthFlow {
    /** The scheme the app's callback activity handles. The API only redirects there (`OAuth:NativeReturnUrl`). */
    const val CALLBACK_SCHEME = "insiteview"
    val callbackURL: URI = URI("insiteview://auth/callback")

    /**
     * `{API}/v1/auth/oauth/{provider}/start?returnTo=insiteview://auth/callback&terms={version}`.
     * `terms` records the accepted terms when the sign-in creates an account.
     */
    fun startURL(apiBaseURL: URI, provider: OAuthProvider, termsVersion: String?): URI {
        val base = apiBaseURL.toString().substringBefore('?').substringBefore('#').trimEnd('/')
        val items = mutableListOf("returnTo" to callbackURL.toString())
        if (!termsVersion.isNullOrEmpty()) items.add("terms" to termsVersion)
        // `:` and `/` are allowed in a query, but the API reads returnTo more safely encoded.
        val query = items.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
        return URI("$base/v1/auth/oauth/${provider.raw}/start?$query")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    sealed interface Callback {
        /** Exchange this with `POST /v1/auth/oauth/exchange` within 60 s. */
        data class Code(val code: String) : Callback

        data class Failed(val error: SignInError) : Callback
    }

    /**
     * Reads the URL the browser came back with. Anything that isn't our callback, or carries
     * neither a code nor an error, is a failed sign-in.
     */
    fun callback(url: URI): Callback {
        if (url.scheme?.lowercase() != CALLBACK_SCHEME || url.host?.lowercase() != "auth" || url.path != "/callback") {
            return Callback.Failed(SignInError.ProviderFailed)
        }
        val items = (url.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.map { item ->
            val name = item.substringBefore('=')
            val value = if ('=' in item) item.substringAfter('=') else null
            decode(name) to value?.let(::decode)
        }
        fun value(name: String): String? = items.firstOrNull { it.first == name }?.second?.ifEmpty { null }
        value("error")?.let { error ->
            return Callback.Failed(if (error == "email_in_use") SignInError.EmailInUse else SignInError.ProviderFailed)
        }
        value("code")?.let { return Callback.Code(it) }
        return Callback.Failed(SignInError.ProviderFailed)
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        value
    }
}
