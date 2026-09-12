package cz.digitalnivedomi.diarium.core.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bar arithmetic of the two Statistiky charts — no Compose, no Android.
 *
 * What is asserted here is what the split changed: two charts reading two different
 * numbers off the same day, each with its own bands, its own height scale and its own
 * summary. The screen-time half pins the web's `getBarColorTotals` thresholds, so the
 * colours cannot drift from `ScreenTimeChart.tsx`; the unlock half has no web
 * counterpart to drift from, so its bands, its averages and its dash-vs-zero rule are
 * written down here as the contract instead.
 *
 * Deliberately not asserted: anything that only exists once it is drawn (the track
 * height, the headroom, the dimming of unselected bars). Those are in
 * `ui/stats/ScreenTimeCharts.kt` and would need a Compose render to check.
 */
class ScreenTimeBarsTest {

    // ── Screen-time bands (web parity) ────────────────────────────────────────

    @Test
    fun `screen time bands break on the web's half and whole hour marks`() {
        assertEquals(0, ScreenTimeBars.timeBucket(0))
        assertEquals(0, ScreenTimeBars.timeBucket(29 * 60))
        assertEquals(1, ScreenTimeBars.timeBucket(30 * 60))
        assertEquals(1, ScreenTimeBars.timeBucket(59 * 60 + 59))
        assertEquals(2, ScreenTimeBars.timeBucket(60 * 60))
        assertEquals(2, ScreenTimeBars.timeBucket(2 * 60 * 60 - 1))
        assertEquals(3, ScreenTimeBars.timeBucket(2 * 60 * 60))
        assertEquals(3, ScreenTimeBars.timeBucket(4 * 60 * 60 - 1))
        assertEquals(4, ScreenTimeBars.timeBucket(4 * 60 * 60))
        assertEquals(4, ScreenTimeBars.timeBucket(6 * 60 * 60 - 1))
        assertEquals(5, ScreenTimeBars.timeBucket(6 * 60 * 60))
        assertEquals(5, ScreenTimeBars.timeBucket(12 * 60 * 60))
    }

    @Test
    fun `a negative duration lands in the first band instead of off the palette`() {
        assertEquals(0, ScreenTimeBars.timeBucket(-1))
        assertEquals(0, ScreenTimeBars.timeBucket(Int.MIN_VALUE))
    }

    // ── Unlock bands ──────────────────────────────────────────────────────────

    @Test
    fun `unlock bands break on round counts`() {
        assertEquals(0, ScreenTimeBars.unlockBucket(0))
        assertEquals(0, ScreenTimeBars.unlockBucket(19))
        assertEquals(1, ScreenTimeBars.unlockBucket(20))
        assertEquals(1, ScreenTimeBars.unlockBucket(49))
        assertEquals(2, ScreenTimeBars.unlockBucket(50))
        assertEquals(2, ScreenTimeBars.unlockBucket(79))
        assertEquals(3, ScreenTimeBars.unlockBucket(80))
        assertEquals(3, ScreenTimeBars.unlockBucket(119))
        assertEquals(4, ScreenTimeBars.unlockBucket(120))
        assertEquals(4, ScreenTimeBars.unlockBucket(179))
        assertEquals(5, ScreenTimeBars.unlockBucket(180))
        assertEquals(5, ScreenTimeBars.unlockBucket(400))
    }

    @Test
    fun `both band functions stay inside the six-colour palette for any value`() {
        // The charts index their colour list with these indices; an index past the end
        // would be a crash, not a wrong colour, so the range is part of the contract.
        // Six is the web's `getBarColorTotals` bucket count, which the palette mirrors.
        assertEquals(6, ScreenTimeBars.BUCKETS)
        listOf(Int.MIN_VALUE, -1, 0, 1, 60, 1800, 3600, 20_000, Int.MAX_VALUE).forEach { value ->
            assertTrue("time bucket of $value", ScreenTimeBars.timeBucket(value) in 0 until ScreenTimeBars.BUCKETS)
            assertTrue("unlock bucket of $value", ScreenTimeBars.unlockBucket(value) in 0 until ScreenTimeBars.BUCKETS)
        }
    }

