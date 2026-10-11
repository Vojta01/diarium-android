package cz.digitalnivedomi.diarium.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Battery-friendly safety net for the period reports. The exact alarms
 * ([NotificationScheduler]) are the primary trigger; this periodic work makes
 * the flow self-healing when an alarm never happened (doze, reboot in a bad
 * moment, OEM battery saver, fresh install). It runs a pure local check every
 * [INTERVAL_HOURS] hours and only inside [ReportSafetyWindow] enqueues the
 * regular [NotificationWorker] — which then either finds a fresh report (and
 * notifies) or asks the server for one. All timing stays in the app; no server
 * schedules exist.
 */
class ReportSafetyNetWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = NotificationPrefsStore(applicationContext).load()
        val now = ZonedDateTime.now(ZoneId.systemDefault())

        if (prefs.weeklyEnabled &&
            ReportSafetyWindow.isWeeklyWindow(now, prefs.weeklyDay, prefs.weeklyTimeMinutes)
        ) {
            enqueueReportWork(NotificationScheduler.TYPE_WEEKLY)
        }
        if (prefs.monthlyEnabled &&
            ReportSafetyWindow.isMonthlyWindow(now, prefs.monthlyTimeMinutes)
        ) {
            enqueueReportWork(NotificationScheduler.TYPE_MONTHLY)
        }
        return Result.success()
    }

    private fun enqueueReportWork(type: String) {
        val work = OneTimeWorkRequestBuilder<NotificationWorker>()
            .setInputData(Data.Builder().putString(NotificationScheduler.EXTRA_TYPE, type).build())
            .build()
        WorkManager.getInstance(applicationContext).enqueue(work)
    }

    companion object {
        const val UNIQUE_NAME = "diarium_report_safety_net"
        private const val INTERVAL_HOURS = 6L

        /** Idempotent ([ExistingPeriodicWorkPolicy.KEEP]) — safe on every rescheduleAll. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReportSafetyNetWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
