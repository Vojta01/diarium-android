package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * One check-in waiting in the outbox: the day it belongs to, the entry exactly
 * as the user last saved it locally, and when it was queued.
 *
 * [queuedAt] is the wall-clock time of the local save that produced this blob
 * (the latest one — [PendingSaveStore.enqueue] overwrites) and is kept so a
 * flush can be logged/diagnosed with the age of the pending save.
 */
data class PendingSave(val date: String, val entry: DiaryEntry, val queuedAt: Long)

/**
 * Offline-first outbox for check-ins: when a save cannot reach the server
 * (no network, RPC failure), the entry is enqueued here instead of being lost,
 * and a sync worker flushes the outbox later.
 *
 * Backed by SharedPreferences on purpose: the outbox is a handful of small,
 * flat records written on the save path and read back wholesale on cold start
 * or network regain, so the cheap synchronous API fits better than another
 * DataStore file — and it shares nothing with the drafts or the auth store.
 *
 * Semantics:
 *  - one key per date (the date string itself); the value is compact JSON:
 *    `{"queuedAt": <long>, "entry": <DiaryEntry.toJson() object>}`,
 *  - enqueue overwrites: the newest local save for a date wins, because that
 *    is what the user last saw on the form — an older queued blob must never
 *    resurrect over it,
 *  - reads are lenient: a corrupt value decodes to null ([peek]) or is skipped
 *    ([peekAll]) instead of throwing — a bad byte in prefs must never crash
 *    the save path that is already having a bad day.
 */
class PendingSaveStore private constructor(private val prefs: SharedPreferences) {

    /**
     * Queues [entry] for [date], replacing any pending save for the same date.
     * Newest wins — see the class docs.
     */
    fun enqueue(date: String, entry: DiaryEntry, queuedAt: Long = System.currentTimeMillis()) {
        val blob = JSONObject()
            .put("queuedAt", queuedAt)
            .put("entry", entry.toJson())
        prefs.edit().putString(date, blob.toString()).apply()
    }

    /** The queued entry for [date], or null when nothing (valid) is queued. */
    fun peek(date: String): DiaryEntry? = read(date)?.entry

    /** Every flushable save, oldest date first so the worker sends days in order. */
    fun peekAll(): List<PendingSave> =
        prefs.all.keys.mapNotNull { date -> read(date) }.sortedBy { it.date }

    /** Drops the queued save for [date] — call it after the worker confirmed the send. */
    fun remove(date: String) {
        prefs.edit().remove(date).apply()
    }

    /** True when the outbox holds at least one flushable save. */
    fun hasAny(): Boolean = peekAll().isNotEmpty()

    /**
     * Number of flushable saves. Corrupt values are skipped, exactly like
     * [peekAll], so [hasAny]/[size] never report work that [peekAll] cannot
     * deliver.
     */
    fun size(): Int = peekAll().size

    /**
     * Reads and decodes one stored blob; null means the value is missing,
     * foreign or corrupt.
     */
    private fun read(date: String): PendingSave? {
        val raw = try {
            prefs.getString(date, null)
        } catch (_: Exception) {
            // A non-string value can only come from outside this store —
            // treat it as corrupt rather than crashing the reader.
            null
        }
        if (raw == null) return null
        return decode(date, raw)
    }

    private fun decode(date: String, raw: String): PendingSave? = try {
        val blob = JSONObject(raw)
        val entry = blob.optJSONObject("entry")?.let { DiaryEntry.fromJson(it.toString()) }
        entry?.let { PendingSave(date, it, blob.optLong("queuedAt")) }
    } catch (_: Exception) {
        // Malformed JSON, wrong shapes, bad numbers — all "nothing usable here".
        null
    }

    companion object {
        /** Own prefs file on purpose: the worker clears it, the editor clears drafts. */
        const val PREFS_NAME = "diarium_pending_saves"

        /**
         * Application context on purpose — the outbox is process-wide and must
         * not keep a screen/Activity alive.
         */
        fun from(context: Context): PendingSaveStore =
            PendingSaveStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
