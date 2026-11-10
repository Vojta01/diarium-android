package cz.digitalnivedomi.diarium.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.core.data.AppClients
import java.util.Base64
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Schedules the daily usage-stat sync jobs:
 *  - "evening"  : ~21:00 snapshot of today's usage (consistent cutoff)
 *  - "morning"  : ~07:00 backfill of yesterday (complete day, corrects totals)
 *  - "init"     : one-time backfill of the last 7 days after install
 * WorkManager is battery-friendly and runs even when the app is closed
 * (subject to Android's usual Doze behaviour).
 */
class SyncScheduler {

    companion object {
        private const val WORK_EVENING = "diarium.sync.evening"
        private const val WORK_MORNING = "diarium.sync.morning"
        private const val WORK_INIT = "diarium.sync.init"
        private const val WORK_AUTH_BACKFILL = "diarium.sync.auth_backfill"
        const val WORK_BOOT = "diarium.sync.boot"
        const val TAG_USAGE_SYNC = "diarium.usage.sync"

        private enum class BackfillReason { DUE, AUTH, NONE }

        /** Startup path: schedule only for the current owner with their consent. */
        fun ensureScheduled(context: Context) {
            schedule(context, BackfillReason.DUE)
        }

        /** Account replacement invalidates every old request before binding new ones. */
        fun onSessionChanged(context: Context, expectedOwnerId: String) {
            cancelUsageWork(context)
            schedule(context, BackfillReason.AUTH, expectedOwnerId)
        }

        /** Re-arm recurring work after reboot and queue a pinned catch-up request. */
        fun onReboot(context: Context) {
            schedule(context, BackfillReason.NONE)
            val ownerId = eligibleOwner(context) ?: return
            enqueueBackfill(context, WORK_BOOT, ownerId, initialDelaySeconds = 0)
        }

        /**
         * Cancel every screen-time chain; stale workers also fail their runtime gates.
         * Cancellation prevents future dispatches, but cannot recall a request already
         * handed to the HTTP transport.
         */
        fun cancelUsageWork(context: Context) {
            val workManager = WorkManager.getInstance(context)
            listOf(WORK_EVENING, WORK_MORNING, WORK_INIT, WORK_AUTH_BACKFILL, WORK_BOOT)
                .forEach { workManager.cancelUniqueWork(it) }
            workManager.cancelAllWorkByTag(TAG_USAGE_SYNC)
        }

        /** The boot receiver and tests share the same immutable owner payload. */
        internal fun bootBackfillInputData(ownerId: String): Data =
            UsageSyncWorker.inputData(mode = "backfill", ownerId = ownerId)

        private fun schedule(
            context: Context,
            backfillReason: BackfillReason,
            expectedOwnerId: String? = null,
        ) {
            val currentOwnerId = AppClients.sessionStore(context).userSubject()
            val ownerId = currentOwnerId
                ?.takeIf { expectedOwnerId == null || it == expectedOwnerId }
                ?.takeIf { UsageSyncConsentStore(context).isEnabledFor(it) }
            if (ownerId == null) {
                cancelUsageWork(context)
                return
            }

            val prefs = cz.digitalnivedomi.diarium.notifications.NotificationPrefsStore(context).load()
            scheduleDaily(
                context = context,
                name = WORK_EVENING,
                hour = prefs.screenSyncEveningMinutes / 60,
                minute = prefs.screenSyncEveningMinutes % 60,
                mode = "today",
                ownerId = ownerId,
            )
            scheduleDaily(
                context = context,
                name = WORK_MORNING,
                hour = prefs.screenSyncMorningMinutes / 60,
                minute = prefs.screenSyncMorningMinutes % 60,
                mode = "yesterday",
                ownerId = ownerId,
            )

            // Explicit diary saves use a separate owner-pinned CAS worker; this
            // scheduler is exclusively for account-consented phone-usage sync.

            when (backfillReason) {
                BackfillReason.NONE -> Unit
                BackfillReason.AUTH -> {
                    markBackfillScheduled(context, ownerId)
                    enqueueBackfill(context, WORK_AUTH_BACKFILL, ownerId, initialDelaySeconds = 0)
                }
                BackfillReason.DUE -> if (isBackfillDue(context, ownerId)) {
                    markBackfillScheduled(context, ownerId)
                    enqueueBackfill(context, WORK_INIT, ownerId, initialDelaySeconds = 45)
                }
            }
        }

        private fun eligibleOwner(context: Context): String? {
            val ownerId = AppClients.sessionStore(context).userSubject() ?: return null
            return ownerId.takeIf { UsageSyncConsentStore(context).isEnabledFor(it) }
        }

        private fun enqueueBackfill(
            context: Context,
            name: String,
            ownerId: String,
            initialDelaySeconds: Long,
        ) {
            val request = OneTimeWorkRequestBuilder<UsageSyncWorker>()
                .setInputData(bootBackfillInputData(ownerId))
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG_USAGE_SYNC)
                .apply {
                    if (initialDelaySeconds > 0) setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
                }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
        }

        private fun scheduleDaily(
            context: Context,
            name: String,
            hour: Int,
            minute: Int,
            mode: String,
            ownerId: String,
        ) {
            val now = Calendar.getInstance()
            val next = Calendar.getInstance().apply {
                timeZone = TimeZone.getDefault()
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            val delay = next.timeInMillis - now.timeInMillis
            val request = PeriodicWorkRequestBuilder<UsageSyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(networkConstraints())
                .addTag(TAG_USAGE_SYNC)
                .setInputData(UsageSyncWorker.inputData(mode = mode, ownerId = ownerId))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                name,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        internal fun isBackfillDue(context: Context, ownerId: String): Boolean {
            val prefs = context.getSharedPreferences("diarium_sync", Context.MODE_PRIVATE)
            val ownerKey = ownerToken(ownerId)
            val lastVersion = prefs.getString("last_backfill_version.$ownerKey", null)
            val lastRun = prefs.getLong("last_backfill_at.$ownerKey", 0L)
            return lastVersion != BuildConfig.VERSION_NAME ||
                (System.currentTimeMillis() - lastRun) > 6 * 3600_000L
        }

        internal fun markBackfillScheduled(context: Context, ownerId: String) {
            val ownerKey = ownerToken(ownerId)
            context.getSharedPreferences("diarium_sync", Context.MODE_PRIVATE).edit()
                .putString("last_backfill_version.$ownerKey", BuildConfig.VERSION_NAME)
                .putLong("last_backfill_at.$ownerKey", System.currentTimeMillis())
                .apply()
        }

        /** Clear only the opted-out account's queued-backfill marker. */
        internal fun resetBackfillState(context: Context, ownerId: String) {
            val ownerKey = ownerToken(ownerId)
            context.getSharedPreferences("diarium_sync", Context.MODE_PRIVATE).edit()
                .remove("last_backfill_version.$ownerKey")
                .remove("last_backfill_at.$ownerKey")
                .apply()
        }

        private fun ownerToken(ownerId: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ownerId.toByteArray(Charsets.UTF_8))
    }
}
