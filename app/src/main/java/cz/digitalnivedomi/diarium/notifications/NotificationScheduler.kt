package cz.digitalnivedomi.diarium.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.ZoneId

/**
 * Schedules the three notification jobs with AlarmManager at the exact times
 * the user chose. Every fire re-arms the next occurrence (daily reminder,
 * weekly Sunday 20:00, monthly 1st). Rescheduled on boot and on prefs change.
 *
 * All wall-clock math lives in the pure [NotificationSchedulerLogic]; this object
 * is only the Android/AlarmManager glue.
 */
object NotificationScheduler {

    const val ACTION_ALARM = "cz.digitalnivedomi.diarium.ALARM"
    const val EXTRA_TYPE = "alarm_type"
    const val TYPE_REMINDER = "reminder"
    const val TYPE_WEEKLY = "weekly"
    const val TYPE_MONTHLY = "monthly"

    private const val REQ_REMINDER = 2001
    private const val REQ_WEEKLY = 2002
    private const val REQ_MONTHLY = 2003

    /** Inexact fallback window used when exact alarms are not permitted. */
    private const val INEXACT_WINDOW_MS = 15 * 60 * 1000L

    fun rescheduleAll(context: Context) {
        val prefs = NotificationPrefsStore(context).load()
        if (prefs.reminderEnabled) scheduleReminder(context, prefs)
        else cancel(context, REQ_REMINDER)
        if (prefs.weeklyEnabled) scheduleWeekly(context, prefs)
        else cancel(context, REQ_WEEKLY)
        if (prefs.monthlyEnabled) scheduleMonthly(context, prefs)
        else cancel(context, REQ_MONTHLY)
    }

    /**
     * Call after the user saves settings: clears the "reminded today" marker so a
     * newly chosen time can still fire today, then re-arms every enabled alarm.
     */
    fun onPrefsSaved(context: Context) {
        NotificationPrefsStore(context).setLastReminderDate(null)
        rescheduleAll(context)
    }

    /**
     * Exact alarms need `SCHEDULE_EXACT_ALARM` on Android 12+. On older versions
     * exact alarms are always allowed, so return true.
     */
    fun canScheduleExactAlarms(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else {
            true
        }

    private fun scheduleReminder(context: Context, prefs: NotificationPrefs) {
        val next = NotificationSchedulerLogic.nextDailyMillis(
            now = System.currentTimeMillis(),
            timeMinutes = prefs.reminderTimeMinutes,
            days = prefs.reminderDays,
            zone = ZoneId.systemDefault(),
        ) ?: return // no day selected → nothing to arm
        setExact(context, REQ_REMINDER, next, alarmIntent(context, TYPE_REMINDER))
    }

    private fun scheduleWeekly(context: Context, prefs: NotificationPrefs) {
        val next = NotificationSchedulerLogic.nextWeeklyMillis(
            now = System.currentTimeMillis(),
            timeMinutes = prefs.weeklyTimeMinutes,
            day = prefs.weeklyDay,
            zone = ZoneId.systemDefault(),
        )
        setExact(context, REQ_WEEKLY, next, alarmIntent(context, TYPE_WEEKLY))
    }

    private fun scheduleMonthly(context: Context, prefs: NotificationPrefs) {
        val next = NotificationSchedulerLogic.nextMonthlyMillis(
            now = System.currentTimeMillis(),
            timeMinutes = prefs.monthlyTimeMinutes,
            dayOfMonth = 1,
            zone = ZoneId.systemDefault(),
        )
        setExact(context, REQ_MONTHLY, next, alarmIntent(context, TYPE_MONTHLY))
    }

    private fun alarmIntent(context: Context, type: String): PendingIntent {
        val intent = Intent(context, NotificationAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            putExtra(EXTRA_TYPE, type)
        }
        return PendingIntent.getBroadcast(
            context, requestCodeFor(type), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCodeFor(type: String): Int = when (type) {
        TYPE_REMINDER -> REQ_REMINDER
        TYPE_WEEKLY -> REQ_WEEKLY
        else -> REQ_MONTHLY
    }

    private fun setExact(context: Context, requestCode: Int, triggerAt: Long, pi: PendingIntent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (canScheduleExactAlarms(context)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                return
            } catch (_: SecurityException) {
                // Permission revoked between the check and the call — degrade below.
            }
        }
        // Graceful degradation: a (≈15 min) window alarm instead of a crash.
        // The notification is delayed, never silently dropped.
        am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, INEXACT_WINDOW_MS, pi)
    }

    private fun cancel(context: Context, requestCode: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationAlarmReceiver::class.java).apply { action = ACTION_ALARM }
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    /** After a job fires we enqueue its worker and re-arm the next occurrence. */
    fun onAlarmFired(context: Context, type: String) {
        val work = OneTimeWorkRequestBuilder<NotificationWorker>()
            .setInputData(Data.Builder().putString(EXTRA_TYPE, type).build())
            .build()
        WorkManager.getInstance(context).enqueue(work)
        rescheduleAll(context) // re-arm the next occurrence for all enabled types
    }
}

/** Receives the exact alarm and hands it to [NotificationScheduler.onAlarmFired]. */
class NotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationScheduler.ACTION_ALARM) return
        val type = intent.getStringExtra(NotificationScheduler.EXTRA_TYPE)
            ?: NotificationScheduler.TYPE_REMINDER
        NotificationScheduler.onAlarmFired(context, type)
    }
}

/** Re-arms alarms after device reboot. */
class NotificationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.MY_PACKAGE_REPLACED") {
            NotificationScheduler.rescheduleAll(context)
        }
    }
}
