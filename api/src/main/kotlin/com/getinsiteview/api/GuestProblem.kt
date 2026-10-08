package com.getinsiteview.api

import com.getinsiteview.modelkit.Manifest
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * The guest state screens (docs/PLAN.md §3 "Guest flows", A-05), from an API error or a building's
 * public status. Screens show these, never raw messages.
 */
sealed interface GuestProblem {
    /** Unknown code, or the building isn't visible: "We couldn't find this building". */
    data object NotFound : GuestProblem

    /** "This building isn't live yet". */
    data object NotLive : GuestProblem

    /** "This digital twin is paused". */
    data object Paused : GuestProblem

    data object LinkExpired : GuestProblem

    data object LinkRevoked : GuestProblem

    /** The building has a PIN (keypad in IOS-M2-02). */
    data object PinRequired : GuestProblem

    data class PinInvalid(val attemptsLeft: Int?) : GuestProblem

    data class PinLocked(val retryAfter: Int?) : GuestProblem

    data class RateLimited(val retryAfter: Int?) : GuestProblem

    /** No connection and nothing cached. */
    data object Offline : GuestProblem

    /**
     * This app version can't open the building: the API turned it away (426 `client.outdated`) or
     * the manifest's schema is newer than it reads. "Update required" (master PLAN §9 "Schema and
     * client compatibility").
     */
    data object UpdateRequired : GuestProblem

    /** Anything else: "Something went wrong. Try again." */
    data object Unavailable : GuestProblem

    /**
     * The visit can't go on: every `/v1/visit/…` call re-checks access, so a link revoked or
     * expired, a building paused or taken offline, or access removed (404) ends it mid-session and
     * replaces whatever screen is open with this state (IOS-M2-02, M4-02). So does an app too old
     * for the API or the building's model.
     */
    val endsAccess: Boolean
        get() = when (this) {
            NotFound, NotLive, Paused, LinkExpired, LinkRevoked, UpdateRequired -> true
            else -> false
        }

    /** Whether trying again could help. */
    val isRetryable: Boolean
        get() = when (this) {
            Offline, Unavailable, is RateLimited -> true
            else -> false
        }

    companion object {
        /** Maps an error thrown by the API client. */
        fun from(error: Throwable): GuestProblem = when {
            error is ApiError -> from(error)
            error is Manifest.DecodingProblem.UnsupportedSchema -> UpdateRequired
            isOffline(error) -> Offline
            else -> Unavailable
        }

        fun from(error: ApiError): GuestProblem {
            error.code?.let { code -> problem(code, error)?.let { return it } }
            return when (error.status) {
                404 -> NotFound
                410 -> LinkExpired
                426 -> UpdateRequired
                429 -> RateLimited(retryAfter = error.retryAfter)
                else -> Unavailable
            }
        }

        private fun problem(code: ApiError.Code, error: ApiError): GuestProblem? = when (code) {
            ApiError.Code.NOT_FOUND -> NotFound
            ApiError.Code.BUILDING_NOT_LIVE, ApiError.Code.VERSION_NOT_READY -> NotLive
            ApiError.Code.BUILDING_PAUSED -> Paused
            ApiError.Code.LINK_EXPIRED -> LinkExpired
            ApiError.Code.LINK_REVOKED -> LinkRevoked
            ApiError.Code.PIN_REQUIRED -> PinRequired
            ApiError.Code.PIN_INVALID -> PinInvalid(attemptsLeft = error.attemptsLeft)
            ApiError.Code.PIN_LOCKED -> PinLocked(retryAfter = error.retryAfter)
            ApiError.Code.RATE_LIMITED -> RateLimited(retryAfter = error.retryAfter)
            ApiError.Code.CLIENT_OUTDATED -> UpdateRequired
            else -> null
        }

        /** From the landing summary's status; `null` when guests can open the twin. */
        fun from(status: PublicBuildingStatus): GuestProblem? = when (status) {
            PublicBuildingStatus.NOT_LIVE -> NotLive
            PublicBuildingStatus.PAUSED -> Paused
            else -> null
        }

        /**
         * Whether a transport failure means there's no connection (iOS's `offlineCodes`: not
         * connected, connection lost, host not found, can't connect, timed out, DNS failed). OkHttp
         * reports those as `UnknownHostException` (also what Android throws with no network),
         * `ConnectException` / `NoRouteToHostException`, `SocketException` (connection reset or
         * lost) and `InterruptedIOException` (`SocketTimeoutException`, OkHttp's call timeout).
         * TLS and protocol failures aren't "offline".
         */
        fun isOffline(error: Throwable): Boolean = when (error) {
            is SSLException -> false
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketException -> true
            // OkHttp's "Canceled" is an IOException, not an InterruptedIOException; timeouts are.
            is InterruptedIOException -> true
            else -> false
        }
    }
}
