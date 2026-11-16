package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Base64
import java.lang.ref.WeakReference

/**
 * File-backed snapshot of the `entries` rows the dashboard last saw, so a cold
 * start can render its numbers instantly instead of waiting for
 * [EntriesRepository.loadRange] to come back from the network.
 *
 * Why a plain JSON file rather than Room or another DataStore: the snapshot is
 * disposable — a file that cannot be read simply means "no cache" and the
 * dashboard falls back to the network — so it needs no schema, no migration
 * and no dependency beyond the `org.json` the data layer already parses rows
 * with. Rows are stored exactly as PostgREST returned them and read back
 * through [EntriesRepository.fromRow], because that is the one row → model
 * mapping; a cache-only mapping would drift from it the first time a column
 * changes.
 *
 * On-disk format (compact `toString()`):
 * ownerless test fixtures use `{"version":1,"rows":{...}}`; app caches use
 * `{"version":2,"ownerId":"…","rows":{...}}` in a per-owner file.
 *
 * Reads and writes go through [mutex] and `Dispatchers.IO`, so two coroutines
 * can never interleave on the file, and a write never leaves a half-written
 * snapshot: [persist] writes a temp sibling first and swaps it in with a
 * rename. A conservative shared in-memory generation token lets a range GET update
 * only the cache version observed before dispatch; if any local mutation wins
 * while it is in flight, the GET is discarded. This coordination is process-local;
 * multiple Android processes would need an OS file lock or transactional store.
 * The cache is not evidence that the server range was complete, so remotely
 * deleted rows may remain offline.
 */
