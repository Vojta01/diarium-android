package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Navigation wrapper for the statistics tab: builds the real [StatsDeps] once per
 * navigation and hands them to [StatsScreen].
 *
 * Deliberately argument-free — the statistics screen navigates nowhere, so the parent
 * (the app shell's tab host) only has to call `StatsRoute()`.
 */
@Composable
fun StatsRoute(
    // Supplied by the app shell (see [cz.digitalnivedomi.diarium.ui.nav.ScreenStateCache]):
    // the statistics are a tab, so their holder has to outlive the tab's composition.
    holder: StatsStateHolder = remember { StatsStateHolder() },
) {
    val context = LocalContext.current
    val deps = remember(context) { StatsDeps.forContext(context) }
    StatsScreen(deps = deps, holder = holder)
}
