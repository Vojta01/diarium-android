package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the goals maths: no Android framework, no Robolectric, no
 * network and no clock — every function asserted here takes its inputs (dates
 * included) as arguments, so the web's counting and streak rules can be pinned
 * down exactly.
 */
class GoalsRepositoryTest {

    // ── streak 🔥 ───────────────────────────────────────────────────────────

    @Test
    fun `streak is zero when the activity was never logged`() {
        assertEquals(0, streakOf(emptyList()))
    }

    @Test
    fun `streak is one for a single day`() {
        assertEquals(1, streakOf(listOf("2026-01-05")))
    }

    @Test
    fun `streak counts a run of three consecutive days`() {
        assertEquals(
            3,
            streakOf(listOf("2026-01-05", "2026-01-04", "2026-01-03")),
        )
    }

    @Test
    fun `streak sorts its input so an unsorted run still counts`() {
        assertEquals(
            3,
            streakOf(listOf("2026-01-03", "2026-01-05", "2026-01-04")),
        )
    }

    @Test
    fun `a one-day gap breaks the streak`() {
        // newest logged day 01-05, then 01-03: the gap is 2 days → the walk stops
        // at the first day, exactly like the web (no grace period).
        assertEquals(1, streakOf(listOf("2026-01-05", "2026-01-03", "2026-01-02")))
    }

    @Test
    fun `the run after the break is not added`() {
        // Two separate runs of two; only the newest run counts.
        assertEquals(
            2,
            streakOf(listOf("2026-01-06", "2026-01-05", "2026-01-02", "2026-01-01")),
        )
    }

    @Test
    fun `a duplicate date does not inflate the streak`() {
        assertEquals(
            2,
            streakOf(listOf("2026-01-05", "2026-01-05", "2026-01-04")),
        )
    }

    @Test
    fun `a run across a month boundary still counts`() {
        assertEquals(
            3,
            streakOf(listOf("2026-02-01", "2026-01-31", "2026-01-30")),
        )
    }

    // ── period count ────────────────────────────────────────────────────────

    @Test
    fun `count ignores days without the activity`() {
        val days = listOf(
            listOf("beh"),
            emptyList(),
            listOf("spanek"),
            listOf("beh", "spanek"),
        )
        assertEquals(2, countActivity(days, "beh"))
    }

    @Test
    fun `count counts a day once even when the key repeats`() {
        val days = listOf(listOf("beh", "beh", "beh"))
        assertEquals(1, countActivity(days, "beh"))
    }

    @Test
    fun `count is zero for an activity that never occurs`() {
        assertEquals(0, countActivity(listOf(listOf("beh"), listOf("spanek")), "kava"))
    }

    @Test
    fun `count respects the period window`() {
        val days = listOf(
            DayActivities("2026-01-15", listOf("beh")),
            DayActivities("2026-01-08", listOf("beh")),
            DayActivities("2026-01-07", listOf("beh")),
        )
        // week window for 2026-01-15 is 2026-01-08 … 2026-01-15 inclusive
        assertEquals(2, countActivityInPeriod(days, "beh", "2026-01-08", "2026-01-15"))
    }

    @Test
    fun `period count is per day even when the day repeats the key`() {
        // Two rows for the same day, and the key twice inside one of them: still
        // exactly one day with the activity in the window.
        val days = listOf(
            DayActivities("2026-01-15", listOf("beh", "beh")),
            DayActivities("2026-01-15", listOf("beh")),
            DayActivities("2026-01-14", listOf("spanek")),
        )
        assertEquals(1, countActivityInPeriod(days, "beh", "2026-01-08", "2026-01-15"))
        assertEquals(
            listOf("2026-01-15"),
            activityDates(days, "beh", "2026-01-08", "2026-01-15"),
        )
    }

    // ── target_met boundary ─────────────────────────────────────────────────

    @Test
    fun `target is met when the count equals the target`() {
        assertTrue(isTargetMet(3, 3))
    }

    @Test
    fun `target is met when the count exceeds the target`() {
        assertTrue(isTargetMet(4, 3))
    }

    @Test
    fun `target is not met one short of it`() {
        assertFalse(isTargetMet(2, 3))
    }

    @Test
    fun `progress is clamped at one`() {
        val goal = Goal("id", "beh", "Běhání", 3, "weekly", true)
        assertEquals(1f, GoalProgress(goal, count = 5, streak = 1).fraction, 0.0001f)
        assertEquals(0f, GoalProgress(goal, count = 0, streak = 0).fraction, 0.0001f)
        assertTrue(GoalProgress(goal, count = 3, streak = 1).targetMet)
        assertEquals(0, GoalProgress(goal, count = 3, streak = 1).remaining)
        assertEquals(1, GoalProgress(goal, count = 2, streak = 0).remaining)
    }

    // ── periods ─────────────────────────────────────────────────────────────

    @Test
    fun `week period starts seven days back`() {
        assertEquals("2026-01-08", periodStart("2026-01-15", "weekly"))
    }

    @Test
    fun `month period starts one month back`() {
        assertEquals("2025-12-15", periodStart("2026-01-15", "monthly"))
    }

    @Test
    fun `day period starts today`() {
        assertEquals("2026-01-15", periodStart("2026-01-15", "daily"))
    }

    @Test
    fun `unknown frequency behaves as weekly`() {
        assertEquals("2026-01-08", periodStart("2026-01-15", "neweekly"))
        assertEquals("týden", periodLabelFor("neweekly"))
    }

    @Test
    fun `month start clamps to the last day of a shorter month`() {
        assertEquals("2026-02-28", minusOneMonth("2026-03-31"))
        assertEquals("2024-02-29", minusOneMonth("2024-03-31"))
    }

    @Test
    fun `period labels match the web copy`() {
        assertEquals("den", periodLabelFor("daily"))
        assertEquals("týden", periodLabelFor("weekly"))
        assertEquals("měsíc", periodLabelFor("monthly"))
    }

    @Test
    fun `frequency values are normalised`() {
        assertEquals("daily", normalizeFrequency("daily"))
        assertEquals("monthly", normalizeFrequency("monthly"))
        assertEquals("weekly", normalizeFrequency(""))
        assertEquals("weekly", normalizeFrequency("weekly"))
    }

    // ── date helpers ────────────────────────────────────────────────────────

    @Test
    fun `days between counts whole days`() {
        assertEquals(1L, daysBetween("2026-01-04", "2026-01-05"))
        assertEquals(2L, daysBetween("2026-01-03", "2026-01-05"))
        assertEquals(1L, daysBetween("2026-02-28", "2026-03-01"))
        assertNull(daysBetween("not-a-date", "2026-01-05"))
    }

    @Test
    fun `iso days round-trip through epoch days`() {
        listOf("1970-01-01", "2000-02-29", "2026-01-15", "2026-12-31").forEach { iso ->
            val epochDay = isoToEpochDay(iso)
            assertEquals(iso, epochDayToIso(epochDay!!))
        }
        assertEquals(0L, isoToEpochDay("1970-01-01"))
        assertNull(isoToEpochDay("2026-13-01"))
        assertNull(isoToEpochDay("2026-01"))
    }

    @Test
    fun `date minus days walks across month and year boundaries`() {
        assertEquals("2026-01-08", dateMinusDays("2026-01-15", 7))
        assertEquals("2025-12-29", dateMinusDays("2026-01-05", 7))
        assertEquals("2026-02-28", dateMinusDays("2026-03-01", 1))
    }
}
