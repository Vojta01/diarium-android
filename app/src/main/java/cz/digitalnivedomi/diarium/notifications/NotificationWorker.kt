package cz.digitalnivedomi.diarium.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.R
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.AppClients
import cz.digitalnivedomi.diarium.core.data.AiKeyStore
import cz.digitalnivedomi.diarium.core.data.GoalsRepository
import cz.digitalnivedomi.diarium.core.data.ProfileStore
import cz.digitalnivedomi.diarium.core.data.ReportGenerator
import cz.digitalnivedomi.diarium.core.data.ReportOutcome
import cz.digitalnivedomi.diarium.core.data.SessionContext
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
 *    first looks for a fresh report row and notifies it without regenerating.
 *    When nothing fresh exists the report is generated:
 *      - with the user's own DeepSeek key (option C, "bring your own key") the
 *        phone generates it itself — [ReportGenerator] builds the exact prompt
 *        the server route uses and inserts the row into `ai_reports`;
 *      - without a key the old server route still mints it (transitional
 *        fallback — `GET {DIARIUM_URL}/api/cron/ai-report?type=…`).
 *    Either way the worker then polls `ai_reports` briefly and notifies the new
 *    row (id ≠ last-notified marker). The tap opens the AI Reports screen
 *    (`Routes.REPORTS`, period in the `open_report_type` extra).
 *
 * Reads and the report download go through [SupabaseClient] with the user JWT
 * (RLS-secured). The one deliberate exception WAS the report *generation* call:
 * the DeepSeek key must never live in the APK, so the worker used to always ask
 * `GET {DIARIUM_URL}/api/cron/ai-report?type=…` — an endpoint that accepts the
 * user's own JWT. Since option C the generation itself needs no server at all
 * when the user stored a key; the route remains only as the no-key fallback. No
 * server secret and no server-side schedule is involved anywhere.
 */
class NotificationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val prefsStore = NotificationPrefsStore(appContext)
    private val session = AppClients.sessionStore(appContext)
    private val supabase = AppClients.supabaseClient(appContext)

    /** The user's own DeepSeek key, if they added one (encrypted at rest). */
    private val aiKeys = AiKeyStore.forContext(appContext)

    /** The on-device profile — name/birth year for the prompt, plus the AI master switch. */
    private val profileStore = ProfileStore.from(appContext)

    /**
     * On-device report generation (option C); used only when a key is stored.
     * Personalised with the local profile (👤 header) and the live goals
     * progress (🎯 section), wired from the same client/session the worker
     * already holds — the construction `GoalsDeps` does for the goals screen.
     */
    private val generator by lazy {
        ReportGenerator(
            client = supabase,
            keyStore = aiKeys,
            session = SessionContext(session),
            profile = profileStore,
            goals = GoalsRepository(supabase, SessionContext(session)),
        )
    }

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
    // otherwise the report is generated — on this phone when the user has
    // stored their own DeepSeek key (option C), via the transitional server
    // route when they have not. Either way the fresh row is then polled.
    // The AI master switch (Profil → AI zapnutá) silences the whole path:
    // with AI off no report is generated and no notification is posted.

    private suspend fun handleReport(type: String) {
        val prefs = prefsStore.load()
        val enabled = if (type == "weekly") prefs.weeklyEnabled else prefs.monthlyEnabled
        if (!enabled) return

        // The master switch is read before anything else happens: with AI off the
        // worker returns success with no generation and no notification.
        if (!profileStore.load().aiEnabled) return

        val marker = if (type == "weekly") prefs.lastWeeklyNotifiedId else prefs.lastMonthlyNotifiedId

        // Never generate a second report when a fresh one already sits on the
        // server (this run's own request, the safety net, or a manual run).
        // Otherwise: on-device generation with the user's key generates and
        // inserts synchronously, so the poll below finds the row immediately;
        // the server fallback needs the longer poll (the route retries empty
        // outputs, so up to ~75 s).
        val existing = latestReportOnce(type)
        val latest = if (existing != null && isFresh(existing)) {
            existing
        } else if (aiKeys.hasKey()) {
            val outcome = generator.generate(type)
            if (outcome !is ReportOutcome.Generated) {
                // No key path never reaches here (checked above); record why a
                // failure happened so the cause is visible in logs — the poll
                // below then simply finds nothing new.
                Log.i(LOG_TAG, "On-device report generation for $type did not produce a row: $outcome")
            }
            latestReport(type, attempts = if (outcome is ReportOutcome.Generated) REPORT_POLL_ATTEMPTS else 1)
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
     * `GET {DIARIUM_URL}/api/cron/ai-report?type=…` with the user's own JWT,
     * personalised with the local profile when it has a name / birth year: the
     * route seeds its prompt the same way the on-device path does. Both params
     * are optional (the server tolerates their absence) and a failed profile
     * read simply drops them.
     *
     * Returns true when the server accepted the request (2xx). A failure is not
     * fatal: the notification path still polls for an already existing report.
     */
    private suspend fun requestReportGeneration(type: String): Boolean {
        val token = session.validAccessToken() ?: return false
        val profile = try {
            profileStore.load()
        } catch (_: Exception) {
            null
        }
        return try {
            val url = buildString {
                append("${BuildConfig.DIARIUM_URL}/api/cron/ai-report?type=$type")
                profile?.name?.takeIf { it.isNotBlank() }?.let { name ->
                    append("&userName=").append(java.net.URLEncoder.encode(name, "UTF-8"))
                }
                profile?.birthYear?.let { append("&birthYear=").append(it) }
            }
            val request = Request.Builder()
                .url(url)
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
        const val LOG_TAG = "DiariumNotify"

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
