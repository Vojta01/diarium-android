package cz.digitalnivedomi.diarium.ui.checkin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Nav destination wrapper: builds the real [CheckInDeps] from the current
 * context once per navigation and hands them to [CheckInScreen].
 *
 * Keeping this separate means the screen itself stays parameter-only (so tests
 * and previews can pass [CheckInDeps.offline]) while the route owns the wiring.
 */
@Composable
fun CheckInRoute() {
    val context = LocalContext.current
    val deps = remember(context) { CheckInDeps.forContext(context) }
    CheckInScreen(deps)
}
