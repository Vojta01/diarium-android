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
import java.util.Calendar
import java.util.TimeZone

/**
 * Schedules the three notification jobs with AlarmManager at the exact times
 * the user chose. Every fire re-arms the next occurrence (daily reminder,
 * weekly Sunday 20:00, monthly 1st). Rescheduled on boot and on prefs change.
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

    fun rescheduleAll(context: Context) {
        val prefs = NotificationPrefsStore(context).load()
        if (prefs.reminderEnabled) scheduleReminder(context, prefs)
        else cancel(context, REQ_REMINDER)
        if (prefs.weeklyEnabled) scheduleWeekly(context, prefs)
        else cancel(context, REQ_WEEKLY)
        if (prefs.monthlyEnabled) scheduleMonthly(context, prefs)
        else cancel(context, REQ_MONTHLY)
    }

    private fun scheduleReminder(context: Context, prefs: NotificationPrefs) {
        val next = nextDaily(prefs.reminderTimeMinutes)
        val intent = alarmIntent(context, TYPE_REMINDER)
        setExact(context, REQ_REMINDER, next, intent)
    }

    private fun scheduleWeekly(context: Context, prefs: NotificationPrefs) {
        val next = nextWeekly(prefs.weeklyDay, prefs.weeklyTimeMinutes)
        val intent = alarmIntent(context, TYPE_WEEKLY)
        setExact(context, REQ_WEEKLY, next, intent)
    }

    private fun scheduleMonthly(context: Context, prefs: NotificationPrefs) {
        val next = nextMonthly(prefs.monthlyTimeMinutes)
        val intent = alarmIntent(context, TYPE_MONTHLY)
        setExact(context, REQ_MONTHLY, next, intent)
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
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AlarmManager.RTC_WAKEUP
        } else {
            AlarmManager.RTC_WAKEUP
        }
        // Exact alarms need SCHEDULE_EXACT_ALARM (Android 12+); if denied we fall
        // back to a (≈15 min) window — still useful, never silent.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.setExactAndAllowWhileIdle(type, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(type, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            am.setWindow(type, triggerAt, 15 * 60 * 1000L, pi)
        }
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

    /** Next daily occurrence at [minutes] (if today's time already passed → tomorrow). */
    private fun nextDaily(minutes: Int): Long {
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            timeZone = TimeZone.getDefault()
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** Next occurrence on [dayOfWeek] (1=Po…7=Ne) at [minutes]. */
    private fun nextWeekly(dayOfWeek: Int, minutes: Int): Long {
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            timeZone = TimeZone.getDefault()
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // java.util.Calendar: SUNDAY=1 … SATURDAY=7; we store 1=Po..7=Ne → convert
        val targetCal = if (dayOfWeek == 7) Calendar.SUNDAY else dayOfWeek + 1
        while (cal.get(Calendar.DAY_OF_WEEK) != targetCal || !cal.after(now)) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** Next 1st-of-month at [minutes]. */
    private fun nextMonthly(minutes: Int): Long {
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply {
            timeZone = TimeZone.getDefault()
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.MONTH, 1)
        }
        return cal.timeInMillis
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