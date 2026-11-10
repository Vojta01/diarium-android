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
) {
    fun conflictsWith(remoteExists: Boolean, remoteUpdatedAt: String?): Boolean {
        val hadRemoteEntry = baselineExists ?: return true
        if (hadRemoteEntry != remoteExists) return true
        if (!hadRemoteEntry) return false
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
    ) {
        stage(date, entry, baselineUpdatedAt, baselineExists).await()
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
    ): Deferred<Unit> {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return CompletableDeferred(Unit)
        val mutation = mutationState(owner, date)
        // Start undispatched so lock acquisition is queued in call order. A clear
        // requested later must not overtake a newer stage that is already queued.
        return appScope.async(start = CoroutineStart.UNDISPATCHED) {
            mutation.mutex.withLock {
                writeDraft(owner, date, entry, baselineUpdatedAt, baselineExists)
            }
        }
    }

    /** A lifecycle flush is bound to the same owner as the screen/store instance. */
    fun flush(
        date: String,
        entry: DiaryEntry,
        baselineUpdatedAt: String? = null,
        baselineExists: Boolean? = null,
    ): Deferred<Unit> = stage(date, entry, baselineUpdatedAt, baselineExists)

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

    /** Clear only the draft snapshot a completed save actually persisted. */
    suspend fun clearIfUnchanged(date: String, completed: DiaryEntry): Boolean {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return false
        val mutation = mutationState(owner, date)
        return mutation.mutex.withLock {
            val key = stringPreferencesKey(key(owner, date))
            var cleared = false
            dataStore.edit { prefs ->
                val current = prefs[key]?.let { decode(owner, date, it) }
                if (current?.entry == completed) {
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
    ) {
        val key = stringPreferencesKey(key(owner, date))
        dataStore.edit { prefs ->
            val prior = prefs[key]?.let { decode(owner, date, it) }
            // Once a draft exists, its original comparison baseline is immutable.
            // Null is meaningful: it says the previous server revision was unknown.
            val baseline = if (prior != null) prior.baselineUpdatedAt else baselineUpdatedAt
            val existed = if (prior != null) prior.baselineExists else baselineExists
            prefs[key] = encode(owner, entry, baseline, existed)
        }
    }

    private fun encode(
        owner: String,
        entry: DiaryEntry,
        baselineUpdatedAt: String?,
        baselineExists: Boolean?,
    ): String = JSONObject()
        .put("ownerId", owner)
        .put("entry", entry.toJson())
        .apply {
            baselineUpdatedAt?.let { put("baselineUpdatedAt", it) }
            baselineExists?.let { put("baselineExists", it) }
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
            DraftRecord(
                ownerId = owner,
                date = date,
                entry = entry,
                baselineUpdatedAt = json.optString("baselineUpdatedAt").takeIf { it.isNotBlank() },
                baselineExists = if (json.isNull("baselineExists")) null else json.optBoolean("baselineExists"),
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

private val Context.draftDataStore by preferencesDataStore(name = "diarium_drafts")
