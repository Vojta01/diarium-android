package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arithmetic behind the "oproti minulému týdnu" caption under each dashboard number.
 *
 * It is pinned here because a trend is easy to get subtly wrong in ways a screenshot
 * cannot show: comparing a total with an average, dividing by a week the owner never
 * filled in, or letting a phone-synced day with no mood (mood = 0) drag the mood
 * average down. The dashboard sums are mirrored by [DashboardTrends.weekMinutes],
 * [DashboardTrends.weekUnlocks] and [DashboardTrends.weekMood], so both windows are
 * always built the same way and a percentage between them means something.
 *
 * [DashboardTrends.of] also has to stay quiet: a move under
 * [DashboardTrends.FLAT_PERCENT] is "stejné jako minulý týden", and with nothing to
 * compare against it reports [TrendDirection.UNKNOWN] rather than inventing a baseline.
 */
class DashboardTrendsTest {

    private fun day(
        date: String,
        mood: Int = 0,
        seconds: Int? = null,
        unlocks: Int? = null,
    ) = DashboardDay(
        date = date,
        mood = mood,
        hasEntry = mood > 0,
        screenTimeSeconds = seconds,
        unlocks = unlocks,
    )

    // ── Nothing to compare against ─────────────────────────────────────────────

    /** The first week of use: a number without a previous week is not a trend. */
    @Test
    fun `no previous window is unknown, not an increase`() {
        val trend = DashboardTrends.of(120, null)
        assertEquals(TrendDirection.UNKNOWN, trend.direction)
        assertNull(trend.percentChange)
        assertEquals(120, trend.current)
        assertNull(trend.previous)
    }

    /** A previous week of zero would divide by zero: the move is real, the % is not. */
    @Test
    fun `a zero previous week reports a direction without a percentage`() {
        val trend = DashboardTrends.of(45, 0)
        assertEquals(TrendDirection.UP, trend.direction)
        assertNull(trend.percentChange)
    }

    @Test
    fun `a missing current side is unknown as well`() {
        assertEquals(TrendDirection.UNKNOWN, DashboardTrends.of(null, 300).direction)
        assertEquals(TrendDirection.UNKNOWN, DashboardTrends.of(null, null).direction)
    }

    // ── Direction and the quiet zone ───────────────────────────────────────────

    @Test
    fun `identical weeks are flat`() {
        val trend = DashboardTrends.of(300, 300)
        assertEquals(TrendDirection.FLAT, trend.direction)
        assertEquals(0, trend.percentChange)
    }

    /** 3 % of a week is inside the noise of the phone's own reporting. */
    @Test
    fun `a small move stays flat but still reports the percentage`() {
        val trend = DashboardTrends.of(103, 100)
        assertEquals(TrendDirection.FLAT, trend.direction)
        assertEquals(3, trend.percentChange)
    }

    @Test
    fun `a real rise is up`() {
        val trend = DashboardTrends.of(120, 100)
        assertEquals(TrendDirection.UP, trend.direction)
        assertEquals(20, trend.percentChange)
    }

    @Test
    fun `a real drop is down`() {
        val trend = DashboardTrends.of(80, 100)
        assertEquals(TrendDirection.DOWN, trend.direction)
        assertEquals(-20, trend.percentChange)
    }

    /** The boundary itself counts as a real move, so the caption is never off by one. */
    @Test
    fun `exactly five percent is a move`() {
        assertEquals(TrendDirection.UP, DashboardTrends.of(105, 100).direction)
        assertEquals(TrendDirection.DOWN, DashboardTrends.of(95, 100).direction)
    }

    // ── Averages (the mood) ────────────────────────────────────────────────────

    /** Moods are compared in tenths, so 3.8 -> 3.6 is a fall and not "the same 4". */
    @Test
    fun `moods are compared in tenths`() {
        val fall = DashboardTrends.ofAverage(3.6, 3.8)
        assertEquals(TrendDirection.DOWN, fall.direction)
        assertEquals(36, fall.current)
        assertEquals(38, fall.previous)
        assertEquals(-5, fall.percentChange)

        val rise = DashboardTrends.ofAverage(3.8, 3.6)
        assertEquals(TrendDirection.UP, rise.direction)
        assertEquals(38, rise.current)
        assertEquals(36, rise.previous)
        assertEquals(6, rise.percentChange)
    }

