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
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
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
    private val requestStore: UsageSyncRequestStore,
) {

    constructor(context: Context) : this(
        store = AppClients.sessionStore(context),
        consentStore = UsageSyncConsentStore(context),
        client = AppClients.supabaseClient(context),
        usageStats = UsageStatsProvider(context),
        requestStore = UsageSyncRequestStore(context),
    )

    /** Update only an existing day's phone fields through the durable conditional RPC. */
    suspend fun pushDay(expectedOwnerId: String, date: String): UsagePushOutcome = withContext(Dispatchers.IO) {
        if (expectedOwnerId.isBlank() || store.userSubject() != expectedOwnerId) {
            return@withContext UsagePushOutcome.OWNER_CHANGED
        }
        if (runCatching { LocalDate.parse(date) }.isFailure) throw IllegalArgumentException("Invalid screen-time date")
        if (!consentStore.isEnabledFor(expectedOwnerId)) {
            requestStore.clearOwner(expectedOwnerId)
            return@withContext UsagePushOutcome.CONSENT_DISABLED
        }
        if (store.validAccessTokenForUser(expectedOwnerId) == null) {
            return@withContext sessionUnavailableOutcome(expectedOwnerId) ?: UsagePushOutcome.NO_SESSION
        }
        gateOutcome(expectedOwnerId)?.let { return@withContext it }

        requestStore.withDateLock(expectedOwnerId, date) {
            gateOutcome(expectedOwnerId)?.let { return@withDateLock it }
            pushDayLocked(expectedOwnerId, date)
        }
    }

    private suspend fun pushDayLocked(ownerId: String, date: String): UsagePushOutcome {
        var request = when (val state = requestStore.read(ownerId, date)) {
            UsageSyncRequestState.Absent -> null
            UsageSyncRequestState.Corrupt -> throw IOException("Stored screen-time request is invalid; refusing to overwrite it")
            is UsageSyncRequestState.Present -> state.request
        }

        if (request == null) {
            if (!usageStats.hasUsageAccess()) return UsagePushOutcome.NO_USAGE_ACCESS
            val usage = usageStats.dayUsage(date)
            gateOutcome(ownerId)?.let { return it }
            if (usage.totalSec <= 0 && usage.unlocks <= 0) return UsagePushOutcome.EMPTY_DAY
            when (val baseline = readCurrentBaseline(ownerId, date)) {
                is BaselineRead.Refused -> return baseline.outcome
                BaselineRead.Missing -> return UsagePushOutcome.NO_DIARY_ENTRY
                is BaselineRead.Found -> {
                    val payload = UsageSyncPayload.build(ownerId, date, usage)
                    val payloadJson = payload.toString()
                    if (requestStore.readConfirmedHash(ownerId, date) == sha256(payloadJson)) {
                        return UsagePushOutcome.UNCHANGED
                    }
                    request = UsageSyncRequest.create(ownerId, date, payloadJson, baseline.baseline)
                    persistRequest(ownerId, request!!, expected = null)?.let { return it }
                }
            }
        }
        return writeWithConflictRecovery(ownerId, date, requireNotNull(request))
    }

    /** Read the actual row token; missing, duplicate, or malformed rows never get a write. */
    private suspend fun readCurrentBaseline(ownerId: String, date: String): BaselineRead {
        gateOutcome(ownerId)?.let { return BaselineRead.Refused(it) }
        val ownerClient = try {
            client.forOwner(ownerId)
        } catch (_: SessionOwnerMismatchException) {
            return BaselineRead.Refused(sessionUnavailableOutcome(ownerId) ?: UsagePushOutcome.NO_SESSION)
        }
        val request = try {
            ownerClient.prepareGetRequest(
                "entries",
                mapOf(
                    "user_id" to "eq.$ownerId",
                    "date" to "eq.$date",
                    "select" to "id,revision",
                    "limit" to "2",
                ),
            )
        } catch (_: SessionOwnerMismatchException) {
            return BaselineRead.Refused(sessionUnavailableOutcome(ownerId) ?: UsagePushOutcome.NO_SESSION)
        }
        val response = when (val result = executeWithAuthRetry(ownerId, ownerClient, request)) {
            is NetworkResult.Refused -> return BaselineRead.Refused(result.outcome)
            is NetworkResult.Received -> result.response
        }
        if (!response.isSuccessful) throw IOException("Could not read screen-time baseline: HTTP ${response.code}")
        val rows = response.asJsonArray() ?: throw IOException("Screen-time baseline response was not an array")
        if (rows.length() == 0) return BaselineRead.Missing
        if (rows.length() != 1) throw IOException("Multiple diary rows matched the screen-time date")
        val row = rows.getJSONObject(0)
        val id = row.optString("id").takeIf { it.isNotBlank() }
            ?: throw IOException("Diary row id is missing from the screen-time baseline")
        if (runCatching { UUID.fromString(id) }.isFailure) throw IOException("Diary row id is invalid")
        val rawRevision = row.opt("revision") as? Number
            ?: throw IOException("Diary row revision is missing from the screen-time baseline")
        val number = rawRevision.toDouble()
        val revision = rawRevision.toLong()
        if (!number.isFinite() || revision < 1L || revision.toDouble() != number) {
            throw IOException("Diary row revision is invalid in the screen-time baseline")
        }
        return BaselineRead.Found(EntryBaseline(exists = true, id = id, revision = revision))
    }

    /** Conflicts refresh the baseline/id; ambiguous requests retain the exact staged body. */
    private suspend fun writeWithConflictRecovery(
        ownerId: String,
        date: String,
        initialRequest: UsageSyncRequest,
    ): UsagePushOutcome {
        val ownerClient = try {
            client.forOwner(ownerId)
        } catch (_: SessionOwnerMismatchException) {
            return sessionUnavailableOutcome(ownerId) ?: UsagePushOutcome.NO_SESSION
        }
        var request = initialRequest
        for (attempt in 0 until MAX_WRITE_ATTEMPTS) {
            gateOutcome(ownerId)?.let { return it }
            val post = try {
                ownerClient.preparePostRequest(RPC_CONDITIONAL_SAVE, request.requestBodyJson)
            } catch (_: SessionOwnerMismatchException) {
                return sessionUnavailableOutcome(ownerId) ?: UsagePushOutcome.NO_SESSION
            }
            val response = when (val result = executeWithAuthRetry(ownerId, ownerClient, post)) {
                is NetworkResult.Refused -> return result.outcome
                is NetworkResult.Received -> result.response
            }
            if (response.code == 404) throw IOException("Conditional save RPC unavailable; no legacy write was attempted")
            if (response.code == 408 || response.code == 429 || response.code >= 500) {
                throw IOException("Conditional screen-time save outcome is unknown: HTTP ${response.code}")
            }
            if (!response.isSuccessful) throw IOException("Conditional screen-time save failed: HTTP ${response.code}")
            val receipt = response.asJsonObject() ?: throw IOException("Conditional screen-time save returned an invalid receipt")

            when (receipt.optString("status")) {
                "conflict" -> {
                    when (val current = readCurrentBaseline(ownerId, date)) {
                        is BaselineRead.Refused -> return current.outcome
                        BaselineRead.Missing -> {
                            clearConfirmedRequest(request)
                            return UsagePushOutcome.NO_DIARY_ENTRY
                        }
                        is BaselineRead.Found -> {
                            val replacement = request.withNewBaseline(current.baseline)
                            persistRequest(ownerId, replacement, expected = request)?.let { return it }
                            request = replacement
                            continue
                        }
                    }
                }
                "saved" -> {
                    val entryId = receipt.optString("entry_id").takeIf { it.isNotBlank() }
                        ?: throw IOException("Conditional screen-time receipt has no row id")
                    val revision = receipt.optLong("revision", 0L)
                    if (revision < 1L) throw IOException("Conditional screen-time receipt has no revision")
                    if (!receipt.optBoolean("replayed", false)) {
                        if (entryId != request.baseline.id || revision <= (request.baseline.revision ?: 0L)) {
                            throw IOException("Conditional screen-time receipt did not match its baseline")
                        }
                        clearConfirmedRequest(request)
                        requestStore.writeConfirmedHash(ownerId, date, sha256(request.phonePayloadJson))
                        return UsagePushOutcome.SENT
                    }
                    // An idempotency receipt is historical; only a matching current
                    // row token lets us discard the durable request.
                    when (val current = readCurrentBaseline(ownerId, date)) {
                        is BaselineRead.Refused -> return current.outcome
                        BaselineRead.Missing -> {
                            clearConfirmedRequest(request)
                            return UsagePushOutcome.NO_DIARY_ENTRY
                        }
                        is BaselineRead.Found -> {
                            if (current.baseline.id == entryId && current.baseline.revision == revision) {
                                clearConfirmedRequest(request)
                                requestStore.writeConfirmedHash(ownerId, date, sha256(request.phonePayloadJson))
                                return UsagePushOutcome.SENT
                            }
                            val replacement = request.withNewBaseline(current.baseline)
                            persistRequest(ownerId, replacement, expected = request)?.let { return it }
                            request = replacement
                            continue
                        }
                    }
                }
                else -> throw IOException("Conditional screen-time response had no recognized status")
            }
        }
        throw IOException("Too many concurrent screen-time save conflicts")
    }

    /** SharedPreferences.commit must succeed before request handoff. */
    private fun persistRequest(
        ownerId: String,
        request: UsageSyncRequest,
        expected: UsageSyncRequest?,
    ): UsagePushOutcome? {
        var refusal: UsagePushOutcome? = null
        var saved = false
        consentStore.withOwnerDispatchLock(ownerId) {
            val active = store.withCurrentOwnerLock(ownerId) {
                if (!consentStore.isEnabledFor(ownerId)) {
                    requestStore.clearOwner(ownerId)
                    refusal = UsagePushOutcome.CONSENT_DISABLED
                    false
                } else {
                    saved = if (expected == null) requestStore.saveIfAbsent(request)
                    else requestStore.replaceIfUnchanged(expected, request)
                    true
                }
            }
            if (active == null) refusal = UsagePushOutcome.OWNER_CHANGED
        }
        refusal?.let { return it }
        if (!saved) throw IOException("Could not durably stage the conditional screen-time request")
        return null
    }

    private fun clearConfirmedRequest(request: UsageSyncRequest) {
        if (requestStore.removeIfUnchanged(request)) return
        when (val current = requestStore.read(request.ownerId, request.date)) {
            UsageSyncRequestState.Absent -> Unit // Consent withdrawal may clear an in-flight request.
            UsageSyncRequestState.Corrupt -> throw IOException("Could not safely clear the completed screen-time request")
            is UsageSyncRequestState.Present -> if (current.request == request) {
                throw IOException("Could not durably clear the completed screen-time request")
            }
        }
    }

    /** Each HTTP handoff and the auth refresh after 401 recheck owner and consent. */
    private suspend fun executeWithAuthRetry(
        ownerId: String,
        ownerClient: SupabaseClient,
        request: HttpRequest,
    ): NetworkResult {
        gateOutcome(ownerId)?.let { return NetworkResult.Refused(it) }
        val first = dispatch(ownerId, ownerClient, request)
        if (first !is NetworkResult.Received || first.response.code != 401) return first
        gateOutcome(ownerId)?.let { return NetworkResult.Refused(it) }
        if (store.refreshAccessTokenForUser(ownerId) == null) {
            val status = sessionUnavailableOutcome(ownerId)
            if (status != null) return NetworkResult.Refused(status)
            throw IOException("Session refresh failed before conditional screen-time retry")
        }
        gateOutcome(ownerId)?.let { return NetworkResult.Refused(it) }
        val token = store.validAccessTokenForUser(ownerId)
            ?: throw IOException("Refreshed screen-time session is unavailable")
        gateOutcome(ownerId)?.let { return NetworkResult.Refused(it) }
        return dispatch(
            ownerId,
            ownerClient,
            request.copy(headers = request.headers + ("Authorization" to "Bearer $token")),
        )
    }

    /** Hold owner/consent locks only through asynchronous transport handoff. */
    private suspend fun dispatch(
        ownerId: String,
        ownerClient: SupabaseClient,
        request: HttpRequest,
    ): NetworkResult {
        var refusal: UsagePushOutcome? = null
        val response = suspendCancellableCoroutine<HttpResponse?> { continuation ->
            val callHandle = AtomicReference<HttpCallHandle?>(null)
            continuation.invokeOnCancellation { callHandle.get()?.cancel() }
            val handedOff = consentStore.withOwnerDispatchLock(ownerId) {
                val active = store.withCurrentOwnerLock(ownerId) {
                    when {
                        !continuation.isActive -> false
                        !consentStore.isEnabledFor(ownerId) -> {
                            requestStore.clearOwner(ownerId)
                            refusal = UsagePushOutcome.CONSENT_DISABLED
                            false
                        }
                        else -> {
                            val handle = ownerClient.enqueue(request) { result ->
                                result.fold(
                                    onSuccess = { if (continuation.isActive) continuation.resume(it) },
                                    onFailure = { if (continuation.isActive) continuation.resumeWithException(it) },
                                )
                            }
                            callHandle.set(handle)
                            if (!continuation.isActive) handle.cancel()
                            true
                        }
                    }
                }
                if (active == null) refusal = UsagePushOutcome.OWNER_CHANGED
                active == true
            }
            if (!handedOff && continuation.isActive) continuation.resume(null)
        }
        return response?.let(NetworkResult::Received)
            ?: NetworkResult.Refused(refusal ?: UsagePushOutcome.OWNER_CHANGED)
    }

    private fun gateOutcome(ownerId: String): UsagePushOutcome? {
        if (ownerId.isBlank() || store.userSubject() != ownerId) return UsagePushOutcome.OWNER_CHANGED
        if (!consentStore.isEnabledFor(ownerId)) {
            requestStore.clearOwner(ownerId)
            return UsagePushOutcome.CONSENT_DISABLED
        }
        return null
    }

    /** null means a retryable token-refresh/session failure. */
    private fun sessionUnavailableOutcome(ownerId: String): UsagePushOutcome? = when {
        store.userSubject() != ownerId -> UsagePushOutcome.OWNER_CHANGED
        !consentStore.isEnabledFor(ownerId) -> {
            requestStore.clearOwner(ownerId)
            UsagePushOutcome.CONSENT_DISABLED
        }
        else -> null
    }

    private sealed interface BaselineRead {
        data object Missing : BaselineRead
        data class Found(val baseline: EntryBaseline) : BaselineRead
        data class Refused(val outcome: UsagePushOutcome) : BaselineRead
    }

    private sealed interface NetworkResult {
        data class Received(val response: HttpResponse) : NetworkResult
        data class Refused(val outcome: UsagePushOutcome) : NetworkResult
    }

    companion object {
        private const val RPC_CONDITIONAL_SAVE = "rpc/save_daily_entry_v2"
        private const val MAX_WRITE_ATTEMPTS = 3
    }
}

/** What one [UsageSyncRepository.pushDay] attempt did. */
enum class UsagePushOutcome {
    /** The conditional v2 RPC accepted the day's phone usage. */
    SENT,
    /** The payload equals the last confirmed send, so nothing was written. */
    UNCHANGED,
    /** The day captured nothing (screen off / phone unused) — nothing to push. */
    EMPTY_DAY,
    /** There is no diary row to update; screen-time sync never creates one. */
    NO_DIARY_ENTRY,
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

/** Stable fingerprint of a phone payload; only equality matters, never the value. */
internal fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
