package cz.digitalnivedomi.diarium.ui.settings

import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cz.digitalnivedomi.diarium.notifications.NotificationPrefs
import cz.digitalnivedomi.diarium.notifications.NotificationPrefsStore
import cz.digitalnivedomi.diarium.notifications.NotificationScheduler
import cz.digitalnivedomi.diarium.stats.UsageStatsProvider
import cz.digitalnivedomi.diarium.sync.SyncScheduler
import android.Manifest

/** Real state of `POST_NOTIFICATIONS` / the app's notification switch. */
enum class PermissionState { GRANTED, DENIED, UNDECIDED }

/**
 * Real state of usage access (`PACKAGE_USAGE_STATS`). `ERRORED` mirrors the
 * `MODE_ERRORED` the system keeps after an explicit denial — only a reinstall
 * clears it (spec risk #1).
 */
enum class UsageAccessState { ALLOWED, DENIED, UNDECIDED, ERRORED }

/**
 * Everything the notifications screen needs from the platform, injected instead of
 * built inline so the screen composes offline (previews, tests) and so the Android
 * reads stay in one place — the same shape as `ui/goals/GoalsDeps.kt`.
 */
class NotificationsSettingsDeps(
    val prefsStore: NotificationPrefsStore?,
    val notificationPermission: PermissionState,
    val usageAccess: UsageAccessState,
    val exactAlarmAllowed: Boolean,
    val usageAccessState: () -> String,
    val onOpenUsageAccessSettings: () -> Unit,
    val onReschedule: (NotificationPrefs) -> Unit,
) {

    companion object {

        /** No context, no store, no alarms — the screen still composes and renders defaults. */
        fun offline(): NotificationsSettingsDeps = NotificationsSettingsDeps(
            prefsStore = null,
            notificationPermission = PermissionState.UNDECIDED,
            usageAccess = UsageAccessState.UNDECIDED,
            exactAlarmAllowed = true,
            usageAccessState = { "undecided" },
            onOpenUsageAccessSettings = {},
            onReschedule = {},
        )

        /** The real thing, built from the current context. */
        fun forContext(context: Context): NotificationsSettingsDeps {
            val app = context.applicationContext
            val provider = UsageStatsProvider(app)
            return NotificationsSettingsDeps(
                prefsStore = NotificationPrefsStore(app),
                notificationPermission = notificationPermissionState(app),
                usageAccess = parseUsageAccess(provider.usageAccessState()),
                exactAlarmAllowed = canScheduleExactAlarms(app),
                usageAccessState = { provider.usageAccessState() },
                onOpenUsageAccessSettings = { provider.openUsageAccessSettings() },
                onReschedule = {
                    // Changing a time re-arms the alarms; the sync times are part of
                    // this screen too, so the WorkManager jobs are re-registered as well.
                    NotificationScheduler.rescheduleAll(app)
                    SyncScheduler.ensureScheduled(app)
                },
            )
        }

        /**
         * `areNotificationsEnabled()` is the honest answer on every API level — the
         * permission can be denied, or notifications switched off in system settings.
         */
        private fun notificationPermissionState(context: Context): PermissionState {
            val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            if (!enabled) return PermissionState.DENIED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                return if (granted) PermissionState.GRANTED else PermissionState.UNDECIDED
            }
            return PermissionState.GRANTED
        }

        /** `UsageStatsProvider.usageAccessState()` returns a status string, not an enum. */
        private fun parseUsageAccess(raw: String): UsageAccessState {
            val value = raw.lowercase()
            return when {
                value.contains("allowed") || value.contains("granted") -> UsageAccessState.ALLOWED
                value.contains("errored") || value.contains("error") -> UsageAccessState.ERRORED
                value.contains("denied") -> UsageAccessState.DENIED
                else -> UsageAccessState.UNDECIDED
            }
        }

        /** Android 12+ may withhold exact alarms; below that they are always exact. */
        private fun canScheduleExactAlarms(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return true
            return try {
                alarms.canScheduleExactAlarms()
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
