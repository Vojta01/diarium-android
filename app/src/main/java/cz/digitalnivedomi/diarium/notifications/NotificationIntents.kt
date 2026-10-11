package cz.digitalnivedomi.diarium.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import cz.digitalnivedomi.diarium.MainActivity

/**
 * The intent extra that carries the in-app route to open when the user taps a
 * notification. This is the cross-branch constant agreed for M6: branch A defines
 * it, branch C only reads it (or uses the literal "open_route"). The name must not
 * drift — it is read in `ui/DiariumApp.kt` / `MainActivity.kt`.
 */
const val EXTRA_OPEN_ROUTE = "open_route"

/**
 * The intent extra that carries the report type ("weekly"/"monthly") when a
 * report notification should open the AI Reports screen (owner request
 * 2026-10-01). Part of the same cross-branch contract as [EXTRA_OPEN_ROUTE]:
 * the tap sets the route to `Routes.REPORTS` and this extra says which period
 * the screen should open. The name must not drift.
 */
const val EXTRA_OPEN_REPORT_TYPE = "open_report_type"

/**
 * Builds tap intents for notifications. Every PendingIntent carries
 * [EXTRA_OPEN_ROUTE] and uses [PendingIntent.FLAG_IMMUTABLE] (required on API 31+)
 * plus `FLAG_UPDATE_CURRENT` so the route can be refreshed. Report notifications
 * additionally carry [EXTRA_OPEN_REPORT_TYPE].
 */
object NotificationIntents {

    /** Same value as the top-level [EXTRA_OPEN_ROUTE]; kept for `NotificationIntents.EXTRA_OPEN_ROUTE` call sites. */
    const val EXTRA_OPEN_ROUTE = "open_route"

    /** Route value for the main Přehled (home) tab. */
    const val ROUTE_HOME = "home"

    /**
     * Activity PendingIntent that opens [MainActivity] and asks the app to navigate
     * to [route]. Use a distinct [requestCode] per notification kind so the intents
     * do not overwrite each other. A non-null [reportType] adds
     * [EXTRA_OPEN_REPORT_TYPE] so the AI Reports screen can open that report.
     */
    fun contentIntent(
        context: Context,
        route: String,
        requestCode: Int,
        reportType: String? = null,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ROUTE, route)
            if (reportType != null) putExtra(EXTRA_OPEN_REPORT_TYPE, reportType)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
