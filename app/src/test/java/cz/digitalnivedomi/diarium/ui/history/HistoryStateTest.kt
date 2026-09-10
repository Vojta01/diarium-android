package cz.digitalnivedomi.diarium.ui.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.HistoryData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [HistoryStateHolder] — the load/loaded/failed flow the Historie screen renders from.
 *
 * The point of these cases is the distinction the calendar cannot afford to lose:
 * "still loading", "loaded, and this month is empty" and "the read failed" must be
 * three different things, and a failed read must never be left holding a month the
 * screen would happily draw.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryStateTest {

    private fun month(
        year: Int = 2026,
        month: Int = 9,
        entries: Map<String, DiaryEntry> = emptyMap(),
    ) = HistoryData(year = year, month = month, today = "2026-09-10", entries = entries)

    @Test
    fun `a fresh holder is loading with nothing on screen`() {
        val holder = HistoryStateHolder()

        assertTrue(holder.state.loading)
        assertNull(holder.state.data)
        assertNull(holder.state.errorMessage)
    }

    @Test
    fun `a loaded month stops the spinner`() {
        val holder = HistoryStateHolder()
        val data = month(entries = mapOf("2026-09-10" to DiaryEntry(mood = 5)))

        holder.show(data)

        assertFalse(holder.state.loading)
        assertEquals(data, holder.state.data)
        assertNull(holder.state.errorMessage)
        // The month and its one entry survive the round-trip through the holder.
        assertEquals(5, holder.state.data?.moodOn("2026-09-10"))
        assertNull(holder.state.data?.entryOn("2026-09-11"))
    }

    @Test
    fun `an empty month is loaded, not loading and not an error`() {
        val holder = HistoryStateHolder()

        holder.show(month())

        assertFalse(holder.state.loading)
        assertNull(holder.state.errorMessage)
        assertTrue(holder.state.data?.entries?.isEmpty() == true)
        assertEquals(30, holder.state.data?.daysInMonth)
    }

    @Test
    fun `a failure drops the month instead of leaving a stale calendar behind`() {
        val holder = HistoryStateHolder()
        holder.show(month(entries = mapOf("2026-09-10" to DiaryEntry(mood = 5))))

        holder.showError("Nepodařilo se připojit k serveru.")

        assertFalse(holder.state.loading)
        assertEquals("Nepodařilo se připojit k serveru.", holder.state.errorMessage)
        assertNull(holder.state.data)
    }

    @Test
    fun `a new attempt clears the previous error`() {
        val holder = HistoryStateHolder()
        holder.showError("Načtení přehledu se nezdařilo (503)")

        holder.markLoading()

        assertTrue(holder.state.loading)
        assertNull(holder.state.errorMessage)
    }

    @Test
    fun `reloading the same month keeps it on screen while the spinner shows`() {
        val holder = HistoryStateHolder()
        val data = month(entries = mapOf("2026-09-10" to DiaryEntry(mood = 4)))
        holder.show(data)

        holder.markLoading()

        assertTrue(holder.state.loading)
        assertEquals(data, holder.state.data)
        assertNull(holder.state.errorMessage)
    }

    @Test
    fun `loading a success after a failure replaces the message with the month`() {
        val holder = HistoryStateHolder()
        holder.showError("Nepodařilo se připojit k serveru.")

        holder.show(month())

        assertFalse(holder.state.loading)
        assertNull(holder.state.errorMessage)
        assertEquals(2026, holder.state.data?.year)
    }

    @Test
    fun `stepping to another month shows only the new month`() {
        val holder = HistoryStateHolder()
        holder.show(month(year = 2026, month = 9, entries = mapOf("2026-09-10" to DiaryEntry(mood = 5))))

        holder.show(month(year = 2026, month = 8))

        assertEquals(8, holder.state.data?.month)
        assertTrue(holder.state.data?.entries?.isEmpty() == true)
        assertNull(holder.state.data?.moodOn("2026-09-10"))
    }
}
