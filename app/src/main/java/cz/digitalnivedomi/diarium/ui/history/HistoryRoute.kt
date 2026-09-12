package cz.digitalnivedomi.diarium.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Navigation wrapper for the history screen: builds the real [HistoryDeps] once per
 * navigation and hands them to [HistoryScreen].
 *
 * [onOpenCheckIn] opens the check-in for the tapped day — both the calendar's
 * day-detail button and its empty state use it, so the screen never has to know how
 * navigation works. The date is passed on so the check-in tab can land on that day;
 * a host that only switches tabs may ignore it, exactly like the dashboard route.
 */
@Composable
fun HistoryRoute(
    onOpenCheckIn: (String) -> Unit = {},
    // Supplied by the app shell (see [cz.digitalnivedomi.diarium.ui.nav.ScreenStateCache]):
    // the month that was read must not be re-read just because a day detail was opened.
    holder: HistoryStateHolder = remember { HistoryStateHolder() },
) {
    val context = LocalContext.current
    val deps = remember(context) { HistoryDeps.forContext(context) }
    HistoryScreen(deps = deps, onOpenCheckIn = onOpenCheckIn, holder = holder)
}
