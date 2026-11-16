package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Navigation wrapper for the statistics tab: builds the real [StatsDeps] once per
 * navigation and hands them to [StatsScreen].
 *
 * The screen itself navigates nowhere: the one entrance out (a best/worst day) is a
 * callback the parent (the app shell's tab host) hands in.
 */
@Composable
fun StatsRoute(
    // Supplied by the app shell (see [cz.digitalnivedomi.diarium.ui.nav.ScreenStateCache]):
    // the statistics are a tab, so their holder has to outlive the tab's composition.
    holder: StatsStateHolder = remember { StatsStateHolder() },
    /**
     * Opens the check-in for a day from the best/worst card — the same entrance the
     * dashboard and history use, owned by the app shell.
     */
    onOpenCheckIn: (String) -> Unit = {},
    onOpenHistoryMonth: (java.time.YearMonth) -> Unit = {},
    savedRange: String? = null,
    savedCategory: String? = null,
    savedCustomDays: Int = 30,
    savedCompare: Boolean = false,
    savedCustomStart: String = "",
    savedCustomEnd: String = "",
    onCustomRangeChanged: (String, String) -> Unit = { _, _ -> },
    onFiltersChanged: (StatsRange, StatsCategory, Int, Boolean) -> Unit = { _, _, _, _ -> },
) {
    val context = LocalContext.current
    val deps = remember(context) { StatsDeps.forContext(context) }
    StatsScreen(deps = deps, holder = holder, onOpenCheckIn = onOpenCheckIn, onOpenHistoryMonth = onOpenHistoryMonth,
        savedRange = savedRange, savedCategory = savedCategory, savedCustomDays = savedCustomDays,
        savedCompare = savedCompare, savedCustomStart = savedCustomStart, savedCustomEnd = savedCustomEnd,
        onCustomRangeChanged = onCustomRangeChanged, onFiltersChanged = onFiltersChanged)
}
