package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.digitalnivedomi.diarium.core.data.StatsData
import cz.digitalnivedomi.diarium.core.data.StatsRepository
import cz.digitalnivedomi.diarium.core.stats.StatsDateRange
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import java.time.LocalDate

/** One global period for every Statistics category. CUSTOM is a rolling, user-sized window. */
enum class StatsRange(val label: String, val testTag: String, val daysCount: Int, val year: Boolean = false) {
    WEEK("7 dní", "stats_range_week", 7),
    MONTH("30 dní", "stats_range_month", 30),
    QUARTER("90 dní", "stats_range_90", 90),
    YEAR("Tento rok", "stats_range_year", 0, true),
    CUSTOM("Vlastní", "stats_range_custom", 0);

    fun dayCount(customDays: Int, today: LocalDate): Int = when (this) {
        YEAR -> today.dayOfYear
        CUSTOM -> customDays.coerceIn(1, StatsRepository.LOAD_DAYS)
        else -> daysCount
    }
}

enum class StatsCategory(val label: String, val testTag: String) {
    MOOD("Nálada", "stats_category_mood"), HABITS("Návyky", "stats_category_habits"),
    DIGITAL("Digitální", "stats_category_digital"), YEAR("Rok", "stats_category_year")
}

data class StatsNavigationIntent(val range: StatsRange, val category: StatsCategory) {
    companion object {
        fun fromDashboard(metric: cz.digitalnivedomi.diarium.ui.home.DashboardMetricKind) = StatsNavigationIntent(
            range = StatsRange.WEEK,
            category = when (metric) {
                cz.digitalnivedomi.diarium.ui.home.DashboardMetricKind.MOOD -> StatsCategory.MOOD
                cz.digitalnivedomi.diarium.ui.home.DashboardMetricKind.SCREEN_TIME,
                cz.digitalnivedomi.diarium.ui.home.DashboardMetricKind.UNLOCKS -> StatsCategory.DIGITAL
            },
        )
    }
}

data class StatsUiState(
    val loading: Boolean = true,
    val data: StatsData? = null,
    val errorMessage: String? = null,
    val range: StatsRange = StatsRange.MONTH,
    val category: StatsCategory = StatsCategory.MOOD,
    val customDays: Int = 30,
    val comparePrevious: Boolean = false,
    /** Editable draft; charts must never derive from these fields. */
    val customStartDate: String = "",
    val customEndDate: String = "",
    /** Last validated custom window, used by charts/comparison until Apply succeeds. */
    val appliedCustomStartDate: String = "",
    val appliedCustomEndDate: String = "",
    val customRangeError: String? = null,
)

internal fun retainSelectedDateWithinBounds(selected: String?, startInclusive: LocalDate, endInclusive: LocalDate): String? {
    if (selected == null) return null
    val parsed = runCatching { LocalDate.parse(selected) }.getOrNull() ?: return null
    return selected.takeIf { parsed >= startInclusive && parsed <= endInclusive }
}

data class GoalStatsLoadState(
    val loading: Boolean = true,
    val goals: List<cz.digitalnivedomi.diarium.core.data.GoalProgress>? = null,
    val errorMessage: String? = null,
) {
    fun begin(): GoalStatsLoadState = copy(loading = true, errorMessage = null)
    fun success(value: List<cz.digitalnivedomi.diarium.core.data.GoalProgress>): GoalStatsLoadState =
        GoalStatsLoadState(loading = false, goals = value, errorMessage = null)
    fun failure(message: String): GoalStatsLoadState = copy(loading = false, errorMessage = message)
}

/** Compose state holder; filters are local and never trigger a repository read. */
class StatsStateHolder {
    var state: StatsUiState by mutableStateOf(StatsUiState())
        private set
    /** Clear any prior owner-bound presentation before resolving the current owner's cache. */
    fun markLoading() { state = state.copy(loading = true, data = null, errorMessage = null) }
    fun show(data: StatsData) { state = state.copy(loading = false, data = data, errorMessage = null) }
    /** Keep a previously owner-verified snapshot visible, but mark it stale on refresh failure. */
    fun showError(message: String) { state = state.copy(loading = false, errorMessage = message) }
    fun selectRange(range: StatsRange) { state = state.copy(range = range) }
    fun selectCategory(category: StatsCategory) { state = state.copy(category = category) }
    fun setCustomDays(days: Int) { state = state.copy(range = StatsRange.CUSTOM, customDays = days.coerceIn(1, StatsRepository.LOAD_DAYS)) }
    fun toggleCompare(enabled: Boolean) { state = state.copy(comparePrevious = enabled) }
    fun editCustomRange(start: String, end: String) {
        state = state.copy(customStartDate = start, customEndDate = end, customRangeError = null)
    }
    /** Entering Custom seeds an applied baseline matching the current window; edits remain drafts. */
    fun seedCustomRange(start: String, end: String): Boolean {
        val data = state.data ?: return false
        val range = validateCustomDateRange(start, end, data.today) ?: return false
        state = state.copy(
            customStartDate = range.startInclusive.toString(),
            customEndDate = range.endInclusive.toString(),
            appliedCustomStartDate = range.startInclusive.toString(),
            appliedCustomEndDate = range.endInclusive.toString(),
            customDays = range.dayCount,
            customRangeError = null,
        )
        return true
    }
    fun applyCustomRange(): Boolean {
        val data = state.data ?: return false
        val range = validateCustomDateRange(state.customStartDate, state.customEndDate, data.today) ?: run {
            state = state.copy(customRangeError = "Zadej platné datum v rozsahu načtené historie (max. 400 dní).")
            return false
        }
        state = state.copy(
            range = StatsRange.CUSTOM,
            customDays = range.dayCount,
            appliedCustomStartDate = range.startInclusive.toString(),
            appliedCustomEndDate = range.endInclusive.toString(),
            customRangeError = null,
        )
        return true
    }
    /** Restore shell-saved filters after process recreation without touching cached data. */
    fun restore(range: String, category: String, customDays: Int, compare: Boolean, customStart: String = "", customEnd: String = "") {
        val sameAppliedRange = state.appliedCustomStartDate == customStart && state.appliedCustomEndDate == customEnd
        state = state.copy(
            range = StatsRange.entries.firstOrNull { it.name == range } ?: state.range,
            category = StatsCategory.entries.firstOrNull { it.name == category } ?: state.category,
            customDays = customDays.coerceIn(1, StatsRepository.LOAD_DAYS),
            comparePrevious = compare,
            customStartDate = if (sameAppliedRange) state.customStartDate else customStart,
            customEndDate = if (sameAppliedRange) state.customEndDate else customEnd,
            appliedCustomStartDate = customStart,
            appliedCustomEndDate = customEnd,
            customRangeError = state.customRangeError,
        )
    }
}

