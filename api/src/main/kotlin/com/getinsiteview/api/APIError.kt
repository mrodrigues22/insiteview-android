package com.getinsiteview.api

import kotlin.math.ceil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * An error response from the API: RFC 9457 ProblemDetails with the machine-readable `code`
 * extension (master PLAN §7 "Error codes"). Screens switch on [code], never on [title].
 */
data class ApiError(
    /** `null` when the body wasn't ProblemDetails (e.g. a proxy's 502 page). */
    val code: Code?,
    val status: Int = 0,
    val title: String? = null,
    val detail: String? = null,
    /** `pin.invalid`: attempts left before the lockout. */
    val attemptsLeft: Int? = null,
    /**
     * Seconds to wait: `retryAfter` in the body (`auth.locked_out`, `pin.locked`) or the
     * `Retry-After` header (`rate_limited`).
     */
    val retryAfter: Int? = null,
    /** `validation`: field → messages. */
    val fieldErrors: Map<String, List<String>> = emptyMap(),
) : Exception() {
    val description: String
        get() = "HTTP $status ${code?.raw ?: "(no code)"}" + (title?.let { ": $it" } ?: "")

    override val message: String get() = description

    override fun toString(): String = description

    /**
     * The `code` values of `InsiteView.Core.Errors.ErrorCodes`. A code this app doesn't know still
     * decodes (as its raw value), so a new server code never breaks a screen.
     */
    @JvmInline
    value class Code(val raw: String) {
        override fun toString(): String = raw

        companion object {
            val VALIDATION = Code("validation")
            val NOT_FOUND = Code("not_found")
            val FORBIDDEN = Code("forbidden")
            val CONFLICT = Code("conflict")
            val RATE_LIMITED = Code("rate_limited")
            val INTERNAL = Code("internal")

            /** 426: this app version is older than the API serves (`X-Client-Version`): "Update the app". */
            val CLIENT_OUTDATED = Code("client.outdated")

            val AUTH_INVALID_CREDENTIALS = Code("auth.invalid_credentials")
            val AUTH_EMAIL_TAKEN = Code("auth.email_taken")
            val AUTH_LOCKED_OUT = Code("auth.locked_out")
            val AUTH_REFRESH_REUSED = Code("auth.refresh_reused")

            /** A missing, expired or invalid access or visit token: refresh (or start a new visit). */
            val AUTH_REQUIRED = Code("auth.required")

            /** An email verification, password reset or sign-in code that is wrong, used or expired. */
            val AUTH_TOKEN_INVALID = Code("auth.token_invalid")

            /** A social login whose email belongs to an account that can't be linked automatically. */
            val AUTH_EMAIL_IN_USE = Code("auth.email_in_use")
            val EMAIL_UNVERIFIED = Code("email.unverified")

            val UPLOAD_TOO_LARGE = Code("upload.too_large")
            val UPLOAD_TOO_MANY_FILES = Code("upload.too_many_files")
            val UPLOAD_UNSUPPORTED_TYPE = Code("upload.unsupported_type")
            val UPLOAD_INCOMPLETE = Code("upload.incomplete")
            val UPLOAD_CLOSED = Code("upload.closed")
            val VERSION_NOT_READY = Code("version.not_ready")

            val BUILDING_NOT_LIVE = Code("building.not_live")
            val BUILDING_PAUSED = Code("building.paused")

            val PIN_REQUIRED = Code("pin.required")
            val PIN_INVALID = Code("pin.invalid")
            val PIN_LOCKED = Code("pin.locked")

            val LINK_EXPIRED = Code("link.expired")
            val LINK_REVOKED = Code("link.revoked")

            val INVITATION_INVALID = Code("invitation.invalid")
            val INVITATION_EMAIL_MISMATCH = Code("invitation.email_mismatch")

            val TRIAL_LIVE_LIMIT = Code("trial.live_limit")
            val ACTIVATION_REQUIRED = Code("activation.required")
            val BILLING_INSUFFICIENT_CREDITS = Code("billing.insufficient_credits")
            val ENTERPRISE_CONTACT = Code("enterprise.contact")
        }
    }

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true }

        /**
         * Builds the error from a non-2xx response. Never fails: a body that isn't ProblemDetails
         * gives an error with just the status.
         *
         * @param retryAfterHeader the `Retry-After` header, if any.
         */
        fun decode(status: Int, retryAfterHeader: String? = null, body: ByteArray): ApiError {
            val headerRetry = retryAfterHeader?.trim(' ', '\t')?.toIntOrNull()
            val error = ApiError(code = null, status = status, retryAfter = headerRetry)
            val problem = try {
                lenient.parseToJsonElement(body.decodeToString()) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return error
            fun string(key: String): String? = (problem[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val bodyStatus = int(problem["status"])
            return error.copy(
                code = string("code")?.let(::Code),
                title = string("title"),
                detail = string("detail"),
                attemptsLeft = int(problem["attemptsLeft"]),
                retryAfter = int(problem["retryAfter"]) ?: headerRetry,
                fieldErrors = fieldErrors(problem["errors"]),
                status = if (status == 0 && bodyStatus != null) bodyStatus else status,
            )
        }

        /** Same as [decode] with the body as a string. */
        fun decode(status: Int, retryAfterHeader: String? = null, body: String): ApiError =
            decode(status, retryAfterHeader, body.encodeToByteArray())

        /** The [ApiError] in [error], if it is one (Swift's `APIError(_:)` unwrapping). */
        fun from(error: Throwable): ApiError? = error as? ApiError

        /** .NET may write numbers as strings (`JsonNumberHandling.AllowReadingFromString`). */
        private fun int(element: kotlinx.serialization.json.JsonElement?): Int? {
            val primitive = element as? JsonPrimitive ?: return null
            if (primitive.isString) return primitive.content.toIntOrNull()
            primitive.intOrNull?.let { return it }
            primitive.doubleOrNull?.let { return ceil(it).toInt() }
            return null
        }

        private fun fieldErrors(element: kotlinx.serialization.json.JsonElement?): Map<String, List<String>> {
            val obj = element as? JsonObject ?: return emptyMap()
            val result = LinkedHashMap<String, List<String>>()
            for ((key, value) in obj) {
                val list = value as? JsonArray ?: return emptyMap()
                result[key] = list.map { (it as? JsonPrimitive)?.contentOrNull ?: return emptyMap() }
            }
            return result
        }
    }
}

/** A 2xx body that didn't decode as the documented shape. */
data class UnexpectedResponse(val operationId: String, val status: Int, val reason: String) : Exception() {
    val description: String get() = "$operationId: HTTP $status: $reason"

    override val message: String get() = description

    override fun toString(): String = description
}
