package cz.digitalnivedomi.diarium.core.stats

import cz.digitalnivedomi.diarium.core.data.PhoneTopApp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * One day as the statistics screens read it.
 *
 * [mood] is the raw stored value: `0` means "the picker was never answered", not
 * "the worst possible day" (the check-in form stores 0 for an untouched picker).
 * Every average and every count below therefore skips it — [answered] is the single
 * test for "this day has a mood".
 *
 * [screenTimeSeconds] keeps the same null-vs-zero distinction the dashboard relies
 * on: `null` is "the sync worker never reported that day", `0` is "the phone said
 * nothing happened", and those two must not look alike.
 *
 * [topApps] reuses [PhoneTopApp] (minutes, as stored) rather than a second model —
 * the web normalises the same rows to seconds inside its charts.
 */
data class StatsDay(
    val date: String,
    val mood: Int = 0,
    val activities: List<String> = emptyList(),
    val screenTimeSeconds: Int? = null,
    val unlocks: Int? = null,
    val topApps: List<PhoneTopApp> = emptyList(),
) {
    /** True when a mood was actually answered (1..5). Mood 0 is "not answered". */
    val answered: Boolean get() = mood in StatsMath.MOOD_MIN..StatsMath.MOOD_MAX
}

/** One day carrying a mood — a trend point, a best/worst day, an activity extreme. */
data class MoodPoint(val date: String, val mood: Int)

/** One activity's relationship with mood — the web's "Aktivity vs nálada" row. */
data class ActivityStat(
    val name: String,
    /** Mean mood of the answered days that mentioned this activity. */
    val averageMood: Double,
    /** How many answered days mentioned it — the web's "N×". */
    val count: Int,
    /** Earliest answered day with the highest mood among [count] (web's `best`). */
    val bestDay: MoodPoint?,
    /** Latest answered day with the lowest mood among [count] (web's `worst`). */
    val worstDay: MoodPoint?,
    /**
     * Point-biserial correlation between "the activity happened" (1/0) and the day's
     * mood, over the answered days; null when either side has no variance. The web
     * ranks activities by [averageMood] only — this coefficient is the native
     * detail line, and it is what makes the degenerate cases explicit.
     */
    val correlation: Double?,
)

/** One calendar day of the screen-time window (a placeholder day has no entry). */
data class ScreenTimeDay(
    val date: String,
    val seconds: Int?,
    val unlocks: Int?,
    val hasApps: Boolean,
)

/** The three numbers above the screen-time bars, exactly as the web labels them. */
data class ScreenTimeSummary(
    /** Mean seconds of the days that actually reported screen time (> 0). */
    val averageSeconds: Double?,
    /** Seconds summed over every day of the window (missing days count as 0). */
    val totalSeconds: Int,
    /** Largest reported day, or null when nothing was reported. */
    val maxSeconds: Int?,
    val maxDate: String?,
)

/** One app's share of the window's screen time. */
data class AppUsage(val name: String, val seconds: Int, val share: Double)

/** Top apps plus the time the tracked apps do not cover ("Další čas"). */
data class TopAppsBreakdown(
    val apps: List<AppUsage>,
    val otherSeconds: Int,
    val totalSeconds: Int,
)

/** One day cell of the year grid; `day == 0` is the padding before/after a month. */
data class YearCell(val date: String, val day: Int, val mood: Int)

/** One month of the year grid, already cut into Monday-first weeks of seven cells. */
data class YearMonthGrid(
    val name: String,
    val index: Int,
    val weeks: List<List<YearCell>>,
    val daysInMonth: Int,
    val trackedDays: Int,
)

/** The year card's two numbers. */
data class YearStats(val averageMood: Double?, val trackedDays: Int)

