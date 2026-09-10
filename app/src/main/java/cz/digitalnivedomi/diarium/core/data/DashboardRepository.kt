package cz.digitalnivedomi.diarium.core.data

import java.time.LocalDate

/**
 * One day of the dashboard's week window, as the UI draws it.
 *
 * [mood] is 0 for a day without an entry (the same "no value" the database stores),
 * so the screen can colour the cell without asking whether an entry exists.
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
    val averageMood: Double?,
    val screenTimeMinutes: Int?,
    val unlocks: Int?,
    val topApps: DashboardTopApps?,
    val reflection: DashboardReflection?,
    val lastGratitude: DashboardGratitude?,
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
 * - **streak** — consecutive days that have an entry, counting back from today; a
 *   missing today does **not** break a run that ended yesterday, it only means the
 *   count starts there. The streak is therefore never zeroed by an unfinished day.
 * - **average mood** — over the week window, ignoring days with no entry and days
 *   whose mood is 0.
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

            val week = weekWindow(today).map { date ->
                val entry = byDate[date]
                DashboardDay(
                    date = date,
                    mood = entry?.mood ?: 0,
                    hasEntry = entry != null,
                    screenTimeSeconds = entry?.phoneScreenTime,
                    unlocks = entry?.phoneUnlocks,
                )
            }

            // A mood of 0 means "not answered", not "the worst possible day": the
            // check-in stores 0 for an untouched picker, so averaging it in would
            // drag every week down for no reason.
            val moods = week.mapNotNull { day -> day.mood.takeIf { it > 0 } }
            // Null (never synced) is dropped; a synced 0 is kept, because "the phone
            // reported nothing" is data and must not read as "no sync yet".
            val seconds = week.mapNotNull { it.screenTimeSeconds }
            val unlockCounts = week.mapNotNull { it.unlocks }

            return DashboardData(
                today = today,
                todayEntry = byDate[today],
                streak = currentStreak(today, byDate.keys),
                longestStreak = longestStreak(byDate.keys),
                week = week,
                averageMood = if (moods.isEmpty()) null else moods.sum().toDouble() / moods.size,
                screenTimeMinutes = seconds
                    .takeIf { it.isNotEmpty() }
                    ?.let { values -> Math.round(values.sum() / 60.0).toInt() },
                unlocks = unlockCounts.takeIf { it.isNotEmpty() }?.sum(),
                topApps = latestTopApps(byDate),
                reflection = latestReflection(byDate),
                lastGratitude = latestGratitude(byDate),
            )
        }

        /**
         * Consecutive days with an entry, counting back from [today].
         *
         * When today has no entry the count starts at yesterday: a day that is not
         * over yet cannot break a run. With no history at all the result is 0.
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

        /** Longest run of consecutive days anywhere in the loaded window. */
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

        /** Top apps of the latest day that captured any — an old snapshot beats none. */
        fun latestTopApps(byDate: Map<String, DiaryEntry>): DashboardTopApps? =
            latestWith(byDate) { it.phoneTopApps.isNotEmpty() }
                ?.let { (date, entry) -> DashboardTopApps(date, entry.phoneTopApps) }

        /** The reflection the dashboard shows: the newest day that has one. */
        fun latestReflection(byDate: Map<String, DiaryEntry>): DashboardReflection? =
            latestWith(byDate) { !it.aiReflection.isNullOrBlank() }
                ?.let { (date, entry) -> DashboardReflection(date, entry.aiReflection.orEmpty()) }

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