internal fun comparisonWindow(
    range: StatsRange,
    customRange: ValidatedStatsDateRange?,
    today: LocalDate,
    customDays: Int,
): StatsDateRange = if (range == StatsRange.CUSTOM && customRange != null) {
    StatsDateRange.between(customRange.startInclusive, customRange.endInclusive)
} else {
    StatsDateRange.of(today, range.dayCount(customDays, today))
}

fun StatsData.moodAnchorDate(): LocalDate? = days.asSequence()
    .filter { it.answered }
    .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
    .filter { it <= runCatching { LocalDate.parse(today) }.getOrElse { LocalDate.MIN } }
    .maxOrNull()

fun StatsData.forRange(range: StatsRange): List<StatsDay> = forRange(range, 30)
fun StatsData.forRange(range: StatsRange, customDays: Int): List<StatsDay> {
    val end = if (range == StatsRange.YEAR) runCatching { LocalDate.parse(today) }.getOrElse { LocalDate.now() }
        else moodAnchorDate() ?: return emptyList()
    val count = range.dayCount(customDays, end)
    val start = end.minusDays((count - 1).toLong()).toString()
    return days.filter { it.date >= start && it.date <= end.toString() }
}

fun StatsData.hasComparablePrevious(range: StatsRange, customDays: Int): Boolean {
    val end = runCatching { LocalDate.parse(today) }.getOrNull() ?: return false
    val count = range.dayCount(customDays, end)
    val currentRange = cz.digitalnivedomi.diarium.core.stats.StatsDateRange.of(end, count)
    val previousRange = currentRange.previous()
    val loadedStart = end.minusDays((StatsRepository.LOAD_DAYS - 1).toLong())
    if (previousRange.startInclusive < loadedStart || currentRange.startInclusive < loadedStart) return false
    val currentMoods = days.count { it.date >= currentRange.startInclusive.toString() && it.date <= currentRange.endInclusive.toString() && it.answered }
    val previousMoods = days.count { it.date >= previousRange.startInclusive.toString() && it.date <= previousRange.endInclusive.toString() && it.answered }
    return currentMoods >= MIN_COMPARISON_OBSERVATIONS && previousMoods >= MIN_COMPARISON_OBSERVATIONS
}

const val MIN_COMPARISON_OBSERVATIONS = 2

data class ValidatedStatsDateRange(val startInclusive: LocalDate, val endInclusive: LocalDate) {
    val dayCount: Int get() = (endInclusive.toEpochDay() - startInclusive.toEpochDay() + 1).toInt()
}

/** Exact inclusive ISO dates, bounded by the loaded 400-day window; LocalDate rejects impossible/leap dates. */
fun validateCustomDateRange(startText: String, endText: String, todayText: String): ValidatedStatsDateRange? {
    val start = runCatching { LocalDate.parse(startText.trim()) }.getOrNull() ?: return null
    val end = runCatching { LocalDate.parse(endText.trim()) }.getOrNull() ?: return null
    val today = runCatching { LocalDate.parse(todayText) }.getOrNull() ?: return null
    val loadedStart = today.minusDays((StatsRepository.LOAD_DAYS - 1).toLong())
    if (start > end || end > today || start < loadedStart) return null
    val count = end.toEpochDay() - start.toEpochDay() + 1
    if (count !in 1..StatsRepository.LOAD_DAYS.toLong()) return null
    return ValidatedStatsDateRange(start, end)
}

/** Equal-length immediately preceding window, never a second fetch. */
fun StatsData.previousRange(range: StatsRange, customDays: Int): List<StatsDay> {
    val currentEnd = runCatching { LocalDate.parse(today) }.getOrElse { LocalDate.now() }
    val count = range.dayCount(customDays, currentEnd)
    val end = currentEnd.minusDays(count.toLong())
    val start = end.minusDays((count - 1).toLong()).toString()
    return days.filter { it.date >= start && it.date <= end.toString() }
}
