package cz.digitalnivedomi.diarium.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Reads and writes the `entries` table through the server-side RPCs — the same
 * contract the web app's `/api/save-entry` uses.
 *
 * Why the RPC rather than a plain PostgREST upsert: `save_daily_entry(jsonb)`
 * owns the conflict target (`user_id, date`), the `scale_entries` mirroring and
 * the "only overwrite a column when the payload actually carries it" rule.
 * Re-implementing that in Kotlin would drift from the web the first time the SQL
 * changes.
 */
class EntriesRepository(
    private val client: SupabaseClient,
    private val session: SessionContext = SessionContext(),
    /**
     * Local snapshot of the range reads (file-backed, see [EntriesCacheStore]).
     * Optional on purpose: with null — as in every test that builds the
     * repository by hand — reads and writes behave exactly as before, and a
     * cache that cannot be written degrades to „no cache", never an error.
     */
    private val cache: EntriesCacheStore? = null,
    /**
     * Offline outbox (see [PendingSaveStore]): a save that never reached the
     * server lands here and `PendingSaveWorker` flushes it later. Null keeps
     * the old behaviour — a transport failure on save is reported straight to
     * the caller. When wired, the store also overlays every read, because a
     * queued day is the newest state the user has seen.
     */
    private val pending: PendingSaveStore? = null,
) {

    suspend fun currentUserId(): String? = session.userId()

    /**
     * Loads the stored entry for [date], or null when that day has none yet.
     *
     * A read that fails (offline phone, HTTP error, a body the client cannot parse)
     * also yields null, because the check-in screen answers null by falling back to
     * the draft and an empty form. Throwing here would crash that screen instead.
     */
    suspend fun loadEntry(date: String): DiaryEntry? = withContext(Dispatchers.IO) {
        // A queued offline save is the newest state of that day: it beats the
        // server's row (older, or not there yet) and survives a failed read.
        pending?.peek(date)?.let { return@withContext it }
        try {
            readEntry(date)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** The real read for [loadEntry]; throws on any transport, HTTP or parse failure. */
    private suspend fun readEntry(date: String): DiaryEntry? {
        val userId = session.userId() ?: return null
        val resp = client.get(
            "entries",
            mapOf(
                "user_id" to "eq.$userId",
                "date" to "eq.$date",
                "select" to "*",
                "limit" to "1",
            ),
        )
        if (!resp.isSuccessful) return null
        val rows = resp.asJsonArray() ?: return null
        if (rows.length() == 0) return null
        return fromRow(rows.getJSONObject(0))
    }

    /**
     * Loads every stored entry in `[from, to]` (inclusive ISO dates) in a single
     * round-trip — the dashboard derives all of its numbers from this one call.
     *
     * The date bound is expressed with PostgREST's `and=(...)` operator because a
     * Kotlin `Map` cannot carry two values for the same `date` column, and both
     * bounds matter: the lower one is the dashboard's window, the upper one keeps
     * anything future-dated out. [limit] is only a safety cap — a 30-day window is
     * far below it — and the order matches the web's `fetchDailyEntries()`.
     *
     * Unlike [loadEntry] this reports failures instead of returning an empty
     * result: the dashboard must be able to tell "nothing saved yet" from "the
     * read failed", otherwise a dead request renders as a screen full of zeroes.
     */
    suspend fun loadRange(
        from: String,
        to: String,
        limit: Int = RANGE_LIMIT,
    ): Result<List<DatedEntry>> = withContext(Dispatchers.IO) {
        try {
            Result.success(withPendingOverlay(readRange(from, to, limit), from, to))
        } catch (e: CancellationException) {
            // Cancellation is the caller walking away, not a failure to report.
            throw e
        } catch (e: IllegalStateException) {
            // Already a sentence written for the screen (signed out, HTTP error, bad body).
            Result.failure(e)
        } catch (_: IOException) {
            // A phone that lost signal is a normal state: it has to reach the screen as
            // a retryable failure in Czech, never as an exception that kills the coroutine.
            Result.failure(IllegalStateException(CONNECT_FAILED))
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("Načtení přehledu se nezdařilo (${e.javaClass.simpleName})."),
            )
        }
    }

    /**
     * The real read behind [loadRange]: throws on a transport failure, an HTTP error
     * or a body the client cannot parse, so [loadRange] has a single place that turns
     * each of them into the [Result] the dashboard renders.
     */
    private suspend fun readRange(from: String, to: String, limit: Int): List<DatedEntry> {
        val userId = session.userId() ?: throw IllegalStateException(SIGNED_OUT)
        val resp = client.get(
            "entries",
            mapOf(
                "user_id" to "eq.$userId",
                "and" to "(date.gte.$from,date.lte.$to)",
                "select" to "*",
                "order" to "date.desc",
                "limit" to limit.toString(),
            ),
        )
        if (!resp.isSuccessful) {
            throw IllegalStateException(resp.errorMessage ?: "Načtení přehledu se nezdařilo (${resp.code})")
        }
        val rows = resp.asJsonArray() ?: throw IllegalStateException("Server vrátil neplatnou odpověď.")
        val raw = (0 until rows.length()).map { index -> rows.getJSONObject(index) }
        // Feed the local snapshot — best effort: a cache that cannot be written
        // must never fail a read that succeeded.
        runCatching { cache?.upsertRows(raw) }
        return raw.map { row -> DatedEntry(row.plainString("date"), fromRow(row)) }
    }

    /**
     * The cached snapshot of `[from, to]`, or null when nothing was ever cached.
     * Pending offline saves are overlaid exactly like on a live read, so the
     * instant render shows the same day the fresh read is about to.
     */
    suspend fun loadRangeCachedFirst(from: String, to: String): List<DatedEntry>? {
        val rows = cache?.readRange(from, to) ?: return null
        return withPendingOverlay(rows, from, to)
    }

    /**
     * Best-effort read of specific days — the memory lane asks for the dates a
     * year, half a year and a month back. Any failure (offline phone, HTTP
     * error, a bad body) yields an empty map: the caller treats it as „no
     * memory today" and the card simply stays off.
     */
    suspend fun loadDates(dates: List<String>): Map<String, DiaryEntry> =
        withContext(Dispatchers.IO) {
            if (dates.isEmpty()) return@withContext emptyMap()
            try {
                val userId = session.userId() ?: return@withContext emptyMap()
                val resp = client.get(
                    "entries",
                    mapOf(
                        "user_id" to "eq.$userId",
                        "date" to "in.(${dates.joinToString(",")})",
                        "select" to "*",
                    ),
                )
                val rows = resp.asJsonArray() ?: return@withContext emptyMap()
                val raw = (0 until rows.length()).map { index -> rows.getJSONObject(index) }
                runCatching { cache?.upsertRows(raw) }
                raw.associate { row -> row.plainString("date") to fromRow(row) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyMap()
            }
        }

    /** A queued offline save replaces the server's row for the same date. */
    private fun withPendingOverlay(rows: List<DatedEntry>, from: String, to: String): List<DatedEntry> {
        val store = pending ?: return rows
        val queued = store.peekAll().filter { it.date in from..to }
        if (queued.isEmpty()) return rows
        val byDate = rows.associateBy { it.date }.toMutableMap()
        for (save in queued) byDate[save.date] = DatedEntry(save.date, save.entry)
        return byDate.values.sortedByDescending { it.date }
    }

    /**
     * Upserts the day's check-in via `save_daily_entry`, then fires
     * `check_achievements`. Re-saving the same date updates in place, because the
     * RPC's conflict target is `(user_id, date)`.
     */
    suspend fun save(entry: DiaryEntry, date: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val userId = session.userId()
                ?: return@withContext Result.failure(IllegalStateException("Přihlášení vypršelo, přihlas se znovu."))
            val payload = buildPayload(entry, userId, date)
            val resp = try {
                client.post("rpc/save_daily_entry", JSONObject().put("p_payload", payload))
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // The phone lost the network. With an outbox wired the day is kept
                // locally and flushed by `PendingSaveWorker`; the caller shows the
                // „Uloženo v telefonu…" card for [QueuedOfflineException]. Without
                // an outbox the failure reaches the screen as the same retryable
                // sentence a failed range read uses — never as a thrown exception
                // that kills the coroutine.
                val outbox = pending
                if (outbox == null) {
                    return@withContext Result.failure(IllegalStateException(CONNECT_FAILED))
                }
                runCatching {
                    outbox.enqueue(date, entry)
                    // The payload doubles as the snapshot row, so the dashboard
                    // cache shows the new numbers before the flush.
                    cache?.upsertRows(listOf(payload))
                }
                return@withContext Result.failure(QueuedOfflineException())
            }
            if (!resp.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException(resp.errorMessage ?: "Uložení se nezdařilo (${resp.code})"),
                )
            }
            // Keep the local snapshot in step: the payload carries exactly the keys
            // this save touched, and the overlay keeps the worker-owned ones
            // (phone_screen_time, ai_reflection).
            runCatching { cache?.upsertRows(listOf(payload)) }
            // Achievements are a side effect — never lose the entry over them.
            runCatching { client.post("rpc/check_achievements", buildAchievementsPayload(entry, userId)) }
            Result.success(Unit)
        }

    /**
     * Deletes the day's row — the second half of moving a stored check-in to another
     * date (see [save]). The owner's RLS policy (`entries_own`) covers DELETE, so the
     * app needs no server-side RPC for this.
     *
     * A failure is reported, not swallowed: the caller has usually already written the
     * entry to its new day, and a stale copy left behind must reach the screen as a
     * sentence instead of silently duplicating the day.
     */
    suspend fun delete(date: String): Result<Unit> = withContext(Dispatchers.IO) {
        val userId = session.userId()
            ?: return@withContext Result.failure(IllegalStateException(SIGNED_OUT))
        val resp = client.delete(
            "entries",
            mapOf("user_id" to "eq.$userId", "date" to "eq.$date"),
        )
        if (!resp.isSuccessful) {
            return@withContext Result.failure(
                IllegalStateException(
                    resp.errorMessage ?: "Původní den se nepodařilo smazat (${resp.code}).",
                ),
            )
        }
        // The day is gone everywhere it was visible: the snapshot must not
        // resurrect it on the next cold start, and a queued copy must not
        // overwrite the delete.
        runCatching {
            cache?.removeDate(date)
            pending?.remove(date)
        }
        Result.success(Unit)
    }

    /**
     * Uploads the day's photo to the public `diary-photos` bucket at
     * `{uid}/{date}.jpg` (upsert) and returns its public URL, or null on failure.
     */
    suspend fun uploadPhoto(date: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext null
        val path = "$userId/$date.jpg"
        val resp = client.uploadObject("diary-photos/$path", bytes, "image/jpeg", upsert = true)
        if (!resp.isSuccessful) return@withContext null
        client.publicStorageUrl("diary-photos/$path")
    }

    companion object {

        /**
         * Safety cap for [loadRange]. The dashboard's 30-day window is far below
         * it, so the cap only guards against a malformed range, never truncates a
         * real read.
         */
        const val RANGE_LIMIT = 100

        /** Same wording `save` uses, so a signed-out read reads like a session problem. */
        private const val SIGNED_OUT = "Přihlášení vypršelo, přihlas se znovu."

        /**
         * Copy for a read that never reached the server — the same sentence the AI
         * section shows, so an offline phone reads the same everywhere in the app.
         */
        private const val CONNECT_FAILED = "Nepodařilo se připojit k serveru."

        /**
         * Maps a [DiaryEntry] onto the exact `save_daily_entry(jsonb)` payload.
         *
         * Fields the form does not own (`phone_screen_time`, `phone_unlocks`,
         * `phone_top_apps`) are deliberately omitted: the RPC assigns `excluded.*`
         * on conflict for any key present in the payload, so sending them as
         * `null` would wipe the screen-time worker's data.
         *
         * `ai_reflection` is the same rule pointing the other way — it travels
         * only when the day already has one, so an ordinary form save (which
         * always starts from an entry loaded without a reflection, or from an
         * empty one) can never blank out what the AI endpoint stored. That is the
         * behaviour of `src/app/api/save-entry/route.ts` on the web side.
         */
        fun buildPayload(entry: DiaryEntry, userId: String, date: String): JSONObject =
            JSONObject().apply {
                put("user_id", userId)
                put("date", date)
                put("mood", entry.mood)
                put("mood_emoji", entry.moodEmoji)
                put("sleep_quality", entry.sleepQuality)
                put("stress", entry.stress)
                put("activities", JSONArray(entry.activities))
                put("habits", JSONObject(entry.habits))
                put("gratitude", JSONArray(entry.filteredGratitude()))
                put("note", entry.note)
                put("weather", JSONArray(entry.weather))
                put("scale_values", JSONObject(entry.positiveScaleValues()))
                entry.aiReflection?.takeIf { it.isNotBlank() }?.let { put("ai_reflection", it) }
                entry.photoPath?.takeIf { it.isNotBlank() }?.let { put("photo_path", it) }
            }

        /** `check_achievements(user_id, has_photo, has_scale)` — the post-save hook. */
        fun buildAchievementsPayload(entry: DiaryEntry, userId: String): JSONObject =
            JSONObject().apply {
                put("p_user_id", userId)
                put("p_has_photo", !entry.photoPath.isNullOrBlank())
                put("p_has_scale", entry.positiveScaleValues().isNotEmpty())
            }

        /** Maps an `entries` row back onto the form model. */
        fun fromRow(row: JSONObject): DiaryEntry = DiaryEntry(
            mood = row.optInt("mood", 0),
            moodEmoji = row.plainString("mood_emoji"),
            sleepQuality = row.optInt("sleep_quality", 0),
            stress = row.optInt("stress", 0),
            activities = row.optArrayTolerant("activities").stringList(),
            habits = row.optJSONObject("habits").booleanMap(),
            gratitude = row.optArrayTolerant("gratitude").slotList(DiaryEntry.GRATITUDE_SLOTS),
            note = row.plainString("note"),
            photoPath = row.plainString("photo_path").takeIf { it.isNotBlank() },
            scaleValues = row.optJSONObject("scale_values").intMap(),
            weather = row.optArrayTolerant("weather").stringList(),
            phoneScreenTime = row.optInt("phone_screen_time", -1).takeIf { it >= 0 },
            phoneUnlocks = row.optInt("phone_unlocks", -1).takeIf { it >= 0 },
            phoneTopApps = row.optArrayTolerant("phone_top_apps").topApps(),
            aiReflection = row.plainString("ai_reflection").takeIf { it.isNotBlank() },
        )
    }
}

/**
 * One `entries` row together with its `date`.
 *
 * [DiaryEntry] has no date on purpose — the check-in form is always scoped to the
 * day it is editing — but the dashboard works across days, so the range read
 * carries the row's date alongside the model instead of widening the form's shape.
 */
data class DatedEntry(val date: String, val entry: DiaryEntry)

/**
 * The save went to the offline outbox instead of the server — not an error
 * state. [EntriesRepository.save] reports it as a failed [Result] on purpose:
 * callers that only know success/failure still keep the day, and the check-in
 * screen keys its „Uloženo v telefonu…" card off this exact type.
 */
class QueuedOfflineException :
    Exception("Uloženo v telefonu — odešle se, jakmile budeš online.")
