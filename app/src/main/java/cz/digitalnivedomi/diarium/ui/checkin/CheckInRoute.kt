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
fun CheckInRoute(
    requestedDate: String? = null,
    onRequestedDateConsumed: () -> Unit = {},
    /**
     * The check-in is finished (saved, reflection window closed): the shell takes the
     * owner back to the overview.
     */
    onFinished: () -> Unit = {},
) {
    val context = LocalContext.current
    val deps = remember(context) { CheckInDeps.forContext(context) }
    CheckInScreen(
        deps = deps,
        requestedDate = requestedDate,
        onRequestedDateConsumed = onRequestedDateConsumed,
        onFinished = onFinished,
    )
}
