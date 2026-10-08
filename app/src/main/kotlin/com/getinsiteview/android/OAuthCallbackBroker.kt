package com.getinsiteview.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.getinsiteview.api.account.OAuthFlow
import java.lang.ref.WeakReference
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The browser step of Google, Microsoft and Apple sign-in (iOS `ASWebAuthenticationSession`,
 * docs/PLAN.md §3 "Networking and auth"): [authenticate] opens the API's start URL in a Custom Tab
 * and suspends until [MainActivity] receives `insiteview://auth/callback` ([complete]). Closing the
 * tab without signing in brings the activity back without a callback ([activityResumed]), which
 * cancels the wait (`CancellationException`, which `SignInError.from` maps to `Cancelled`).
 *
 * One sign-in at a time: a new one cancels the previous wait.
 */
object OAuthCallbackBroker {
    private val lock = Any()
    private var pending: CompletableDeferred<URI>? = null

    /** Whether the activity was paused since the tab opened (it returns to the foreground after). */
    private var pausedSinceLaunch = false
    private var currentActivity: WeakReference<Activity>? = null

    /**
     * `AccountService.signIn(provider, authenticate)`'s browser step: shows [startURL] in a Custom
     * Tab and returns the callback URL.
     *
     * @throws CancellationException when the person closes the tab.
     */
    suspend fun authenticate(context: Context, startURL: URI): URI {
        val deferred = CompletableDeferred<URI>()
        synchronized(lock) {
            pending?.completeExceptionally(CancellationException("Another sign-in started"))
            pending = deferred
            pausedSinceLaunch = false
        }
        try {
            withContext(Dispatchers.Main) { launch(context, startURL) }
            return deferred.await()
        } finally {
            synchronized(lock) { if (pending === deferred) pending = null }
        }
    }

    /**
     * An intent's data URL; returns whether it was the sign-in callback (handled here, whether or
     * not a sign-in was waiting: a callback after the app was killed is dropped, its code expires).
     */
    fun complete(data: Uri?): Boolean {
        val url = data?.toString() ?: return false
        if (!isCallback(url)) return false
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            URI(OAuthFlow.callbackURL.toString() + "?error=oauth_failed")
        }
        synchronized(lock) {
            pending?.complete(uri)
            pending = null
        }
        return true
    }

    /** Whether [url] is `insiteview://auth/callback…`. */
    fun isCallback(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme.equals(OAuthFlow.CALLBACK_SCHEME, ignoreCase = true) &&
            uri.host.equals("auth", ignoreCase = true) && uri.path == "/callback"
    }

    /** [MainActivity.onPause]: the Custom Tab is covering it. */
    fun activityPaused() {
        synchronized(lock) { if (pending != null) pausedSinceLaunch = true }
    }

    /**
     * [MainActivity.onResume], after any `onNewIntent`: back without a callback means the tab was
     * closed, so the sign-in is cancelled.
     */
    fun activityResumed(activity: Activity) {
        currentActivity = WeakReference(activity)
        synchronized(lock) {
            val waiting = pending ?: return
            if (!pausedSinceLaunch) return
            waiting.completeExceptionally(CancellationException("The sign-in tab was closed"))
            pending = null
        }
    }

    private fun launch(context: Context, url: URI) {
        // Prefer the activity on screen, so the tab opens in the app's task.
        val activity = currentActivity?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        val launcher: Context = activity ?: context
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        if (launcher !is Activity) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            intent.launchUrl(launcher, Uri.parse(url.toString()))
        } catch (error: ActivityNotFoundException) {
            // No browser at all: report a failed sign-in ("Signing in didn't work").
            synchronized(lock) {
                pending?.completeExceptionally(error)
                pending = null
            }
        }
    }
}
