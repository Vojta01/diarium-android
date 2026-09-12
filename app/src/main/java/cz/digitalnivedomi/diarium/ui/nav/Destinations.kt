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

    /**
     * M5 sub-screens. They are not tabs: the bottom bar already carries five
     * destinations, and goals, scales, templates and achievements are things you
     * open from the dashboard or from settings, read, and leave again.
     */
    const val GOALS = "goals"
    const val SCALES = "scales"
    const val TEMPLATES = "templates"
    const val ACHIEVEMENTS = "achievements"

    /**
     * M6 sub-screen: reminder times, the weekly/monthly reports and the phone
     * screen-time collection — the settings the app owns itself (the web keeps
     * no schedule of its own for these).
     */
    const val NOTIFICATIONS = "notifications"

    /**
     * Data sub-screen: the CSV export. It has no tab of its own either — it is
     * opened from Nastavení and writes the file through the Storage Access
     * Framework, so the export needs no permission and no server round-trip.
     */
    const val EXPORT = "export"
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
