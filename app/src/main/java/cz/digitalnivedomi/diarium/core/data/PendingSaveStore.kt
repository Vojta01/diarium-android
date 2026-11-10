package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex

/**
 * One check-in waiting in the outbox. The owner is captured from the authenticated
 * session at enqueue time and is part of both the record key and its value.
 */
data class PendingSave(
    val ownerId: String,
    val date: String,
    val entry: DiaryEntry,
    val queuedAt: Long,
    /** Persisted identity for this exact enqueue; protects same-payload races. */
    val transactionId: String,
    /** The server revision the queued edit was based on, if one existed. */
    val baselineUpdatedAt: String? = null,
    val baselineExists: Boolean? = null,
    val baselineId: String? = null,
    val baselineRevision: Long? = null,
    /** Exact immutable v2 CAS request. Null means legacy/manual recovery only. */
    val conditionalRequest: ConditionalEntrySaveRequest? = null,
    /** Terminal server conflict or rejection; payload and request token stay intact. */
    val recoveryReason: String? = null,
) {
    val isReplayable: Boolean
        get() {
            val request = conditionalRequest ?: return false
            return recoveryReason == null &&
                ownerId == request.ownerId && date == request.date && entry == request.entry &&
                request.baseline.isValid &&
                isValidIsoDate(request.date) &&
                runCatching { UUID.fromString(request.requestId) }.isSuccess
        }
}

sealed interface PendingSaveFlush {
    data object SessionChanged : PendingSaveFlush
    data object Changed : PendingSaveFlush
    data object Retry : PendingSaveFlush
    /** This record is retained for explicit review; it must not be replayed again. */
    data class RecoveryRequired(val record: PendingSave, val reason: String? = null) : PendingSaveFlush
    data class Saved(val record: PendingSave) : PendingSaveFlush
}

enum class PendingSaveRecoveryIssueKind { MALFORMED, OWNERLESS }

/** Text-free marker for a local blob that cannot safely be assigned or decoded. */
data class PendingSaveRecoveryIssue(
    val date: String,
    val kind: PendingSaveRecoveryIssueKind,
)

private fun isValidIsoDate(value: String): Boolean =
    runCatching { LocalDate.parse(value) }.isSuccess

/**
 * Owner-partitioned offline outbox. Each instance is bound to one authenticated
 * Supabase user. A date-only key from the pre-owner format is intentionally never
 * decoded: it remains in SharedPreferences as quarantined data because its owner
 * cannot be inferred safely.
 *
 * New records live at `v2/<base64url(owner)>/<date>` and repeat `ownerId` inside
 * the JSON. The duplicate binding is deliberate: a moved/copied blob cannot be
 * replayed under a different namespace. Reads fail closed on owner mismatch or
 * corrupt data.
 */
