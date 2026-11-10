package cz.digitalnivedomi.diarium.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms only consented, owner-pinned usage sync after a reboot or app update. */
class SyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        SyncScheduler.onReboot(context)
    }
}