/**
 * The statistics screens' arithmetic — pure Kotlin, no Android and no Compose, so
 * every number the screens show is unit tested directly instead of through a fake
 * network (`app/src/test/.../core/stats/`).
 *
 * The definitions are ported from the web app:
 *
 * - `src/lib/stats.ts` — the row shape, `MOOD_COLORS` / `MOOD_LABELS`.
 * - `src/components/AdvancedStats.tsx` — distribution, moving average, activity
 *   correlation, best/worst day.
 * - `src/components/ScreenTimeChart.tsx` — the anchor rule, the averages, top apps.
 * - `src/components/YearInPixels.tsx` — the month grid and the legend.
 *
 * Two deliberate differences, both decided by the native screen's own rules:
 *
 * 1. **A mood of 0 never counts.** The web's year card averages the raw `mood`
 *    column, so its "Průměrná nálada" is dragged down by days nobody answered.
 *    Here 0 means "not answered" and is excluded from every average, every count
 *    and every correlation — the same rule the dashboard already follows.
 * 2. **Windows are calendar days, not row slices.** `AdvancedStats` works on
 *    `entries.slice(-30)`, the last 30 *rows*; the native screens take the last N
 *    *calendar days* ending today ([window]). The two agree for anyone who writes
 *    daily and the calendar version can never present an old entry as a recent day.
 *
 * The input is always treated as an unordered set of days: every function sorts by
 * ISO date itself, so no result depends on the query's `order=date.desc`.
 */
object StatsMath {

    /** The stored "not answered" mood; excluded from every average and count. */
    const val MOOD_UNANSWERED = 0

    /** Lowest answered mood (web: `MOOD_LABELS[1]` = "😡 Hrozně"). */
    const val MOOD_MIN = 1

    /** Highest answered mood (web: `MOOD_LABELS[5]` = "😄 Skvěle"). */
    const val MOOD_MAX = 5

    /** Window of the web's "7denní průměr" (`AdvancedStats` moving average). */
    const val MOVING_AVERAGE_WINDOW = 7

    /** The web's `activityCorrelations.slice(0, 10)`. */
    const val ACTIVITY_LIMIT = 10

    /** How many apps the top-apps breakdown lists before folding the rest away. */
    const val TOP_APPS_LIMIT = 10

    /** The five mood values, in the web's bar order (`[1, 2, 3, 4, 5]`). */
    val MOOD_VALUES: List<Int> = (MOOD_MIN..MOOD_MAX).toList()

    /** `calendar.month_names` from the web's `cs.ts`, January first. */
    val MONTH_NAMES: List<String> = listOf(
        "Leden", "Únor", "Březen", "Duben", "Květen", "Červen",
        "Červenec", "Srpen", "Září", "Říjen", "Listopad", "Prosinec",
    )

    /** `calendar.day_names` / `screenTime.weekdays` from `cs.ts`, Monday first. */
    val WEEKDAY_LABELS: List<String> = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")

    // ── Mood ───────────────────────────────────────────────────────────────────

    /**
     * Counts how many days carried each mood 1..5 — the web's `moodCounts`, where
     * `last30.forEach(e => { if (e.mood >= 1 && e.mood <= 5) moodCounts[e.mood]++ })`.
     *
     * All five keys are always present (the web initialises 1..5 to 0), so the bar
     * chart draws five bars even when a mood never occurred. An answered day with a
     * value outside 1..5 is ignored, exactly like the web's range check.
     */
    fun moodDistribution(days: List<StatsDay>): Map<Int, Int> {
        val counts = MOOD_VALUES.associateWith { 0 }.toMutableMap()
        days.forEach { day ->
            if (day.answered) counts[day.mood] = (counts[day.mood] ?: 0) + 1
        }
        return counts
    }

    /** How many days of [days] carried an answered mood — the web's `totalMoods`. */
    fun answeredDays(days: List<StatsDay>): Int = days.count { it.answered }

    /**
     * Mean of the answered moods, or null when nothing was answered.
     *
     * The web's year card divides by *every* row; here mood 0 is "not answered" and
     * is dropped, so this is the same number the dashboard's "Ø nálada" shows.
     */
    fun averageMood(days: List<StatsDay>): Double? {
        val moods = days.filter { it.answered }.map { it.mood }
        if (moods.isEmpty()) return null
        return moods.sum().toDouble() / moods.size
    }

    /** The daily mood of every loaded day, oldest first — the web's `moodTrend`. */
    fun moodTrend(days: List<StatsDay>): List<MoodPoint> =
        sorted(days).map { MoodPoint(it.date, it.mood) }

