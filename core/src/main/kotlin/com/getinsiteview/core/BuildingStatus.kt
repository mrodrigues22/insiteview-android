package com.getinsiteview.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlinx.serialization.Serializable

/**
 * A building's lifecycle state as members see it (`BuildingResponse.status` and
 * `MyBuildingResponse.status`; master PLAN §6 "Building lifecycle"). An open enum like the
 * API's other enums: a value the server adds later decodes as its raw string.
 */
@JvmInline
@Serializable
value class BuildingStatus(val raw: String) {
    /** Whether the public QR opens the twin (the API's `Building.IsLive`). */
    val isLive: Boolean get() = this == TRIAL_LIVE || this == ACTIVE || this == EXPIRED

    companion object {
        /** Created; no processed model yet. */
        val DRAFT = BuildingStatus("Draft")

        /** A model is ready; the QR doesn't work until the building goes live. */
        val READY = BuildingStatus("Ready")

        /** Live during the organization's trial. */
        val TRIAL_LIVE = BuildingStatus("TrialLive")

        /** Activated: live until `expiresAt` (10 years). */
        val ACTIVE = BuildingStatus("Active")

        /** The trial ended before activation: QR codes show "This digital twin is paused". */
        val PAUSED = BuildingStatus("Paused")

        /** The activation ended: a read-only archive, still viewable. */
        val EXPIRED = BuildingStatus("Expired")
    }
}

/**
 * The line members see under a building's name (A-02, IOS-M3-03, M4-01): "Trial · 9 days left",
 * "Active until 2036", "Paused · Activate on the web". Billing itself stays on the web: the
 * line never links anywhere and never shows a price (master PLAN §11).
 */
sealed interface BuildingStatusLine {
    /** Draft: no processed model yet ("No model yet"). */
    data object NoModel : BuildingStatusLine

    /** Ready: the model is there, the QR isn't live ("Not live yet"). */
    data object NotLive : BuildingStatusLine

    /**
     * Live during the trial. [daysLeft] is `null` when the trial's end isn't known; 0 on the
     * last day ("Trial ends today").
     */
    data class Trial(val daysLeft: Int?) : BuildingStatusLine

    /** Activated; [untilYear] is the year the activation ends ("Active until 2036"). */
    data class Active(val untilYear: Int?) : BuildingStatusLine

    /**
     * The trial ended: guests see "This digital twin is paused"; members read "Activate on the
     * web" (plain text, no link).
     */
    data object Paused : BuildingStatusLine

    /** The activation ended: a read-only archive. */
    data object Expired : BuildingStatusLine

    /** A status this app doesn't know yet: its raw value. */
    data class Other(val raw: String) : BuildingStatusLine

    /** Whether this line warns (paused, expired, a trial's last days). */
    val isAttention: Boolean
        get() = when (this) {
            Paused, Expired -> true
            is Trial -> daysLeft != null && daysLeft <= 3
            else -> false
        }

    companion object {
        /**
         * @param trialEndsAt the organization's trial end (`GET /v1/orgs`), for trial buildings.
         * @param expiresAt the building's activation end (`GET /v1/buildings/{id}`).
         * @param zone for the year; it decides which year an end near New Year falls in.
         */
        fun from(
            status: BuildingStatus,
            trialEndsAt: Instant?,
            expiresAt: Instant?,
            now: Instant,
            zone: ZoneId = ZoneId.systemDefault(),
        ): BuildingStatusLine = when (status) {
            BuildingStatus.DRAFT -> NoModel
            BuildingStatus.READY -> NotLive
            BuildingStatus.TRIAL_LIVE -> Trial(trialEndsAt?.let { daysLeft(it, now) })
            BuildingStatus.ACTIVE -> Active(expiresAt?.atZone(zone)?.year)
            BuildingStatus.PAUSED -> Paused
            BuildingStatus.EXPIRED -> Expired
            else -> Other(status.raw)
        }

        /** Whole days left, rounded up: 8 days and 2 hours is "9 days left"; past the end, 0. */
        fun daysLeft(until: Instant, now: Instant): Int {
            val seconds = (until.epochSecond - now.epochSecond) + (until.nano - now.nano) / 1e9
            if (seconds <= 0) return 0
            return ceil(seconds / 86_400).toInt()
        }
    }
}
