package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.stats.DayUsage
import cz.digitalnivedomi.diarium.stats.UsageStatsProvider
import cz.digitalnivedomi.diarium.stats.UsageStatsSource
import cz.digitalnivedomi.diarium.sync.UsageSyncConsentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pushes one day of phone usage to Supabase with the owner captured by the
 * WorkRequest. Consent and the session owner are checked before collection and again
 * under owner-scoped locks at the asynchronous network handoff. Opt-out waits for
 * that short handoff; a request already started by the transport may still complete.
 */
class UsageSyncRepository internal constructor(
    private val store: SessionStore,
    private val consentStore: UsageSyncConsentStore,
    private val client: SupabaseClient,
    private val usageStats: UsageStatsSource,
) {

    constructor(context: Context) : this(
        store = AppClients.sessionStore(context),
        consentStore = UsageSyncConsentStore(context),
        client = AppClients.supabaseClient(context),
        usageStats = UsageStatsProvider(context),
    )

    /**
     * Reads [date]'s usage and upserts it only for [expectedOwnerId]. Missing
     * consent or a changed owner is a terminal no-op, not a retryable condition.
     * Usage access, refresh failures and HTTP failures remain retryable.
     */
    suspend fun pushDay(expectedOwnerId: String, date: String): UsagePushOutcome = withContext(Dispatchers.IO) {
        if (expectedOwnerId.isBlank() || store.userSubject() != expectedOwnerId) {
            return@withContext UsagePushOutcome.OWNER_CHANGED
        }
        if (!consentStore.isEnabledFor(expectedOwnerId)) {
            return@withContext UsagePushOutcome.CONSENT_DISABLED
        }
        if (!usageStats.hasUsageAccess()) return@withContext UsagePushOutcome.NO_USAGE_ACCESS

        // This refresh is still owner-bound: SessionStore verifies the refreshed
        // JWT subject before persisting/returning it.
        if (store.validAccessTokenForUser(expectedOwnerId) == null) {
            return@withContext if (store.userSubject() == expectedOwnerId) {
                UsagePushOutcome.NO_SESSION
            } else {
                UsagePushOutcome.OWNER_CHANGED
            }
        }

        val usage = usageStats.dayUsage(date)

        // UsageStats collection may take time; re-read both independent gates at
        // the write boundary so opt-out and account replacement stop this attempt.
        if (store.userSubject() != expectedOwnerId) return@withContext UsagePushOutcome.OWNER_CHANGED
        if (!consentStore.isEnabledFor(expectedOwnerId)) {
            return@withContext UsagePushOutcome.CONSENT_DISABLED
        }
        if (usage.totalSec <= 0 && usage.unlocks <= 0) return@withContext UsagePushOutcome.EMPTY_DAY

        val payload = UsageSyncPayload.build(expectedOwnerId, date, usage)
        val ownerClient = try {
            client.forOwner(expectedOwnerId)
        } catch (_: SessionOwnerMismatchException) {
            return@withContext UsagePushOutcome.OWNER_CHANGED
        }
        val request = try {
            ownerClient.preparePostRequest("rpc/save_daily_entry", JSONObject().put("p_payload", payload))
        } catch (_: SessionOwnerMismatchException) {
            return@withContext UsagePushOutcome.OWNER_CHANGED
        }

        var refusal: UsagePushOutcome? = null
        suspend fun dispatch(request: HttpRequest): HttpResponse? {
            refusal = null
            return suspendCancellableCoroutine { continuation ->
                val callHandle = AtomicReference<HttpCallHandle?>(null)
                continuation.invokeOnCancellation { callHandle.get()?.cancel() }

                val dispatched = consentStore.withOwnerDispatchLock(expectedOwnerId) {
                    val ownerStillActive = store.withCurrentOwnerLock(expectedOwnerId) {
                        when {
                            !continuation.isActive -> false
                            !consentStore.isEnabledFor(expectedOwnerId) -> {
                                refusal = UsagePushOutcome.CONSENT_DISABLED
                                false
                            }
                            else -> {
                                val handle = ownerClient.enqueue(request) { result ->
                                    result.fold(
                                        onSuccess = { if (continuation.isActive) continuation.resume(it) },
                                        onFailure = {
                                            if (continuation.isActive) continuation.resumeWithException(it)
                                        },
                                    )
                                }
                                callHandle.set(handle)
                                if (!continuation.isActive) handle.cancel()
                                true
                            }
                        }
                    }
                    if (ownerStillActive == null) refusal = UsagePushOutcome.OWNER_CHANGED
                    ownerStillActive == true
                }
                if (!dispatched && continuation.isActive) continuation.resume(null)
            }
        }

        var response = dispatch(request)
        if (response == null) {
            return@withContext refusal ?: UsagePushOutcome.OWNER_CHANGED
        }
        if (response.code == 401) {
            // Do not spend a refresh token after this work item's account or
            // screen-time consent has changed. The refresh itself is owner-pinned
            // and runs without the consent dispatch lock; retry dispatch rechecks
            // both gates below to avoid holding either lock across network I/O.
            if (store.userSubject() != expectedOwnerId) return@withContext UsagePushOutcome.OWNER_CHANGED
            if (!consentStore.isEnabledFor(expectedOwnerId)) {
                return@withContext UsagePushOutcome.CONSENT_DISABLED
            }
            if (store.refreshAccessTokenForUser(expectedOwnerId) == null) {
                return@withContext if (store.userSubject() != expectedOwnerId) {
                    UsagePushOutcome.OWNER_CHANGED
                } else {
                    throw IOException("token refresh failed for $date after HTTP 401")
                }
            }

            val retryRequest = try {
                ownerClient.preparePostRequest("rpc/save_daily_entry", JSONObject().put("p_payload", payload))
            } catch (_: SessionOwnerMismatchException) {
                return@withContext UsagePushOutcome.OWNER_CHANGED
            }
            response = dispatch(retryRequest)
            if (response == null) {
                return@withContext refusal ?: UsagePushOutcome.OWNER_CHANGED
            }
        }
        if (!response.isSuccessful) throw IOException("push failed for $date: HTTP ${response.code}")
        UsagePushOutcome.SENT
    }
}

/** What one [UsageSyncRepository.pushDay] attempt did. */
enum class UsagePushOutcome {
    /** The RPC accepted the day's usage. */
    SENT,

    /** The day captured nothing (screen off / phone unused) — nothing to push. */
    EMPTY_DAY,

    /** The expected owner is no longer the active session; discard stale work. */
    OWNER_CHANGED,

    /** The owner withdrew screen-time consent; discard stale work. */
    CONSENT_DISABLED,

    /** The active owner's session needs a retry (including failed token refresh). */
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