    /**
     * The web's "7denní průměr": one value per day, each the mean of that day and
     * the days before it inside the window (`slice(max(0, i-6), i+1)`), so the series
     * ramps up over its first six days instead of starting empty.
     *
     * Two refinements over the web's version: unanswered days (`mood == 0`) are
     * skipped rather than averaged in, and a window with nothing answered yields
     * null — an answered-only average can never be dragged towards "not answered".
     * The result is aligned with [moodTrend].
     */
    fun movingAverage(
        days: List<StatsDay>,
        window: Int = MOVING_AVERAGE_WINDOW,
    ): List<Double?> {
        val size = window.coerceAtLeast(1)
        val moods = sorted(days).map { if (it.answered) it.mood else null }
        return moods.indices.map { index ->
            val from = (index - size + 1).coerceAtLeast(0)
            val slice = moods.subList(from, index + 1).filterNotNull()
            if (slice.isEmpty()) null else slice.sum().toDouble() / slice.size
        }
    }

    /**
     * Mean mood per weekday, answered days only.
     *
     * The web's `AdvancedStats` has no per-weekday block; this is the native
     * screen's addition, and it uses the same "answered days only" rule as
     * [averageMood]. Weekdays with no answered day are absent from the map.
     */
    fun weekdayAverages(days: List<StatsDay>): Map<DayOfWeek, Double> =
        averageByWeekday(sorted(days).filter { it.answered }) { it.mood.toDouble() }

    /**
     * The best day of the window: the highest mood, and — matching the web's stable
     * `sort((a, b) => b.mood - a.mood)` over a chronological list — the *earliest*
     * day that reached it. Null when nothing was answered.
     */
    fun bestDay(days: List<StatsDay>): MoodPoint? {
        val answered = sorted(days).filter { it.answered }
        if (answered.isEmpty()) return null
        val best = answered.maxOf { it.mood }
        return answered.first { it.mood == best }.let { MoodPoint(it.date, it.mood) }
    }

    /**
     * The worst day of the window: the lowest mood, and — because the web reads the
     * *last* element of the same stable sort — the latest day that hit it. Null when
     * nothing was answered, so an empty window cannot show "worst day: none".
     */
    fun worstDay(days: List<StatsDay>): MoodPoint? {
        val answered = sorted(days).filter { it.answered }
        if (answered.isEmpty()) return null
        val worst = answered.minOf { it.mood }
        return answered.last { it.mood == worst }.let { MoodPoint(it.date, it.mood) }
    }

