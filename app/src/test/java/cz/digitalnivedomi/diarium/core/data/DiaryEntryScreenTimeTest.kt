package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.ui.checkin.components.formatMinutes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The seconds-to-minutes edge between the sync worker and the UI.
 *
 * `phone_screen_time` arrives from the worker in whole **seconds** while every
 * screen renders minutes through `formatMinutes`, so a call site that forgets the
 * conversion turns a 3 h 39 min day (13 180 s) into "219 h 40 min". These tests
 * pin [phoneScreenTimeMinutes] as that one conversion, and keep the raw field and
 * the rendered string side by side so the regression is visible in the data.
 */
class DiaryEntryScreenTimeTest {

    /** A real value: the day the bug was reported (Monday, 3 h 39 min on screen). */
    private val reportedDaySeconds = 13_180

    @Test
    fun `seconds become the minutes every screen shows`() {
        val entry = DiaryEntry(phoneScreenTime = reportedDaySeconds)

        assertEquals(219, entry.phoneScreenTimeMinutes)
        assertEquals("3 h 39 min", formatMinutes(entry.phoneScreenTimeMinutes!!))
    }

    @Test
    fun `the raw field is seconds, not minutes`() {
        val entry = DiaryEntry(phoneScreenTime = reportedDaySeconds)

        // What the check-in section used to render: feed seconds straight in and the
        // same day reads as 219 hours. The property above is the only safe reader.
        assertEquals("219 h 40 min", formatMinutes(entry.phoneScreenTime!!))
        assertEquals("3 h 39 min", formatMinutes(entry.phoneScreenTimeMinutes!!))
    }

    @Test
    fun `conversion truncates to whole minutes`() {
        // The worker writes whole seconds; 59 s short of a minute is 3 min, not 4.
        assertEquals(3, DiaryEntry(phoneScreenTime = 239).phoneScreenTimeMinutes)
        assertEquals(1, DiaryEntry(phoneScreenTime = 60).phoneScreenTimeMinutes)
        assertEquals(0, DiaryEntry(phoneScreenTime = 59).phoneScreenTimeMinutes)
    }

    @Test
    fun `a missing sync stays null instead of reading as zero`() {
        assertNull(DiaryEntry().phoneScreenTimeMinutes)
        assertNull(DiaryEntry(phoneScreenTime = null).phoneScreenTimeMinutes)
    }

    @Test
    fun `a synced zero keeps rendering as zero minutes`() {
        assertEquals(0, DiaryEntry(phoneScreenTime = 0).phoneScreenTimeMinutes)
        assertEquals("0 min", formatMinutes(DiaryEntry(phoneScreenTime = 0).phoneScreenTimeMinutes!!))
    }

    @Test
    fun `four and a half hours matches the week average shown in Statistiky`() {
        // 4 h 29 min — the weekly average the statistics screen shows for the same
        // data, so the day detail and the stats agree on one unit.
        assertEquals("4 h 29 min", formatMinutes(DiaryEntry(phoneScreenTime = 16_140).phoneScreenTimeMinutes!!))
    }
}