    @Test
    fun `each chart's legend names every band it can draw`() {
        assertEquals(ScreenTimeBars.BUCKETS, ScreenTimeBars.TIME_BUCKET_LABELS.size)
        assertEquals(ScreenTimeBars.BUCKETS, ScreenTimeBars.UNLOCK_BUCKET_LABELS.size)
        assertEquals(ScreenTimeBars.BUCKETS, ScreenTimeBars.TIME_BUCKET_LABELS.toSet().size)
        assertEquals(ScreenTimeBars.BUCKETS, ScreenTimeBars.UNLOCK_BUCKET_LABELS.toSet().size)
        // The web's captions, first band to last.
        assertEquals("<30m", ScreenTimeBars.TIME_BUCKET_LABELS.first())
        assertEquals("30m–1h", ScreenTimeBars.TIME_BUCKET_LABELS[1])
        assertEquals("6h+", ScreenTimeBars.TIME_BUCKET_LABELS.last())
        assertEquals("<20", ScreenTimeBars.UNLOCK_BUCKET_LABELS.first())
        assertEquals("180+", ScreenTimeBars.UNLOCK_BUCKET_LABELS.last())
    }

    // ── Bar heights ───────────────────────────────────────────────────────────

    @Test
    fun `a bar is its share of the window's biggest day`() {
        // The scale is one chart's own maximum: 90 unlocks is a full bar whether the
        // window's other days held the screen for one hour or six.
        assertEquals(1f, ScreenTimeBars.barFraction(value = 90, max = 90), 0.0001f)
        assertEquals(0.5f, ScreenTimeBars.barFraction(value = 45, max = 90), 0.0001f)
        assertEquals(40f / 90f, ScreenTimeBars.barFraction(value = 40, max = 90), 0.0001f)
    }

    @Test
    fun `a small day still draws a visible stub`() {
        // 2 % of the track is the floor: a real day must never be invisible next to a
        // day five times its size.
        assertEquals(0.02f, ScreenTimeBars.barFraction(value = 0, max = 3600), 0.0001f)
        assertEquals(0.02f, ScreenTimeBars.barFraction(value = 1, max = 20_000), 0.0001f)
        assertEquals(0.02f, ScreenTimeBars.barFraction(value = -5, max = 3600), 0.0001f)
    }

    @Test
    fun `no bar can outgrow the track, even on a degenerate maximum`() {
        assertEquals(1f, ScreenTimeBars.barFraction(value = 200, max = 100), 0.0001f)
        assertEquals(1f, ScreenTimeBars.barFraction(value = 30, max = 0), 0.0001f)
        assertEquals(0.02f, ScreenTimeBars.barFraction(value = 0, max = 0), 0.0001f)
        assertEquals(1f, ScreenTimeBars.barFraction(value = 5, max = -1), 0.0001f)
    }

    @Test
    fun `durations and counts scale by the same rule`() {
        // The two charts differ in what they read, not in how a bar is drawn — that is
        // what keeps them looking like one pair.
        val seconds = listOf(40 * 60, 5 * 60 * 60, 2 * 60 * 60)
        val unlocks = listOf(40, 90, 50)
        val bySeconds = seconds.map { ScreenTimeBars.barFraction(it, seconds.max()) }
        val byUnlocks = unlocks.map { ScreenTimeBars.barFraction(it, unlocks.max()) }
        // 2 h, 5 h, 40 min against 5 h: the middle day is the full bar, and the minute
        // spread of the two orders is not asserted — only that both are drawn 1, .4, .13.
        assertEquals(1f, bySeconds[1], 0.0001f)
        assertEquals(1f, byUnlocks[1], 0.0001f)
        assertEquals(0.4f, bySeconds[2], 0.0001f)
        assertEquals(40f / 90f, byUnlocks[0], 0.0001f)
        assertTrue(bySeconds[0] < bySeconds[2])
        assertTrue(byUnlocks[0] < byUnlocks[2])
    }

    // ── The dashed average line ───────────────────────────────────────────────

