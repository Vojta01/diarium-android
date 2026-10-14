package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [computeHistory] and the [GoalProgress] history getters:
 * no Android framework, no Robolectric, no network and no clock — `today` is
 * always passed in as a fixed date, so the per-period windows, the `null`
 * ("no data") rule and the met/streak relationship are pinned down exactly.
 */
class GoalsHistoryTest {

    private val today = "2026-10-13"
    private val key = "beh"

    private fun goal(frequency: String, target: Int = 1) =
        Goal("g1", key, "Běhání", target, frequency, true)

    private fun day(date: String, vararg keys: String) = DayActivities(date, keys.toList())

    /** Mirrors how `loadProgress` fills a card, pinned to [today]. */
    private fun progress(frequency: String, target: Int, days: List<DayActivities>): GoalProgress {
        val g = goal(frequency, target)
        return GoalProgress(
            goal = g,
            count = countActivityInPeriod(days, key, periodStart(today, frequency), today),
            streak = streakOf(activityDates(days, key, dateMinusDays(today, 365), today)),
            history = computeHistory(days, g, today),
        )
    }

    // ── shape ───────────────────────────────────────────────────────────────

    @Test
    fun `history shows 14 days, 8 weeks or 6 months`() {
        val days = listOf(day(today, key))
        assertEquals(14, computeHistory(days, goal("daily"), today).size)
        assertEquals(8, computeHistory(days, goal("weekly"), today).size)
        assertEquals(6, computeHistory(days, goal("monthly"), today).size)
    }

    @Test
    fun `history is oldest first with the current period last`() {
        // Only today has entries: the current period is the only known one and
        // everything older is null ("no data") — never rendered as a miss.
        val days = listOf(day(today, key))
        listOf("daily" to 13, "weekly" to 7, "monthly" to 5).forEach { (frequency, olderCount) ->
            val history = computeHistory(days, goal(frequency), today)
            assertEquals(true, history.last())
            assertEquals(olderCount, history.count { it == null })
            assertTrue(history.dropLast(1).all { it == null })
        }
    }

    @Test
    fun `no entries at all means every period is null`() {
        val history = computeHistory(emptyList(), goal("daily"), today)
        assertEquals(14, history.size)
        assertTrue(history.all { it == null })
    }

    // ── met rule ────────────────────────────────────────────────────────────

    @Test
    fun `a period is met when its window reaches the target`() {
        val days = listOf(day("2026-10-11", key), day("2026-10-12", key), day(today, key))
        // the current calendar week is 2026-10-12 … 2026-10-13 (Monday … today);
        // 2026-10-11 lies before it, in the previous week.
        assertEquals(true, computeHistory(days, goal("weekly", target = 2), today).last())
        // one short of the target: a definite miss, not "no data"
        assertEquals(false, computeHistory(days, goal("weekly", target = 3), today).last())
    }

    @Test
    fun `a day counts once even when the key repeats`() {
        val days = listOf(day(today, key, key, key))
        assertEquals(false, computeHistory(days, goal("daily", target = 2), today).last())
    }

    @Test
    fun `a day with other activities is a miss, not no data`() {
        val days = listOf(day("2026-10-12", "spanek"), day(today, key))
        val history = computeHistory(days, goal("daily"), today)
        assertEquals(false, history[12]) // yesterday has a row, just not this activity
        assertEquals(true, history[13])
    }

    // ── streak relationship ─────────────────────────────────────────────────

    @Test
    fun `trailing trues match the streak when the goal is logged today`() {
        val days = listOf(day("2026-10-11", key), day("2026-10-12", key), day(today, key))
        val g = goal("daily")
        val history = computeHistory(days, g, today)

        val streak = streakOf(activityDates(days, key, dateMinusDays(today, 365), today))
        assertEquals(3, streak)
        assertEquals(streak, history.takeLastWhile { it == true }.size)
        assertEquals(11, history.count { it == null })
    }

    @Test
    fun `streak anchors on the newest logged day while history anchors on today`() {
        // The web streak has no "still alive today" grace: activity ending
        // yesterday keeps streak 3. History runs up to today, so today's period
        // is "no data" and the trailing-true run is empty — the two numbers
        // only agree while the newest logged day is today's period.
        val days = listOf(day("2026-10-10", key), day("2026-10-11", key), day("2026-10-12", key))
        val history = computeHistory(days, goal("daily"), today)

        assertEquals(3, streakOf(activityDates(days, key, dateMinusDays(today, 365), today)))
        assertNull(history.last())
        assertEquals(0, history.takeLastWhile { it == true }.size)
    }

    // ── previousMet / counters ──────────────────────────────────────────────

    @Test
    fun `previousMet is the period before the current one`() {
        val met = progress("daily", 1, listOf(day("2026-10-12", key), day(today, key)))
        assertEquals(true, met.previousMet)

        val missed = progress("daily", 1, listOf(day("2026-10-12", "spanek"), day(today, key)))
        assertEquals(false, missed.previousMet)

        val unknown = progress("daily", 1, listOf(day(today, key)))
        assertNull(unknown.previousMet)
    }

