package cz.digitalnivedomi.diarium.core.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The label arithmetic under the screen-time bars — no Compose, no Android.
 *
 * What is asserted here is exactly what the two charts show: the compact duration
 * under a bar, the dash that separates "no sync" from a real zero, the unlock line
 * that only unlocked days get, and the rule that decides which bars in a 30-day
 * window are labelled at all.
 */
class ScreenTimeSeriesTest {

    // ── Compact duration ──────────────────────────────────────────────────────

    @Test
    fun `compact format keeps the owner's spelling 5h58 45m 8h`() {
        // 5 h 58 min is 358 min; the minutes follow the hours with no separator.
        assertEquals("5h58", ScreenTimeSeries.formatMinutesCompact(358))
        assertEquals("45m", ScreenTimeSeries.formatMinutesCompact(45))
        assertEquals("8h", ScreenTimeSeries.formatMinutesCompact(480))
    }

    @Test
    fun `compact format drops the minutes on a whole hour and pads nothing`() {
        assertEquals("1h", ScreenTimeSeries.formatMinutesCompact(60))
        // 1 h 1 min is not "1h01" — a single minute has no leading zero.
        assertEquals("1h1", ScreenTimeSeries.formatMinutesCompact(61))
        assertEquals("1h30", ScreenTimeSeries.formatMinutesCompact(90))
        assertEquals("10h", ScreenTimeSeries.formatMinutesCompact(600))
        assertEquals("59m", ScreenTimeSeries.formatMinutesCompact(59))
    }

    @Test
    fun `a synced zero is written as 0m, not as an empty label`() {
        assertEquals("0m", ScreenTimeSeries.formatMinutesCompact(0))
    }

    @Test
    fun `a negative duration never reaches the screen as a minus sign`() {
        // Nothing should ever store one; if it does, the label must not read
        // "-3m" under a bar that cannot be negative either.
        assertEquals("0m", ScreenTimeSeries.formatMinutesCompact(-3))
    }

    // ── Null vs zero ──────────────────────────────────────────────────────────

    @Test
    fun `a day the worker never synced prints a dash, not a zero`() {
        assertEquals(ScreenTimeSeries.NO_DATA, ScreenTimeSeries.secondsLabel(null))
        assertEquals("—", ScreenTimeSeries.secondsLabel(null))
    }

    @Test
    fun `a synced zero prints 0m so it cannot be mistaken for missing data`() {
        assertEquals("0m", ScreenTimeSeries.secondsLabel(0))
        assertEquals("5h58", ScreenTimeSeries.secondsLabel(358 * 60))
        assertEquals("8h", ScreenTimeSeries.secondsLabel(480 * 60))
    }

    // ── Unlocks ───────────────────────────────────────────────────────────────

    @Test
    fun `the unlock line is silent for a missing or zero count`() {
        assertNull(ScreenTimeSeries.unlockLabel(null))
        assertNull(ScreenTimeSeries.unlockLabel(0))
    }

    @Test
    fun `the unlock line prints the lock and the count with no space`() {
        assertEquals("🔓1", ScreenTimeSeries.unlockLabel(1))
        assertEquals("🔓196", ScreenTimeSeries.unlockLabel(196))
    }

    // ── Label thinning ────────────────────────────────────────────────────────

    @Test
    fun `a seven day window labels every bar`() {
        assertEquals(
            setOf(0, 1, 2, 3, 4, 5, 6),
            ScreenTimeSeries.labelIndices(count = 7, windowDays = 7),
        )
    }

    @Test
    fun `a thirty day window labels every fifth bar, oldest first`() {
        // 0, 5, 10, 15, 20, 25 — the same cadence the weekday captions use, so a
        // value always sits under a day name.
        assertEquals(
            setOf(0, 5, 10, 15, 20, 25),
            ScreenTimeSeries.labelIndices(count = 30, windowDays = 30),
        )
    }

    @Test
    fun `the selected bar is labelled even when the window thins labels out`() {
        assertEquals(
            setOf(0, 5, 10, 15, 20, 25, 27),
            ScreenTimeSeries.labelIndices(count = 30, windowDays = 30, selectedIndex = 27),
        )
        // A selected bar that already carries a label produces no duplicate.
        assertEquals(
            setOf(0, 5, 10, 15, 20, 25),
            ScreenTimeSeries.labelIndices(count = 30, windowDays = 30, selectedIndex = 25),
        )
        // An out-of-range selection cannot invent a bar.
        assertEquals(
            setOf(0, 5, 10, 15, 20, 25),
            ScreenTimeSeries.labelIndices(count = 30, windowDays = 30, selectedIndex = 99),
        )
    }

    @Test
    fun `an empty window labels nothing`() {
        assertTrue(ScreenTimeSeries.labelIndices(count = 0, windowDays = 30).isEmpty())
    }
}
