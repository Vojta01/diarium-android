package cz.digitalnivedomi.diarium.ui.history

import cz.digitalnivedomi.diarium.core.data.isRecordedDay
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The calendar maths behind "Historie", as pure functions.
 *
 * Deliberately free of Compose and Android: the month grid, the leading blanks, the
 * day counts and the Czech names are the parts that are easy to get subtly wrong
 * (off-by-one weeks, leap February, December → January), so they are unit tested
 * directly instead of through a rendered screen.
 *
 * The rules mirror the web calendar (`src/components/CalendarView.tsx`):
 *
 * - weeks start on **Monday** — `calendarDays` pads with `firstDay.getDay() - 1`
 *   blanks and wraps Sunday back to 6, which is exactly [leadingBlanks];
 * - one cell per day of the month, numbered from 1 to the month's length;
 * - a month is stepped one whole month at a time, rolling the year over;
 * - "today" is compared **against a supplied day**, never against the clock, so the
 *   screen's notion of today and a test's expectation cannot drift apart.
 *
 * Names are verbatim from the web's `calendar.day_names` / `calendar.month_names`.
 */
object HistoryCalendar {

    /** Days in a week; the grid is always seven cells wide. */
    const val DAYS_PER_WEEK = 7

    /** Czech weekday abbreviations, Monday first (`calendar.day_names`). */
    val WEEKDAY_NAMES: List<String> = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")

    /** Czech month names, January first (`calendar.month_names`). */
    val MONTH_NAMES: List<String> = listOf(
        "Leden", "Únor", "Březen", "Duben", "Květen", "Červen",
        "Červenec", "Srpen", "Září", "Říjen", "Listopad", "Prosinec",
    )

    /** The month the calendar opens on, 1-based like `LocalDate.monthValue`. */
    val FIRST_MONTH = 1

    /** December. */
    val LAST_MONTH = 12

    /** "Září" for 9; a month outside 1..12 is clamped rather than thrown at. */
    fun monthName(month: Int): String = MONTH_NAMES[(month - 1).coerceIn(0, MONTH_NAMES.size - 1)]

    /** The month header: "Září 2026". */
    fun title(year: Int, month: Int): String = "${monthName(month)} $year"

    /** Day 1 of the month. */
    fun firstOfMonth(year: Int, month: Int): LocalDate = LocalDate.of(year, month, 1)

    /** The month's last day — 28/29/30/31, leap year included. */
    fun lastOfMonth(year: Int, month: Int): LocalDate =
        firstOfMonth(year, month).withDayOfMonth(daysInMonth(year, month))

    /** How many days the month has. */
    fun daysInMonth(year: Int, month: Int): Int = firstOfMonth(year, month).lengthOfMonth()

    /**
     * Empty cells before day 1, Monday-based: Monday is 0 … Sunday is 6.
     *
     * This is the web's `startDow` and the one number that decides whether the grid
     * lines up with the weekday headers.
     */
    fun leadingBlanks(year: Int, month: Int): Int = firstOfMonth(year, month).dayOfWeek.value - 1

    /** Monday-based index of a date: Monday 0 … Sunday 6. */
    fun weekdayIndex(date: LocalDate): Int = date.dayOfWeek.value - 1

    /** "Po" … "Ne" for a date. */
    fun weekdayName(date: LocalDate): String = WEEKDAY_NAMES[weekdayIndex(date)]

    /**
     * One month as the grid draws it: one `null` per [leadingBlanks] cell, then the
     * month's days in order. The result is **not** padded to a full week — the UI
     * fills the tail of the last row, so a month never grows phantom cells here.
     */
    fun monthGrid(year: Int, month: Int): List<LocalDate?> {
        val blanks = List(leadingBlanks(year, month)) { null }
        val days = (1..daysInMonth(year, month)).map { day -> LocalDate.of(year, month, day) }
        return blanks + days
    }

    /** Steps [delta] whole months from [year]/[month], rolling the year over. */
    fun stepMonth(year: Int, month: Int, delta: Int): Pair<Int, Int> {
        val moved = firstOfMonth(year, month).plusMonths(delta.toLong())
        return moved.year to moved.monthValue
    }

    /** The next month (December → January of the next year). */
    fun nextMonth(year: Int, month: Int): Pair<Int, Int> = stepMonth(year, month, 1)

    /** The previous month (January → December of the previous year). */
    fun previousMonth(year: Int, month: Int): Pair<Int, Int> = stepMonth(year, month, -1)

    /** The device's today — only ever used to seed the screen's `today`. */
    fun today(): LocalDate = LocalDate.now()

    /** ISO `yyyy-MM-dd`, the key the entries map is stored under. */
    fun iso(date: LocalDate): String = date.toString()

    /** True when [date] is the day the screen is calling today. */
    fun isToday(date: LocalDate, today: LocalDate): Boolean = date == today

    /** A day the user cannot open yet. */
    fun isFuture(date: LocalDate, today: LocalDate): Boolean = date.isAfter(today)

    /**
     * Whether the calendar marks a day as **logged** — the mood-filled rule, shared
     * with the rest of the app (see [isRecordedDay]).
     *
     * [mood] is nullable on purpose: a row the phone synced without a mood (screen
     * time, unlocks, top apps) must get the same neutral cell as a day with no row at
     * all, and only a filled mood may paint the mood colour and emoji. The history
     * screen asks this once per cell rather than testing `entry != null`, so the
     * marking rule has exactly one definition.
     */
    fun isLogged(mood: Int?): Boolean = isRecordedDay(mood)

    /**
     * Whether a cell reacts to a tap: every day up to and including [today]. Future
     * days — and, in the grid, the leading blanks [monthGrid] returns as `null` — are
     * inert, so the calendar can never open a check-in for a day that has not happened.
     */
    fun isSelectable(date: LocalDate, today: LocalDate): Boolean = !isFuture(date, today)

    /** The weekday headers in order, for a `forEach` over the grid's columns. */
    fun weekdayHeaders(): List<String> = WEEKDAY_NAMES

    /** Weekday of a raw [DayOfWeek], for callers that already hold the enum. */
    fun weekdayName(dayOfWeek: DayOfWeek): String = WEEKDAY_NAMES[dayOfWeek.value - 1]
}
