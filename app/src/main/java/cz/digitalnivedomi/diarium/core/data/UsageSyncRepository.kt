package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.stats.DayUsage
import cz.digitalnivedomi.diarium.stats.UsageStatsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Pushes one day of phone usage straight into Supabase, with the signed-in user's
 * own JWT — no Vercel hop, no `service_role` key.
 *
 * The legacy path POSTed the payload to `BuildConfig.SAVE_ENTRY_URL`
 * (`/api/save-entry`), a server route that held the service-role key in an env var.
 * M6 removes that dependency: the app calls the same RPC the web check-in uses
 * (`rpc/save_daily_entry`, exactly like [EntriesRepository.save]), so the phone's
 * numbers land on the day's row even when the app is never opened.
 *
 * The transport is [SupabaseClient], which means the request carries the anon key
 * and `SessionStore.validAccessToken()` — an expired token is refreshed before the
 * push, so a background job can never spin on a dead token. `auth.uid()` must match
 * the payload's `user_id` or the RPC raises 42501, which is why the user id comes
 * from [SessionContext] (the session the token belongs to), never from decoding the
 * JWT by hand the way the old worker did.
 */
class UsageSyncRepository(private val context: Context) {

    private val store = SessionStore(context)
    private val session = SessionContext(store)
    private val client = SupabaseClient(store)
    private val usageStats = UsageStatsProvider(context)

    /**
     * Reads [date]'s usage and upserts it via the RPC.
     *
     * Returns why nothing was pushed instead of throwing for the two states that are
     * simply "not ready yet" (signed out, usage access not granted) so the worker can
     * retry without a round-trip. A day with no captured usage is [EMPTY_DAY] — the
     * legacy worker skipped it, and pushing a zero row would erase a good snapshot
     * with a bad one.
     *
     * A failed HTTP push **throws** [IOException] on purpose: the old worker only
     * logged the status code and returned success, so WorkManager believed the push
     * worked and never retried. Throwing is what makes the retry happen; 401 gets a
     * fresh token on the next attempt via `validAccessToken()`.
     */
    suspend fun pushDay(date: String): UsagePushOutcome = withContext(Dispatchers.IO) {
        // Not logged in yet (or the session is gone) — retry later so the daily
        // backfill still happens once the user signs in.
        store.validAccessToken() ?: return@withContext UsagePushOutcome.NO_SESSION
        if (!usageStats.hasUsageAccess()) return@withContext UsagePushOutcome.NO_USAGE_ACCESS
        val userId = session.userId() ?: return@withContext UsagePushOutcome.NO_SESSION

        val usage = usageStats.dayUsage(date)
        if (usage.totalSec <= 0 && usage.unlocks <= 0) return@withContext UsagePushOutcome.EMPTY_DAY

        val payload = UsageSyncPayload.build(userId, date, usage)
        val resp = client.post("rpc/save_daily_entry", JSONObject().put("p_payload", payload))
        if (!resp.isSuccessful) {
            throw IOException("push failed for $date: HTTP ${resp.code}")
        }
        UsagePushOutcome.SENT
    }
}

/** What one [UsageSyncRepository.pushDay] attempt did. */
enum class UsagePushOutcome {
    /** The RPC accepted the day's usage. */
    SENT,

    /** The day captured nothing (screen off / phone unused) — nothing to push. */
    EMPTY_DAY,

    /** No session or no user id — the worker retries once the user signs in. */
    NO_SESSION,

    /** PACKAGE_USAGE_STATS not granted yet — the worker retries. */
    NO_USAGE_ACCESS,
}

/**
 * The exact `save_daily_entry(jsonb)` payload for a day of phone usage.
 *
 * **Only these five keys may ever be sent** (`user_id`, `date`,
 * `phone_screen_time`, `phone_unlocks`, `phone_top_apps`). The RPC upserts with
 * `col = case when (p_payload ? 'col') then excluded.col else t.col end`, i.e. it
 * overwrites **every key present in the payload** — so carrying `mood`, `note`,
 * `activities`, `habits`, `gratitude`, `weather`, `scale_values`, `photo_path` or
 * `ai_reflection` here would wipe the day's check-in with nulls. This is the mirror
 * image of [EntriesRepository.buildPayload], which omits `phone_*` for the same
 * reason. [UsageSyncPayloadTest] pins the key set.
 *
 * `phone_screen_time`/`phone_unlocks` are whole seconds and a whole count: the RPC
 * matches them against `^-?[0-9]+$` and raises 22023 for anything else, so they are
 * never Doubles. `minutes` inside `phone_top_apps` is fractional on purpose — the
 * web normalises it to seconds as `Math.round(minutes * 60)`, so a 90-second app
 * survives the round-trip instead of being flattened to one minute.
 */
object UsageSyncPayload {

    /** Apps below this are left out — too small to be a chart slice. */
    const val MIN_APP_SEC = 30L

    /**
     * At most this many apps travel. Top-5 left ~95% of the day labelled "other";
     * top-15 keeps the "remaining time" slice small.
     */
    const val MAX_APPS = 15

    /**
     * Builds the payload for [usage] on [date], owned by [userId].
     *
     * [usage]'s apps are already in encounter order, so the slice keeps the same
     * apps the legacy worker sent (first 15 at or above [minSec]). The app names are
     * the resolved labels ([DayUsage]'s `label`), not package names — that is what
     * `phone_top_apps.app` holds on the web too.
     */
    fun build(
        userId: String,
        date: String,
        usage: DayUsage,
        minSec: Long = MIN_APP_SEC,
        maxApps: Int = MAX_APPS,
    ): JSONObject {
        val topApps = JSONArray()
        var sent = 0
        for (app in usage.apps) {
            if (app.seconds < minSec) continue
            topApps.put(
                JSONObject().apply {
                    put("app", app.label)
                    put("minutes", app.seconds / 60.0)
                },
            )
            sent++
            if (sent >= maxApps) break
        }

        return JSONObject().apply {
            put("user_id", userId)
            put("date", date)
            put("phone_screen_time", usage.totalSec)
            put("phone_unlocks", usage.unlocks)
            put("phone_top_apps", topApps)
        }
    }
}
