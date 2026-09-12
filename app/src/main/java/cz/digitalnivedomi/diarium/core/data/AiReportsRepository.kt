package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One row of `ai_reports` as the screen renders it. */
data class AiReport(
    val id: String,
    val type: String,
    val periodStart: String,
    val periodEnd: String,
    val content: String,
    val createdAt: String,
)

/** The one server hop this feature needs: the web's cron route mints the report. */
fun interface ReportRequester {
    fun request(type: String): Boolean
}

/**
 * Reads the AI reports the web app generates, and asks the server for a new one.
 *
 * **Why the app reads the table directly.** The report is written by the web's cron
 * route, which runs with the `service_role` key; that key must never ship inside the
 * APK, so the app cannot go through the same door. It does not need to: `ai_reports`
 * is protected by the RLS policy `Users read own`, so a plain PostgREST read carrying
 * the signed-in user's own JWT returns exactly (and only) their rows — the same rows
 * the browser shows, scoped by the database rather than by our code. There is no
 * endpoint of ours in this path and no secret in the binary.
 *
 * **Why the poll exists.** Generation is not synchronous: the cron route accepts the
 * request and the model writes the row some seconds later. A user who taps
 * "Vygenerovat" must see something happen, so [generate] waits for a report that is
 * genuinely newer than [AiReport.id] already on screen instead of returning the stale
 * one it would read immediately — a button that answers instantly with the *old* text
 * is indistinguishable from a no-op.
 *
 * Failures arrive as an [IllegalStateException] whose message is a Czech sentence the
 * screen can show as-is, mirroring [ExportRepository].
 */