    @Test
    fun `the average line reaches the floor, unlike a bar`() {
        // An average of zero is a fact about the window; its line belongs on the
        // baseline. A zero bar has no line to draw, which is why barFraction floors at
        // 2 % and this does not.
        assertEquals(0f, ScreenTimeBars.averageLineFraction(average = 0.0, max = 3600), 0.0001f)
        assertEquals(0.02f, ScreenTimeBars.barFraction(value = 0, max = 3600), 0.0001f)
    }

    @Test
    fun `the average line sits at the average's share of the largest day`() {
        assertEquals(0.5f, ScreenTimeBars.averageLineFraction(average = 45.0, max = 90), 0.0001f)
        assertEquals(0.5f, ScreenTimeBars.averageLineFraction(average = 1800.0, max = 3600), 0.0001f)
        assertEquals(1f, ScreenTimeBars.averageLineFraction(average = 90.0, max = 90), 0.0001f)
        // An average cannot exceed the maximum, but a hand-built window could.
        assertEquals(1f, ScreenTimeBars.averageLineFraction(average = 500.0, max = 90), 0.0001f)
        // A window whose maximum is zero: the line is at the top of nothing.
        assertEquals(1f, ScreenTimeBars.averageLineFraction(average = 60.0, max = 0), 0.0001f)
        assertEquals(0f, ScreenTimeBars.averageLineFraction(average = 0.0, max = 0), 0.0001f)
    }

    // ── What draws a bar at all ───────────────────────────────────────────────

    @Test
    fun `a day that was never reported draws no bar, a synced zero still does not`() {
        assertFalse(ScreenTimeBars.drawsBar(null))
        assertFalse(ScreenTimeBars.drawsBar(0))
        assertFalse(ScreenTimeBars.drawsBar(-1))
        assertTrue(ScreenTimeBars.drawsBar(1))
        assertTrue(ScreenTimeBars.drawsBar(196))
    }

    @Test
    fun `the two charts read two different numbers off the same day`() {
        // The whole point of the split: a day of navigation in the car has unlocks and
        // almost no screen time, so one fused chart had to be wrong about it.
        val day = screenTimeDay("2026-09-04", seconds = null, unlocks = 12)

        assertFalse("nothing synced for the time chart", ScreenTimeBars.drawsBar(day.seconds))
        assertTrue("the unlock chart still has a bar", ScreenTimeBars.drawsBar(day.unlocks))
        assertEquals(12.0, ScreenTimeBars.unlockSummary(listOf(day)).averageUnlocks!!, 0.0001)
    }

    // ── The unlock chart's bar label ──────────────────────────────────────────

    @Test
    fun `the unlock bar prints its count and a dash for a day never reported`() {
        assertEquals(ScreenTimeSeries.NO_DATA, ScreenTimeBars.unlockCountLabel(null))
        assertEquals("—", ScreenTimeBars.unlockCountLabel(null))
        assertEquals("0", ScreenTimeBars.unlockCountLabel(0))
        assertEquals("12", ScreenTimeBars.unlockCountLabel(12))
        assertEquals("196", ScreenTimeBars.unlockCountLabel(196))
    }

    @Test
    fun `a synced zero unlock count reads as a zero, not as missing data`() {
        // Now that the count is the whole subject of its chart, the zero is a reading.
        // In the fused chart it was noise printed under a screen-time bar, which is
        // what made it silent (`ScreenTimeSeries.unlockLabel(0) == null`).
        assertEquals("0", ScreenTimeBars.unlockCountLabel(0))
        assertNull(ScreenTimeSeries.unlockLabel(0))
    }

    // ── The unlock chart's three numbers ──────────────────────────────────────

    @Test
    fun `the average and the maximum come from the days that reported unlocks`() {
        // Same denominators as StatsMath.screenTimeSummary: 40 + 90 + 50 over the three
        // reporting days, not over the five days of the window.
        val summary = ScreenTimeBars.unlockSummary(
            listOf(
                screenTimeDay("2026-09-04", seconds = 3 * 3600, unlocks = 40),
                screenTimeDay("2026-09-05", seconds = 0, unlocks = 0),
                screenTimeDay("2026-09-06", seconds = null, unlocks = null),
                screenTimeDay("2026-09-07", seconds = 5 * 3600, unlocks = 90),
                screenTimeDay("2026-09-08", seconds = 2 * 3600, unlocks = 50),
            ),
        )

        assertEquals(60.0, summary.averageUnlocks!!, 0.0001)
        assertEquals(180, summary.totalUnlocks)
        assertEquals(90, summary.maxUnlocks)
        assertEquals("2026-09-07", summary.maxDate)
    }