    @Test
    fun `an average with no previous week is unknown`() {
        assertEquals(TrendDirection.UNKNOWN, DashboardTrends.ofAverage(4.1, null).direction)
    }

    // ── The week aggregations the cards and the trends share ───────────────────

    @Test
    fun `screen time is the week's total in minutes`() {
        val week = listOf(
            day("2026-09-06", seconds = 3600),
            day("2026-09-07", seconds = 1800),
            day("2026-09-08"), // never synced
        )
        assertEquals(90, DashboardTrends.weekMinutes(week))
    }

    @Test
    fun `a week with no sync has no screen time at all`() {
        val week = listOf(day("2026-09-06"), day("2026-09-07"))
        assertNull(DashboardTrends.weekMinutes(week))
        assertNull(DashboardTrends.weekUnlocks(week))
    }

    @Test
    fun `unlocks are the week's total`() {
        val week = listOf(
            day("2026-09-06", unlocks = 40),
            day("2026-09-07", unlocks = 55),
            day("2026-09-08"), // a day without the metric must not count as zero
        )
        assertEquals(95, DashboardTrends.weekUnlocks(week))
    }

    /**
     * The owner's rule: a day without a mood is not a record, and a phone-synced row
     * carries mood 0. Averaging those in would report a bad week that never happened.
     */
    @Test
    fun `the mood average skips days without a mood`() {
        val week = listOf(
            day("2026-09-06", mood = 4, seconds = 1000),
            day("2026-09-07", mood = 2, seconds = 1000),
            day("2026-09-08", mood = 0, seconds = 1000), // synced, not answered
        )
        assertEquals(3.0, DashboardTrends.weekMood(week)!!, 0.0001)
    }

    @Test
    fun `a week with no mood at all has no average`() {
        assertNull(DashboardTrends.weekMood(listOf(day("2026-09-06"), day("2026-09-07"))))
        assertNull(DashboardTrends.weekMood(emptyList()))
    }

    // ── The comparison window itself ───────────────────────────────────────────

    /** Both windows are the same length and never overlap — the point of the baseline. */
    @Test
    fun `the previous window is the seven days before the week`() {
        val today = "2026-09-12"
        val week = DashboardRepository.weekWindow(today)
        val previous = DashboardRepository.previousWindow(today)

        assertEquals(
            listOf(
                "2026-09-06", "2026-09-07", "2026-09-08", "2026-09-09",
                "2026-09-10", "2026-09-11", "2026-09-12",
            ),
            week,
        )
        assertEquals(
            listOf(
                "2026-08-30", "2026-08-31", "2026-09-01", "2026-09-02",
                "2026-09-03", "2026-09-04", "2026-09-05",
            ),
            previous,
        )
        assertEquals(week.size, previous.size)
        assertEquals(emptySet<String>(), week.toSet() intersect previous.toSet())
    }

    /** A window always has seven entries, missing days included, so bars line up. */
    @Test
    fun `days for a window keeps the missing days as placeholders`() {
        val entry = DiaryEntry(mood = 5, phoneScreenTime = 3600, phoneUnlocks = 30)
        val days = DashboardRepository.daysFor(
            DashboardRepository.weekWindow("2026-09-12"),
            mapOf("2026-09-07" to entry),
        )
        assertEquals(7, days.size)
        assertEquals(5, days.first { it.date == "2026-09-07" }.mood)
        assertEquals(3600, days.first { it.date == "2026-09-07" }.screenTimeSeconds)
        val missing = days.first { it.date == "2026-09-06" }
        assertEquals(0, missing.mood)
        assertFalse(missing.hasEntry)
        assertNull(missing.screenTimeSeconds)
    }
}
