package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.util.WeakHashMap

/** A draft is bound to the authenticated owner and the server revision it started from. */
data class DraftRecord(
    val ownerId: String,
    val date: String,
    val entry: DiaryEntry,
    val baselineUpdatedAt: String?,
    /** null means this draft predates revision tracking and needs recovery on an existing day. */
    val baselineExists: Boolean? = null,
    val baselineId: String? = null,
    val baselineRevision: Long? = null,
    /** Exact unresolved v2 write; retries must keep its payload and baseline unchanged. */
    val pendingSaveRequestId: String? = null,
    /** AI text explicitly included in that request; ordinary server text is not persisted. */
    val pendingAiReflection: String? = null,
    /** Local recovery origin, used only to make retries idempotent and cleanup verifiable. */
    val legacyRecoveryOrigin: LegacyRecoveryRecordId? = null,
) {
    fun conflictsWith(
        remoteExists: Boolean,
        remoteUpdatedAt: String?,
        remoteId: String? = null,
        remoteRevision: Long? = null,
    ): Boolean {
        val hadRemoteEntry = baselineExists ?: return true
        if (hadRemoteEntry != remoteExists) return true
        if (!hadRemoteEntry) return false
        if (baselineId != null && baselineRevision != null) {
            return baselineId != remoteId || baselineRevision != remoteRevision
        }
        val baseline = baselineUpdatedAt ?: return true
        val current = remoteUpdatedAt ?: return true
        return baseline != current
    }
}

/** A recoverable local-draft read failed or the stored draft is malformed. */
class DraftStoreReadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Owner-namespaced, restart-safe draft storage. Legacy date-only keys are left
 * untouched and ignored: their former owner cannot be inferred safely.
 */
