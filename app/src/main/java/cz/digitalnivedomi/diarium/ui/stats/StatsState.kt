package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.digitalnivedomi.diarium.core.data.StatsData
import cz.digitalnivedomi.diarium.core.stats.StatsDay

/**
 * The three windows the statistics tab offers, mirroring the web's controls
 * (`AdvancedStats` shows the last 30 days, `ScreenTimeChart` the last 7, and
 * `YearInPixels` the current calendar year). The Czech labels are the ones the web
 * puts on those controls.
 */
enum class StatsRange(
    val label: String,
    val testTag: String,
    /** Calendar days back from today; ignored for [YEAR], which follows the calendar. */
    val daysCount: Int,
    /** True for the "Tento rok" tab, which uses the calendar year instead of a count. */
    val year: Boolean,
) {
    /** "7 dní" — the same window `ScreenTimeChart` draws. */
    WEEK(label = "7 dní", testTag = "stats_range_week", daysCount = 7, year = false),

    /** "30 dní" — the window the web's mood statistics work on. */
    MONTH(label = "30 dní", testTag = "stats_range_month", daysCount = 30, year = false),

    /** "Tento rok" — drives the year grid as well as the mood numbers. */
    YEAR(label = "Tento rok", testTag = "stats_range_year", daysCount = 0, year = true),
}

/**
 * What the statistics screen renders from.
 *
 * [loading] is a first-class flag rather than "data == null", exactly like
 * [cz.digitalnivedomi.diarium.ui.home.DashboardUiState]: "still loading" and "loaded,
 * and there is nothing to show" must not look the same. [range] sits here too so
 * switching the window is a state change, not a second read.
 */
data class StatsUiState(
    val loading: Boolean = true,
    val data: StatsData? = null,
    val errorMessage: String? = null,
    val range: StatsRange = StatsRange.MONTH,
)

/**
 * Plain state holder — no ViewModel, no Android dependency — so the load/error/range
 * flow is drivable from a JVM test, the same way `DashboardStateHolder` is.
 */
class StatsStateHolder {

    var state: StatsUiState by mutableStateOf(StatsUiState())
        private set

    fun markLoading() {
        state = state.copy(loading = true, errorMessage = null)
    }

    fun show(data: StatsData) {
        state = state.copy(loading = false, data = data, errorMessage = null)
    }

    /**
     * A failed load drops the numbers instead of keeping the previous ones: a stale
     * statistics screen would look current while showing a window that is no longer
     * being shown. The message is the repository's own Czech sentence.
     */
    fun showError(message: String) {
        state = state.copy(loading = false, data = null, errorMessage = message)
    }

    /**
     * Switching the window is local — the read already holds
     * [cz.digitalnivedomi.diarium.core.data.StatsRepository.LOAD_DAYS] days, so every
     * range is a slice of what is on screen and nothing has to be fetched again.
     */
    fun selectRange(range: StatsRange) {
        state = state.copy(range = range)
    }
}

/** The days one window shows: the last N calendar days, or the year's own days. */
fun StatsData.forRange(range: StatsRange): List<StatsDay> =
    if (range.year) yearDays() else window(range.daysCount)
