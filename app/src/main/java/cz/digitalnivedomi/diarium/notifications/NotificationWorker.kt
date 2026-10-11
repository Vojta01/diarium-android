package cz.digitalnivedomi.diarium.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.R
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.SupabaseClient
import cz.digitalnivedomi.diarium.core.data.isRecordedDay
import cz.digitalnivedomi.diarium.ui.nav.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Handles one notification alarm:
 *  - reminder: the pure [SmartReminder] decides whether to fire (day selected,
 *    not already reminded today, and — when smart reminder is on — no saved entry
 *    today). The saved-entry answer comes from Supabase; on a network failure it
 *    is treated as "unknown" (`false`) so the reminder still fires.
 *  - morning: the pure [MorningReminder] fires only when YESTERDAY's mood is
 *    definitively missing (owner request 2026-09-25); unknown (offline) stays
 *    silent, and it does not care whether today is filled.
 *  - weekly/monthly: the app is the only scheduler — the exact alarm (and the
 *    periodic safety net, [ReportSafetyNetWorker]) triggers this worker, which
 *    asks the server to generate the period report when nothing fresh exists,
 *    then polls `ai_reports` briefly and notifies the new row (id ≠ last-notified
 *    marker). A row that is already fresh is notified without another
 *    generation. The tap opens the AI Reports screen (`Routes.REPORTS`, period
 *    in the `open_report_type` extra).
 *
 * Reads and the report download go through [SupabaseClient] with the user JWT
 * (RLS-secured). The one deliberate exception is the report *generation* call:
 * the DeepSeek key must never live in the APK, so the worker asks
 * `GET {DIARIUM_URL}/api/cron/ai-report?type=…` — an endpoint that accepts the
 * user's own JWT and generates only that user's report. No server secret and no
 * server-side schedule is involved anywhere.
 */
class NotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val prefsStore = NotificationPrefsStore(appContext)
    private val session = SessionStore(appContext)
    private val supabase = SupabaseClient(session)

    /** Only for the report-generation request; no secret travels with it. */
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val type = inputData.getString(NotificationScheduler.EXTRA_TYPE)
                ?: NotificationScheduler.TYPE_REMINDER
            when (type) {
                NotificationScheduler.TYPE_REMINDER -> handleReminder()
                NotificationScheduler.TYPE_MORNING -> handleMorningReminder()
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

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayIso = today.toString()
        val dayOfWeek = today.dayOfWeek.value // 1=Po … 7=Ne (same as prefs)

        // Unknown on network failure → hasSavedEntry=false → do not silently drop.
        val hasSavedEntry = hasSavedEntry(todayIso)

        if (!SmartReminder.shouldNotify(prefs, todayIso, dayOfWeek, hasSavedEntry)) return

        prefsStore.setLastReminderDate(todayIso)
        showNotification(
            title = "Diarium",
            body = "Nezapomeň vyplnit dnešní záznam! 🖊️",
            route = NotificationIntents.ROUTE_HOME,
            requestCode = REQ_REMINDER_PI,
            sound = prefs.sound,
        )
    }

    // ── Morning reminder ("včerejšek není vyplněn") ────────────

    /**
     * Owner request 2026-09-25: fires only when yesterday's mood is definitively
     * missing — no `entries` row for yesterday, or a row whose mood is null/0.
     * An unknown answer (offline / query failure) stays silent: the notification
     * asserts a fact about yesterday, and crying wolf when the day may be filled
     * is worse than staying silent (unlike the evening reminder, which fires on
     * unknown). It does not care whether TODAY is filled — yesterday is still
     * missing either way.
     */
    private fun handleMorningReminder() {
        val prefs = prefsStore.load()
        if (!prefs.morningReminderEnabled) return

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterdayIso = today.minusDays(1).toString()

        val hasYesterdayMood = hasMoodFor(yesterdayIso)

        if (!MorningReminder.shouldNotify(prefs, today.toString(), hasYesterdayMood)) return

        prefsStore.setLastMorningReminderDate(today.toString())
        showNotification(
            title = "Diarium",
            body = "Včerejší den ještě nemá náladu — doplň si ho. 🖊️",
            route = NotificationIntents.ROUTE_HOME,
            requestCode = REQ_MORNING_PI,
            sound = prefs.sound,
        )
    }

    // ── Weekly / monthly reports ───────────────────────────────
    // App-driven: a fresh row (asked for moments ago) is only notified;
    // otherwise we ask the route to generate one and poll `ai_reports`.

    private suspend fun handleReport(type: String) {
        val prefs = prefsStore.load()
        val enabled = if (type == "weekly") prefs.weeklyEnabled else prefs.monthlyEnabled
        if (!enabled) return

        val marker = if (type == "weekly") prefs.lastWeeklyNotifiedId else prefs.lastMonthlyNotifiedId

        // Never ask for a second report when a fresh one already sits on the
        // server (this run's own request, the safety net, or a manual run).
        // Otherwise ask the route to generate it and give DeepSeek a moment
        // (the route retries empty outputs, so up to ~75 s) to produce the row.
        val existing = latestReportOnce(type)
        val latest = if (existing != null && isFresh(existing)) {
            existing
        } else {
            val requested = requestReportGeneration(type)
            latestReport(type, attempts = if (requested) REPORT_POLL_ATTEMPTS else 1)
        } ?: return
        val id = latest.optString("id")
        if (id.isEmpty() || id == marker) return // nothing new yet

        if (type == "weekly") prefsStore.setLastWeeklyId(id)
        else prefsStore.setLastMonthlyId(id)

        val label = if (type == "weekly") "📊 Týdenní reflexe" else "📊 Měsíční reflexe"
        showNotification(
            title = label,
            body = "Je připravená nová reflexe — podívej se, co ti data říkají.",
            route = Routes.REPORTS,
            requestCode = if (type == "weekly") REQ_WEEKLY_PI else REQ_MONTHLY_PI,
            sound = prefs.sound,
            reportType = type,
        )
    }

    /** A recently generated row is "this run's" report — notify it, do not regenerate. */
    private fun isFresh(report: JSONObject): Boolean {
        val createdAt = report.optString("created_at")
        return createdAt.isNotEmpty() &&
            ReportFreshness.isFresh(createdAt, Instant.now(), FRESH_REPORT_WINDOW_HOURS)
    }

    // ── Supabase queries (user JWT, RLS-secured) ────────────────

    private fun hasSavedEntry(date: String): Boolean {
        return try {
            val resp = supabase.get(
                "entries",
                mapOf("date" to "eq.$date", "select" to "id", "limit" to "1"),
            )
            if (resp.code !in 200..299) return false
            (resp.asJsonArray()?.length() ?: 0) > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Mood for [date] — a row alone proves nothing: the sync creates `entries`
     * rows without journaling, only `mood` (1..5) means the owner filled the day
     * in. Returns true = recorded, false = definitively missing (no row, or mood
     * null/0 = unanswered), null = unknown (network failure).
     */
    private fun hasMoodFor(date: String): Boolean? {
        return try {
            val resp = supabase.get(
                "entries",
                mapOf("date" to "eq.$date", "select" to "mood", "limit" to "1"),
            )
            if (resp.code !in 200..299) return null
            val arr = resp.asJsonArray() ?: return false
            if (arr.length() == 0) return false
            val obj = arr.getJSONObject(0)
            if (obj.isNull("mood")) false else isRecordedDay(obj.optInt("mood"))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `GET {DIARIUM_URL}/api/cron/ai-report?type=…` with the user's own JWT.
     *
     * Returns true when the server accepted the request (2xx). A failure is not
     * fatal: the notification path still polls for an already existing report.
     */
    private fun requestReportGeneration(type: String): Boolean {
        val token = session.validAccessToken() ?: return false
        return try {
            val request = Request.Builder()
                .url("${BuildConfig.DIARIUM_URL}/api/cron/ai-report?type=$type")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** Polls `ai_reports` up to [attempts] times, [REPORT_POLL_DELAY_MS] apart. */
    private suspend fun latestReport(type: String, attempts: Int = 1): JSONObject? {
        var last: JSONObject? = null
        for (attempt in 1..attempts.coerceAtLeast(1)) {
            last = latestReportOnce(type)
            if (last != null) return last
            if (attempt < attempts) delay(REPORT_POLL_DELAY_MS)
        }
        return last
    }

    private fun latestReportOnce(type: String): JSONObject? {
        return try {
            val resp = supabase.get(
                "ai_reports",
                mapOf(
                    "type" to "eq.$type",
                    "select" to "id,created_at",
                    "order" to "created_at.desc",
                    "limit" to "1",
                ),
            )
            if (resp.code !in 200..299) return null
            val arr = resp.asJsonArray() ?: return null
            if (arr.length() > 0) arr.getJSONObject(0) else null
        } catch (_: Exception) {
            null
        }
    }

    // ── Notification display ───────────────────────────────────

    private fun showNotification(
        title: String,
        body: String,
        route: String,
        requestCode: Int,
        sound: Boolean,
        reportType: String? = null,
    ) {
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

        val pi = NotificationIntents.contentIntent(applicationContext, route, requestCode, reportType)

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

    private companion object {
        /** The route retries empty generations, so allow up to ~75 s (15 × 5 s) of polling. */
        const val REPORT_POLL_ATTEMPTS = 15
        const val REPORT_POLL_DELAY_MS = 5_000L

        /** How old a report may be and still count as "this run's" (no regeneration). */
        const val FRESH_REPORT_WINDOW_HOURS = 24L

        const val REQ_REMINDER_PI = 3001
        const val REQ_WEEKLY_PI = 3002
        const val REQ_MONTHLY_PI = 3003
        const val REQ_MORNING_PI = 3004
    }
}
