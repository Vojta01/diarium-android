package cz.digitalnivedomi.diarium.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Every navigation route in the app, in one place. */
object Routes {
    const val AUTH = "auth"

    /**
     * The dashboard ("Přehled") and the app's start destination: a daily diary is
     * opened to see how the day and the week are going, not to fill in a form.
     */
    const val HOME = "home"
    const val CHECK_IN = "check_in"
    const val HISTORY = "history"
    const val STATS = "stats"
    const val SETTINGS = "settings"
}

/** Routes that get a tab in the bottom bar, in display order. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Dashboard(Routes.HOME, "Přehled", Icons.Filled.Home, Icons.Outlined.Home),
    CheckIn(Routes.CHECK_IN, "Dnes", Icons.Filled.EditNote, Icons.Outlined.EditNote),
    History(Routes.HISTORY, "Historie", Icons.Filled.History, Icons.Outlined.History),
    // "Statistiky" rather than "Přehledy", so the deep-dive tab cannot be confused
    // with the "Přehled" overview tab next to it — the same split as the web nav.
    Stats(Routes.STATS, "Statistiky", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    Settings(Routes.SETTINGS, "Nastavení", Icons.Filled.Settings, Icons.Outlined.Settings),
}
