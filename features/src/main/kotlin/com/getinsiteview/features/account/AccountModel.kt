package com.getinsiteview.features.account

import com.getinsiteview.api.Me
import com.getinsiteview.api.account.AccountService
import com.getinsiteview.api.account.AuthState
import com.getinsiteview.api.account.OAuthProvider
import com.getinsiteview.api.buildings.MyBuildingsRepository
import com.getinsiteview.core.UnitSystem
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The signed-in state for Compose (IOS-M3-01): [AccountService]'s [AuthState] as a [StateFlow]
 * (`collectAsState()`), plus the calls screens make. The sign-in screens live in `:app`.
 *
 * @param scope where the service's updates are followed (the app's scope).
 */
class AccountModel(
    val service: AccountService,
    private val myBuildings: MyBuildingsRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(AuthState())
    private var started = false

    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    val me: Me? get() = state.value.me

    val isSignedIn: Boolean get() = state.value.isSignedIn

    /** Reads the stored tokens and follows the service's state from then on. Called once at launch. */
    fun start() {
        if (started) return
        started = true
        // One collector keeps the updates in order.
        scope.launch {
            service.states.collect { next ->
                val wasSignedIn = mutableState.value.isSignedIn
                mutableState.value = next
                if (wasSignedIn && !next.isSignedIn) {
                    // Someone else's buildings must not stay on screen.
                    myBuildings.clear()
                }
            }
        }
        scope.launch { service.restore() }
    }

    // Sign-in

    /** Throws [com.getinsiteview.api.account.SignInError]. */
    suspend fun signIn(email: String, password: String) {
        service.signIn(email, password)
    }

    /** Throws [com.getinsiteview.api.account.SignInError]. */
    suspend fun register(email: String, password: String, displayName: String) {
        service.register(email, password, displayName)
    }

    /**
     * Google, Microsoft or Apple in a Custom Tab (Android signs in with Apple on the web too,
     * docs/PLAN.md §6): [authenticate] opens the start URL and returns the callback URL. Throws
     * [com.getinsiteview.api.account.SignInError].
     */
    suspend fun signIn(provider: OAuthProvider, authenticate: suspend (URI) -> URI) {
        service.signIn(provider, authenticate)
    }

    suspend fun oauthProviders(): List<OAuthProvider> = service.oauthProviders()

    // Profile

    suspend fun refreshProfile() {
        try {
            service.refreshProfile()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Units in Profile: the account's, so the web and other devices follow. */
    suspend fun setUnits(units: UnitSystem) {
        service.updateProfile(units = units)
    }

    suspend fun sendEmailConfirmation() {
        service.sendEmailConfirmation()
    }

    suspend fun signOut() {
        service.signOut()
        myBuildings.clear()
    }

    /** Throws [com.getinsiteview.api.account.AccountError]. */
    suspend fun deleteAccount() {
        service.deleteAccount()
        myBuildings.clear()
    }
}

/** Why the app is asking someone to sign in; the sign-in sheet's heading. */
sealed interface SignInReason {
    /** "Save this building → free account" (IOS-M3-08). */
    data class SaveBuilding(val name: String) : SignInReason

    /** From the Buildings tab or Profile. */
    data object Account : SignInReason
}

/**
 * Lets a shared screen (the guest landing's "Save this building") ask the app to show its sign-in
 * sheet and wait for the outcome. The app presents the sheet while [reason] is set and calls
 * [finish] when it closes. (iOS's Clip has no sign-in and sets [isAvailable] to false; on Android
 * it is always available.)
 */
class SignInPrompt(val isAvailable: Boolean) {
    private val mutableReason = MutableStateFlow<SignInReason?>(null)
    private var pending: CompletableDeferred<Boolean>? = null

    /** The sheet shows while this is set. */
    val reason: StateFlow<SignInReason?> = mutableReason.asStateFlow()

    /** Shows the sign-in sheet; returns whether the person signed in. Call on the main thread. */
    suspend fun requestSignIn(reason: SignInReason): Boolean {
        if (!isAvailable) return false
        finish(signedIn = false)
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        mutableReason.value = reason
        return deferred.await()
    }

    /** The sheet closed (signed in, or dismissed). */
    fun finish(signedIn: Boolean) {
        mutableReason.value = null
        pending?.complete(signedIn)
        pending = null
    }
}