    @Test
    fun `the total sums the whole window, missing days counting as nothing`() {
        val summary = ScreenTimeBars.unlockSummary(
            listOf(
                screenTimeDay("2026-09-04", unlocks = 10),
                screenTimeDay("2026-09-05", seconds = 3600, unlocks = null),
                screenTimeDay("2026-09-06", unlocks = 5),
            ),
        )

        assertEquals(15, summary.totalUnlocks)
        // The average has one denominator fewer than the window has days.
        assertEquals(7.5, summary.averageUnlocks!!, 0.0001)
    }

    @Test
    fun `a tie for the maximum is dated by the earliest day that reached it`() {
        // The web's `reduce` keeps the first of equal maxima; the unlock chart keeps
        // that, so "Nejvíc" cannot point at a later day than the bar it names.
        val summary = ScreenTimeBars.unlockSummary(
            listOf(
                screenTimeDay("2026-09-04", unlocks = 90),
                screenTimeDay("2026-09-05", unlocks = 41),
                screenTimeDay("2026-09-06", unlocks = 90),
            ),
        )

        assertEquals(90, summary.maxUnlocks)
        assertEquals("2026-09-04", summary.maxDate)
        assertEquals(221.0 / 3.0, summary.averageUnlocks!!, 0.0001)
    }

    @Test
    fun `an empty window reports nothing instead of inventing numbers`() {
        val summary = ScreenTimeBars.unlockSummary(emptyList())

        assertNull(summary.averageUnlocks)
        assertNull(summary.maxUnlocks)
        assertNull(summary.maxDate)
        assertEquals(0, summary.totalUnlocks)
    }

    @Test
    fun `a window with no reported unlocks is empty, not zero`() {
        // Otherwise the card would claim "Průměr denně 0×" for a week the phone never
        // synced, which reads as "you never unlocked it" rather than "no data".
        val summary = ScreenTimeBars.unlockSummary(
            listOf(
                screenTimeDay("2026-09-04", seconds = 3600, unlocks = null),
                screenTimeDay("2026-09-05", seconds = 3600, unlocks = 0),
            ),
        )

        assertNull(summary.averageUnlocks)
        assertNull(summary.maxUnlocks)
        assertNull(summary.maxDate)
        assertEquals(0, summary.totalUnlocks)
    }

    @Test
    fun `a single reported day averages to itself`() {
        val summary = ScreenTimeBars.unlockSummary(
            listOf(
                screenTimeDay("2026-09-04", unlocks = null),
                screenTimeDay("2026-09-05", unlocks = 63),
            ),
        )

        assertEquals(63.0, summary.averageUnlocks!!, 0.0001)
        assertEquals(63, summary.maxUnlocks)
        assertEquals("2026-09-05", summary.maxDate)
        assertEquals(63, summary.totalUnlocks)
    }

    // ── The legends the two cards print ───────────────────────────────────────

    @Test
    fun `each chart's legend names its own unit and nothing else`() {
        // The fused chart's legend had to explain both numbers at once
        // (`ScreenTimeSeries.LEGEND`); each chart now says one thing.
        assertEquals("Číslo pod sloupcem = čas na obrazovce.", ScreenTimeBars.SCREEN_TIME_LEGEND)
        assertEquals("Číslo pod sloupcem = počet odemknutí.", ScreenTimeBars.UNLOCK_LEGEND)
        assertEquals(ScreenTimeSeries.NO_DATA, "—")
    }
}

/** One day of the window, in the shape [StatsMath.screenTimeWindow] hands the chart. */
private fun screenTimeDay(
    date: String,
    seconds: Int? = null,
    unlocks: Int? = null,
    hasApps: Boolean = false,
): ScreenTimeDay = ScreenTimeDay(date = date, seconds = seconds, unlocks = unlocks, hasApps = hasApps)
