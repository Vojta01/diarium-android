package cz.digitalnivedomi.diarium.notifications

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Immutable snapshot of the user's notification preferences. */
data class NotificationPrefs(
    val reminderEnabled: Boolean = true,
    val reminderTimeMinutes: Int = 19 * 60,          // 19:00
    val reminderDays: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7), // 1=Po … 7=Ne
    val smartReminder: Boolean = true,               // only if no entry saved today
    val weeklyEnabled: Boolean = true,
    val weeklyDay: Int = 7,                          // 7 = Sunday (report generated Sunday 20:00)
    val weeklyTimeMinutes: Int = 20 * 60,            // 20:00
    val monthlyEnabled: Boolean = true,
    val monthlyTimeMinutes: Int = 9 * 60,            // 09:00, 1st day
    val openAppOnTap: Boolean = true,
    val sound: Boolean = true,
    // Markers — remember what we already notified so we never double-notify
    val lastReminderDate: String? = null,
    val lastWeeklyNotifiedId: String? = null,
    val lastMonthlyNotifiedId: String? = null,
)

/** SharedPreferences-backed store. */
class NotificationPrefsStore(context: Context) {

    private val prefs = context.getSharedPreferences("diarium_notifications", Context.MODE_PRIVATE)

    fun load(): NotificationPrefs = NotificationPrefs(
        reminderEnabled = prefs.getBoolean(KEY_REMINDER, true),
        reminderTimeMinutes = prefs.getInt(KEY_REMINDER_TIME, 19 * 60),
        reminderDays = prefs.getString(KEY_REMINDER_DAYS, null)
            ?.let { parseDays(it) }
            ?: setOf(1, 2, 3, 4, 5, 6, 7),
        smartReminder = prefs.getBoolean(KEY_SMART, true),
        weeklyEnabled = prefs.getBoolean(KEY_WEEKLY, true),
        weeklyDay = prefs.getInt(KEY_WEEKLY_DAY, 7),
        weeklyTimeMinutes = prefs.getInt(KEY_WEEKLY_TIME, 20 * 60),
        monthlyEnabled = prefs.getBoolean(KEY_MONTHLY, true),
        monthlyTimeMinutes = prefs.getInt(KEY_MONTHLY_TIME, 9 * 60),
        openAppOnTap = prefs.getBoolean(KEY_OPEN, true),
        sound = prefs.getBoolean(KEY_SOUND, true),
        lastReminderDate = prefs.getString(KEY_LAST_REMINDER, null),
        lastWeeklyNotifiedId = prefs.getString(KEY_LAST_WEEKLY, null),
        lastMonthlyNotifiedId = prefs.getString(KEY_LAST_MONTHLY, null),
    )

    /** Persists mutable settings; markers are updated separately. */
    fun save(p: NotificationPrefs) {
        prefs.edit()
            .putBoolean(KEY_REMINDER, p.reminderEnabled)
            .putInt(KEY_REMINDER_TIME, p.reminderTimeMinutes)
            .putString(KEY_REMINDER_DAYS, JSONArray(p.reminderDays.toList()).toString())
            .putBoolean(KEY_SMART, p.smartReminder)
            .putBoolean(KEY_WEEKLY, p.weeklyEnabled)
            .putInt(KEY_WEEKLY_DAY, p.weeklyDay)
            .putInt(KEY_WEEKLY_TIME, p.weeklyTimeMinutes)
            .putBoolean(KEY_MONTHLY, p.monthlyEnabled)
            .putInt(KEY_MONTHLY_TIME, p.monthlyTimeMinutes)
            .putBoolean(KEY_OPEN, p.openAppOnTap)
            .putBoolean(KEY_SOUND, p.sound)
            .apply()
    }

    fun setLastReminderDate(date: String?) {
        prefs.edit().putString(KEY_LAST_REMINDER, date).apply()
    }

    fun setLastWeeklyId(id: String?) {
        prefs.edit().putString(KEY_LAST_WEEKLY, id).apply()
    }

    fun setLastMonthlyId(id: String?) {
        prefs.edit().putString(KEY_LAST_MONTHLY, id).apply()
    }

    private fun parseDays(json: String): Set<Int> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getInt(it) }.toSet()
        } catch (_: Exception) {
            setOf(1, 2, 3, 4, 5, 6, 7)
        }
    }

    companion object {
        private const val KEY_REMINDER = "reminder_enabled"
        private const val KEY_REMINDER_TIME = "reminder_time"
        private const val KEY_REMINDER_DAYS = "reminder_days"
        private const val KEY_SMART = "reminder_smart"
        private const val KEY_WEEKLY = "weekly_enabled"
        private const val KEY_WEEKLY_DAY = "weekly_day"
        private const val KEY_WEEKLY_TIME = "weekly_time"
        private const val KEY_MONTHLY = "monthly_enabled"
        private const val KEY_MONTHLY_TIME = "monthly_time"
        private const val KEY_OPEN = "open_on_tap"
        private const val KEY_SOUND = "sound"
        private const val KEY_LAST_REMINDER = "last_reminder_date"
        private const val KEY_LAST_WEEKLY = "last_weekly_id"
        private const val KEY_LAST_MONTHLY = "last_monthly_id"
    }
}