    /**
     * Longest run of consecutive calendar days that have an entry, anywhere in the
     * loaded window — the same rule (and the same result) as
     * `DashboardRepository.longestStreak`, kept here so the stats screens never
     * import the dashboard. Days are compared as dates, not rows, so two rows for
     * one date count once.
     */
    fun longestStreak(days: List<StatsDay>): Int {
        val dates = days.mapNotNull { parseDate(it.date) }.distinct().sorted()
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in dates) {
            run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
            if (run > best) best = run
            previous = day
        }
        return best
    }

    // ── Activities ─────────────────────────────────────────────────────────────

    /**
     * The web's `activityCorrelations`: for every activity mentioned in the window,
     * how many answered days mentioned it, the mean mood of those days, and the best
     * and worst of them.
     *
     * Differences from the web, both documented decisions: days with mood 0 are
     * excluded (the web averages them in, so its activity averages are pulled down
     * by unanswered days), and ties in the mean mood are broken by name so the list
     * is stable. Sorted by mean mood descending and capped — the web's
     * `sort((a, b) => b.avgMood - a.avgMood).slice(0, 10)`.
     */
    fun activityStats(days: List<StatsDay>, limit: Int = ACTIVITY_LIMIT): List<ActivityStat> {
        val answered = sorted(days).filter { it.answered }
        val names = linkedSetOf<String>()
        answered.forEach { day -> day.activities.forEach { if (it.isNotBlank()) names.add(it) } }

        return names
            .map { name ->
                val withActivity = answered.filter { name in it.activities }
                val moods = withActivity.map { it.mood }
                ActivityStat(
                    name = name,
                    averageMood = moods.sum().toDouble() / moods.size,
                    count = withActivity.size,
                    bestDay = withActivity.first { it.mood == moods.max() }
                        .let { MoodPoint(it.date, it.mood) },
                    // The web's running minimum (`e.mood < worst.mood`) keeps the
                    // first day that reached the low, so a tie goes to the earliest
                    // mention — same rule as the best day above.
                    worstDay = withActivity.first { it.mood == moods.min() }
                        .let { MoodPoint(it.date, it.mood) },
                    correlation = activityCorrelation(days, name),
                )
            }
            .sortedWith(compareByDescending<ActivityStat> { it.averageMood }.thenBy { it.name })
            .take(limit)
    }

    /**
     * Point-biserial correlation between an activity and mood: `1` for every
     * answered day that mentioned [activity], `0` for the others, correlated with
     * that day's mood.
     *
     * Null when there is nothing to correlate — fewer than two answered days, the
     * activity never or always mentioned (zero variance on the activity side), or
     * every mood identical (zero variance on the mood side). Those degenerate cases
     * are the whole point: a correlation must not be invented out of a constant.
     */
    fun activityCorrelation(days: List<StatsDay>, activity: String): Double? {
        val answered = sorted(days).filter { it.answered }
        val mentions = answered.map { if (activity in it.activities) 1.0 else 0.0 }
        val moods = answered.map { it.mood.toDouble() }
        return pearsonCorrelation(mentions, moods)
    }

    /**
     * Pearson's r, or null when it is undefined: fewer than two pairs, mismatched
     * lengths, or zero variance in either series (a constant column has no
     * correlation with anything).
     */
    fun pearsonCorrelation(xs: List<Double>, ys: List<Double>): Double? {
        if (xs.size != ys.size || xs.size < 2) return null
        val meanX = xs.average()
        val meanY = ys.average()
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (index in xs.indices) {
            val dx = xs[index] - meanX
            val dy = ys[index] - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        val denominator = Math.sqrt(varianceX * varianceY)
        if (denominator == 0.0) return null
        return covariance / denominator
    }

    // ── Screen time ────────────────────────────────────────────────────────────

    /**
     * The window's screen time per loaded day, oldest first: seconds, unlocks and
     * whether that day captured any apps. A day the worker never synced keeps
     * `null` seconds, so the chart can draw "no data" instead of a zero bar.
     */
    fun screenTimeByDay(days: List<StatsDay>): List<ScreenTimeDay> =
        sorted(days).map { ScreenTimeDay(it.date, it.screenTimeSeconds, it.unlocks, it.topApps.isNotEmpty()) }

    /**
     * The [windowDays] consecutive calendar days the screen-time chart draws,
     * oldest first.
     *
     * Ported from `ScreenTimeChart.tsx`: the window is anchored on the **most recent
     * day that actually reported data** (screen time or unlocks) rather than on
     * today, so a today that has not synced yet never becomes a pointless empty
     * column; interior days without data stay in as placeholders. The web always
     * draws 7 days — the native chart also offers 30, using the same anchor rule.
     *
     * Returns an empty list when nothing in the window ever reported data (the
     * caller shows "Zatím žádná data o screen timu."). [endDate] pins the last day
     * explicitly, which is what the tests use.
     */
    fun screenTimeWindow(
        days: List<StatsDay>,
        windowDays: Int,
        endDate: String? = null,
    ): List<ScreenTimeDay> {
        val size = windowDays.coerceAtLeast(1)
        val byDate = sorted(days).associateBy { it.date }
        val end = endDate?.let { parseDate(it) } ?: byDate.values
            .filter { (it.screenTimeSeconds ?: 0) > 0 || (it.unlocks ?: 0) > 0 }
            .mapNotNull { parseDate(it.date) }
            .maxOrNull()
            ?: return emptyList()

        return (size - 1 downTo 0).map { back ->
            val date = end.minusDays(back.toLong()).toString()
            val day = byDate[date]
            ScreenTimeDay(
                date = date,
                seconds = day?.screenTimeSeconds,
                unlocks = day?.unlocks,
                hasApps = day?.topApps?.isNotEmpty() == true,
            )
        }
    }

    /**
     * The chart's three summary numbers, with the web's exact denominators: the
     * average and the maximum come from the days that reported screen time
     * (`phone_screen_time > 0`), while the total sums every day of the window
     * (missing days count as zero). [ScreenTimeSummary.maxDate] is the *earliest*
     * day that reached the maximum, matching the web's `reduce`.
     */
    fun screenTimeSummary(window: List<ScreenTimeDay>): ScreenTimeSummary {
        val timed = window.filter { (it.seconds ?: 0) > 0 }
        val maxSeconds = timed.maxOfOrNull { it.seconds ?: 0 }
        return ScreenTimeSummary(
            averageSeconds = if (timed.isEmpty()) {
                null
            } else {
                timed.sumOf { it.seconds ?: 0 }.toDouble() / timed.size
            },
            totalSeconds = window.sumOf { it.seconds ?: 0 },
            maxSeconds = maxSeconds,
            maxDate = timed.firstOrNull { it.seconds == maxSeconds }?.date,
        )
    }

    /**
     * Mean screen time per weekday (seconds), over the days that reported data.
     * Weekdays with nothing reported are absent — the native counterpart of the
     * chart's per-day bars, for the days of the week the user actually uses.
     */
    fun screenTimeWeekdayAverages(days: List<StatsDay>): Map<DayOfWeek, Double> =
        averageByWeekday(sorted(days).filter { (it.screenTimeSeconds ?: 0) > 0 }) {
            (it.screenTimeSeconds ?: 0).toDouble()
        }

    /**
     * The window's most-used apps, aggregated the way the web's legend does: every
     * `phone_top_apps` entry of every day summed by app (`{app, minutes}` becomes
     * seconds, the same normalisation `fetchDailyEntries` performs), sorted
     * descending by time.
     *
     * [TopAppsBreakdown.otherSeconds] is the screen time the listed apps do not
     * cover — the web's muted "Další čas" filler — measured against the total of the
     * days that captured apps, so the breakdown always adds up to real time.
     * [AppUsage.share] is that day-window's share of the same total. Null when no
     * day in the input captured any app.
     */
    fun topApps(days: List<StatsDay>, limit: Int = TOP_APPS_LIMIT): TopAppsBreakdown? {
        val withApps = sorted(days).filter { it.topApps.isNotEmpty() }
        if (withApps.isEmpty()) return null

        val seconds = linkedMapOf<String, Int>()
        withApps.forEach { day ->
            day.topApps.forEach { app ->
                if (app.app.isNotBlank()) {
                    seconds[app.app] = (seconds[app.app] ?: 0) + app.minutes * 60
                }
            }
        }
        if (seconds.isEmpty()) return null

        val total = withApps.sumOf { it.screenTimeSeconds ?: 0 }
        val apps = seconds.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { entry ->
                AppUsage(
                    name = entry.key,
                    seconds = entry.value,
                    share = if (total > 0) entry.value.toDouble() / total else 0.0,
                )
            }

        return TopAppsBreakdown(
            apps = apps,
            otherSeconds = (total - seconds.values.sum()).coerceAtLeast(0),
            totalSeconds = total,
        )
    }

    // ── Windows ────────────────────────────────────────────────────────────────

    /**
     * The [daysCount] calendar days ending at [endDate] (inclusive), oldest first,
     * taken from [days]. Days the read did not return are simply absent — the caller
     * decides whether that gap matters (the mood charts draw only what exists, the
     * screen-time chart fills placeholders with [screenTimeWindow]).
     */
    fun window(days: List<StatsDay>, endDate: String, daysCount: Int): List<StatsDay> {
        val end = parseDate(endDate) ?: return emptyList()
        val size = daysCount.coerceAtLeast(1)
        val from = end.minusDays((size - 1).toLong()).toString()
        val to = end.toString()
        return sorted(days).filter { it.date >= from && it.date <= to }
    }

    /** The loaded days that fall inside the calendar [year]. */
    fun yearDays(days: List<StatsDay>, year: Int): List<StatsDay> {
        val prefix = "$year-"
        return sorted(days).filter { it.date.startsWith(prefix) }
    }

    // ── Year in pixels ─────────────────────────────────────────────────────────

    /**
     * The year grid, ported from `YearInPixels.tsx`: twelve months, each cut into
     * Monday-first weeks of exactly seven cells (padding cells before the 1st and
     * after the last day carry `day == 0`), each cell coloured by that day's mood —
     * 0 when the read has no row for it.
     *
     * The grid only knows the days it is given, so a year the 400-day read cannot
     * reach stays grey; the screen says so above the grid.
     */
    fun yearGrid(year: Int, days: List<StatsDay>): List<YearMonthGrid> {
        val moods = sorted(days).associate { it.date to it.mood }

        return (0 until 12).map { monthIndex ->
            val first = LocalDate.of(year, monthIndex + 1, 1)
            val daysInMonth = YearMonth.of(year, monthIndex + 1).lengthOfMonth()
            // Monday = 1 … Sunday = 7, the web's `firstDay.getDay() || 7`.
            val startDow = first.dayOfWeek.value

            val weeks = mutableListOf<List<YearCell>>()
            var current = mutableListOf<YearCell>()
            repeat(startDow - 1) { current.add(YearCell(date = "", day = 0, mood = 0)) }

            for (dayOfMonth in 1..daysInMonth) {
                val date = LocalDate.of(year, monthIndex + 1, dayOfMonth).toString()
                current.add(YearCell(date, dayOfMonth, moods[date] ?: 0))
                if (current.size == 7) {
                    weeks.add(current)
                    current = mutableListOf()
                }
            }
            if (current.isNotEmpty()) {
                while (current.size < 7) current.add(YearCell(date = "", day = 0, mood = 0))
                weeks.add(current)
            }

            YearMonthGrid(
                name = MONTH_NAMES[monthIndex],
                index = monthIndex,
                weeks = weeks,
                daysInMonth = daysInMonth,
                trackedDays = weeks.flatten().count { it.mood in MOOD_MIN..MOOD_MAX },
            )
        }
    }

    /**
     * The year card's numbers: mean of the answered moods of [year] and how many
     * days those were.
     *
     * The web shows `avg` over every row of the year (mood 0 included) and `total`
     * as the row count; both are answered-only here, for the reason in the class
     * documentation. Null when the read holds no day of that year at all (the web's
     * `yearEntries.length === 0`), which is what hides the row instead of printing
     * a meaningless zero.
     */
    fun yearStats(days: List<StatsDay>, year: Int): YearStats? {
        val inYear = yearDays(days, year)
        if (inYear.isEmpty()) return null
        val answered = inYear.filter { it.answered }
        return YearStats(
            averageMood = if (answered.isEmpty()) {
                null
            } else {
                answered.sumOf { it.mood }.toDouble() / answered.size
            },
            trackedDays = answered.size,
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** The [days] that were answered, oldest first. */
    private fun sorted(days: List<StatsDay>): List<StatsDay> =
        days.filter { it.date.isNotBlank() }.sortedBy { it.date }

    /**
     * Mean of [value] over [days], grouped by weekday, Monday first. Weekdays with
     * no day in the input are absent from the map.
     */
    private fun averageByWeekday(
        days: List<StatsDay>,
        value: (StatsDay) -> Double,
    ): Map<DayOfWeek, Double> {
        val buckets = linkedMapOf<DayOfWeek, MutableList<Double>>()
        days.forEach { day ->
            val date = parseDate(day.date) ?: return@forEach
            buckets.getOrPut(date.dayOfWeek) { mutableListOf() }.add(value(day))
        }
        return DayOfWeek.entries
            .mapNotNull { weekday -> buckets[weekday]?.let { weekday to it.average() } }
            .toMap()
    }

    /** ISO date to [LocalDate]; null for a value the database should never hold. */
    private fun parseDate(date: String): LocalDate? =
        runCatching { LocalDate.parse(date) }.getOrNull()
}
