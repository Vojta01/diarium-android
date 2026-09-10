package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import java.time.LocalDate

/**
 * Everything the statistics screens read, produced by one range read.
 *
 * The window helpers live here so the screens never re-derive a date: [window] is
 * the "7 dní" / "30 dní" slice and [yearDays] is what the year grid colours. See
 * [StatsRepository.LOAD_DAYS] for how much history [StatsData.days] can hold.
 */
data class StatsData(
    /** The ISO date the read ended on — the range's right edge and the screens' "today". */
    val today: String,
    /** Every loaded day, oldest first, at most one per date. */
    val days: List<StatsDay>,
) {

    /** The [daysCount] calendar days ending at [today], oldest first. */
    fun window(daysCount: Int): List<StatsDay> = StatsMath.window(days, today, daysCount)

    /** The calendar year [today] falls in; the year-in-pixels view's year. */
    val year: Int
        get() = today.take(4).toIntOrNull() ?: LocalDate.now().year

    /** The loaded days of the calendar [year] (defaults to [year]). */
    fun yearDays(year: Int = this.year): List<StatsDay> = StatsMath.yearDays(days, year)
}

/**
 * The statistics screens' read, built on [EntriesRepository.loadRange] exactly like
 * [DashboardRepository] is — one round-trip, the same query shape, the same Czech
 * failure sentences.
 *
 * **How much history one read holds.** The year view is the longest window the
 * statistics tab can show, so the read asks for [LOAD_DAYS] days (400) ending today
 * and caps the response at [LOAD_LIMIT] rows. 400 is deliberate:
 *
 * - a calendar year needs 366 days, and 400 leaves the 30-day and 7-day windows with
 *   room to spare, so switching the range never triggers a second request;
 * - it is a hard ceiling on the year grid, which can therefore only colour the last
 *   400 days — a day older than that stays untracked even though the database still
 *   holds it. The grid says so above the pixels. (The web calls `.limit(1000)`, so it
 *   can paint a whole past year; the native read trades that for a bounded payload.)
 *
 * A failure is never a screen full of zeroes: [load] hands the caller a failed
 * [Result] whose message is already the Czech sentence for the cause — a phone
 * without signal reads `Nepodařilo se připojit k serveru.`, a signed-out session reads
 * `Přihlášení vypršelo, přihlas se znovu.`, and an HTTP error carries the server's own
 * message or `Načtení přehledu se nezdařilo (…)`. The stats screen shows that sentence
 * with a retry button; it must never be mistaken for "the user has no data yet".
 */
class StatsRepository(private val entries: EntriesRepository) {

    /**
     * Loads the statistics window ending at [today] (inclusive, ISO) in one
     * round-trip, already mapped into [StatsMath]'s input.
     *
     * [today] is a parameter rather than `LocalDate.now()` inside the body so the
     * window, the tests and the reload-after-resume path all agree on the day.
     */
    suspend fun load(today: String = LocalDate.now().toString()): Result<StatsData> {
        val from = LocalDate.parse(today).minusDays((LOAD_DAYS - 1).toLong()).toString()
        val rows = entries.loadRange(from = from, to = today, limit = LOAD_LIMIT)
        if (rows.isFailure) {
            // Keep the read's own sentence; only a message-less failure gets the
            // generic one, so the screen always has something in Czech to show.
            val message = rows.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: ERROR_LOAD
            return Result.failure(IllegalStateException(message, rows.exceptionOrNull()))
        }
        return Result.success(derive(today, rows.getOrThrow()))
    }

    companion object {

        /**
         * How many days one statistics read covers, ending today — the year view's
         * requirement (366) plus slack. Documents the year grid's hard limit: older
         * days cannot be coloured by this read.
         */
        const val LOAD_DAYS = 400

        /**
         * Safety cap for the read. One row per day is all the table stores per user
         * (`save_daily_entry`'s conflict target is `(user_id, date)`), and
         * [LOAD_DAYS] days can never produce more than [LOAD_DAYS] rows, so this cap
         * only guards a malformed range — it never truncates a real read.
         */
        const val LOAD_LIMIT = 400

        /** Shown only when a failed read somehow arrives without a message. */
        const val ERROR_LOAD = "Načtení statistik se nezdařilo."

        /**
         * Maps the rows of one range read into [StatsData].
         *
         * The rows arrive `date.desc`. They are re-keyed by date and sorted ascending
         * because every [StatsMath] function assumes an unordered set of days, and
         * they are de-duplicated by the same rule `ScreenTimeChart.tsx` uses when it
         * groups a day's entries — the row with the most screen time wins, `null`
         * losing to any number. A duplicate should not exist at all, but a stale
         * client that wrote two rows for one date must not turn a day into two bars.
         */
        fun derive(today: String, rows: List<DatedEntry>): StatsData {
            val byDate = linkedMapOf<String, DatedEntry>()
            rows.forEach { row ->
                if (row.date.isBlank()) return@forEach
                val current = byDate[row.date]
                val keepNew = current == null ||
                    (row.entry.phoneScreenTime ?: -1) > (current.entry.phoneScreenTime ?: -1)
                if (keepNew) byDate[row.date] = row
            }
            val days = byDate.values
                .sortedBy { it.date }
                .map { row -> toStatsDay(row.date, row.entry) }
            return StatsData(today = today, days = days)
        }

        /** The fields [StatsMath] needs from an entry, and nothing else. */
        fun toStatsDay(date: String, entry: DiaryEntry): StatsDay = StatsDay(
            date = date,
            mood = entry.mood,
            activities = entry.activities,
            screenTimeSeconds = entry.phoneScreenTime,
            unlocks = entry.phoneUnlocks,
            topApps = entry.phoneTopApps,
        )
    }
}
