package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

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
 * `{"version":1,"rows":{"<date>":{...row...}, ...}}`.
 *
 * Reads and writes go through [mutex] and `Dispatchers.IO`, so two coroutines
 * can never interleave on the file, and a write never leaves a half-written
 * snapshot: [persist] writes a temp sibling first and swaps it in with a
 * rename.
 */
class EntriesCacheStore(private val file: File) {

    /** Serialises this store's file access; see the class KDoc. */
    private val mutex = Mutex()

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
                if (changed) persist(stored)
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
            if (root.optInt("version", -1) == FORMAT_VERSION) rows else null
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
     * file. A failed rename (which should not happen: the temp file lives in
     * the target's own directory) falls back to a plain copy rather than
     * dropping the snapshot.
     */
    private fun persist(rows: JSONObject) {
        val directory = file.parentFile ?: return
        if (!directory.exists()) directory.mkdirs()
        val payload = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("rows", rows)
            .toString()
        val temp = File.createTempFile(TEMP_FILE_PREFIX, ".tmp", directory)
        try {
            temp.writeText(payload)
            if (!temp.renameTo(file)) temp.copyTo(file, overwrite = true)
        } finally {
            // After a successful rename the temp file is already gone; on the
            // failure paths this removes the leftover.
            temp.delete()
        }
    }

    companion object {
        /** The snapshot file inside `context.filesDir`; see [from]. */
        const val FILE_NAME = "entries_cache.json"

        /** Current on-disk format; any other version reads as "no cache". */
        private const val FORMAT_VERSION = 1

        /** Prefix for the temp sibling [persist] writes before the rename. */
        private const val TEMP_FILE_PREFIX = "entries_cache_"

        /**
         * The store the app uses: one snapshot file in the app-private files
         * dir. Application context on purpose — the cache is process-wide and
         * must not keep a screen alive.
         */
        fun from(context: Context): EntriesCacheStore =
            EntriesCacheStore(File(context.filesDir, FILE_NAME))
    }
}
