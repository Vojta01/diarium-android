package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.core.export.ExportEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Reads *every* `entries` row of the signed-in user for the CSV export.
 *
 * The web endpoint (`/api/export/csv`) reads through the `service_role` key and
 * asks for at most 10 000 rows in one shot. The app must not carry that key, so it
 * reads the same ten columns straight through PostgREST with the user's own JWT —
 * RLS (`entries_own`) is what scopes the rows to the account, exactly as in the
 * browser — and it pages, because PostgREST caps one response at 1000 rows.
 *
 * Paging is the pitfall this class exists to get right: every select gets an
 * **explicit** `limit` (a select without one is silently capped) and consecutive
 * pages are pulled with `offset` until a page comes back shorter than
 * [PAGE_SIZE]. Ordering is `date.asc`, matching the web's
 * `.order("date", { ascending: true })`, and the loop is bounded by [MAX_PAGES]
 * so a server that ignored `offset` could not spin forever inside the export.
 */
class ExportRepository(
    private val client: SupabaseClient,
    private val session: SessionContext = SessionContext(),
    /**
     * Rows per request — [PAGE_SIZE] in production. Injectable so the paging loop
     * (including its stop condition) can be exercised by a test without building
     * 100 000-row fixtures; it can never exceed PostgREST's per-response cap.
     */
    private val pageSize: Int = PAGE_SIZE,
) {

    init {
        require(pageSize in 1..PAGE_SIZE) { "pageSize must be 1..$PAGE_SIZE, was $pageSize" }
    }

    /**
     * All entries, oldest first, or a failure that already carries a Czech sentence
     * for the screen. Mirrors the endpoint's optional `from`/`to` (inclusive ISO
     * dates) even though the screen exports everything — a bounded export is a
     * one-line change later, not a rewrite.
     */
    suspend fun loadAllEntries(
        from: String? = null,
        to: String? = null,
    ): Result<List<ExportEntry>> = withContext(Dispatchers.IO) {
        try {
            Result.success(readAll(from, to))
        } catch (e: CancellationException) {
            // Cancellation is the caller leaving the screen, not an export failure.
            throw e
        } catch (e: IllegalStateException) {
            Result.failure(e)
        } catch (_: IOException) {
            Result.failure(IllegalStateException(CONNECT_FAILED))
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("Načtení zápisů se nezdařilo (${e.javaClass.simpleName})."),
            )
        }
    }

    /**
     * The real read behind [loadAllEntries]: throws on a transport failure, an HTTP
     * error or a body that is not a JSON array, so the caller has one place that
     * turns each of them into the [Result] the screen renders.
     */
    private suspend fun readAll(from: String?, to: String?): List<ExportEntry> {
        val userId = session.userId() ?: throw IllegalStateException(SIGNED_OUT)
        val collected = ArrayList<ExportEntry>()
        var page = 0
        while (page < MAX_PAGES) {
            val query = LinkedHashMap<String, String>()
            query["user_id"] = "eq.$userId"
            // The web's exact column list: the export file must not grow or lose a column.
            query["select"] = SELECT
            query["order"] = "date.asc"
            // Explicit limit on every request — PostgREST caps a response at 1000 rows
            // and a select without a limit is capped silently.
            query["limit"] = pageSize.toString()
            query["offset"] = (page * pageSize).toString()
            if (from != null || to != null) {
                // `and=(...)` because a Kotlin Map cannot hold a `date` bound twice.
                val bounds = buildList {
                    if (from != null) add("date.gte.$from")
                    if (to != null) add("date.lte.$to")
                }
                query["and"] = "(${bounds.joinToString(",")})"
            }

            val resp = client.get("entries", query)
            if (!resp.isSuccessful) {
                throw IllegalStateException(
                    resp.errorMessage ?: "Načtení zápisů se nezdařilo (${resp.code})",
                )
            }
            val rows = resp.asJsonArray() ?: throw IllegalStateException("Server vrátil neplatnou odpověď.")
            for (index in 0 until rows.length()) {
                collected.add(fromRow(rows.getJSONObject(index)))
            }
            // A short page means there is no next one.
            if (rows.length() < pageSize) return collected
            page++
        }
        return collected
    }

    companion object {

        /**
         * Rows per request. This is PostgREST's hard per-response cap, so asking for
         * more would be a lie and asking for less would only add round-trips.
         */
        const val PAGE_SIZE = 1000

        /**
         * Safety stop: 100 pages ≈ 100 000 entries, four times the web's own 10 000
         * cap. It exists so a broken `offset` cannot make the export loop forever.
         */
        const val MAX_PAGES = 100

        /** The `select` list of the web endpoint, character for character. */
        const val SELECT = "date, mood, mood_emoji, sleep_quality, stress, activities, habits, gratitude, note, scale_values"

        /** Same wording the rest of the data layer uses for an expired session. */
        private const val SIGNED_OUT = "Přihlášení vypršelo, přihlas se znovu."

        /** A phone that lost signal is a normal state — it reaches the screen as retryable Czech. */
        private const val CONNECT_FAILED = "Nepodařilo se připojit k serveru."

        /**
         * Maps one `entries` row onto the export shape.
         *
         * Text columns are copied verbatim (`optString`, no trimming — the web writes
         * `String(val)`, so a note that is one space stays one space). Object columns
         * keep their JSON-ness: a missing or JSON-`null` column becomes `null` (an
         * empty CSV field, like the web), while an empty array or object stays
         * `[]`/`{}` and is written as the quoted JSON. Array slots are copied
         * verbatim — unlike the form's `slotList`, nothing is padded or dropped — so
         * the file holds what the row holds.
         */
        fun fromRow(row: JSONObject): ExportEntry = ExportEntry(
            date = row.textOrNull("date").orEmpty(),
            mood = row.numberOrNull("mood"),
            moodEmoji = row.textOrNull("mood_emoji"),
            sleepQuality = row.numberOrNull("sleep_quality"),
            stress = row.numberOrNull("stress"),
            activities = row.valueList("activities"),
            habits = row.valueMap("habits"),
            gratitude = row.valueList("gratitude"),
            note = row.textOrNull("note"),
            scaleValues = row.valueMap("scale_values"),
        )

        /** Integer column: JSON `null` (or a missing key) is absent, not zero. */
        private fun JSONObject.numberOrNull(key: String): Int? =
            if (isNull(key)) null else optInt(key)

        /** TEXT column, untrimmed. JSON `null`/missing → absent (both write an empty field). */
        private fun JSONObject.textOrNull(key: String): String? =
            if (isNull(key)) null else optString(key)

        /** A JSON array column, kept slot by slot (`[null]` survives as a JSON null). */
        private fun JSONObject.valueList(key: String): List<Any?>? =
            if (isNull(key)) null else optJSONArray(key)?.jsonList()

        /** A JSON object column, kept key by key and in wire order. */
        private fun JSONObject.valueMap(key: String): Map<String, Any?>? =
            if (isNull(key)) null else optJSONObject(key)?.jsonMap()

        private fun JSONArray.jsonList(): List<Any?> {
            val out = ArrayList<Any?>(length())
            for (index in 0 until length()) out.add(opt(index).toExportValue())
            return out
        }

        private fun JSONObject.jsonMap(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            keys().forEach { key -> out[key] = opt(key).toExportValue() }
            return out
        }

        /**
         * `org.json` hands back its own types; [cz.digitalnivedomi.diarium.core.export.CsvExport.encodeJson]
         * speaks Kotlin's. Nested arrays/objects are converted all the way down so the
         * encoder never has to guess and never wraps a nested value in a string.
         */
        private fun Any?.toExportValue(): Any? = when (this) {
            // A JSON null arrives as the `org.json` sentinel, never as a Kotlin null —
            // read the wrong way it would be exported as the *string* "null".
            JSONObject.NULL -> null
            null, is String, is Boolean, is Number -> this
            is JSONArray -> this.jsonList()
            is JSONObject -> this.jsonMap()
            else -> toString()
        }
    }
}