class AiReportsRepository(
    private val client: SupabaseClient,
    private val session: SessionContext = SessionContext(),
    private val requester: ReportRequester = ReportRequester { false },
) {

    /**
     * The newest report of [type], or null when the account has none yet — an empty
     * array is an ordinary state (a new account), not an error.
     */
    suspend fun latest(type: String): Result<AiReport?> = withContext(Dispatchers.IO) {
        try {
            Result.success(readLatest(type))
        } catch (e: CancellationException) {
            // Cancellation is the caller leaving the screen, not a read failure.
            throw e
        } catch (e: IllegalStateException) {
            Result.failure(e)
        } catch (_: IOException) {
            Result.failure(IllegalStateException(CONNECT_FAILED))
        } catch (e: Exception) {
            // A body that will not parse must reach the screen as Czech, never as a
            // crash inside the caller's coroutine.
            Result.failure(IllegalStateException("$LOAD_FAILED (${e.javaClass.simpleName})."))
        }
    }

    /**
     * Asks the server for a fresh report and waits for one newer than [previousId]
     * (the report already on screen), so the button never looks like a no-op.
     * Returns null when nothing new appeared before the attempts ran out.
     *
     * [attempts] and [delayMs] are injectable so the polling loop can be exercised
     * without spending real seconds on it; production uses [POLL_ATTEMPTS] and
     * [POLL_DELAY_MS] (≈18 s of patience).
     */
    suspend fun generate(
        type: String,
        previousId: String?,
        attempts: Int = POLL_ATTEMPTS,
        delayMs: Long = POLL_DELAY_MS,
    ): Result<AiReport?> = withContext(Dispatchers.IO) {
        // Asking the server while signed out would be a guaranteed 401 — fail cheap.
        if (session.validAccessToken() == null) {
            return@withContext Result.failure(IllegalStateException(SIGNED_OUT))
        }
        val requested = try {
            requester.request(type)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            return@withContext Result.failure(IllegalStateException(CONNECT_FAILED))
        } catch (e: Exception) {
            return@withContext Result.failure(
                IllegalStateException("$GENERATE_FAILED (${e.javaClass.simpleName})."),
            )
        }
        if (!requested) return@withContext Result.failure(IllegalStateException(GENERATE_FAILED))

        repeat(attempts.coerceAtLeast(0)) { attempt ->
            // The first look is immediate: a fast mint costs the user no wait.
            if (attempt > 0) delay(delayMs)
            val report = latest(type).getOrElse { failure ->
                return@withContext Result.failure(failure)
            }
            if (report != null && report.id != previousId) return@withContext Result.success(report)
        }
        // Nothing new inside the window: the screen says so rather than showing the
        // report it already had.
        Result.success(null)
    }

    /**
     * The real read behind [latest]: throws on a transport failure, an HTTP error or a
     * body that is not a JSON array, so the caller has one place that turns each of
     * them into the [Result] the screen renders.
     */
    private fun readLatest(type: String): AiReport? {
        val response = client.get(TABLE, latestQuery(type))
        if (!response.isSuccessful) {
            throw IllegalStateException(
                response.errorMessage ?: "$LOAD_FAILED (${response.code}).",
            )
        }
        val rows = response.asJsonArray() ?: throw IllegalStateException(INVALID_RESPONSE)
        // `limit=1` means at most one row; an empty array is "no report yet".
        val row = rows.optJSONObject(0) ?: return null
        return fromRow(row)
    }

    companion object {

        /** How many times [generate] looks for the freshly minted report. */
        const val POLL_ATTEMPTS = 6

        /** Pause between those looks — 6 × 3 s gives the model ~18 s to answer. */
        const val POLL_DELAY_MS = 3_000L

        /** A phone that lost signal is a normal state — it reaches the screen as retryable Czech. */
        const val CONNECT_FAILED = "Nepodařilo se spojit se serverem."

        /** The table the web's cron route writes each report into. */
        const val TABLE = "ai_reports"

        /** Wording style of [ExportRepository]'s read failures. */
        const val LOAD_FAILED = "Načtení přehledů se nezdařilo"

        /** The server refused to mint a report (cron disabled, quota, signed out there). */
        const val GENERATE_FAILED = "Přehled se nepodařilo vygenerovat."

        /** Same wording the rest of the data layer uses for an unparseable body. */
        const val INVALID_RESPONSE = "Server vrátil neplatnou odpověď."

        /** Same wording the rest of the data layer uses for an expired session. */
        const val SIGNED_OUT = "Přihlášení vypršelo, přihlas se znovu."

        /**
         * The read [latest] sends: newest first, exactly one row.
         *
         * `type` is the only filter on purpose — RLS already scopes the rows to
         * `auth.uid()`, so adding `user_id` here would only duplicate the database's
         * own guarantee. The explicit `limit` matters: a select without one is capped
         * silently by PostgREST, and this screen wants the single newest row.
         */
        fun latestQuery(type: String): Map<String, String> = linkedMapOf(
            "type" to "eq.$type",
            "select" to "*",
            "order" to "created_at.desc",
            "limit" to "1",
        )

        /**
         * Maps one `ai_reports` row onto [AiReport].
         *
         * Every column is read through [plainString], which turns a SQL NULL (JSON
         * `null`, which `optString` would hand back as the *string* "null"), a missing
         * key and a blank value into an empty string — the screen then decides what an
         * empty period or an empty report means instead of rendering the word "null".
         */
        fun fromRow(row: JSONObject): AiReport = AiReport(
            id = row.plainString("id"),
            type = row.plainString("type"),
            periodStart = row.plainString("period_start"),
            periodEnd = row.plainString("period_end"),
            content = row.plainString("content"),
            createdAt = row.plainString("created_at"),
        )
    }
}

/**
 * The production [ReportRequester]: `GET {baseUrl}/api/cron/ai-report?type=…` with the
 * signed-in user's own JWT — the very call the web cron and the notification worker
 * make. Kept thin on purpose (it is the only network-coupled part of this feature):
 * request building and the status check are the whole class, and the repository's
 * polling logic never depends on it.
 */
class HttpReportRequester(
    private val session: SessionContext,
    private val baseUrl: String = BuildConfig.DIARIUM_URL,
) : ReportRequester {

    override fun request(type: String): Boolean {
        // No token means no request: the endpoint authenticates with the JWT alone.
        val token = session.validAccessToken() ?: return false
        return try {
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/api/cron/ai-report?type=$type")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            // Offline, DNS, a 30 s timeout — all of them just mean "not requested",
            // and the caller still polls for an already existing report.
            false
        }
    }

    private companion object {
        /** The cron route generates with the model, so it answers slower than a read. */
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
