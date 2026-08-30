package cz.digitalnivedomi.diarium.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.MainActivity
import cz.digitalnivedomi.diarium.R
import cz.digitalnivedomi.diarium.auth.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Handles one notification alarm:
 *  - reminder: checks the day-of-week, the smart "do I have today's entry?"
 *    (queries the real saved entry in Supabase, not a local draft), then shows
 *    the check-in reminder.
 *  - weekly/monthly: polls ai_reports for a NEW report (created after the last
 *    notified marker) and notifies "📊 Týdenní reflexe je připravená".
 */
class NotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val prefsStore = NotificationPrefsStore(appContext)
    private val sessionStore = SessionStore(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val type = inputData.getString(NotificationScheduler.EXTRA_TYPE)
                ?: NotificationScheduler.TYPE_REMINDER
            when (type) {
                NotificationScheduler.TYPE_REMINDER -> handleReminder()
                NotificationScheduler.TYPE_WEEKLY -> handleReport("weekly")
                NotificationScheduler.TYPE_MONTHLY -> handleReport("monthly")
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    // ── Reminder ───────────────────────────────────────────────

    private fun handleReminder() {
        val prefs = prefsStore.load()
        if (!prefs.reminderEnabled) return

        val today = todayStr()
        if (prefs.lastReminderDate == today) return // already reminded today

        // Day-of-week check (1=Po…7=Ne)
        val dow = Calendar.getInstance().apply { timeZone = TimeZone.getDefault() }
            .get(Calendar.DAY_OF_WEEK) // SUNDAY=1…SATURDAY=7
        val ourDow = if (dow == Calendar.SUNDAY) 7 else dow - 1
        if (ourDow !in prefs.reminderDays) return

        // Smart reminder: skip if today's entry already exists (real saved row)
        if (prefs.smartReminder && hasSavedEntry(today)) {
            prefsStore.setLastReminderDate(today) // don't nag later today
            return
        }

        prefsStore.setLastReminderDate(today)
        showNotification(
            title = "Diarium",
            body = "Nezapomeň vyplnit dnešní záznam! 🖊️",
            openUrl = "https://" + BuildConfig.DIARIUM_URL.removePrefix("https://") + "/?open=checkin",
            sound = prefs.sound,
        )
    }

    // ── Weekly / monthly reports ───────────────────────────────

    private fun handleReport(type: String) {
        val prefs = prefsStore.load()
        val enabled = if (type == "weekly") prefs.weeklyEnabled else prefs.monthlyEnabled
        if (!enabled) return

        val latest = latestReport(type) ?: return
        val marker = if (type == "weekly") prefs.lastWeeklyNotifiedId else prefs.lastMonthlyNotifiedId
        if (latest.getString("id") == marker) return // already notified

        if (type == "weekly") prefsStore.setLastWeeklyId(latest.getString("id"))
        else prefsStore.setLastMonthlyId(latest.getString("id"))

        val label = if (type == "weekly") "📊 Týdenní reflexe" else "📊 Měsíční reflexe"
        showNotification(
            title = label,
            body = "Je připravená nová reflexe — podívej se, co ti data říkají.",
            openUrl = "https://" + BuildConfig.DIARIUM_URL.removePrefix("https://") + "/?open=reports",
            sound = prefs.sound,
        )
    }

    // ── Supabase queries (user JWT, RLS-secured) ────────────────

    private fun hasSavedEntry(date: String): Boolean {
        val token = sessionStore.accessToken() ?: return false
        val url = "${BuildConfig.SUPABASE_URL}/rest/v1/entries" +
            "?date=eq.$date&select=id&limit=1"
        val req = Request.Builder()
            .url(url)
            .header("apikey", BuildConfig.SUPABASE_URL)
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            val arr = JSONArray(resp.body?.string() ?: "[]")
            return arr.length() > 0
        }
    }

    private fun latestReport(type: String): org.json.JSONObject? {
        val token = sessionStore.accessToken() ?: return null
        val url = "${BuildConfig.SUPABASE_URL}/rest/v1/ai_reports" +
            "?type=eq.$type&select=id,created_at&order=created_at.desc&limit=1"
        val req = Request.Builder()
            .url(url)
            .header("apikey", BuildConfig.SUPABASE_URL)
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val arr = JSONArray(resp.body?.string() ?: "[]")
            return if (arr.length() > 0) arr.getJSONObject(0) else null
        }
    }

    // ── Notification display ───────────────────────────────────

    private fun showNotification(title: String, body: String, openUrl: String, sound: Boolean) {
        val channelId = if (sound) "diarium_reminders" else "diarium_reminders_silent"
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    "diarium_reminders",
                    "Diarium připomenutí",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    "diarium_reminders_silent",
                    "Diarium připomenutí (tichá)",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_url", openUrl)
        }
        val pi = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_stat_diarium)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(1002, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently skip
        }
    }

    private fun todayStr(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Calendar.getInstance().time)
    }
}