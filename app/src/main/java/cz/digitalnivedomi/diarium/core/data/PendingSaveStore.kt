package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
)

sealed interface PendingSaveFlush {
    data object SessionChanged : PendingSaveFlush
    data object Changed : PendingSaveFlush
    data object Retry : PendingSaveFlush
    /** No verified atomic compare-and-swap exists; local data needs explicit review. */
    data class RecoveryRequired(val record: PendingSave) : PendingSaveFlush
    data class Saved(val record: PendingSave) : PendingSaveFlush
}

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

    /**
     * The server exposes only a blind full-row save RPC; no revision comparison is
     * atomic with that write. Automatic replay would therefore be able to overwrite
     * another device's newer row. Keep this compatibility seam fail-closed until a
     * verifiable CAS RPC is added: it never calls [dispatch], removes a record, or
     * runs cleanup. Explicit UI recovery owns the queued copy.
     */
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
        PendingSaveFlush.RecoveryRequired(snapshot)
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

    /** The queued entry for [date], or null when no valid save belongs to this owner. */
    fun peek(date: String): DiaryEntry? = read(date)?.entry

    /** Exact persisted transaction snapshot, used for compare-and-remove after a flush. */
    fun peekRecord(date: String): PendingSave? = read(date)

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

    /** True when this owner has at least one flushable save. */
    fun hasAny(): Boolean = peekAll().isNotEmpty()

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
            val blob = JSONObject(raw)
            val owner = blob.optString("ownerId").takeIf { it.isNotBlank() } ?: return null
            if (owner != expectedOwner) return null
            val entry = blob.optJSONObject("entry")?.let { DiaryEntry.fromJson(it.toString()) } ?: return null
            val transactionId = blob.optString("transactionId").takeIf { it.isNotBlank() } ?: return null
            PendingSave(
                ownerId = owner,
                date = date,
                entry = entry,
                queuedAt = blob.optLong("queuedAt"),
                transactionId = transactionId,
                baselineUpdatedAt = blob.optString("baselineUpdatedAt").takeIf { it.isNotBlank() },
                baselineExists = if (blob.isNull("baselineExists")) null else blob.optBoolean("baselineExists"),
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Stable preferences file; legacy date-only entries are quarantined in place. */
        const val PREFS_NAME = "diarium_pending_saves"
        private const val RECORD_VERSION_PREFIX = "v2/"
        private val LEGACY_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val OUTBOX_LOCK = Any()
        private val DATE_LOCKS = ConcurrentHashMap<String, Mutex>()

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
