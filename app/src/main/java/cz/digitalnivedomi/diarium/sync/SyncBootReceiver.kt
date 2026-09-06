package cz.digitalnivedomi.diarium.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Re-arms the usage-stat sync pipeline after a device reboot or an app
 * update. WorkManager periodic jobs are NOT reliably restored across reboots
 * (Doze / force-stop can drop them), which is how whole days of screen time
 * used to go missing. The boot receiver:
 *   1. re-schedules the daily evening/morning jobs (idempotent),
 *   2. fires a fresh 7-day backfill so any missed days are repaired as soon
 *      as the device is back online.
 */
class SyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        SyncScheduler.ensureScheduled(context)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val backfill = OneTimeWorkRequestBuilder<UsageSyncWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString("mode", "backfill").build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(SyncScheduler.WORK_BOOT, ExistingWorkPolicy.REPLACE, backfill)
    }
}