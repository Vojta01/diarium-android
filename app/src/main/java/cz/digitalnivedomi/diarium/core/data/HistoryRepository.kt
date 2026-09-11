package cz.digitalnivedomi.diarium.core.data

import java.time.LocalDate

/**
 * One visible month of entries, keyed by ISO date — everything the "Historie"
 * screen renders from.
 *
 * [entries] holds **every** row the month read returned, including the days the
 * screen-time worker synced without the owner filling anything in. Those rows are
 * kept (the owner may want to see the synced screen time) but they must never be
 * counted or drawn as a journaled day — see [recordedCount] and [isRecorded], and
 * [isRecordedDay] for the owner's rule (2026-09-11): only a filled mood makes a day
 * a record. So there are two different questions, and they have different answers:
 * *does a row exist?* ([entryOn]) and *did the owner journal it?* ([isRecorded]).
 *
 * A day with no row at all is simply absent from [entries]; the calendar draws that
 * cell empty rather than inventing a zero entry, exactly like the web view
 * (`src/components/CalendarView.tsx` builds a `moodMap` the same way). A synced-only
 * row is the opposite case: present in [entries], yet still not a record.
 */
data class HistoryData(
    val year: Int,
    /** 1..12, the same numbering as [LocalDate.monthValue]. */
    val month: Int,
    /** The day the screen considered "today" when this month was read. */
    val today: String,
    /** ISO date -> that day's entry, synced-only rows included. */
    val entries: Map<String, DiaryEntry>,
) {
    /** How many days the loaded month has — leap February included. */
    val daysInMonth: Int get() = LocalDate.of(year, month, 1).lengthOfMonth()

    /** The check-in for an ISO [date], or null when that day has none. */
    fun entryOn(date: String): DiaryEntry? = entries[date]

    /**
     * The mood marker the calendar draws for a day: null when that day is *not* a
     * record. A row the phone synced without a mood is reported here as "no marker"
     * on purpose, so it paints the same neutral cell as a day with no row — see
     * [isRecordedDay].
     */
    fun moodOn(date: String): Int? = entries[date]?.mood?.takeIf { isRecordedDay(it) }

    /**
     * Did the owner journal [date]? True only when the day has a row **and** its mood
     * is filled — a day that only carries synced phone data (screen time, unlocks) is
     * not a record, per the owner's rule (see [isRecordedDay]).
     */
    fun isRecorded(date: String): Boolean = isRecordedDay(entries[date]?.mood)

    /**
     * True when [date] has a row that the phone synced but nobody journaled — the
     * "entry exists, mood missing" case the calendar must not present as a record.
     */
    fun isSyncedOnly(date: String): Boolean =
        entries[date] != null && !isRecordedDay(entries[date]?.mood)

    /**
     * How many days of the month count as records. This is the number the screen
     * prints ("máš N zápisů"), so a month of synced-only rows reports 0.
     */
    val recordedCount: Int get() = entries.values.count { isRecordedDay(it.mood) }

    /**
     * Rows that exist without a mood: days the phone synced but nobody journaled.
     * They stay in [entries] — only the count and the marking treat them as missing.
     */
    val syncedOnlyCount: Int get() = entries.size - recordedCount
}

/**
 * Reads the month the calendar is showing, as one round-trip over the range read.
 *
 * Why a wrapper instead of calling [EntriesRepository.loadRange] from the screen:
 *
 * 1. **The month's boundaries are calendar maths, not screen maths.** [load] owns
 *    "first day of the month … today", so the screen only ever says *which month*
 *    it is showing.
 * 2. **A failure is a failure.** The [Result] fails when the read fails and never
 *    collapses into an empty month: "you have not written anything in September"
 *    and "the request died" must not look the same on a calendar either. The Czech
 *    sentences come from [EntriesRepository] — the same ones the dashboard shows —
 *    so nothing here invents a second vocabulary for the same failure.
 * 3. **No future day is ever fetched.** The upper bound is [today], the same rule
 *    the dashboard's window uses, which is also what makes a future month a
 *    request that does not need to happen at all.
 *
 * [load] takes `today` as a parameter rather than reading the clock so the screen's
 * notion of today is unambiguous and the tests are deterministic.
 */
class HistoryRepository(private val entries: EntriesRepository) {

    /**
     * Loads the entries of `year`/`month` up to [today].
     *
     * A month that has not started yet is an empty history and costs no request —
     * the calendar already draws those days as inert.
     */
    suspend fun load(
        year: Int,
        month: Int,
        today: String = LocalDate.now().toString(),
    ): Result<HistoryData> {
        val todayDate = runCatching { LocalDate.parse(today) }.getOrNull() ?: LocalDate.now()
        if (runCatching { LocalDate.of(year, month, 1) }.isFailure) {
            return Result.failure(IllegalStateException("Neplatné období ($month/$year)."))
        }

        val range = monthRange(year, month, todayDate)
            ?: return Result.success(HistoryData(year, month, today, emptyMap()))

        return entries.loadRange(from = range.first, to = range.second)
            .map { rows -> derive(year, month, today, rows) }
    }

    companion object {

        /**
         * The inclusive `(from, to)` bounds of a single round-trip for the month,
         * or null when the whole month is still in the future.
         *
         * Pure: `today` is passed in, never read from the clock.
         */
        fun monthRange(year: Int, month: Int, today: LocalDate): Pair<String, String>? {
            val first = LocalDate.of(year, month, 1)
            if (first.isAfter(today)) return null
            val last = first.withDayOfMonth(first.lengthOfMonth())
            // The upper bound is today, not the month's end: nothing future-dated can
            // exist, and asking for it would be a read for days that cannot have rows.
            val to = if (last.isAfter(today)) today else last
            return first.toString() to to.toString()
        }

        /**
         * Derives the screen's month from the loaded rows.
         *
         * Pure: same rows in, same month out — no clock, no network, no Android. Rows
         * without a date are dropped instead of landing under an empty key.
         */
        fun derive(
            year: Int,
            month: Int,
            today: String,
            rows: List<DatedEntry>,
        ): HistoryData = HistoryData(
            year = year,
            month = month,
            today = today,
            entries = rows
                .filter { it.date.isNotBlank() }
                .associate { it.date to it.entry },
        )
    }
}
