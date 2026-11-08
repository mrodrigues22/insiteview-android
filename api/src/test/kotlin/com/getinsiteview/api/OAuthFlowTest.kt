package com.getinsiteview.api

import com.getinsiteview.api.account.OAuthFlow
import com.getinsiteview.api.account.OAuthProvider
import com.getinsiteview.api.account.Pkce
import com.getinsiteview.api.account.SignInError
import java.net.URI
import java.net.URLDecoder
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("OAuth round trip")
class OAuthFlowTest {
    @Test
    fun `Start URL - provider, the app's callback and the terms version`() {
        val url = OAuthFlow.startURL(URI("https://api.staging.getinsiteview.com"), OAuthProvider.GOOGLE, termsVersion = "2026-09")
        assertEquals(
            "https://api.staging.getinsiteview.com/v1/auth/oauth/google/start?returnTo=insiteview%3A%2F%2Fauth%2Fcallback&terms=2026-09",
            url.toString(),
        )
        val local = OAuthFlow.startURL(URI("https://tunnel.example.com/"), OAuthProvider.MICROSOFT, termsVersion = null)
        assertEquals(
            "https://tunnel.example.com/v1/auth/oauth/microsoft/start?returnTo=insiteview%3A%2F%2Fauth%2Fcallback",
            local.toString(),
        )
        val returnTo = url.rawQuery.split('&').first { it.startsWith("returnTo=") }.substringAfter('=')
        assertEquals("insiteview://auth/callback", URLDecoder.decode(returnTo, Charsets.UTF_8))
        val bound = OAuthFlow.startURL(URI("https://api.getinsiteview.com"), OAuthProvider.GOOGLE, termsVersion = null, codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        assertTrue(bound.rawQuery.endsWith("&codeChallenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))
    }

    /** RFC 7636 Appendix B, and fresh verifiers: 43 unreserved characters, never the same twice. */
    @Test
    fun `PKCE - the RFC 7636 example and fresh verifiers`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val verifier = Pkce.newVerifier()
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(verifier))
        assertTrue(verifier != Pkce.newVerifier())
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(Pkce.challenge(verifier)))
    }

    @Test
    fun `Callbacks - a code, the two errors, and anything else`() {
        fun callback(url: String) = OAuthFlow.callback(URI(url))
        assertEquals(OAuthFlow.Callback.Code("abc123"), callback("insiteview://auth/callback?code=abc123"))
        assertEquals(OAuthFlow.Callback.Failed(SignInError.EmailInUse), callback("insiteview://auth/callback?error=email_in_use"))
        assertEquals(OAuthFlow.Callback.Failed(SignInError.ProviderFailed), callback("insiteview://auth/callback?error=oauth_failed"))
        assertEquals(
            OAuthFlow.Callback.Failed(SignInError.ProviderFailed),
            callback("insiteview://auth/callback?error=oauth_failed&code=abc"),
        )
        assertEquals(OAuthFlow.Callback.Failed(SignInError.ProviderFailed), callback("insiteview://auth/callback"))
        assertEquals(OAuthFlow.Callback.Failed(SignInError.ProviderFailed), callback("insiteview://auth/callback?code="))
        assertEquals(OAuthFlow.Callback.Failed(SignInError.ProviderFailed), callback("insiteview://other/callback?code=abc"))
        assertEquals(OAuthFlow.Callback.Failed(SignInError.ProviderFailed), callback("https://getinsiteview.com/auth/callback?code=abc"))
    }

    /** Apple is left out, as in iOS's browser flow; Android has no Apple sign-in (docs/PLAN.md §6). */
    @Test
    fun `Offered providers - Google and Microsoft, never Apple`() {
        assertEquals(
            listOf(OAuthProvider.GOOGLE, OAuthProvider.MICROSOFT),
            OAuthProvider.offered(listOf("apple", "google", "microsoft")),
        )
        assertEquals(listOf(OAuthProvider.MICROSOFT), OAuthProvider.offered(listOf("Microsoft", "facebook")))
        assertTrue(OAuthProvider.offered(emptyList()).isEmpty())
    }
}
