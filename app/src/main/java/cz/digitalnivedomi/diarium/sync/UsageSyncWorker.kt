package cz.digitalnivedomi.diarium.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.stats.UsageStatsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Background sync worker:
 *  - mode "today"     : snapshot current day's usage (used by the 21:00 job)
 *  - mode "yesterday" : full previous day (used by the 07:00 backfill + on-open check)
 *  - mode "backfill"  : last 7 days (used after install)
 *
 * Authenticates with the user's own JWT stored in [SessionStore] — no server
 * secrets live in a public APK.
 */
class UsageSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val sessionStore = SessionStore(appContext)
    private val usageStats = UsageStatsProvider(appContext)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val token = sessionStore.accessToken() ?: return Result.failure() // not logged in yet
        if (!usageStats.hasUsageAccess()) return Result.failure() // permission not granted

        val mode = inputData.getString("mode") ?: "today"
        return withContext(Dispatchers.IO) {
            try {
                when (mode) {
                    "today" -> pushDay(canonicalToday(), token)
                    "yesterday" -> pushDay(canonicalYesterday(), token)
                    "backfill" -> {
                        for (i in 1..7) pushDay(canonicalPastDay(i), token)
                    }
                    else -> pushDay(canonicalToday(), token)
                }
                Result.success()
            } catch (e: Exception) {
                Log.e("DiariumSync", "sync failed: ${e.message}")
                Result.retry()
            }
        }
    }

    private fun canonicalToday(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        return sdf.format(Calendar.getInstance().time)
    }

    private fun canonicalYesterday(): String {
        val cal = Calendar.getInstance().apply { timeZone = TimeZone.getDefault() }
        cal.add(Calendar.DAY_OF_YEAR, -1)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
            .format(cal.time)
    }

    private fun canonicalPastDay(daysAgo: Int): String {
        val cal = Calendar.getInstance().apply { timeZone = TimeZone.getDefault() }
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
            .format(cal.time)
    }

    private fun pushDay(date: String, token: String) {
        val stats = usageStats.dayStats(date)
        if (stats.optInt("totalSec", 0) <= 0 && stats.optInt("unlocks", 0) <= 0) return

        // save-entry requires user_id, which must match the JWT's `sub` claim.
        val jwtSub = try {
            val payloadB64 = token.split(".").getOrNull(1) ?: return
            val decoded = String(
                android.util.Base64.decode(payloadB64, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            )
            JSONObject(decoded).optString("sub")
        } catch (_: Exception) {
            return
        }
        if (jwtSub.isBlank()) return

        val appsArr = stats.optJSONArray("apps") ?: org.json.JSONArray()
        val topApps = org.json.JSONArray()
        for (i in 0 until appsArr.length()) {
            val a = appsArr.getJSONObject(i)
            val o = JSONObject().apply {
                put("app", a.getString("app"))
                put("minutes", a.getLong("timeSec") / 60.0)
            }
            topApps.put(o)
            if (topApps.length() >= 5) break
        }

        val payload = JSONObject().apply {
            put("user_id", jwtSub)
            put("date", date)
            put("phone_screen_time", stats.optLong("totalSec"))
            put("phone_unlocks", stats.optLong("unlocks"))
            put("phone_top_apps", topApps)
        }

        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(BuildConfig.SAVE_ENTRY_URL)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w("DiariumSync", "push $date → HTTP ${resp.code}")
            }
        }
    }
}