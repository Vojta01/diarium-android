package cz.digitalnivedomi.diarium.ui.stats

import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.data.StatsData
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [StatsStateHolder] — the load / loaded / failed flow the Statistiky screen renders
 * from, and the 7-days / 30-days / this-year switching.
 *
 * The distinction these cases protect is the one the screen cannot afford to lose:
 * "still loading", "loaded, and this window is empty" and "the read failed" are three
 * different things. A failed read must never be left holding a window the screen would
 * happily draw, and it must never be mistaken for "the user has no data yet".
 *
 * The range is local state: the read already holds [cz.digitalnivedomi.diarium.core.data.StatsRepository.LOAD_DAYS]
 * days, so switching the window is a slice of what is on screen and never a second
 * request.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StatsStateTest {

    private fun data(
        today: String = "2026-09-10",
        days: List<StatsDay> = emptyList(),
    ) = StatsData(today = today, days = days)

    private fun day(date: String, mood: Int = 0) = StatsDay(date = date, mood = mood)

    @Test
    fun `a fresh holder is loading with nothing on screen`() {
        val holder = StatsStateHolder()

        assertTrue(holder.state.loading)
        assertNull(holder.state.data)
        assertNull(holder.state.errorMessage)
        // The mood statistics default to the web's 30-day window.
        assertEquals(StatsRange.MONTH, holder.state.range)
    }

    @Test
    fun `a loaded window stops the spinner and keeps the days`() {
        val holder = StatsStateHolder()
        val loaded = data(days = listOf(day("2026-09-10", mood = 5)))

        holder.show(loaded)

        assertFalse(holder.state.loading)
        assertEquals(loaded, holder.state.data)
        assertNull(holder.state.errorMessage)
        // The day survives the round-trip through the holder.
        assertEquals(5, holder.state.data?.days?.single()?.mood)
    }

    @Test
    fun `an empty window is loaded, not loading and not an error`() {
        val holder = StatsStateHolder()

        holder.show(data(days = emptyList()))

        assertFalse(holder.state.loading)
        assertNull(holder.state.errorMessage)
        assertNotNull(holder.state.data)
        assertTrue(holder.state.data?.days?.isEmpty() == true)
    }

    @Test
    fun `a failure shows the repository's Czech sentence and drops the days`() {
        val holder = StatsStateHolder()
        holder.show(data(days = listOf(day("2026-09-10", mood = 5))))

        holder.showError("Nepodařilo se připojit k serveru.")

        assertFalse(holder.state.loading)
        assertEquals("Nepodařilo se připojit k serveru.", holder.state.errorMessage)
        // Never a silent empty screen: the numbers are gone and the error is what shows.
        assertNull(holder.state.data)
    }

    @Test
    fun `an error is never rendered as an empty window`() {
        // The two states are deliberately different: null data + a message is a
        // failure, empty days + no message is "this window has nothing in it".
        val failed = StatsStateHolder()
        failed.markLoading()
        failed.showError("Načtení statistik se nezdařilo.")

        assertNull(failed.state.data)
        assertNotNull(failed.state.errorMessage)

        val empty = StatsStateHolder()
        empty.show(data(days = emptyList()))

        assertNotNull(empty.state.data)
        assertNull(empty.state.errorMessage)
    }

    @Test
    fun `a new attempt clears the previous error and shows the spinner`() {
        val holder = StatsStateHolder()
        holder.showError("Načtení statistik se nezdařilo.")

        holder.markLoading()

        assertTrue(holder.state.loading)
        assertNull(holder.state.errorMessage)
    }

    @Test
    fun `a success after a failure replaces the message with the window`() {
        val holder = StatsStateHolder()
        holder.showError("Nepodařilo se připojit k serveru.")

        holder.show(data(today = "2026-09-10", days = listOf(day("2026-09-10", mood = 3))))

        assertFalse(holder.state.loading)
        assertNull(holder.state.errorMessage)
        assertEquals("2026-09-10", holder.state.data?.today)
        assertEquals(1, holder.state.data?.days?.size)
    }

    @Test
    fun `switching the range is local and keeps the loaded window`() {
        val holder = StatsStateHolder()
        val loaded = data(days = listOf(day("2026-09-10", mood = 5)))
        holder.show(loaded)

        holder.selectRange(StatsRange.WEEK)
        assertEquals(StatsRange.WEEK, holder.state.range)
        assertEquals(loaded, holder.state.data)   // no second read, no spinner

        holder.selectRange(StatsRange.YEAR)
        assertEquals(StatsRange.YEAR, holder.state.range)
        assertEquals(loaded, holder.state.data)

        holder.selectRange(StatsRange.MONTH)
        assertEquals(StatsRange.MONTH, holder.state.range)
        assertEquals(loaded, holder.state.data)

        assertFalse(holder.state.loading)
        assertNull(holder.state.errorMessage)
    }

    @Test
    fun `the seven day range is the last seven calendar days`() {
        val days = (1..10).map { day("2026-09-%02d".format(it), mood = 5) }
        val loaded = data(today = "2026-09-10", days = days)

        val week = loaded.forRange(StatsRange.WEEK)

        assertEquals(7, week.size)
        // 2026-09-10 − 6 days = 2026-09-04, and 09-01..09-03 fall outside.
        assertEquals("2026-09-04", week.first().date)
        assertEquals("2026-09-10", week.last().date)
    }

    @Test
    fun `the thirty day range is the last thirty calendar days`() {
        val days = (1..30).map { day("2026-09-%02d".format(it), mood = 3) }
        val loaded = data(today = "2026-09-30", days = days)

        val month = loaded.forRange(StatsRange.MONTH)

        assertEquals(30, month.size)
        // 2026-09-30 − 29 days = 2026-09-01.
        assertEquals("2026-09-01", month.first().date)
        assertEquals("2026-09-30", month.last().date)

        // A day just outside the window is not drawn, even though it was read.
        val withOutside = data(today = "2026-09-30", days = days + day("2026-08-31", mood = 4))
        val sliced = withOutside.forRange(StatsRange.MONTH)
        assertEquals(30, sliced.size)
        assertFalse(sliced.any { it.date == "2026-08-31" })
    }

    @Test
    fun `the year range is the calendar year of today, not a day count`() {
        val days = listOf(
            day("2025-12-31", mood = 4),
            day("2026-01-01", mood = 2),
            day("2026-09-10", mood = 5),
        )
        val loaded = data(today = "2026-09-10", days = days)

        assertEquals(2026, loaded.year)

        val year = loaded.forRange(StatsRange.YEAR)

        assertEquals(listOf("2026-01-01", "2026-09-10"), year.map { it.date })
    }

    @Test
    fun `the range controls carry the web's Czech labels`() {
        assertEquals("7 dní", StatsRange.WEEK.label)
        assertEquals("30 dní", StatsRange.MONTH.label)
        assertEquals("Tento rok", StatsRange.YEAR.label)

        assertEquals(7, StatsRange.WEEK.daysCount)
        assertEquals(30, StatsRange.MONTH.daysCount)

        assertFalse(StatsRange.WEEK.year)
        assertFalse(StatsRange.MONTH.year)
        assertTrue(StatsRange.YEAR.year)
    }
}