    @Test
    fun `previousMet and counters are empty without history`() {
        val bare = GoalProgress(goal("daily"), count = 0, streak = 0)
        assertNull(bare.previousMet)
        assertEquals(0, bare.metPeriods)
        assertEquals(0, bare.knownPeriods)
    }

    @Test
    fun `metPeriods and knownPeriods count history entries`() {
        val days = listOf(
            day("2026-10-10", key),
            day("2026-10-12", "spanek"),
            day(today, key),
        )
        val card = progress("daily", 1, days)
        assertEquals(2, card.metPeriods)   // 2026-10-10 and today
        assertEquals(3, card.knownPeriods) // those two plus 2026-10-12's miss
        assertEquals(false, card.previousMet)
        assertEquals(true, card.history.last())
    }

    // ── window shifting ─────────────────────────────────────────────────────

    @Test
    fun `weekly periods are whole calendar weeks, Monday to Sunday`() {
        val days = listOf(
            day("2026-09-16", key), // week 2026-09-14 … 2026-09-20, four weeks back
            day("2026-09-30", key), // week 2026-09-28 … 2026-10-04, two weeks back
            day(today, key),        // week 2026-10-12 … today, current
        )
        val history = computeHistory(days, goal("weekly"), today)
        assertEquals(8, history.size)
        assertEquals(true, history[7]) // current week
        assertEquals(true, history[5]) // 2026-09-28 … 2026-10-04
        assertEquals(true, history[3]) // 2026-09-14 … 2026-09-20
        assertEquals(3, history.count { it == true })
        assertEquals(5, history.count { it == null })
    }

    @Test
    fun `monthly periods are whole calendar months`() {
        val days = listOf(
            day("2026-08-20", key), // August, two months back
            day("2026-09-15", key), // September, one month back
            day("2026-10-01", key), // October, current
        )
        val history = computeHistory(days, goal("monthly"), today)
        assertEquals(6, history.size)
        assertEquals(true, history[5]) // current month
        assertEquals(true, history[4]) // September
        assertEquals(true, history[3]) // August
        assertEquals(3, history.count { it == true })
        assertEquals(3, history.count { it == null })
    }

    @Test
    fun `the last history item measures the same window as count`() {
        val days = listOf(
            day("2026-10-06", key),
            day("2026-10-07", key),
            day("2026-10-12", "spanek"),
            day(today, key),
        )
        val g = goal("weekly", target = 2)
        val count = countActivityInPeriod(days, key, periodStart(today, "weekly"), today)
        assertEquals(1, count) // the current week is 2026-10-12 … 2026-10-13

        val card = progress("weekly", 2, days)
        assertEquals(count, card.count)
        assertEquals(count >= g.targetCount, card.history.last())
        assertFalse(card.targetMet)
        // The calendar week before the current one (2026-10-05 … 2026-10-11)
        // logged the activity on two days: at target 2 that one was met.
        assertEquals(true, card.previousMet)
    }

    // ── calendar helpers ────────────────────────────────────────────────────

    @Test
    fun `weekStart pins the Monday boundary`() {
        assertEquals("2026-10-12", weekStart("2026-10-12")) // Monday maps to itself
        assertEquals("2026-10-12", weekStart("2026-10-13"))
        assertEquals("2026-10-12", weekStart("2026-10-18")) // Sunday stays in its week
        assertEquals("2026-10-05", weekStart("2026-10-11")) // the week before's Sunday
        assertEquals("2026-09-28", weekStart("2026-10-01"))
    }

    @Test
    fun `calendar month helpers clamp to real month edges`() {
        assertEquals("2026-10-01", firstOfMonth("2026-10-13"))
        assertEquals("2026-10-31", lastDayOfMonth("2026-10-13"))
        assertEquals("2026-02-28", lastDayOfMonth("2026-02-10"))
        assertEquals("2024-02-29", lastDayOfMonth("2024-02-10"))
        assertEquals("2026-02-01", periodStart("2026-02-27", "monthly"))
        assertEquals("2026-09-07", periodStart("2026-09-13", "weekly"))
    }

    @Test
    fun `periodEndBack returns full past calendar windows`() {
        assertEquals("2026-10-13", periodEndBack("2026-10-13", "weekly", 0))
        assertEquals("2026-10-11", periodEndBack("2026-10-13", "weekly", 1))
        assertEquals("2026-10-04", periodEndBack("2026-10-13", "weekly", 2))
        assertEquals("2026-10-13", periodEndBack("2026-10-13", "monthly", 0))
        assertEquals("2026-09-30", periodEndBack("2026-10-13", "monthly", 1))
        assertEquals("2026-08-31", periodEndBack("2026-10-13", "monthly", 2))
    }
}
