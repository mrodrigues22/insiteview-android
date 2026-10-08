package com.getinsiteview.core

import java.time.Instant
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One analytics event (master PLAN §12), sent in batches to `POST /v1/events`.
 * The factories in the companion are the only way screens make events, so types and props stay in
 * step with the API's list (`InsiteView.Core.Analytics.AnalyticsEvent.Types`).
 */
@Serializable
data class AnalyticsEvent(
    val type: EventType,
    val props: Map<String, JSONValue>,
    @Serializable(with = ISO8601InstantSerializer::class) val occurredAt: Instant,
) {
    constructor(type: EventType, occurredAt: Instant) : this(type, emptyMap(), occurredAt)

    @Serializable
    enum class EventType(val raw: String) {
        @SerialName("view_3d_opened") VIEW_3D_OPENED("view_3d_opened"),
        @SerialName("ar_opened") AR_OPENED("ar_opened"),
        @SerialName("ar_aligned") AR_ALIGNED("ar_aligned"),
        @SerialName("system_toggled") SYSTEM_TOGGLED("system_toggled"),
        @SerialName("object_opened") OBJECT_OPENED("object_opened"),
        @SerialName("search_performed") SEARCH_PERFORMED("search_performed"),
        @SerialName("document_opened") DOCUMENT_OPENED("document_opened"),
        @SerialName("building_saved") BUILDING_SAVED("building_saved"),
        @SerialName("session_ended") SESSION_ENDED("session_ended"),
    }

    /** How AR got aligned. */
    @Serializable
    enum class AlignmentMethod(val raw: String) {
        @SerialName("plate") PLATE("plate"),

        /** Dragging the model on the floor (the last resort). */
        @SerialName("manual") MANUAL("manual"),

        /** Marking outlets, switches or floor corners. */
        @SerialName("points") POINTS("points"),
    }

    companion object {
        /** The API accepts at most this many events per call. */
        const val MAX_PER_CALL = 100

        /** The API rejects props bigger than this (bytes of JSON). */
        const val MAX_PROPS_BYTES = 2048

        fun view3DOpened(at: Instant) = AnalyticsEvent(EventType.VIEW_3D_OPENED, at)

        fun arOpened(at: Instant) = AnalyticsEvent(EventType.AR_OPENED, at)

        /** `ms`: from opening AR to the alignment locking. */
        fun arAligned(method: AlignmentMethod, duration: Duration, at: Instant) = AnalyticsEvent(
            EventType.AR_ALIGNED,
            mapOf("method" to JSONValue.String(method.raw), "ms" to JSONValue.Number(milliseconds(duration))),
            at,
        )

        fun systemToggled(system: String, on: Boolean, at: Instant) = AnalyticsEvent(
            EventType.SYSTEM_TOGGLED,
            mapOf("system" to JSONValue.String(system), "on" to JSONValue.Bool(on)),
            at,
        )

        fun objectOpened(kind: String, system: String, at: Instant) = AnalyticsEvent(
            EventType.OBJECT_OPENED,
            mapOf("kind" to JSONValue.String(kind), "system" to JSONValue.String(system)),
            at,
        )

        fun searchPerformed(results: Int, at: Instant) = AnalyticsEvent(
            EventType.SEARCH_PERFORMED,
            mapOf("results" to JSONValue.Number(results.toDouble())),
            at,
        )

        fun documentOpened(at: Instant) = AnalyticsEvent(EventType.DOCUMENT_OPENED, at)

        fun buildingSaved(at: Instant) = AnalyticsEvent(EventType.BUILDING_SAVED, at)

        /** `ms`: how long the building was open. */
        fun sessionEnded(duration: Duration, at: Instant) = AnalyticsEvent(
            EventType.SESSION_ENDED,
            mapOf("ms" to JSONValue.Number(milliseconds(duration))),
            at,
        )

        /** Whole milliseconds, never negative. */
        internal fun milliseconds(duration: Duration): Double {
            if (duration.isInfinite()) return 0.0
            return max(0.0, duration.toDouble(DurationUnit.MILLISECONDS).roundedHalfAway())
        }
    }
}
