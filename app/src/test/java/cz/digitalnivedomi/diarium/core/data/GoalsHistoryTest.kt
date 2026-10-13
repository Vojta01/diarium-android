package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
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
        // current week window is 2026-10-06 … 2026-10-13
        assertEquals(true, computeHistory(days, goal("weekly", target = 3), today).last())
        // one short of the target: a definite miss, not "no data"
        assertEquals(false, computeHistory(days, goal("weekly", target = 4), today).last())
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
    fun `weekly periods shift the current window back whole weeks`() {
        val days = listOf(
            day("2026-09-17", key), // 2026-09-15 … 2026-09-22, three weeks back
            day("2026-09-30", key), // 2026-09-29 … 2026-10-06, one week back
            day(today, key),        // 2026-10-06 … 2026-10-13, current
        )
        val history = computeHistory(days, goal("weekly"), today)
        assertEquals(8, history.size)
        assertEquals(true, history[7])
        assertEquals(true, history[6])
        assertEquals(true, history[4])
        assertEquals(3, history.count { it == true })
        assertEquals(5, history.count { it == null })
    }

    @Test
    fun `monthly periods shift the current window back whole calendar months`() {
        val days = listOf(
            day("2026-08-20", key), // 2026-08-13 … 2026-09-13, one month back
            day("2026-10-01", key), // 2026-09-13 … 2026-10-13, current
        )
        val history = computeHistory(days, goal("monthly"), today)
        assertEquals(6, history.size)
        assertEquals(true, history[5])
        assertEquals(true, history[4])
        assertEquals(2, history.count { it == true })
        assertEquals(4, history.count { it == null })
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
        assertEquals(3, count)

        val card = progress("weekly", 2, days)
        assertEquals(count, card.count)
        assertEquals(count >= g.targetCount, card.history.last())
        assertTrue(card.targetMet)
        // The week before the current one holds only the boundary day 2026-10-06,
        // one logged day — under target, so previousMet is a definite false.
        assertEquals(false, card.previousMet)
    }
}
