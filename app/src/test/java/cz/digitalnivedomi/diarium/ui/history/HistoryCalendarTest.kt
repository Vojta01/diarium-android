package cz.digitalnivedomi.diarium.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The calendar maths behind Historie — no Compose, no Android, no clock.
 *
 * The dates are pinned rather than computed from `LocalDate.now()` so the expectations
 * are facts about the calendar (a Tuesday 1st leaves one blank, 2024 has a 29th of
 * February) and never move with the day the suite runs.
 */
class HistoryCalendarTest {

    @Test
    fun `weekday headers start on Monday and are Czech`() {
        assertEquals(listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne"), HistoryCalendar.WEEKDAY_NAMES)
        assertEquals(7, HistoryCalendar.DAYS_PER_WEEK)
        assertEquals(HistoryCalendar.WEEKDAY_NAMES, HistoryCalendar.weekdayHeaders())
    }

    @Test
    fun `month names are Czech and January is first`() {
        assertEquals("Leden", HistoryCalendar.monthName(1))
        assertEquals("Únor", HistoryCalendar.monthName(2))
        assertEquals("Červenec", HistoryCalendar.monthName(7))
        assertEquals("Září", HistoryCalendar.monthName(9))
        assertEquals("Prosinec", HistoryCalendar.monthName(12))
        assertEquals(12, HistoryCalendar.MONTH_NAMES.size)
    }

    @Test
    fun `a month outside 1 to 12 is clamped instead of thrown at`() {
        assertEquals("Leden", HistoryCalendar.monthName(0))
        assertEquals("Prosinec", HistoryCalendar.monthName(13))
    }

    @Test
    fun `the header is the Czech month and the year`() {
        assertEquals("Září 2026", HistoryCalendar.title(2026, 9))
        assertEquals("Leden 2026", HistoryCalendar.title(2026, 1))
        assertEquals("Prosinec 2025", HistoryCalendar.title(2025, 12))
    }

    /**
     * One month per weekday, each verified against `java.time`: Monday 1st needs no
     * blanks, Sunday 1st needs six. This is the number that decides whether day 1 sits
     * under its own weekday header.
     */
    @Test
    fun `a month starting on each weekday gets the right number of leading blanks`() {
        assertEquals(0, HistoryCalendar.leadingBlanks(2025, 9)) // pondělí
        assertEquals(1, HistoryCalendar.leadingBlanks(2025, 4)) // úterý
        assertEquals(2, HistoryCalendar.leadingBlanks(2025, 1)) // středa
        assertEquals(3, HistoryCalendar.leadingBlanks(2025, 5)) // čtvrtek
        assertEquals(4, HistoryCalendar.leadingBlanks(2025, 8)) // pátek
        assertEquals(5, HistoryCalendar.leadingBlanks(2025, 2)) // sobota
        assertEquals(6, HistoryCalendar.leadingBlanks(2025, 6)) // neděle
    }

    @Test
    fun `day one lands in its own weekday column`() {
        // 2026-09-01 is a Tuesday: one blank, then the 1st at index 1 (= "Út").
        assertEquals(1, HistoryCalendar.leadingBlanks(2026, 9))
        assertEquals(1, HistoryCalendar.monthGrid(2026, 9).indexOf(LocalDate.of(2026, 9, 1)))

        // 2025-09-01 is a Monday: no blank at all, the 1st is the very first cell.
        assertEquals(0, HistoryCalendar.leadingBlanks(2025, 9))
        assertEquals(0, HistoryCalendar.monthGrid(2025, 9).indexOf(LocalDate.of(2025, 9, 1)))

        // 2025-06-01 is a Sunday: six blanks, the 1st is the last cell of the first row.
        assertEquals(6, HistoryCalendar.monthGrid(2025, 6).indexOf(LocalDate.of(2025, 6, 1)))
    }

    @Test
    fun `the grid is the leading blanks then the month's days in order`() {
        val grid = HistoryCalendar.monthGrid(2026, 9)

        assertEquals(1 + 30, grid.size)
        assertNull(grid[0])
        assertEquals(LocalDate.of(2026, 9, 1), grid[1])
        assertEquals(LocalDate.of(2026, 9, 30), grid.last())
        assertEquals(30, grid.filterNotNull().size)
    }

    @Test
    fun `a month with no leading blank starts the grid on day one`() {
        val grid = HistoryCalendar.monthGrid(2025, 9)

        assertEquals(30, grid.size)
        assertEquals(LocalDate.of(2025, 9, 1), grid.first())
        assertEquals(30, grid.filterNotNull().size)
        assertTrue(grid.none { it == null })
    }

    @Test
    fun `a leap February has twenty-nine days and the grid ends there`() {
        assertEquals(29, HistoryCalendar.daysInMonth(2024, 2))
        assertEquals(LocalDate.of(2024, 2, 29), HistoryCalendar.lastOfMonth(2024, 2))

        // 2024-02-01 is a Thursday: three blanks + 29 days = 32 cells.
        val leap = HistoryCalendar.monthGrid(2024, 2)
        assertEquals(3, HistoryCalendar.leadingBlanks(2024, 2))
        assertEquals(32, leap.size)
        assertEquals(LocalDate.of(2024, 2, 29), leap.last())
        assertEquals(29, leap.filterNotNull().size)

        // And the common year right after it is one day shorter.
        assertEquals(28, HistoryCalendar.daysInMonth(2025, 2))
        assertEquals(LocalDate.of(2025, 2, 28), HistoryCalendar.lastOfMonth(2025, 2))
        assertEquals(28, HistoryCalendar.monthGrid(2025, 2).filterNotNull().size)
    }

    @Test
    fun `month boundaries have the length they should`() {
        assertEquals(31, HistoryCalendar.daysInMonth(2026, 1))
        assertEquals(30, HistoryCalendar.daysInMonth(2026, 4))
        assertEquals(31, HistoryCalendar.daysInMonth(2026, 5))
        assertEquals(30, HistoryCalendar.daysInMonth(2026, 6))
        assertEquals(31, HistoryCalendar.daysInMonth(2026, 7))
        assertEquals(31, HistoryCalendar.daysInMonth(2026, 8))
        assertEquals(30, HistoryCalendar.daysInMonth(2026, 9))
        assertEquals(31, HistoryCalendar.daysInMonth(2026, 12))
        assertEquals(28, HistoryCalendar.daysInMonth(2026, 2))
        assertEquals(31, HistoryCalendar.daysInMonth(2026, 3))
    }

    @Test
    fun `the month's first and last day are the boundaries of the read`() {
        assertEquals(LocalDate.of(2026, 9, 1), HistoryCalendar.firstOfMonth(2026, 9))
        assertEquals(LocalDate.of(2026, 9, 30), HistoryCalendar.lastOfMonth(2026, 9))
        assertEquals(LocalDate.of(2026, 12, 31), HistoryCalendar.lastOfMonth(2026, 12))
    }

    @Test
    fun `month stepping rolls the year over in both directions`() {
        assertEquals(2026 to 10, HistoryCalendar.nextMonth(2026, 9))
        assertEquals(2026 to 8, HistoryCalendar.previousMonth(2026, 9))
        assertEquals(2025 to 1, HistoryCalendar.nextMonth(2024, 12))
        assertEquals(2026 to 12, HistoryCalendar.previousMonth(2027, 1))
    }

    @Test
    fun `month stepping never lands on a day the target month does not have`() {
        // Stepping from a 31st, or into a February, must not overflow — the maths goes
        // through the 1st of the month on purpose.
        assertEquals(2026 to 2, HistoryCalendar.nextMonth(2026, 1))
        assertEquals(2026 to 4, HistoryCalendar.nextMonth(2026, 3))
        assertEquals(2027 to 3, HistoryCalendar.nextMonth(2027, 2))
        assertEquals(2026 to 12, HistoryCalendar.previousMonth(2027, 1))
    }

    @Test
    fun `twelve steps is exactly one year`() {
        assertEquals(2027 to 9, HistoryCalendar.stepMonth(2026, 9, 12))
        assertEquals(2025 to 9, HistoryCalendar.stepMonth(2026, 9, -12))
        assertEquals(2026 to 9, HistoryCalendar.stepMonth(2026, 9, 0))
    }

    @Test
    fun `today is compared against the supplied day, never the clock`() {
        val today = LocalDate.of(2026, 9, 10)

        assertTrue(HistoryCalendar.isToday(today, today))
        assertFalse(HistoryCalendar.isToday(LocalDate.of(2026, 9, 9), today))

        assertFalse(HistoryCalendar.isFuture(today, today))
        assertFalse(HistoryCalendar.isFuture(LocalDate.of(2026, 9, 9), today))
        assertTrue(HistoryCalendar.isFuture(LocalDate.of(2026, 9, 11), today))
    }

    @Test
    fun `only days up to and including today can be tapped`() {
        val today = LocalDate.of(2026, 9, 10)

        assertTrue(HistoryCalendar.isSelectable(today, today))
        assertTrue(HistoryCalendar.isSelectable(LocalDate.of(2026, 9, 1), today))
        assertTrue(HistoryCalendar.isSelectable(LocalDate.of(2026, 8, 31), today))
        assertFalse(HistoryCalendar.isSelectable(LocalDate.of(2026, 9, 11), today))
        assertFalse(HistoryCalendar.isSelectable(LocalDate.of(2026, 10, 1), today))
        assertFalse(HistoryCalendar.isSelectable(LocalDate.of(2027, 1, 1), today))
    }

    @Test
    fun `every day of the visible month but the future ones is tappable`() {
        val today = LocalDate.of(2026, 9, 10)

        val selectable = HistoryCalendar.monthGrid(2026, 9)
            .filterNotNull()
            .filter { HistoryCalendar.isSelectable(it, today) }

        // The 1st through the 10th — ten days, and nothing from the 11th onward.
        assertEquals(10, selectable.size)
        assertEquals(LocalDate.of(2026, 9, 1), selectable.first())
        assertEquals(LocalDate.of(2026, 9, 10), selectable.last())
    }

    @Test
    fun `weekday names follow the date, Monday first`() {
        assertEquals("Čt", HistoryCalendar.weekdayName(LocalDate.of(2026, 9, 10)))
        assertEquals("Po", HistoryCalendar.weekdayName(LocalDate.of(2025, 9, 1)))
        assertEquals("Ne", HistoryCalendar.weekdayName(LocalDate.of(2025, 6, 1)))
        assertEquals("Pá", HistoryCalendar.weekdayName(LocalDate.of(2025, 8, 1)))
        assertEquals("Ne", HistoryCalendar.weekdayName(DayOfWeek.SUNDAY))
        assertEquals("Po", HistoryCalendar.weekdayName(DayOfWeek.MONDAY))
    }

    @Test
    fun `the weekday index is Monday zero`() {
        assertEquals(0, HistoryCalendar.weekdayIndex(LocalDate.of(2025, 9, 1)))
        assertEquals(3, HistoryCalendar.weekdayIndex(LocalDate.of(2026, 9, 10)))
        assertEquals(6, HistoryCalendar.weekdayIndex(LocalDate.of(2025, 6, 1)))
    }

    @Test
    fun `the iso key is the one the entries map is keyed by`() {
        assertEquals("2026-09-10", HistoryCalendar.iso(LocalDate.of(2026, 9, 10)))
        assertEquals("2026-09-01", HistoryCalendar.iso(HistoryCalendar.firstOfMonth(2026, 9)))
        assertEquals("2026-09-30", HistoryCalendar.iso(HistoryCalendar.lastOfMonth(2026, 9)))
    }

    /**
     * The owner's rule (2026-09-11): the calendar marks a day as logged only when the
     * mood is filled. A day whose row came from the phone's sync — screen time and
     * unlocks, no mood — must look exactly like a day with no row at all.
     */
    @Test
    fun `only a filled mood makes the calendar mark a day as logged`() {
        assertTrue(HistoryCalendar.isLogged(1))
        assertTrue(HistoryCalendar.isLogged(3))
        assertTrue(HistoryCalendar.isLogged(5))
        // No row at all, and a synced-only row (the row mapper reads a missing `mood`
        // column as 0): both are unmarked, which is the point — the calendar cannot and
        // must not tell them apart.
        assertFalse(HistoryCalendar.isLogged(null))
        assertFalse(HistoryCalendar.isLogged(0))
    }

    @Test
    fun `a synced-only day and a day with no row get the same neutral cell`() {
        assertEquals(HistoryCalendar.isLogged(null), HistoryCalendar.isLogged(0))
        assertFalse(HistoryCalendar.isLogged(0))
    }
}
