package cz.digitalnivedomi.diarium.ui.checkin

import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three rules the check-in tab gained in 2026-09, driven straight off
 * [CheckInStateHolder]:
 *
 * 1. a day that is stored opens as the passive view (with "Upravit"), an unwritten
 *    day opens as the form;
 * 2. a finished check-in is reported once, so the host can navigate home without a
 *    recomposition navigating twice;
 * 3. the after-midnight step back to yesterday is a one-shot that never survives a
 *    day switch.
 *
 * A plain JVM test on purpose: all three are holder transitions, not pixels, and
 * the screen's own effect only ever reads the flags asserted here.
 */
class StoredDayStateTest {

    private fun holder(date: String = DATE) = CheckInStateHolder(date)

    @Test
    fun `a stored day opens as the passive view`() {
        val holder = holder()

        holder.load(DiaryEntry(mood = 4, note = "den zapsán"), stored = true)

        assertTrue("a row from the database reads passively", holder.state.showsPassiveDay)
        assertFalse("and is not being edited yet", holder.state.editing)
    }

    @Test
    fun `a day with no row opens as the form`() {
        val holder = holder()

        holder.load(DiaryEntry.EMPTY)

        assertFalse(holder.state.showsPassiveDay)
    }

    @Test
    fun `a draft never opens as the passive view`() {
        // What the screen does for a day that only lives in DraftStore: the owner is
        // mid-entry, so he must land in the form, not in a read-only card.
        val holder = holder()

        holder.load(DiaryEntry(mood = 3, note = "rozepsané"), stored = false)

        assertFalse(holder.state.showsPassiveDay)
    }

    @Test
    fun `Upravit opens the form and the next load is passive again`() {
        val holder = holder()
        holder.load(DiaryEntry(mood = 4), stored = true)

        holder.startEditing()
        assertFalse("the form is what 'Upravit' means", holder.state.showsPassiveDay)

        // Returning to the tab (or a configuration change) re-reads the row, which is
        // passive again — editing is a gesture, not a stored property of the day.
        holder.load(DiaryEntry(mood = 4), stored = true)
        assertFalse(holder.state.editing)
        assertTrue(holder.state.showsPassiveDay)
    }

    @Test
    fun `a day switch drops editing and the stored flag`() {
        val holder = holder()
        holder.load(DiaryEntry(mood = 4), stored = true)
        holder.startEditing()

        holder.setDate("2026-09-09")

        assertFalse(holder.state.editing)
        assertFalse(holder.state.storedEntry)
        assertFalse(holder.state.showsPassiveDay)
        assertFalse(holder.state.lateNightHint)
    }

    @Test
    fun `the after-midnight step back lands on yesterday and says so`() {
        val holder = holder()

        holder.startLateNight()

        assertEquals(CheckInDates.yesterday(), holder.state.date)
        assertTrue("the screen has to explain the date change", holder.state.lateNightHint)
        assertFalse("the shifted day is not stored yet", holder.state.storedEntry)
        assertFalse(holder.state.saved)
    }

    @Test
    fun `the hint does not survive a later day switch`() {
        val holder = holder()
        holder.startLateNight()
        assertTrue(holder.state.lateNightHint)

        holder.setDate(CheckInDates.today())

        assertFalse(holder.state.lateNightHint)
    }

    @Test
    fun `the after-midnight window ends at four in the morning`() {
        // 00:00–03:59 is a night owl still writing up the day; from 04:00 the day
        // being filled in is the one that has already begun.
        assertEquals(4, CheckInDates.LATE_NIGHT_UNTIL_HOUR)
    }

    @Test
    fun `closing the post-save window reports the check-in as finished once`() {
        val holder = holder()
        holder.markSaving()
        holder.markSaved()

        holder.dismissReflectionDialog()

        assertTrue(holder.state.finished)
        assertTrue("the host navigates home on it", holder.consumeFinished())
        assertFalse("and cannot be told twice by a recomposition", holder.consumeFinished())
        assertFalse(holder.state.finished)
    }

    @Test
    fun `an open window is not a finished check-in yet`() {
        // The reflection is read before the owner leaves the tab, so nothing may
        // navigate while the window is still up.
        val holder = holder()
        holder.markSaved()

        assertFalse(holder.state.finished)
        assertFalse(holder.consumeFinished())
    }

    @Test
    fun `switching the day drops a finished check-in`() {
        val holder = holder()
        holder.markSaved()
        holder.dismissReflectionDialog()
        assertTrue(holder.state.finished)

        holder.setDate("2026-09-09")

        assertFalse(holder.state.finished)
        assertFalse(holder.consumeFinished())
    }

    private companion object {
        const val DATE = "2026-09-10"
    }
}