class EntriesCacheStore(
    private val file: File,
    val ownerId: String? = null,
    private val replaceFile: (File, File) -> Boolean = { temp, target -> temp.renameTo(target) },
) {

    /** All stores for the same canonical owner/file share a lock and CAS version. */
    private val shared = sharedState(file, ownerId)
    private val mutex get() = shared.mutex

    /** Capture before dispatching a range GET. */
    suspend fun generationSnapshot(): Long = mutex.withLock { shared.generation }

    /** Merge fetched rows only if no cache mutation has happened since the GET began. */
    suspend fun upsertRowsIfGeneration(rows: List<JSONObject>, expectedGeneration: Long): Boolean =
        mutex.withLock {
            if (shared.generation != expectedGeneration) return@withLock false
            withContext(Dispatchers.IO) {
                val stored = readRowsOrNull() ?: JSONObject()
                var changed = false
                rows.forEach { row ->
                    val date = row.plainString("date").takeIf { it.isNotBlank() } ?: return@forEach
                    val rowOwner = row.plainString("user_id")
                    if (ownerId != null && rowOwner != ownerId) {
                        throw IOException("Server row does not match the cache owner.")
                    }
                    val merged = stored.optJSONObject(date) ?: JSONObject()
                    row.keys().forEach { key -> merged.put(key, row.get(key)) }
                    stored.put(date, merged)
                    changed = true
                }
                if (changed) {
                    persist(stored)
                    shared.generation++
                }
                true
            }
        }

    /**
     * Returns the cached rows whose `date` lies in `[from, to]` (inclusive),
     * newest first — the same order [EntriesRepository.loadRange] produces, so
     * the dashboard renders the same list whether it came from here or from
     * the server.
     *
     * `null` means "no usable cache": the file is absent (first run), was
     * truncated by a kill, or is not the shape this store writes. Degrading to
     * `null` instead of throwing matters — the caller answers `null` by
     * keeping its loading state, while an exception would crash a screen that
     * is merely waiting for data.
     */
    suspend fun readRange(from: String, to: String): List<DatedEntry>? = mutex.withLock {
        withContext(Dispatchers.IO) { readRangeLocked(from, to) }
    }

    /**
     * Merges [rows] into the snapshot, keyed by each row's non-blank `date`;
     * a row without one has no key to live under and is skipped.
     *
     * The merge is a per-date overlay: the stored row object is the base and
     * the incoming row's keys are copied over it. That is what makes a partial
     * save safe — a check-in save never carries `phone_screen_time` or
     * `ai_reflection`, so a straight replace would wipe the columns the
     * workers own, while the overlay updates exactly the keys the save
     * touched and keeps the rest.
     *
     * The new snapshot replaces the file atomically (see [persist]), so a
     * process killed mid-write leaves the previous snapshot intact instead of
     * a half-written file that would read as no cache at all.
     */
    suspend fun upsertRows(rows: List<JSONObject>) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                // An unreadable file (first run, corruption) is not an error
                // here: the snapshot is rebuilt from this upsert instead.
                val stored = readRowsOrNull() ?: JSONObject()
                var changed = false
                rows.forEach { row ->
                    val date = row.plainString("date").takeIf { it.isNotBlank() } ?: return@forEach
                    val merged = stored.optJSONObject(date) ?: JSONObject()
                    row.keys().forEach { key -> merged.put(key, row.get(key)) }
                    stored.put(date, merged)
                    changed = true
                }
                if (changed) {
                    persist(stored)
                    shared.generation++
                }
            }
        }
    }

    /**
     * Atomically mirrors a confirmed server-side date move in this disposable
     * cache. The moved server row replaces any target row and the source key is
     * removed in the same file replacement; the move outbox is retained by the
     * repository until this write succeeds.
     */
    suspend fun moveDate(from: String, to: String, movedRow: JSONObject) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                require(movedRow.plainString("date") == to) { "Moved cache row has the wrong date." }
                if (ownerId != null && movedRow.plainString("user_id") != ownerId) {
                    throw IOException("Moved cache row belongs to another owner.")
                }
                val stored = if (!file.exists()) {
                    JSONObject()
                } else {
                    readRowsOrNull() ?: throw IOException("Existing entries cache could not be safely updated.")
                }
                stored.remove(from)
                stored.put(to, JSONObject(movedRow.toString()))
                persist(stored)
                shared.generation++
            }
        }
    }

    /**
     * Drops the snapshot row for [date] — the cache half of a server-side
     * delete, so the next cold start cannot resurrect a day the user moved or
     * removed. Removing a date that is not cached (or having no cache at all)
     * is a no-op.
     */
    suspend fun removeDate(date: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val stored = readRowsOrNull() ?: return@withContext
                if (stored.has(date)) {
                    stored.remove(date)
                    persist(stored)
                    shared.generation++
                }
            }
        }
    }

    /**
     * Parses the stored `rows` object, or `null` for every flavour of "no
     * usable cache": missing file, malformed JSON, or a shape this store did
     * not write (no `rows` object, or a `version` that is not
     * [FORMAT_VERSION]). Callers treat `null` as "nothing cached".
     */
    private fun readRowsOrNull(): JSONObject? = try {
        if (!file.exists()) {
            null
        } else {
            val root = JSONObject(file.readText())
            val rows = root.optJSONObject("rows")
            val version = if (ownerId == null) LEGACY_FORMAT_VERSION else OWNER_FORMAT_VERSION
            val ownerMatches = ownerId == null || root.optString("ownerId") == ownerId
            if (root.optInt("version", -1) == version && ownerMatches) rows else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The real read behind [readRange]; a stored value that is not an object
     * (a hand-edited file, a future shape) degrades to `null` like any other
     * unreadable cache.
     */
    private fun readRangeLocked(from: String, to: String): List<DatedEntry>? = try {
        readRowsOrNull()?.let { rows ->
            rows.keys().asSequence()
                .filter { date -> date >= from && date <= to }
                .map { date -> DatedEntry(date, EntriesRepository.fromRow(rows.getJSONObject(date))) }
                .sortedByDescending { it.date }
                .toList()
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Replaces the file with a complete snapshot: the payload is written to a
     * temp sibling first and renamed onto the target, so a kill at any point
     * leaves either the old snapshot or the new one — never a half-written
     * file. A failed rename is an error: copying over an existing target would
     * truncate the last known-good snapshot and is not an atomic fallback.
     */
    private fun persist(rows: JSONObject) {
        val directory = file.parentFile ?: throw IOException("Entries cache has no parent directory.")
        if (!directory.exists()) directory.mkdirs()
        val root = JSONObject()
            .put("version", if (ownerId == null) LEGACY_FORMAT_VERSION else OWNER_FORMAT_VERSION)
        ownerId?.let { root.put("ownerId", it) }
        root.put("rows", rows)
        val payload = root.toString()
        val temp = File.createTempFile(TEMP_FILE_PREFIX, ".tmp", directory)
        try {
            temp.writeText(payload)
            if (!replaceFile(temp, file)) throw IOException("Could not atomically replace entries cache.")
        } finally {
            // After a successful rename the temp file is already gone; on the
            // failure paths this removes the leftover.
            temp.delete()
        }
    }

    companion object {
        /** Legacy unscoped cache filename; retained but never read by the app factory. */
        const val FILE_NAME = "entries_cache.json"

        private const val LEGACY_FORMAT_VERSION = 1
        private const val OWNER_FORMAT_VERSION = 2
        private const val TEMP_FILE_PREFIX = "entries_cache_"

        private class SharedState {
            val mutex = Mutex()
            var generation: Long = 0
        }

        private data class StateKey(val ownerId: String?, val canonicalPath: String)
        private val states = mutableMapOf<StateKey, WeakReference<SharedState>>()

        /** Weak registry: live stores retain state; dead owners/files can be collected. */
        private fun sharedState(file: File, ownerId: String?): SharedState = synchronized(states) {
            val canonicalPath = runCatching { file.canonicalPath }
                .getOrElse { file.absoluteFile.normalize().path }
            val key = StateKey(ownerId, canonicalPath)
            states.entries.removeAll { it.value.get() == null }
            states[key]?.get() ?: SharedState().also { states[key] = WeakReference(it) }
        }

        /** Stable per-owner file name; base64url keeps arbitrary IDs out of path syntax. */
        fun fileName(ownerId: String): String =
            "entries_cache_v2_${Base64.getUrlEncoder().withoutPadding().encodeToString(ownerId.toByteArray(Charsets.UTF_8))}.json"

        /** The app never reads the old ownerless snapshot. */
        fun from(context: Context, ownerId: String): EntriesCacheStore =
            EntriesCacheStore(File(context.filesDir, fileName(ownerId)), ownerId)

    }
}
