package com.getinsiteview.android

import android.content.Context
import android.os.RemoteException
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import com.getinsiteview.core.BuildingHandoff
import java.time.Instant
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The web → app handoff on first launch (docs/PLAN.md §3 "Guest entry", §6): the web's "Get the
 * app" opens Google Play with `referrer=code=…&plate=…`; the app reads the Play Install Referrer
 * once and saves the building in [BuildingHandoff] (iOS reads the App Clip's App Group handoff).
 * Opening it is `AppRootModel.openHandoff()`.
 */
object InstallReferrerHandoff {
    /** What the Play Store's referrer service answered. */
    private sealed interface Outcome {
        data class Details(val details: ReferrerDetails) : Outcome

        /** No Play Store, or a build that wasn't installed from it: nothing will ever come. */
        data object NotSupported : Outcome

        /** The service is busy or went away: try again on the next launch. */
        data object TryLater : Outcome
    }

    /** Reads the referrer if this install hasn't yet and saves its building; returns when done. */
    suspend fun read(context: Context, handoff: BuildingHandoff) {
        if (handoff.hasReadReferrer()) return
        when (val outcome = fetch(context.applicationContext)) {
            is Outcome.Details -> {
                val details = outcome.details
                val at = when {
                    details.referrerClickTimestampSeconds > 0 -> Instant.ofEpochSecond(details.referrerClickTimestampSeconds)
                    details.installBeginTimestampSeconds > 0 -> Instant.ofEpochSecond(details.installBeginTimestampSeconds)
                    else -> Instant.now()
                }
                handoff.saveReferrer(details.installReferrer, at)
            }
            // Marks the referrer read without a building.
            Outcome.NotSupported -> handoff.saveReferrer(null)
            Outcome.TryLater -> Unit
        }
    }

    private suspend fun fetch(context: Context): Outcome = suspendCancellableCoroutine { continuation ->
        val client = InstallReferrerClient.newBuilder(context).build()
        fun finish(outcome: Outcome) {
            if (continuation.isActive) continuation.resume(outcome)
            try {
                client.endConnection()
            } catch (_: Exception) {
            }
        }
        continuation.invokeOnCancellation {
            try {
                client.endConnection()
            } catch (_: Exception) {
            }
        }
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    when (responseCode) {
                        InstallReferrerClient.InstallReferrerResponse.OK -> {
                            val outcome = try {
                                Outcome.Details(client.installReferrer)
                            } catch (_: RemoteException) {
                                Outcome.TryLater
                            }
                            finish(outcome)
                        }
                        InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                        InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR,
                        -> finish(Outcome.NotSupported)
                        else -> finish(Outcome.TryLater)
                    }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    finish(Outcome.TryLater)
                }
            })
        } catch (_: Exception) {
            // e.g. SecurityException on devices without the Play Store's service.
            finish(Outcome.NotSupported)
        }
    }
}
