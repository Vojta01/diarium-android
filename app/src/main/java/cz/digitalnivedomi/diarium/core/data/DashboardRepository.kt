package cz.digitalnivedomi.diarium.core.data

import java.time.LocalDate

/**
 * One day of the dashboard's week window, as the UI draws it.
 *
 * [mood] is 0 for a day without a mood (the same "no value" the database stores),
 * so the screen can colour the cell without asking whether an entry exists.
 *
 * [hasEntry] says only that a synced `entries` row exists for the day; it is
 * **not** "the day was journaled". Under the owner's rule (see [isRecordedDay]) a
 * record needs a mood, and the phone sync writes a row for every day regardless.
 */
data class DashboardDay(
    val date: String,
    val mood: Int,
    val hasEntry: Boolean,
    /** Seconds the sync worker stored, or null when that day was never synced. */
    val screenTimeSeconds: Int?,
    val unlocks: Int?,
)

/** The latest day that has an AI reflection, with the text to render. */
data class DashboardReflection(val date: String, val text: String)

/**
 * The newest logged day and the reflection it carries, if any.
 *
 * The dashboard's reflection card keys off this — not off the newest day that
 * *has* a reflection — so it can never sit on an older day (it showed "Ze dne
 * 9. 9." while 10. 9. was on screen) while a newer entry goes unreflected.
 * [reflection] is null exactly when that newest entry has none, blank counting
 * as none.
 */
data class DashboardNewestEntry(val date: String, val entry: DiaryEntry) {
    /** The newest entry's AI reflection, or null when it has none. */
    val reflection: String? get() = entry.aiReflection?.takeIf { it.isNotBlank() }
}

/** The latest day that has gratitude lines, for the "last gratitude" block. */
data class DashboardGratitude(val date: String, val lines: List<String>, val moodEmoji: String)

/** The latest day the sync worker captured top apps for. */
data class DashboardTopApps(val date: String, val apps: List<PhoneTopApp>)

/** Everything the dashboard screen renders, derived from one list of entries. */
data class DashboardData(
    val today: String,
    val todayEntry: DiaryEntry?,
    val streak: Int,
    val longestStreak: Int,
    val week: List<DashboardDay>,
    /**
     * The same seven days, one week earlier — the baseline every "oproti minulému
     * týdnu" under a number is compared against. Empty when the read held no older
     * rows, and then the cards say there is nothing to compare instead of inventing
     * a zero.
     */
    val previousWeek: List<DashboardDay> = emptyList(),
    val averageMood: Double?,
    val screenTimeMinutes: Int?,
    val unlocks: Int?,
    val topApps: DashboardTopApps?,
    /** The same, restricted to the newest day whose list is long enough to rank. */
    val rankedTopApps: DashboardTopApps?,
    val reflection: DashboardReflection?,
    /**
     * The newest *recorded* day (mood filled) — the day the reflection card speaks
     * about. [newestEntry] may be a day the phone merely synced, which is not a
     * record at all and must not hide yesterday's reflection.
     */
    val newestRecorded: DashboardNewestEntry?,
    /** The newest logged day, whatever it holds. */
    val newestEntry: DashboardNewestEntry?,
    val lastGratitude: DashboardGratitude?,
    /**
     * Every loaded day's full record, keyed by ISO date ([LOAD_DAYS] days).
     *
     * The rows are already in memory — the read is a single range — so a tap on a day
     * in the week strip can open the whole day's detail (mood, gratitude, reflection)
     * without a second request. Empty in tests that build the dashboard by hand.
     */
    val entryByDate: Map<String, DiaryEntry> = emptyMap(),
)

/**
 * Reads the days the dashboard needs and derives every number on it.
 *
 * Two rules shape this class:
 *
 * 1. **One round-trip.** The screen needs a month of context (streak, longest run,
 *    week window, screen-time history), so [load] fetches a single range and derives
 *    everything from it. [derive] and its helpers are pure functions of the entry
 *    list — no clock, no I/O, no Android — which is why the rules below are unit
 *    tested directly instead of through a fake network.
 * 2. **A failure is a failure.** [load] returns a [Result] that fails when the read
 *    fails, instead of substituting an empty list: an empty list is a legitimate
 *    answer ("you have not written anything yet") and must not also mean "the
 *    request died", or a network error renders as a screen full of zeroes.
 *
 * The definitions mirror the web dashboard (`src/components/Dashboard.tsx` plus
 * `src/lib/stats.ts`), because both clients read the same rows and the numbers are
 * expected to agree:
 *
 * - **window** — [LOAD_DAYS] calendar days ending today, inclusive.
 * - **week overview / screen time** — the last [WEEK_DAYS] calendar days ending
 *   today, inclusive, oldest first; today is the last cell even when empty.
 * - **streak** — consecutive *recorded* days (mood filled, see [isRecordedDay]),
 *   counting back from today; a missing today does **not** break a run that ended
 *   yesterday, it only means the count starts there. The streak is therefore never
 *   zeroed by an unfinished day. A day the phone synced without a mood is treated
 *   exactly like a missing day — it neither extends nor breaks the run.
 * - **average mood** — over the week window, ignoring days with no mood and days
 *   whose mood is 0 (the same "recorded" test).
 *
 * The web keys the streak off any row, so a day the phone synced but nobody
 * journaled would extend the web's run. This app counts only mood days on purpose:
 * see [isRecordedDay] for why a row alone is not a record.
 */
