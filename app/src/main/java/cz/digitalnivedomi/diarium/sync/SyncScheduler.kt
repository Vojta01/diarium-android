package cz.digitalnivedomi.diarium.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cz.digitalnivedomi.diarium.BuildConfig
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

        fun ensureScheduled(context: Context) {
            val wm = WorkManager.getInstance(context)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Evening: today's snapshot at ~21:00
            scheduleDaily(context, WORK_EVENING, 21, 0, "today")
            // Morning: yesterday backfill at ~07:00
            scheduleDaily(context, WORK_MORNING, 7, 0, "yesterday")

            // One-time backfill of the last 7 days — re-run whenever the app is
            // opened (version change OR last run older than 6 h). This makes the
            // chart self-heal after updates instead of relying on a single
            // install-time enqueue that WorkManager may sit on.
            val prefs = context.getSharedPreferences("diarium_sync", Context.MODE_PRIVATE)
            val lastVersion = prefs.getString("last_backfill_version", null)
            val lastRun = prefs.getLong("last_backfill_at", 0L)
            val currentVersion = BuildConfig.VERSION_NAME
            val due = lastVersion != currentVersion || (System.currentTimeMillis() - lastRun) > 6 * 3600_000L

            if (due) {
                prefs.edit()
                    .putString("last_backfill_version", currentVersion)
                    .putLong("last_backfill_at", System.currentTimeMillis())
                    .apply()

                val init = OneTimeWorkRequestBuilder<UsageSyncWorker>()
                    .setInitialDelay(45, TimeUnit.SECONDS)
                    .setConstraints(constraints)
                    .setInputData(androidx.work.Data.Builder().putString("mode", "backfill").build())
                    .build()
                wm.enqueueUniqueWork(WORK_INIT, ExistingWorkPolicy.REPLACE, init)
            }
        }

        private fun scheduleDaily(context: Context, name: String, hour: Int, minute: Int, mode: String) {
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

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = PeriodicWorkRequestBuilder<UsageSyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .addTag(name)
                .setInputData(androidx.work.Data.Builder().putString("mode", mode).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                name,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }
    }
}