package cz.digitalnivedomi.diarium.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Navigation wrapper for the dashboard: builds the real [DashboardDeps] once per
 * navigation and hands them to [DashboardScreen].
 *
 * [onOpenCheckIn] switches to the check-in tab — both the "Dnes" empty state and the
 * "edit today" action use it, so the screen never has to know how navigation works.
 * [onOpen] pushes one of the M5 sub-screens (cíle, škály, šablony, odznaky), which
 * the dashboard reaches through its entry card.
 */
@Composable
fun DashboardRoute(
    onOpenCheckIn: (String) -> Unit = {},
    onOpen: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val deps = remember(context) { DashboardDeps.forContext(context) }
    DashboardScreen(deps = deps, onOpenCheckIn = onOpenCheckIn, onOpen = onOpen)
}