class DashboardRepository(private val entries: EntriesRepository) {

    /**
     * Loads the [LOAD_DAYS]-day window ending on [today] and derives the dashboard.
     *
     * [today] is a parameter rather than a call to the system clock so the screen's
     * "today" is unambiguous and the tests are deterministic.
     */
    suspend fun load(today: String = LocalDate.now().toString()): Result<DashboardData> {
        val from = LocalDate.parse(today).minusDays((LOAD_DAYS - 1).toLong()).toString()
        return entries.loadRange(from = from, to = today).map { rows -> derive(today, rows) }
    }

    companion object {

        /**
         * Days fetched in the dashboard's single round-trip: comfortably more than
         * the week window, which is what lets the screen show a longest run.
         */
        const val LOAD_DAYS = 30

        /** Calendar days in the week overview and in the screen-time window. */
        const val WEEK_DAYS = 7

        /**
         * Derives the whole screen from the loaded rows.
         *
         * Pure: same rows in, same dashboard out — no clock, no network, no Android.
         * The input is treated as an unordered set of unique dates, so it does not
         * depend on the `date.desc` ordering the query happens to request.
         */
        fun derive(today: String, rows: List<DatedEntry>): DashboardData {
            val byDate: Map<String, DiaryEntry> = rows
                .filter { it.date.isNotBlank() }
                .associate { it.date to it.entry }

            // The owner's rule: a day is a record only when the mood is filled.
            // The phone sync writes an `entries` row for every day, so the streak
            // and longest run must key off recorded days, not off row presence —
            // a mood-less 2026-09-07 (5 h 58 min screen time, 196 unlocks) is
            // treated exactly like a missing day.
            val recordedDates: Set<String> = rows
                .filter { it.date.isNotBlank() && isRecordedDay(it.entry.mood) }
                .map { it.date }
                .toSet()

            val week = daysFor(weekWindow(today), byDate)
            // Same shape, one week back: the trend captions compare like with like.
            val previousWeek = daysFor(previousWindow(today), byDate)

            // A mood of 0 means "not answered", not "the worst possible day": the
            // check-in stores 0 for an untouched picker, so averaging it in would
            // drag every week down for no reason.
            val moods = week.mapNotNull { day -> day.mood.takeIf { isRecordedDay(it) } }
            // Null (never synced) is dropped; a synced 0 is kept, because "the phone
            // reported nothing" is data and must not read as "no sync yet".
            val seconds = week.mapNotNull { it.screenTimeSeconds }
            val unlockCounts = week.mapNotNull { it.unlocks }

            return DashboardData(
                today = today,
                todayEntry = byDate[today],
                streak = currentStreak(today, recordedDates),
                longestStreak = longestStreak(recordedDates),
                week = week,
                previousWeek = previousWeek,
                averageMood = if (moods.isEmpty()) null else moods.sum().toDouble() / moods.size,
                screenTimeMinutes = seconds
                    .takeIf { it.isNotEmpty() }
                    ?.let { values -> Math.round(values.sum() / 60.0).toInt() },
                unlocks = unlockCounts.takeIf { it.isNotEmpty() }?.sum(),
                topApps = latestTopApps(byDate),
                rankedTopApps = rankedTopApps(byDate),
                reflection = latestReflection(byDate),
                newestEntry = newestEntry(byDate),
                newestRecorded = newestRecorded(byDate),
                lastGratitude = latestGratitude(byDate),
                entryByDate = byDate,
            )
        }

        /**
         * Consecutive *recorded* days (mood filled), counting back from [today].
         *
         * [dates] must already be the recorded set — [derive] filters by
         * [isRecordedDay] before calling, so a day the phone synced without a mood
         * is not in it. When today is not recorded the count starts at yesterday:
         * a day that is not over yet cannot break a run. With no history at all
         * the result is 0.
         *
         * Exact up to [LOAD_DAYS] days and a lower bound beyond that, since only a
         * window is ever loaded.
         */
        fun currentStreak(today: String, dates: Set<String>): Int {
            var cursor = LocalDate.parse(today)
            if (today !in dates) cursor = cursor.minusDays(1)
            var count = 0
            while (cursor.toString() in dates) {
                count++
                cursor = cursor.minusDays(1)
            }
            return count
        }

        /** Longest run of consecutive recorded days anywhere in the loaded window. */
        fun longestStreak(dates: Set<String>): Int {
            val days = dates.mapNotNull { parseDate(it) }.distinct().sorted()
            var best = 0
            var run = 0
            var previous: LocalDate? = null
            for (day in days) {
                run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
                if (run > best) best = run
                previous = day
            }
            return best
        }

        /** The [WEEK_DAYS] calendar days ending on [today], oldest first. */
        fun weekWindow(today: String): List<String> {
            val end = LocalDate.parse(today)
            return (WEEK_DAYS - 1 downTo 0).map { back -> end.minusDays(back.toLong()).toString() }
        }

        /**
         * The seven calendar days *before* [weekWindow]: `today - 13 .. today - 7`.
         * The unchanged week boundary keeps both windows the same length, which is
         * what makes a percentage between them honest.
         */
        fun previousWindow(today: String): List<String> {
            val end = LocalDate.parse(today).minusDays(WEEK_DAYS.toLong())
            return (WEEK_DAYS - 1 downTo 0).map { back -> end.minusDays(back.toLong()).toString() }
        }

        /**
         * One [DashboardDay] per date, missing days included as mood-less placeholders
         * so both week windows always have exactly [WEEK_DAYS] entries and a chart can
         * line them up without knowing which days exist.
         */
        fun daysFor(dates: List<String>, byDate: Map<String, DiaryEntry>): List<DashboardDay> =
            dates.map { date ->
                val entry = byDate[date]
                DashboardDay(
                    date = date,
                    mood = entry?.mood ?: 0,
                    hasEntry = entry != null,
                    screenTimeSeconds = entry?.phoneScreenTime,
                    unlocks = entry?.phoneUnlocks,
                )
            }

        /** Top apps of the latest day that captured any — an old snapshot beats none. */
        fun latestTopApps(byDate: Map<String, DiaryEntry>): DashboardTopApps? =
            latestWith(byDate) { it.phoneTopApps.isNotEmpty() }
                ?.let { (date, entry) -> DashboardTopApps(date, entry.phoneTopApps) }

        /** Below this a list cannot show a ranking, and the card says so instead. */
        const val RANKED_TOP_APPS_MIN = 3

        /**
         * Top apps of the newest day whose list is long enough to rank.
         *
         * A day the phone synced this morning holds two or three apps and would
         * replace a complete snapshot from yesterday with a stub — the defect the
         * owner reported on 2026-09-12 ("seznam používaných aplikací"). The card
         * prefers the newest *rankable* day and only falls back to [latestTopApps]
         * when no day qualifies; the date is printed with the list, so an older
         * snapshot never pretends to be today.
         */
        fun rankedTopApps(byDate: Map<String, DiaryEntry>): DashboardTopApps? =
            latestWith(byDate) { it.phoneTopApps.size >= RANKED_TOP_APPS_MIN }
                ?.let { (date, entry) -> DashboardTopApps(date, entry.phoneTopApps) }

        /**
         * The newest *recorded* day (mood filled) — the day the reflection card
         * talks about.
         *
         * The owner's rule (2026-09-12): a day the phone synced without a mood is
         * not a record, so its empty reflection must not hide the text of the last
         * day that has one ("chci, aby tam byla zatím ta včerejší z 11. 9.").
         */
        fun newestRecorded(byDate: Map<String, DiaryEntry>): DashboardNewestEntry? {
            val date = byDate
                .filterValues { isRecordedDay(it.mood) }
                .keys
                .sortedDescending()
                .firstOrNull()
                ?: return null
            return DashboardNewestEntry(date, byDate.getValue(date))
        }

        /** The reflection the dashboard shows: the newest day that has one. */
        fun latestReflection(byDate: Map<String, DiaryEntry>): DashboardReflection? =
            latestWith(byDate) { !it.aiReflection.isNullOrBlank() }
                ?.let { (date, entry) -> DashboardReflection(date, entry.aiReflection.orEmpty()) }

        /**
         * The newest logged day, whatever it holds — the reflection card keys off
         * this so a newer unreflected day is never hidden behind an older one.
         */
        fun newestEntry(byDate: Map<String, DiaryEntry>): DashboardNewestEntry? {
            val date = byDate.keys.sortedDescending().firstOrNull() ?: return null
            return DashboardNewestEntry(date, byDate.getValue(date))
        }

        /** The newest day with gratitude lines (the web dashboard's "last gratitude"). */
        fun latestGratitude(byDate: Map<String, DiaryEntry>): DashboardGratitude? =
            latestWith(byDate) { it.filteredGratitude().isNotEmpty() }
                ?.let { (date, entry) ->
                    DashboardGratitude(date, entry.filteredGratitude(), entry.moodEmoji)
                }

        /** Newest date (ISO sorts lexicographically) whose entry matches [predicate]. */
        private fun latestWith(
            byDate: Map<String, DiaryEntry>,
            predicate: (DiaryEntry) -> Boolean,
        ): Pair<String, DiaryEntry>? {
            for (date in byDate.keys.sortedDescending()) {
                val entry = byDate.getValue(date)
                if (predicate(entry)) return date to entry
            }
            return null
        }

        /** ISO date to [LocalDate]; null for a value the database should never hold. */
        private fun parseDate(date: String): LocalDate? =
            runCatching { LocalDate.parse(date) }.getOrNull()
    }
}
