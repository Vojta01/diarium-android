package cz.digitalnivedomi.diarium.ui.history

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Renders the real month card on the JVM and measures the day cells.
 *
 * The calendar bug this pins down was a layout bug, not calendar maths: a month whose
 * final week held fewer than seven days built a `Row` with fewer than seven weighted
 * children, so `weight(1f)` gave one day the entire row width. November 2026 is the
 * production case — it starts on a Sunday (six leading blanks) and ends with Monday
 * the 30th alone. These tests assert what the user sees: every day cell is one column
 * wide, including the days in a partial final week.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w432dp-h960dp-400dpi")
class HistoryCalendarLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** Mounts the production card; `today` is the month's last day so every day is live. */
    private fun showMonth(year: Int, month: Int) {
        compose.setContent {
            DiariumTheme {
                CalendarCard(
                    year = year,
                    month = month,
                    today = HistoryCalendar.lastOfMonth(year, month),
                    data = null,
                    loading = false,
                    selected = null,
                    onPrevious = {},
                    onNext = {},
                    onSelect = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun cellWidth(date: LocalDate): Float {
        val bounds = compose.onNodeWithTag("history_day_${HistoryCalendar.iso(date)}")
            .getUnclippedBoundsInRoot()
        return (bounds.right - bounds.left).value
    }

    private fun assertSameWidth(reference: LocalDate, vararg dates: LocalDate) {
        val expected = cellWidth(reference)
        dates.forEach { date ->
            assertEquals(
                "${HistoryCalendar.iso(date)} must be one of seven equal columns",
                expected,
                cellWidth(date),
                0.5f,
            )
        }
    }

    @Test
    fun `a month ending with one lone day keeps seven equal columns`() {
        showMonth(2026, 11)

        // 2 November is a Monday in a full week; 30 November is a Monday alone in the
        // final week. Before the fix the lone cell was seven times wider.
        assertSameWidth(
            LocalDate.of(2026, 11, 2),
            LocalDate.of(2026, 11, 30),
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 7),
            LocalDate.of(2026, 11, 29),
        )

        // And the whole month agrees, not just the row under test.
        val expected = cellWidth(LocalDate.of(2026, 11, 2))
        (1..30).forEach { day ->
            val date = LocalDate.of(2026, 11, day)
            assertEquals(
                "${HistoryCalendar.iso(date)} width",
                expected,
                cellWidth(date),
                0.5f,
            )
        }
    }

    @Test
    fun `a month ending with two trailing days keeps seven equal columns`() {
        showMonth(2026, 7)

        // July 2026's last row is 27–31 July followed by two empty slots.
        assertSameWidth(
            LocalDate.of(2026, 7, 6),
            LocalDate.of(2026, 7, 27),
            LocalDate.of(2026, 7, 30),
            LocalDate.of(2026, 7, 31),
        )
    }

    @Test
    fun `a leap february's four day final row keeps seven equal columns`() {
        showMonth(2024, 2)

        // 2024-02-01 is a Thursday; the last row is 26–29 February plus three slots.
        assertSameWidth(
            LocalDate.of(2024, 2, 5),
            LocalDate.of(2024, 2, 26),
            LocalDate.of(2024, 2, 27),
            LocalDate.of(2024, 2, 28),
            LocalDate.of(2024, 2, 29),
        )
    }
}
