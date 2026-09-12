package cz.digitalnivedomi.diarium.core.data

import kotlin.math.abs

/** Which way a metric moved against the previous comparable window. */
enum class TrendDirection {
    UP,
    DOWN,

    /** Moved, but by less than [DashboardTrends.FLAT_PERCENT] — rounding noise. */
    FLAT,

    /** Nothing to compare against (no previous window, either side missing). */
    UNKNOWN,
}

/**
 * One of the dashboard's numbers compared with the seven days before it.
 *
 * [percentChange] is null whenever a percentage would be meaningless — no previous
 * window, or a previous total of zero — so the screen can say "bez srovnání"
 * instead of printing "+100 %" off a single day. [current] and [previous] are
 * carried so the card can spell out both figures ("4 h 12 min vs 3 h 40 min").
 */
data class MetricTrend(
    val current: Int?,
    val previous: Int?,
    val direction: TrendDirection,
    val percentChange: Int?,
)

/**
 * The trend maths behind the caption under each dashboard number.
 *
 * Pure and JVM-testable on purpose: the dashboard's totals are aggregates over a
 * week window, and comparing two of them is exactly the kind of arithmetic that
 * silently goes wrong (dividing by a zero week, averaging days the owner never
 * filled in) — so it lives next to the repository and is tested directly rather
 * than through the screen.
 *
 * Every helper here mirrors the aggregation [DashboardRepository.derive] uses for
 * the number it compares, so a trend can never be computed from a differently
 * summed week:
 *
 * - screen time and unlocks are **week totals** (as on the cards),
 * - mood is the **average of recorded days** (a mood of 0 is "not answered").
 */
object DashboardTrends {

    /**
     * Below this, a move is treated as "stejné jako minulý týden" instead of up or
     * down. Five percent of a week is inside the noise of a phone's own reporting;
     * a card that shouts "↑ o 2 %" every time teaches the owner to ignore it.
     */
    const val FLAT_PERCENT = 5

    /** Compares two counts (week totals, or averages already scaled to tenths). */
    fun of(current: Int?, previous: Int?): MetricTrend {
        if (current == null || previous == null) {
            return MetricTrend(current, previous, TrendDirection.UNKNOWN, null)
        }
        val delta = current - previous
        val percent = if (previous == 0) null else Math.round(delta * 100.0 / previous).toInt()
        val direction = when {
            delta == 0 -> TrendDirection.FLAT
            percent != null && abs(percent) < FLAT_PERCENT -> TrendDirection.FLAT
            delta > 0 -> TrendDirection.UP
            else -> TrendDirection.DOWN
        }
        return MetricTrend(current, previous, direction, percent)
    }

    /**
     * Compares two averages. They are scaled to tenths before comparing, so a mood
     * moving 3.8 -> 3.6 reads as a real move instead of rounding to the same 4.
     */
    fun ofAverage(current: Double?, previous: Double?): MetricTrend =
        of(
            current?.let { Math.round(it * 10).toInt() },
            previous?.let { Math.round(it * 10).toInt() },
        )

    /**
     * The week's screen time in minutes — the same sum [DashboardRepository] puts on
     * the card. Null when no day in the window was ever synced.
     */
    fun weekMinutes(days: List<DashboardDay>): Int? =
        days.mapNotNull { it.screenTimeSeconds }
            .takeIf { it.isNotEmpty() }
            ?.let { values -> Math.round(values.sum() / 60.0).toInt() }

    /** The week's unlocks — the same sum the card shows. */
    fun weekUnlocks(days: List<DashboardDay>): Int? =
        days.mapNotNull { it.unlocks }.takeIf { it.isNotEmpty() }?.sum()

    /**
     * The week's average mood over *recorded* days. A mood of 0 means "not answered"
     * and is dropped, exactly as the card's average does — otherwise a phone-synced
     * day with no mood would pull the average down for no reason.
     */
    fun weekMood(days: List<DashboardDay>): Double? {
        val moods = days.mapNotNull { day -> day.mood.takeIf { isRecordedDay(it) } }
        return if (moods.isEmpty()) null else moods.sum().toDouble() / moods.size
    }
}