class DraftStore(
    private val dataStore: DataStore<Preferences>,
    val ownerId: String?,
) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * All instances sharing one DataStore and owner/date share this fence. A screen
     * can be recreated while an old lifecycle flush is still queued, so instance-
     * local generations would leave that flush able to resurrect a cleared draft.
     */
    private class MutationState {
        val mutex = Mutex()
    }

    private fun mutationState(owner: String, date: String): MutationState =
        synchronized(mutationStates) {
            mutationStates.getOrPut(dataStore) { mutableMapOf() }
                .getOrPut(key(owner, date)) { MutationState() }
        }

    /** Save a draft without replacing the baseline captured when editing began. */
    suspend fun save(
        date: String,
        entry: DiaryEntry,
        baselineUpdatedAt: String? = null,
        baselineExists: Boolean? = null,
        baselineId: String? = null,
        baselineRevision: Long? = null,
        pendingSaveRequestId: String? = null,
        pendingAiReflection: String? = null,
    ) {
        stage(
            date,
            entry,
            baselineUpdatedAt,
            baselineExists,
            baselineId,
            baselineRevision,
            pendingSaveRequestId,
            pendingAiReflection,
        ).await()
    }

    /**
     * Start an owner-scoped write in the store's lifetime, independent of a screen's
     * cancellable composition scope. Callers that are about to issue a remote save
     * await this handle first; a screen disposal can then cancel the request without
     * cancelling the local durability point.
     */
    fun stage(
        date: String,
        entry: DiaryEntry,
        baselineUpdatedAt: String? = null,
        baselineExists: Boolean? = null,
        baselineId: String? = null,
        baselineRevision: Long? = null,
        pendingSaveRequestId: String? = null,
        pendingAiReflection: String? = null,
    ): Deferred<Unit> {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return CompletableDeferred(Unit)
        val mutation = mutationState(owner, date)
        // Start undispatched so lock acquisition is queued in call order. A clear
        // requested later must not overtake a newer stage that is already queued.
        return appScope.async(start = CoroutineStart.UNDISPATCHED) {
            mutation.mutex.withLock {
                writeDraft(
                    owner,
                    date,
                    entry,
                    baselineUpdatedAt,
                    baselineExists,
                    baselineId,
                    baselineRevision,
                    pendingSaveRequestId,
                    pendingAiReflection,
                )
            }
        }
    }

    /** A lifecycle flush is bound to the same owner as the screen/store instance. */
    fun flush(
        date: String,
        entry: DiaryEntry,
        baselineUpdatedAt: String? = null,
        baselineExists: Boolean? = null,
        baselineId: String? = null,
        baselineRevision: Long? = null,
        pendingSaveRequestId: String? = null,
        pendingAiReflection: String? = null,
    ): Deferred<Unit> = stage(
        date,
        entry,
        baselineUpdatedAt,
        baselineExists,
        baselineId,
        baselineRevision,
        pendingSaveRequestId,
        pendingAiReflection,
    )

    suspend fun loadRecord(date: String): DraftRecord? {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return null
        val raw = try {
            dataStore.data.first()[stringPreferencesKey(key(owner, date))]
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw DraftStoreReadException("The local draft for $date could not be read.", failure)
        } ?: return null
        return decode(owner, date, raw)
    }

    /** Raw ownerless beta5 values for the read-only recovery inventory only. */
    internal suspend fun legacyRecoverySnapshot(): Map<String, Any?> =
        dataStore.data.first().asMap().entries
            .filter { (key, _) ->
                key.name.startsWith("diarium_draft_") &&
                    !key.name.startsWith("diarium_draft_v2_")
            }
            .associate { (key, value) -> key.name to value }

    /**
     * Copy a manually attested legacy record into this owner's draft namespace.
     * The DataStore edit is an atomic compare-and-set: an existing target is never
     * replaced, and draft-origin bytes must still match the inspected source.
     */
    internal suspend fun importLegacyRecoveryIfAbsent(
        date: String,
        entry: DiaryEntry,
        origin: LegacyRecoveryRecordId,
        sourceRaw: String,
        sourceStillUnchanged: () -> Boolean = { true },
    ): LegacyRecoveryCopyResult {
        val owner = ownerId?.takeIf { it.isNotBlank() }
            ?: return LegacyRecoveryCopyResult.OWNER_UNAVAILABLE
        if (origin.date != date || !isCanonicalDate(date)) return LegacyRecoveryCopyResult.RECORD_NOT_RECOVERABLE
        val mutation = mutationState(owner, date)
        var outcome = LegacyRecoveryCopyResult.FAILED
        mutation.mutex.withLock {
            val targetKey = stringPreferencesKey(key(owner, date))
            dataStore.edit { prefs ->
                val sourceKey = stringPreferencesKey(legacyKey(date))
                val sourceValue = prefs.asMap().entries
                    .firstOrNull { it.key.name == sourceKey.name }
                    ?.value
                if (origin.type == LegacyRecoveryType.DRAFT && sourceValue != sourceRaw) {
                    outcome = LegacyRecoveryCopyResult.SOURCE_CHANGED
                    return@edit
                }
                if (origin.type == LegacyRecoveryType.PENDING_SAVE && !runCatching(sourceStillUnchanged).getOrDefault(false)) {
                    outcome = LegacyRecoveryCopyResult.SOURCE_CHANGED
                    return@edit
                }
                val existingPreference = prefs.asMap().entries
                    .firstOrNull { it.key.name == targetKey.name }
                    ?.value
                if (existingPreference != null) {
                    val existingRaw = existingPreference as? String
                    val existing = existingRaw?.let { runCatching { decode(owner, date, it) }.getOrNull() }
                    outcome = if (
                        existing != null &&
                        existing.legacyRecoveryOrigin == origin &&
                        sameUserEntry(existing.entry, entry)
                    ) {
                        LegacyRecoveryCopyResult.ALREADY_COPIED
                    } else {
                        LegacyRecoveryCopyResult.TARGET_CONFLICT
                    }
                    return@edit
                }
                prefs[targetKey] = encode(
                    owner = owner,
                    date = date,
                    entry = entry,
                    baselineUpdatedAt = null,
                    baselineExists = null,
                    baselineId = null,
                    baselineRevision = null,
                    pendingSaveRequestId = null,
                    pendingAiReflection = null,
                    legacyRecoveryOrigin = origin,
                )
                outcome = LegacyRecoveryCopyResult.COPIED
            }
        }
        return outcome
    }

    /**
     * Remove a draft-origin source in the same DataStore transaction that verifies
     * the copied target. The caller invokes this only after explicit user confirmation.
     */
    internal suspend fun confirmAndRemoveLegacyDraftSource(
        date: String,
        origin: LegacyRecoveryRecordId,
        sourceRaw: String,
        expectedEntry: DiaryEntry,
    ): LegacyRecoveryFinalizeResult {
        val owner = ownerId?.takeIf { it.isNotBlank() }
            ?: return LegacyRecoveryFinalizeResult.OWNER_UNAVAILABLE
        if (origin.type != LegacyRecoveryType.DRAFT || origin.date != date) {
            return LegacyRecoveryFinalizeResult.RECORD_NOT_RECOVERABLE
        }
        val mutation = mutationState(owner, date)
        var outcome = LegacyRecoveryFinalizeResult.FAILED
        mutation.mutex.withLock {
            val targetKey = stringPreferencesKey(key(owner, date))
            val sourceKey = stringPreferencesKey(legacyKey(date))
            dataStore.edit { prefs ->
                val currentRaw = prefs.asMap().entries
                    .firstOrNull { it.key.name == targetKey.name }
                    ?.value as? String
                val current = currentRaw?.let { runCatching { decode(owner, date, it) }.getOrNull() }
                if (current == null || current.legacyRecoveryOrigin != origin || !sameUserEntry(current.entry, expectedEntry)) {
                    outcome = LegacyRecoveryFinalizeResult.TARGET_CHANGED
                    return@edit
                }
                val sourceValue = prefs.asMap().entries
                    .firstOrNull { it.key.name == sourceKey.name }
                    ?.value
                if (sourceValue != sourceRaw) {
                    outcome = LegacyRecoveryFinalizeResult.SOURCE_CHANGED
                    return@edit
                }
                prefs.remove(sourceKey)
                outcome = LegacyRecoveryFinalizeResult.ORIGINAL_REMOVED
            }
        }
        return outcome
    }

    /**
     * For a pending-save origin, serialize source cleanup with normal draft writes
     * and remove the outbox record only after the target still matches this copy.
     */
    internal suspend fun confirmAndRemoveLegacyPendingSource(
        date: String,
        origin: LegacyRecoveryRecordId,
        expectedEntry: DiaryEntry,
        removeSourceIfUnchanged: () -> Boolean,
    ): LegacyRecoveryFinalizeResult {
        val owner = ownerId?.takeIf { it.isNotBlank() }
            ?: return LegacyRecoveryFinalizeResult.OWNER_UNAVAILABLE
        if (origin.type != LegacyRecoveryType.PENDING_SAVE || origin.date != date) {
            return LegacyRecoveryFinalizeResult.RECORD_NOT_RECOVERABLE
        }
        val mutation = mutationState(owner, date)
        return mutation.mutex.withLock {
            val raw = try {
                dataStore.data.first()[stringPreferencesKey(key(owner, date))]
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock LegacyRecoveryFinalizeResult.FAILED
            } ?: return@withLock LegacyRecoveryFinalizeResult.TARGET_CHANGED
            val current = runCatching { decode(owner, date, raw) }.getOrNull()
                ?: return@withLock LegacyRecoveryFinalizeResult.TARGET_CHANGED
            if (current.legacyRecoveryOrigin != origin || !sameUserEntry(current.entry, expectedEntry)) {
                return@withLock LegacyRecoveryFinalizeResult.TARGET_CHANGED
            }
            if (!runCatching(removeSourceIfUnchanged).getOrDefault(false)) {
                return@withLock LegacyRecoveryFinalizeResult.SOURCE_CHANGED
            }
            LegacyRecoveryFinalizeResult.ORIGINAL_REMOVED
        }
    }

    /** Convenience for callers that do not need conflict metadata. */
    suspend fun load(date: String): DiaryEntry? = loadRecord(date)?.entry

    suspend fun clear(date: String) {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return
        val mutation = mutationState(owner, date)
        mutation.mutex.withLock {
            val key = stringPreferencesKey(key(owner, date))
            dataStore.edit { prefs -> prefs.remove(key) }
        }
    }

    /** A terminal CAS conflict is not retryable: keep its draft/token, release only its request id. */
    suspend fun clearRequestForConflict(
        date: String,
        conflictedEntry: DiaryEntry,
        conflictedRequestId: String,
    ): Boolean {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return false
        val mutation = mutationState(owner, date)
        return mutation.mutex.withLock {
            val key = stringPreferencesKey(key(owner, date))
            var cleared = false
            dataStore.edit { prefs ->
                val current = prefs[key]?.let { decode(owner, date, it) }
                if (current != null && sameUserEntry(current.entry, conflictedEntry) &&
                    current.pendingSaveRequestId == conflictedRequestId
                ) {
                    prefs[key] = encode(
                        owner,
                        current.entry,
                        current.baselineUpdatedAt,
                        current.baselineExists,
                        current.baselineId,
                        current.baselineRevision,
                        null,
                        current.pendingAiReflection,
                        current.legacyRecoveryOrigin,
                    )
                    cleared = true
                }
            }
            cleared
        }
    }

    /** Clear only the draft snapshot a completed save actually persisted. */
    suspend fun clearIfUnchanged(
        date: String,
        completed: DiaryEntry,
        completedRequestId: String? = null,
    ): Boolean {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return false
        val mutation = mutationState(owner, date)
        return mutation.mutex.withLock {
            val key = stringPreferencesKey(key(owner, date))
            var cleared = false
            dataStore.edit { prefs ->
                val current = prefs[key]?.let { decode(owner, date, it) }
                if (current != null && sameUserEntry(current.entry, completed) &&
                    (completedRequestId == null || current.pendingSaveRequestId == completedRequestId)
                ) {
                    prefs.remove(key)
                    cleared = true
                }
            }
            cleared
        }
    }

    private suspend fun writeDraft(
        owner: String,
        date: String,
        entry: DiaryEntry,
        baselineUpdatedAt: String?,
        baselineExists: Boolean?,
        baselineId: String?,
        baselineRevision: Long?,
        pendingSaveRequestId: String?,
        pendingAiReflection: String?,
    ) {
        val key = stringPreferencesKey(key(owner, date))
        dataStore.edit { prefs ->
            val prior = prefs[key]?.let { decode(owner, date, it) }
            // Once a draft exists, its original comparison baseline is immutable.
            // Null is meaningful: it says the previous server revision was unknown.
            val baseline = if (prior != null) prior.baselineUpdatedAt else baselineUpdatedAt
            val existed = if (prior != null) prior.baselineExists else baselineExists
            val baselineRowId = if (prior != null) prior.baselineId else baselineId
            val baselineRowRevision = if (prior != null) prior.baselineRevision else baselineRevision
            val sameEntry = prior != null && sameUserEntry(prior.entry, entry)
            val requestId = when {
                pendingSaveRequestId != null -> pendingSaveRequestId
                sameEntry -> prior?.pendingSaveRequestId
                else -> null
            }
            val reflection = pendingAiReflection ?: prior?.pendingAiReflection?.takeIf { sameEntry }
            prefs[key] = encode(
                owner,
                entry,
                baseline,
                existed,
                baselineRowId,
                baselineRowRevision,
                requestId,
                reflection,
                prior?.legacyRecoveryOrigin,
            )
        }
    }

    /** AI reflections are server-owned except while explicitly awaiting a CAS write. */
    private fun sameUserEntry(left: DiaryEntry, right: DiaryEntry): Boolean =
        left.copy(aiReflection = null) == right.copy(aiReflection = null)

    private fun encode(
        owner: String,
        entry: DiaryEntry,
        baselineUpdatedAt: String?,
        baselineExists: Boolean?,
        baselineId: String?,
        baselineRevision: Long?,
        pendingSaveRequestId: String?,
        pendingAiReflection: String?,
        legacyRecoveryOrigin: LegacyRecoveryRecordId? = null,
    ): String = JSONObject()
        .put("ownerId", owner)
        .put("entry", entry.toJson())
        .apply {
            baselineUpdatedAt?.let { put("baselineUpdatedAt", it) }
            baselineExists?.let { put("baselineExists", it) }
            baselineId?.let { put("baselineId", it) }
            baselineRevision?.let { put("baselineRevision", it) }
            pendingSaveRequestId?.let { put("pendingSaveRequestId", it) }
            pendingAiReflection?.let { put("pendingAiReflection", it) }
            legacyRecoveryOrigin?.let { origin ->
                put(
                    "legacyRecoverySource",
                    JSONObject().put("type", origin.type.name).put("date", origin.date),
                )
            }
        }
        .toString()

    private fun decode(owner: String, date: String, raw: String): DraftRecord {
        return try {
            val json = JSONObject(raw)
            if (json.optString("ownerId") != owner) {
                throw IllegalArgumentException("Draft owner does not match its storage key.")
            }
            val entry = json.optJSONObject("entry")?.let { DiaryEntry.fromJson(it.toString()) }
                ?: throw IllegalArgumentException("Draft entry is missing.")
            val pendingAiReflection = if (json.has("pendingAiReflection") && !json.isNull("pendingAiReflection")) {
                json.optString("pendingAiReflection")
            } else {
                null
            }
            DraftRecord(
                ownerId = owner,
                date = date,
                entry = pendingAiReflection?.let { entry.copy(aiReflection = it) } ?: entry,
                baselineUpdatedAt = json.optString("baselineUpdatedAt").takeIf { it.isNotBlank() },
                baselineExists = if (json.isNull("baselineExists")) null else json.optBoolean("baselineExists"),
                baselineId = json.optString("baselineId").takeIf { it.isNotBlank() },
                baselineRevision = if (json.has("baselineRevision") && !json.isNull("baselineRevision")) {
                    json.optLong("baselineRevision")
                } else {
                    null
                },
                pendingSaveRequestId = json.optString("pendingSaveRequestId").takeIf { it.isNotBlank() },
                pendingAiReflection = pendingAiReflection,
                legacyRecoveryOrigin = decodeLegacyRecoveryOrigin(json.optJSONObject("legacyRecoverySource")),
            )
        } catch (failure: DraftStoreReadException) {
            throw failure
        } catch (failure: Exception) {
            throw DraftStoreReadException("The local draft for $date is corrupted.", failure)
        }
    }

    companion object {
        /** Shared fence across screen/store instances for the same DataStore and owner/date. */
        private val mutationStates = WeakHashMap<DataStore<Preferences>, MutableMap<String, MutationState>>()

        /** V2 namespace includes the account; ownerless legacy key is never read. */
        fun key(ownerId: String, date: String): String = "diarium_draft_v2_${ownerId}_$date"

        /** Key used by builds before account scoping; retained only for quarantine diagnostics. */
        fun legacyKey(date: String): String = "diarium_draft_$date"

        fun from(context: Context, ownerId: String?): DraftStore =
            DraftStore(context.draftDataStore, ownerId?.takeIf { it.isNotBlank() })
    }
}

private fun isCanonicalDate(value: String): Boolean =
    runCatching { java.time.LocalDate.parse(value).toString() == value }.getOrDefault(false)

private fun decodeLegacyRecoveryOrigin(json: JSONObject?): LegacyRecoveryRecordId? {
    if (json == null) return null
    val type = runCatching { LegacyRecoveryType.valueOf(json.optString("type")) }.getOrNull() ?: return null
    val date = json.optString("date")
    if (!isCanonicalDate(date)) return null
    return LegacyRecoveryRecordId(type, date)
}

private val Context.draftDataStore by preferencesDataStore(name = "diarium_drafts")
