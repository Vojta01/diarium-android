package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The "Tenhle den z historie" picker — [targetMemoryDates] and [pickBestMemory]
 * are pure functions of today's date and the loaded rows, so these tests pass
 * both in and assert the result: no clock, no transport, no Android.
 */
class OnThisDayTest {

    private val today = "2026-10-14"

    /** Picks from rows placed on lane dates, mirroring the dashboard's call. */
    private fun pick(vararg rows: Pair<String, DiaryEntry>): OnThisDayMemory? =
        pickBestMemory(today, rows.toMap())

    // ── Target dates ───────────────────────────────────────────────────────────

    @Test
    fun `target dates are one year, six months and one month back in priority order`() {
        assertEquals(
            listOf(
                MemoryHorizon.ONE_YEAR to "2025-10-14",
                MemoryHorizon.SIX_MONTHS to "2026-04-14",
                MemoryHorizon.ONE_MONTH to "2026-09-14",
            ),
            targetMemoryDates("2026-10-14"),
        )
    }

    @Test
    fun `a leap day clamps to the last valid day a year back`() {
        // 2027 is not a leap year: minusYears(1) must clamp 02-29 to 02-28 via
        // LocalDate's native month-end rule, not roll over into March.
        assertEquals(
            MemoryHorizon.ONE_YEAR to "2027-02-28",
            targetMemoryDates("2028-02-29").first(),
        )
    }

    @Test
    fun `a leap day keeps the month lanes on valid dates`() {
        val targets = targetMemoryDates("2028-02-29")

        assertEquals(MemoryHorizon.SIX_MONTHS to "2027-08-29", targets[1])
        assertEquals(MemoryHorizon.ONE_MONTH to "2028-01-29", targets[2])
    }

    @Test
    fun `a month-end today clamps the month lanes too`() {
        // 31 March minus one month has no 31 February, so the day clamps to the
        // 28th; the six-month lane lands on 30 September, the last valid day.
        val targets = targetMemoryDates("2026-03-31")

        assertEquals(MemoryHorizon.SIX_MONTHS to "2025-09-30", targets[1])
        assertEquals(MemoryHorizon.ONE_MONTH to "2026-02-28", targets[2])
    }

    // ── Priority ───────────────────────────────────────────────────────────────

    @Test
    fun `the oldest qualifying lane wins`() {
        // A year-old memory beats the month-old one even when both qualify: the
        // rarer find is the point of the card.
        val memory = pick(
            "2025-10-14" to DiaryEntry(mood = 4, note = "Před rokem"),
            "2026-04-14" to DiaryEntry(mood = 3, note = "Před půl rokem"),
            "2026-09-14" to DiaryEntry(mood = 5, note = "Před měsícem"),
        )

        assertEquals("2025-10-14", memory?.date)
        assertEquals(MemoryHorizon.ONE_YEAR, memory?.horizon)
        assertEquals("Před rokem", memory?.entry?.note)
    }

    @Test
    fun `a mood-only year-old day falls through to the six-month lane`() {
        val memory = pick(
            "2025-10-14" to DiaryEntry(mood = 4),
            "2026-04-14" to DiaryEntry(mood = 3, note = "Před půl rokem"),
        )

        assertEquals(MemoryHorizon.SIX_MONTHS, memory?.horizon)
        assertEquals("2026-04-14", memory?.date)
    }

    @Test
    fun `a mood-zero year-old day falls through to the six-month lane`() {
        // The phone sync wrote the row but nobody journaled the day, so even its
        // note must not turn it into a memory.
        val memory = pick(
            "2025-10-14" to DiaryEntry(mood = 0, note = "Synced row"),
            "2026-04-14" to DiaryEntry(mood = 3, note = "Před půl rokem"),
        )

        assertEquals(MemoryHorizon.SIX_MONTHS, memory?.horizon)
    }

    @Test
    fun `the one-month lane is the last fallback`() {
        val memory = pick(
            "2025-10-14" to DiaryEntry(mood = 4),
            "2026-09-14" to DiaryEntry(mood = 2, note = "Před měsícem"),
        )

        assertEquals(MemoryHorizon.ONE_MONTH, memory?.horizon)
        assertEquals("2026-09-14", memory?.date)
    }

    // ── Qualifying rules ───────────────────────────────────────────────────────

    @Test
    fun `a note alone is a memory`() {
        val memory = pick("2025-10-14" to DiaryEntry(mood = 3, note = "Dnes bylo hezky"))

        assertEquals(MemoryHorizon.ONE_YEAR, memory?.horizon)
    }

    @Test
    fun `a gratitude line alone is a memory`() {
        val memory = pick(
            "2025-10-14" to DiaryEntry(mood = 3, gratitude = listOf("", "rodina", "")),
        )

        assertEquals(MemoryHorizon.ONE_YEAR, memory?.horizon)
        assertEquals(listOf("rodina"), memory?.entry?.filteredGratitude())
    }

    @Test
    fun `a photo alone is a memory`() {
        val memory = pick(
            "2025-10-14" to DiaryEntry(mood = 3, photoPath = "photos/2025-10-14.jpg"),
        )

        assertEquals(MemoryHorizon.ONE_YEAR, memory?.horizon)
    }

    @Test
    fun `a mood-only day is not a memory`() {
        // The mood says how the day felt, not what happened — there is nothing to
        // re-read, so the card must skip the day entirely.
        assertNull(pick("2025-10-14" to DiaryEntry(mood = 5)))
    }

    @Test
    fun `blank content is not a memory`() {
        assertNull(
            pick(
                "2025-10-14" to DiaryEntry(
                    mood = 4,
                    note = "   ",
                    gratitude = listOf("", " ", ""),
                    photoPath = " ",
                ),
            ),
        )
    }

    @Test
    fun `mood zero with a note is not a memory`() {
        // mood == 0 means the owner never journaled the day (the phone sync alone
        // writes rows), so it fails the recorded-day gate before content matters.
        assertNull(pick("2025-10-14" to DiaryEntry(mood = 0, note = "Pouze sync")))
    }

    // ── No memory ──────────────────────────────────────────────────────────────

    @Test
    fun `an empty history has no memory`() {
        assertNull(pickBestMemory(today, emptyMap()))
    }

    @Test
    fun `a qualifying entry on a non-lane date is not a memory`() {
        // Only the three candidate dates are ever considered.
        assertNull(pick("2026-10-01" to DiaryEntry(mood = 5, note = "Jindy")))
    }
}
