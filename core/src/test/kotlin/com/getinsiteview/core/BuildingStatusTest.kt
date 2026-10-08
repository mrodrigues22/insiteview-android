package com.getinsiteview.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Building status lines. */
class BuildingStatusTest {
    private val now = ISO8601Timestamp.parse("2026-09-30T12:00:00Z")!!
    private val utc: ZoneId = ZoneOffset.UTC

    private fun date(string: String): Instant = ISO8601Timestamp.parse(string)!!

    @Test
    fun `Trial - whole days left, rounded up - the last day is 0`() {
        val line = BuildingStatusLine.from(BuildingStatus.TRIAL_LIVE, date("2026-10-09T12:00:00Z"), null, now, utc)
        assertEquals(BuildingStatusLine.Trial(daysLeft = 9), line)
        assertEquals(9, BuildingStatusLine.daysLeft(date("2026-10-08T14:00:00Z"), now))
        assertEquals(1, BuildingStatusLine.daysLeft(date("2026-09-30T13:00:00Z"), now))
        assertEquals(0, BuildingStatusLine.daysLeft(date("2026-09-30T11:00:00Z"), now))
        assertEquals(BuildingStatusLine.Trial(daysLeft = null), BuildingStatusLine.from(BuildingStatus.TRIAL_LIVE, null, null, now))
        assertTrue(BuildingStatusLine.Trial(daysLeft = 3).isAttention)
        assertFalse(BuildingStatusLine.Trial(daysLeft = 9).isAttention)
        assertFalse(BuildingStatusLine.Trial(daysLeft = null).isAttention)
    }

    @Test
    fun `Active until the activation's year, in the calendar's time zone`() {
        assertEquals(
            BuildingStatusLine.Active(untilYear = 2036),
            BuildingStatusLine.from(BuildingStatus.ACTIVE, null, date("2036-09-29T12:00:00Z"), now, utc),
        )
        val saoPaulo = ZoneId.of("America/Sao_Paulo")
        assertEquals(
            BuildingStatusLine.Active(untilYear = 2036),
            BuildingStatusLine.from(BuildingStatus.ACTIVE, null, date("2037-01-01T01:00:00Z"), now, saoPaulo),
        )
        assertEquals(BuildingStatusLine.Active(untilYear = null), BuildingStatusLine.from(BuildingStatus.ACTIVE, null, null, now))
    }

    @Test
    fun `Every other state`() {
        assertEquals(BuildingStatusLine.NoModel, BuildingStatusLine.from(BuildingStatus.DRAFT, null, null, now))
        assertEquals(BuildingStatusLine.NotLive, BuildingStatusLine.from(BuildingStatus.READY, null, null, now))
        assertEquals(BuildingStatusLine.Paused, BuildingStatusLine.from(BuildingStatus.PAUSED, null, null, now))
        assertEquals(BuildingStatusLine.Expired, BuildingStatusLine.from(BuildingStatus.EXPIRED, null, null, now))
        assertEquals(BuildingStatusLine.Other("Archived"), BuildingStatusLine.from(BuildingStatus("Archived"), null, null, now))
        assertTrue(BuildingStatusLine.Paused.isAttention && BuildingStatusLine.Expired.isAttention)
        assertTrue(BuildingStatus.EXPIRED.isLive && BuildingStatus.TRIAL_LIVE.isLive && !BuildingStatus.PAUSED.isLive)
    }

    @Test
    fun `Statuses decode from the API's strings, unknown ones too`() {
        val decoded = Json.decodeFromString<List<BuildingStatus>>("""["TrialLive","Paused","Archived"]""")
        assertEquals(listOf(BuildingStatus.TRIAL_LIVE, BuildingStatus.PAUSED, BuildingStatus("Archived")), decoded)
    }
}