class PendingSaveStore private constructor(
    private val prefs: SharedPreferences,
    val ownerId: String?,
) {

    private val recordPrefix: String? = ownerId?.takeIf { it.isNotBlank() }?.let(::prefixFor)

    /**
     * Serialize the complete server-write transaction for one owner/date. A foreground
     * save and worker flush both take this suspendable lock before checking/sending,
     * so an older request cannot finish after a newer direct foreground write.
     */
    suspend fun <T> withDateLock(date: String, action: suspend () -> T): T {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: "<unbound>"
        val lock = DATE_LOCKS.computeIfAbsent("$owner\u0000$date") { Mutex() }
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    /** Acquire multiple date locks in deterministic order for a two-day transaction. */
    suspend fun <T> withDateLocks(dates: Collection<String>, action: suspend () -> T): T {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: "<unbound>"
        val locks = dates.distinct().sorted().map { date ->
            DATE_LOCKS.computeIfAbsent("$owner\u0000$date") { Mutex() }
        }
        val acquired = ArrayList<Mutex>(locks.size)
        try {
            for (lock in locks) {
                lock.lock()
                acquired += lock
            }
            return action()
        } finally {
            acquired.asReversed().forEach { it.unlock() }
        }
    }

    /** Replay only a complete, owner-matched CAS request with a verified row token. */
    suspend fun flushRecord(
        date: String,
        expectedOwnerId: String,
        currentOwnerId: () -> String?,
        dispatch: suspend (PendingSave) -> Result<Unit>,
        afterSaved: suspend (PendingSave) -> Unit = {},
    ): PendingSaveFlush = withDateLock(date) {
        if (ownerId != expectedOwnerId || currentOwnerId() != expectedOwnerId) {
            return@withDateLock PendingSaveFlush.SessionChanged
        }
        val snapshot = peekRecord(date) ?: return@withDateLock PendingSaveFlush.Changed
        if (snapshot.ownerId != expectedOwnerId) return@withDateLock PendingSaveFlush.SessionChanged
        if (!snapshot.isReplayable) {
            return@withDateLock PendingSaveFlush.RecoveryRequired(
                snapshot,
                snapshot.recoveryReason ?: "The original owner, revision, or CAS request is not verified.",
            )
        }
        val result = try {
            dispatch(snapshot)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withDateLock PendingSaveFlush.Retry
        }
        val failure = result.exceptionOrNull()
        if (failure is ConditionalSaveConflictException) {
            val reason = "Server conflict: ${failure.reason}"
            if (!markRecoveryIfUnchanged(snapshot, reason)) {
                return@withDateLock PendingSaveFlush.Retry
            }
            return@withDateLock PendingSaveFlush.RecoveryRequired(
                peekRecord(date) ?: snapshot.copy(recoveryReason = reason),
                reason,
            )
        }
        if (failure is SessionOwnerMismatchException || currentOwnerId() != expectedOwnerId) {
            return@withDateLock PendingSaveFlush.SessionChanged
        }
        if (failure is ConditionalSaveUnknownException || failure is java.io.IOException) {
            return@withDateLock PendingSaveFlush.Retry
        }
        if (failure != null) {
            val reason = failure.message ?: "The server rejected this saved request."
            if (!markRecoveryIfUnchanged(snapshot, reason)) {
                return@withDateLock PendingSaveFlush.Retry
            }
            return@withDateLock PendingSaveFlush.RecoveryRequired(
                peekRecord(date) ?: snapshot.copy(recoveryReason = reason),
                reason,
            )
        }
        if (currentOwnerId() != expectedOwnerId) return@withDateLock PendingSaveFlush.SessionChanged
        if (!removeIfUnchanged(snapshot)) return@withDateLock PendingSaveFlush.Changed
        try {
            afterSaved(snapshot)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // This callback is cleanup after a durable server receipt, not a write.
        }
        PendingSaveFlush.Saved(snapshot)
    }

    /** Queue [entry] for [date], replacing only this owner's pending save for that date. */
    fun enqueue(
        date: String,
        entry: DiaryEntry,
        queuedAt: Long = System.currentTimeMillis(),
        transactionId: String = UUID.randomUUID().toString(),
        baselineUpdatedAt: String? = null,
        baselineExists: Boolean? = null,
    ): Boolean {
        val owner = ownerId ?: return false
        val key = storageKey(owner, date)
        synchronized(OUTBOX_LOCK) {
            val blob = JSONObject()
                .put("ownerId", owner)
                .put("queuedAt", queuedAt)
                .put("transactionId", transactionId)
                .put("baselineExists", baselineExists ?: JSONObject.NULL)
                .put("entry", entry.toJson())
                .apply { baselineUpdatedAt?.let { put("baselineUpdatedAt", it) } }
            return prefs.edit().putString(key, blob.toString()).commit()
        }
    }

    /** Persist the exact conditional request after an ambiguous foreground result. */
    fun enqueueConditional(
        request: ConditionalEntrySaveRequest,
        queuedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return false
        if (request.ownerId != owner || !isValidIsoDate(request.date) || !request.baseline.isValid) return false
        if (runCatching { UUID.fromString(request.requestId) }.isFailure) return false
        val key = storageKey(owner, request.date)
        val transactionId = UUID.randomUUID().toString()
        val baseline = JSONObject()
            .put("exists", request.baseline.exists)
            .put("id", request.baseline.id ?: JSONObject.NULL)
            .put("revision", request.baseline.revision ?: JSONObject.NULL)
        val exactRequest = JSONObject()
            .put("version", CONDITIONAL_REQUEST_VERSION)
            .put("ownerId", request.ownerId)
            .put("date", request.date)
            .put("entry", conditionalEntryJson(request.entry))
            .put("baseline", baseline)
            .put("requestId", request.requestId)
            .put("writeAiReflection", request.writeAiReflection)
        val blob = JSONObject()
            .put("ownerId", owner)
            .put("queuedAt", queuedAt)
            .put("transactionId", transactionId)
            .put("baselineExists", request.baseline.exists)
            .put("baselineId", request.baseline.id ?: JSONObject.NULL)
            .put("baselineRevision", request.baseline.revision ?: JSONObject.NULL)
            .put("entry", conditionalEntryJson(request.entry))
            .put("conditionalRequest", exactRequest)
        synchronized(OUTBOX_LOCK) {
            return prefs.edit().putString(key, blob.toString()).commit()
        }
    }

    /** The queued entry for [date], or null when no valid save belongs to this owner. */
    fun peek(date: String): DiaryEntry? = read(date)?.entry

    /** Exact persisted transaction snapshot, used for compare-and-remove after a flush. */
    fun peekRecord(date: String): PendingSave? = read(date)

    /**
     * Observe the durable outbox record for this owner and date. Register before
     * reading so a worker mutation cannot fall into a read/subscribe gap.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRecord(date: String): Flow<PendingSave?> {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return flowOf(null)
        val key = storageKey(owner, date)
        return callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                if (changedKey == null || changedKey == key) trySend(read(date))
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(read(date))
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.distinctUntilChanged()
    }

    /** This owner's flushable saves, oldest date first. */
    fun peekAll(): List<PendingSave> {
        val owner = ownerId ?: return emptyList()
        val prefix = recordPrefix ?: return emptyList()
        return prefs.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { key ->
                val date = key.removePrefix(prefix)
                if (date.isBlank()) null else read(date)
            }
            .sortedBy { it.date }
            .toList()
            .filter { it.ownerId == owner }
    }

    /** Drops this owner's queued save for [date]. */
    fun remove(date: String): Boolean {
        val owner = ownerId ?: return false
        synchronized(OUTBOX_LOCK) {
            return prefs.edit().remove(storageKey(owner, date)).commit()
        }
    }

    /**
     * Removes [completed] only if this owner still has the same queued transaction.
     * Keep enqueues blocked until cleanup of the removed transaction finishes.
     */
    fun removeIfUnchanged(completed: PendingSave, afterRemove: () -> Unit = {}): Boolean =
        synchronized(OUTBOX_LOCK) {
            val owner = ownerId ?: return@synchronized false
            if (completed.ownerId != owner) return@synchronized false
            val current = read(completed.date) ?: return@synchronized false
            if (current.ownerId != completed.ownerId || current.transactionId != completed.transactionId) {
                return@synchronized false
            }
            if (!prefs.edit().remove(storageKey(owner, completed.date)).commit()) {
                return@synchronized false
            }
            // Prevent an enqueue from racing photo cleanup for the old snapshot.
            runCatching { afterRemove() }
            true
        }

    /** Add only recovery metadata; never change the queued payload or CAS token. */
    private fun markRecoveryIfUnchanged(completed: PendingSave, reason: String): Boolean =
        synchronized(OUTBOX_LOCK) {
            val owner = ownerId ?: return@synchronized false
            if (completed.ownerId != owner) return@synchronized false
            val current = read(completed.date) ?: return@synchronized false
            if (current.transactionId != completed.transactionId ||
                current.conditionalRequest?.requestId != completed.conditionalRequest?.requestId
            ) return@synchronized false
            val key = storageKey(owner, completed.date)
            val raw = prefs.getString(key, null) ?: return@synchronized false
            val updated = try {
                JSONObject(raw).put("recoveryReason", reason.take(MAX_RECOVERY_REASON_CHARS)).toString()
            } catch (_: Exception) {
                return@synchronized false
            }
            prefs.edit().putString(key, updated).commit()
        }

    /** True when this owner has at least one flushable save. */
    fun hasAny(): Boolean = peekAll().isNotEmpty()

    /** True only for complete CAS requests safe to schedule for automatic replay. */
    fun hasReplayable(): Boolean = peekAll().any { it.isReplayable }

    /** A malformed owner-keyed blob or legacy date-only blob stays quarantined. */
    fun recoveryIssueForDate(date: String): PendingSaveRecoveryIssue? {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return null
        val key = storageKey(owner, date)
        if (prefs.contains(key) && read(date) == null) {
            return PendingSaveRecoveryIssue(date, PendingSaveRecoveryIssueKind.MALFORMED)
        }
        if (LEGACY_DATE.matches(date) && prefs.contains(date)) {
            return PendingSaveRecoveryIssue(date, PendingSaveRecoveryIssueKind.OWNERLESS)
        }
        return null
    }

    /** Number of flushable records for this owner. */
    fun size(): Int = peekAll().size

    /** Raw legacy date keys are left in place and never included in normal reads. */
    fun quarantinedLegacyKeys(): Set<String> = prefs.all.keys
        .filter { key -> LEGACY_DATE.matches(key) }
        .toSet()

    /** Raw beta5 date-keyed values for the read-only recovery inventory only. */
    internal fun legacyRecoverySnapshot(): Map<String, Any?> =
        prefs.all.filterKeys { key -> LEGACY_DATE.matches(key) }

    private fun read(date: String): PendingSave? {
        val owner = ownerId ?: return null
        val raw = try {
            prefs.getString(storageKey(owner, date), null)
        } catch (_: Exception) {
            null
        } ?: return null
        return decode(owner, date, raw)
    }

    private fun decode(expectedOwner: String, date: String, raw: String): PendingSave? {
        return try {
            if (!isValidIsoDate(date)) return null
            val blob = JSONObject(raw)
            val owner = blob.optString("ownerId").takeIf { it.isNotBlank() } ?: return null
            if (owner != expectedOwner) return null
            val hasConditionalRequest = blob.has("conditionalRequest")
            val conditionalJson = if (hasConditionalRequest) {
                blob.optJSONObject("conditionalRequest") ?: return null
            } else {
                null
            }
            val entryJson = blob.optJSONObject("entry") ?: return null
            val entry = if (hasConditionalRequest) {
                decodeConditionalEntry(entryJson) ?: return null
            } else {
                DiaryEntry.fromJson(entryJson)
            }
            val transactionId = blob.optString("transactionId").takeIf { it.isNotBlank() } ?: return null
            val conditionalRequest = if (conditionalJson != null) {
                decodeConditionalRequest(conditionalJson, owner, date, entry) ?: return null
            } else {
                null
            }
            PendingSave(
                ownerId = owner,
                date = date,
                entry = entry,
                queuedAt = blob.optLong("queuedAt"),
                transactionId = transactionId,
                baselineUpdatedAt = blob.optString("baselineUpdatedAt").takeIf { it.isNotBlank() },
                baselineExists = if (blob.isNull("baselineExists")) null else blob.optBoolean("baselineExists"),
                baselineId = blob.optString("baselineId").takeIf { it.isNotBlank() },
                baselineRevision = if (blob.has("baselineRevision") && !blob.isNull("baselineRevision")) {
                    blob.optLong("baselineRevision")
                } else {
                    null
                },
                conditionalRequest = conditionalRequest,
                recoveryReason = blob.optString("recoveryReason").takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeConditionalRequest(
        json: JSONObject,
        recordOwner: String,
        recordDate: String,
        recordEntry: DiaryEntry,
    ): ConditionalEntrySaveRequest? {
        if (json.optInt("version", 0) != CONDITIONAL_REQUEST_VERSION) return null
        val owner = json.optString("ownerId").takeIf { it.isNotBlank() } ?: return null
        val date = json.optString("date").takeIf { isValidIsoDate(it) } ?: return null
        if (owner != recordOwner || date != recordDate) return null
        val entryJson = json.optJSONObject("entry") ?: return null
        val entry = decodeConditionalEntry(entryJson) ?: return null
        if (entry != recordEntry) return null
        val baselineJson = json.optJSONObject("baseline") ?: return null
        val rawExists = baselineJson.opt("exists") as? Boolean ?: return null
        if (!baselineJson.has("id") || !baselineJson.has("revision")) return null
        val rawId = baselineJson.opt("id")
        val id = when {
            rawId == null || rawId == JSONObject.NULL -> null
            rawId is String -> rawId.takeIf { it.isNotBlank() } ?: return null
            else -> return null
        }
        val rawRevision = baselineJson.opt("revision")
        val revision = when {
            rawRevision == null || rawRevision == JSONObject.NULL -> null
            rawRevision is Number && rawRevision.toDouble() == rawRevision.toLong().toDouble() -> rawRevision.toLong()
            else -> return null
        }
        val baseline = EntryBaseline(rawExists, id, revision)
        if (!baseline.isValid) return null
        val requestId = json.optString("requestId").takeIf { it.isNotBlank() } ?: return null
        if (runCatching { UUID.fromString(requestId) }.isFailure) return null
        val writeAiReflection = json.opt("writeAiReflection") as? Boolean ?: return null
        return ConditionalEntrySaveRequest(owner, date, entry, baseline, requestId, writeAiReflection)
    }

    /** Strict decoder for versioned CAS payloads; the legacy DiaryEntry decoder is intentionally lenient. */
    private fun decodeConditionalEntry(json: JSONObject): DiaryEntry? {
        listOf("mood", "sleepQuality", "stress").forEach { field ->
            val number = json.opt(field) as? Number ?: return null
            if (number.toDouble() != number.toInt().toDouble()) return null
        }
        if (json.opt("moodEmoji") !is String || json.opt("note") !is String) return null
        listOf("activities", "gratitude", "weather").forEach { field ->
            val values = json.optJSONArray(field) ?: return null
            if ((0 until values.length()).any { values.opt(it) !is String }) return null
        }
        val habits = json.optJSONObject("habits") ?: return null
        if (habits.length() > 0) {
            val names = habits.names() ?: return null
            for (index in 0 until names.length()) {
                if (habits.opt(names.optString(index)) !is Boolean) return null
            }
        }
        val scales = json.optJSONObject("scaleValues") ?: return null
        if (scales.length() > 0) {
            val names = scales.names() ?: return null
            for (index in 0 until names.length()) {
                val number = scales.opt(names.optString(index)) as? Number ?: return null
                if (number.toDouble() != number.toInt().toDouble()) return null
            }
        }
        if (json.has("clearPhoto") && json.opt("clearPhoto") !is Boolean) return null
        if (json.has("photoPath") && json.opt("photoPath") !is String) return null
        if (json.has("photoPathToDelete") && json.opt("photoPathToDelete") !is String) return null
        if (json.has("clearedScaleIds")) {
            val ids = json.optJSONArray("clearedScaleIds") ?: return null
            if ((0 until ids.length()).any { ids.opt(it) !is String }) return null
        }
        val aiReflection = when (val raw = json.opt("aiReflection")) {
            null, JSONObject.NULL -> null
            is String -> raw
            else -> return null
        }
        return DiaryEntry.fromJson(json).copy(aiReflection = aiReflection)
    }

    private fun conditionalEntryJson(entry: DiaryEntry): JSONObject = entry.toJson().apply {
        entry.aiReflection?.let { put("aiReflection", it) }
    }

    companion object {
        /** Stable preferences file; legacy date-only entries are quarantined in place. */
        const val PREFS_NAME = "diarium_pending_saves"
        private const val RECORD_VERSION_PREFIX = "v2/"
        private val LEGACY_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val OUTBOX_LOCK = Any()
        private val DATE_LOCKS = ConcurrentHashMap<String, Mutex>()
        private const val MAX_RECOVERY_REASON_CHARS = 240
        private const val CONDITIONAL_REQUEST_VERSION = 1

        /** Stable, explicit key used by tests and diagnostics. */
        fun key(ownerId: String, date: String): String = storageKey(ownerId, date)

        private fun storageKey(ownerId: String, date: String): String = "${prefixFor(ownerId)}$date"

        private fun prefixFor(ownerId: String): String =
            RECORD_VERSION_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(ownerId.toByteArray(Charsets.UTF_8)) + "/"

        /** Bind to the currently authenticated owner; null is a fail-closed store. */
        fun from(context: Context, ownerId: String?): PendingSaveStore =
            PendingSaveStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                ownerId?.takeIf { it.isNotBlank() },
            )
    }
}
