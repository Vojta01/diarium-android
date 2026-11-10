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
import cz.digitalnivedomi.diarium.core.data.AppClients
import cz.digitalnivedomi.diarium.core.data.SessionContext
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
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
        val ownerId = inputData.getString(NotificationScheduler.EXTRA_OWNER_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.failure()
        val sessionStore = AppClients.sessionStore(applicationContext)
        val session = SessionContext(sessionStore)
        if (!NotificationScheduler.capturedOwnerMatches(ownerId, session.verifiedUserId())) return Result.success()
        if (sessionStore.validAccessTokenForUser(ownerId) == null) return Result.success()
        if (!NotificationScheduler.capturedOwnerMatches(ownerId, session.verifiedUserId())) return Result.success()

        val prefs = NotificationPrefsStore(applicationContext).load()
        val now = ZonedDateTime.now(ZoneId.systemDefault())

        if (prefs.weeklyEnabled &&
            ReportSafetyWindow.isWeeklyWindow(now, prefs.weeklyDay, prefs.weeklyTimeMinutes)
        ) {
            enqueueReportWork(NotificationScheduler.TYPE_WEEKLY, ownerId)
        }
        if (prefs.monthlyEnabled &&
            ReportSafetyWindow.isMonthlyWindow(now, prefs.monthlyTimeMinutes)
        ) {
            enqueueReportWork(NotificationScheduler.TYPE_MONTHLY, ownerId)
        }
        return Result.success()
    }

    private fun enqueueReportWork(type: String, ownerId: String) {
        val work = OneTimeWorkRequestBuilder<NotificationWorker>()
            .setInputData(NotificationScheduler.workerInputData(type, ownerId))
            .build()
        WorkManager.getInstance(applicationContext).enqueue(work)
    }

    companion object {
        const val UNIQUE_NAME = "diarium_report_safety_net"
        private const val INTERVAL_HOURS = 6L
        private const val SAFETY_NET_TAG = "diarium_report_safety_net_owner"

        /** Keep one job per owner; routine alarm reschedules must not reset the interval. */
        fun schedule(context: Context, ownerId: String) {
            val request = PeriodicWorkRequestBuilder<ReportSafetyNetWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setInputData(Data.Builder().putString(NotificationScheduler.EXTRA_OWNER_ID, ownerId).build())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(SAFETY_NET_TAG)
                .build()
            val workManager = WorkManager.getInstance(context)
            // Drain the pre-owner singleton request; its ownerless input cannot be migrated.
            workManager.cancelUniqueWork(UNIQUE_NAME)
            workManager.enqueueUniquePeriodicWork(
                uniqueNameFor(ownerId),
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Cancel all owner-pinned safety nets on sign-out. */
        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_NAME)
            workManager.cancelAllWorkByTag(SAFETY_NET_TAG)
        }

        private fun uniqueNameFor(ownerId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(ownerId.toByteArray(Charsets.UTF_8))
            val suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            return "$UNIQUE_NAME-$suffix"
        }
    }
}
