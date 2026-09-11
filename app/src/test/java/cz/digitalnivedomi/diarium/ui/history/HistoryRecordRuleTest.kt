package cz.digitalnivedomi.diarium.ui.history

import cz.digitalnivedomi.diarium.core.data.DatedEntry
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.HistoryData
import cz.digitalnivedomi.diarium.core.data.HistoryRepository
import cz.digitalnivedomi.diarium.core.data.PhoneTopApp
import cz.digitalnivedomi.diarium.core.data.isRecordedDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's rule on the history screen: a day counts as a record only when the mood
 * is filled (see [isRecordedDay]).
 *
 * The phone-sync worker writes an `entries` row for every synced day, so "a row
 * exists" and "the owner journaled" are two different questions. These cases pin both
 * halves of the rule — the count the screen prints and the calendar's marking — with
 * the real 2026-09-07 row (5 h 58 min of screen time, 196 unlocks, no mood) as the
 * reference day.
 *
 * Pure JVM: no Compose, no Android, no clock, no transport. The row a synced day
 * produces arrives here as a [DiaryEntry] with `mood = 0`, because the row mapper
 * (`optInt("mood", 0)`) reads a missing `mood` column as zero.
 */
class HistoryRecordRuleTest {

    /**
     * 2026-09-07 exactly as the owner described it: an `entries` row the worker wrote,
     * 5 h 58 min of screen time and 196 unlocks, and no mood — so the day's record is
     * missing even though the row is there.
     */
    private fun syncedOnly0709(): DiaryEntry = DiaryEntry(
        mood = 0,
        phoneScreenTime = 5 * 3600 + 58 * 60, // seconds, the worker's unit
        phoneUnlocks = 196,
        phoneTopApps = listOf(PhoneTopApp("Instagram", 22)),
    )

    private fun month(vararg entries: Pair<String, DiaryEntry>): HistoryData =
        HistoryData(year = 2026, month = 9, today = "2026-09-10", entries = entries.toMap())

    // ── The rule itself ────────────────────────────────────────────────────────

    @Test
    fun `only a filled mood makes a day a record`() {
        assertTrue(isRecordedDay(1))
        assertTrue(isRecordedDay(3))
        assertTrue(isRecordedDay(5))
        assertFalse(isRecordedDay(null)) // no mood at all
        assertFalse(isRecordedDay(0))    // a missing mood arrives as 0 from the row mapper
    }

    @Test
    fun `the calendar only marks a mood filled day as logged`() {
        assertTrue(HistoryCalendar.isLogged(5))
        assertTrue(HistoryCalendar.isLogged(1))
        assertFalse(HistoryCalendar.isLogged(null)) // no row
        assertFalse(HistoryCalendar.isLogged(0))    // row, but no mood
    }

    @Test
    fun `a synced-only day paints the same neutral cell as a day with no row`() {
        // One has no row, the other has a row without a mood; both are "not logged".
        assertEquals(HistoryCalendar.isLogged(null), HistoryCalendar.isLogged(0))
        assertFalse(HistoryCalendar.isLogged(0))
    }

    // ── Counting ───────────────────────────────────────────────────────────────

    @Test
    fun `2026-09-07 with screen time and unlocks but no mood is not a record`() {
        val data = month("2026-09-07" to syncedOnly0709())

        assertFalse(data.isRecorded("2026-09-07"))
        assertTrue(data.isSyncedOnly("2026-09-07"))
        assertEquals(0, data.recordedCount)

        // Not dropped: the row is still there so the detail can show the synced data.
        assertNotNull(data.entryOn("2026-09-07"))
        assertEquals(196, data.entryOn("2026-09-07")?.phoneUnlocks)
        // …and the calendar gets no mood marker for it.
        assertNull(data.moodOn("2026-09-07"))
    }

    @Test
    fun `the month count is the journaled days, not the rows`() {
        val data = month(
            "2026-09-10" to DiaryEntry(mood = 5),
            "2026-09-09" to DiaryEntry(mood = 3),
            "2026-09-08" to DiaryEntry(mood = 0), // synced only
            "2026-09-07" to syncedOnly0709(),      // synced only — the real case
        )

        assertEquals(4, data.entries.size)   // every row is kept
        assertEquals(2, data.recordedCount)  // only the two with a mood count as records
        assertEquals(2, data.syncedOnlyCount)
        assertTrue(data.isRecorded("2026-09-10"))
        assertFalse(data.isRecorded("2026-09-08"))
        assertFalse(data.isRecorded("2026-09-07"))
    }

    @Test
    fun `a month that is only phone synced data counts zero records`() {
        val data = month(
            "2026-09-07" to syncedOnly0709(),
            "2026-09-08" to DiaryEntry(mood = 0, phoneUnlocks = 120),
        )

        assertEquals(0, data.recordedCount)
        assertEquals(2, data.syncedOnlyCount)
        // The rows still exist — the screen lists them, it just does not count them.
        assertEquals(2, data.entries.size)
    }

    @Test
    fun `a day with no row at all is neither a record nor synced-only`() {
        val data = month("2026-09-10" to DiaryEntry(mood = 4))

        assertEquals(1, data.recordedCount)
        assertEquals(0, data.syncedOnlyCount)
        assertFalse(data.isRecorded("2026-09-07"))
        assertFalse(data.isSyncedOnly("2026-09-07"))
        assertNull(data.moodOn("2026-09-07"))
    }

    // ── The read keeps the synced-only row (derive is the repository's pure step) ─

    @Test
    fun `the month read keeps a synced-only row instead of dropping it`() {
        val data = HistoryRepository.derive(
            year = 2026,
            month = 9,
            today = "2026-09-10",
            rows = listOf(
                DatedEntry("2026-09-10", DiaryEntry(mood = 5)),
                DatedEntry("2026-09-07", syncedOnly0709()),
            ),
        )

        assertEquals(setOf("2026-09-10", "2026-09-07"), data.entries.keys)
        assertEquals(1, data.recordedCount)
        assertEquals(1, data.syncedOnlyCount)
        assertNotNull(data.entryOn("2026-09-07"))
    }

    // ── The screen's copy ──────────────────────────────────────────────────────

    @Test
    fun `the month count sentence uses Czech plurals`() {
        assertEquals("V tomto měsíci máš 1 zápis.", monthCountText(1))
        assertEquals("V tomto měsíci máš 3 zápisy.", monthCountText(3))
        assertEquals("V tomto měsíci máš 7 zápisů.", monthCountText(7))
    }

    @Test
    fun `the synced-only hint names the days without a check-in`() {
        assertTrue(syncedOnlyText(1).startsWith("U 1 dne"))
        assertTrue(syncedOnlyText(3).startsWith("U 3 dnů"))
        assertTrue(syncedOnlyText(1).contains("bez nálady"))
    }

    @Test
    fun `the detail's short marker is the agreed Czech hint`() {
        assertEquals("bez nálady — check-in nevyplněn", UNRECORDED_HINT)
    }
}
