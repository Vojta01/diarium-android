package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Result of one on-device generation attempt ([ReportGenerator.generate]).
 *
 * The worker only needs to know whether a fresh row was written (to size its
 * poll), but the reasons are kept apart so tests and logs can tell a missing key
 * from a thin window from a real failure.
 */
sealed interface ReportOutcome {
    /** The report was generated and inserted into `ai_reports`. */
    data object Generated : ReportOutcome
    /** No API key is configured — the caller uses the server fallback. */
    data object NoKey : ReportOutcome
    /** Fewer than two days with data in the window — nothing to summarize. */
    data object NotEnoughData : ReportOutcome
    /** Transport, DeepSeek, or insert failure. [reason] is a short diagnostic. */
    data class Failed(val reason: String) : ReportOutcome
}

/**
 * Generates the weekly/monthly AI report **on the phone**, with the user's own
 * DeepSeek key (option C, owner decision 2026-10-11). This is the native port of
 * the server route `src/app/api/cron/ai-report/route.ts` — same prompt builder,
 * same model handling, same token budgets — with the one structural difference
 * that the DeepSeek key comes from [AiKeyStore] instead of an environment
 * variable, so no secret of ours is involved anywhere.
 *
 * The whole exchange:
 *  1. reads `entries` (last 7 / 30 days), `scales`, `habit_catalog` and
 *     `user_habits` through [SupabaseClient] with the signed-in user's JWT (RLS
 *     scopes every row); a failed scales/habits read degrades exactly like the
 *     route's `try/catch` — the prompt just loses that section;
 *  2. optionally reads the local profile and the current goals progress
 *     ([profile] / [goals], both nullable): the prompt gains the
 *     `👤 Uživatel` header and the `🎯 Cíle:` section, and a failed read just
 *     drops them — personalisation never fails a report;
 *  3. builds the prompt via [ReportPromptBuilder];
 *  4. calls DeepSeek directly (`POST https://api.deepseek.com/chat/completions`);
 *     the model comes from `app_settings.deepseek_model` when that row is
 *     readable, else [DEFAULT_MODEL]. (Today `app_settings` has RLS on with no
 *     read policy, so the app naturally falls back — same value the route
 *     resolves; adding a policy later would light the DB path up without a
 *     release.)
 *     An empty `choices[0].message.content` is retried up to [MAX_ATTEMPTS]
 *     times — the model occasionally answers empty on a 200;
 *  5. inserts the row into `ai_reports` with the user's JWT (RLS policy
 *     `Users insert own`: `with_check (user_id = auth.uid())`).
 *
 * The HTTP exchange is an injected [HttpTransport], so the whole path is
 * unit-testable with fakes; production passes an OkHttp transport with long
 * timeouts (the model can think for well over a minute).
 */
class ReportGenerator(
    private val client: SupabaseClient,
    private val keyStore: AiKeyStore,
    private val session: SessionContext = SessionContext(),
    private val deepSeek: HttpTransport = defaultDeepSeekTransport(),
    private val deepSeekUrl: String = BuildConfig.DEEPSEEK_URL,
    /** Overridable clock — the period windows are `today-7d..today` / `today-30d..today`. */
    private val today: () -> LocalDate = { LocalDate.now() },
    /** Pause between empty-content retries; 0 in tests so they stay instant. */
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    /**
     * The user's local profile (name + birth year) — read only to personalise
     * the prompt header; null (or a failed read) simply omits the header.
     */
    private val profile: ProfileStore? = null,
    /**
     * The goals repository feeding the `🎯 Cíle:` section with the current
     * period's progress; null (or a failed read) simply omits the section.
     */
    private val goals: GoalsRepository? = null,
) {

    suspend fun generate(type: String): ReportOutcome = withContext(Dispatchers.IO) {
        try {
            generateBlocking(type)
        } catch (e: CancellationException) {
            // The caller walked away; not a failure to report.
            throw e
        } catch (e: DeepSeekHttpError) {
            ReportOutcome.Failed("DeepSeek error (${e.code})")
        } catch (_: IOException) {
            ReportOutcome.Failed(CONNECT_FAILED)
        } catch (e: Exception) {
            ReportOutcome.Failed("${GENERATE_FAILED} (${e.javaClass.simpleName})")
        }
    }

    private suspend fun generateBlocking(type: String): ReportOutcome {
        // No key, no call — the worker then walks the server-fallback path.
        val apiKey = keyStore.key() ?: return ReportOutcome.NoKey
        val userId = session.userId() ?: return ReportOutcome.Failed(SIGNED_OUT)

        val periodEnd = today()
        val periodStart = periodEnd.minusDays(if (type == "monthly") MONTHLY_DAYS else WEEKLY_DAYS)
        val fromDate = periodStart.toString()
        val periodEndIso = periodEnd.toString()

        val entries = readEntries(userId, fromDate)
            ?: return ReportOutcome.Failed("entries read failed")
        if (entries.size < MIN_ENTRIES) return ReportOutcome.NotEnoughData

        val scaleNameMap = readScaleNames(userId)
        val habitMap = readHabitMap(userId)

        // Personalisation is best-effort: a failed profile read drops the header
        // (null), a failed goals read drops the section (empty list) — neither
        // may cost the user their report. Cancellation still propagates.
        val profileData = try {
            profile?.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val goalsProgress = try {
            goals?.loadProgress()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }.orEmpty()

        val userPrompt = ReportPromptBuilder.buildPeriodPrompt(
            entries = entries,
            type = type,
            scaleNameMap = scaleNameMap,
            habitMap = habitMap,
            userName = profileData?.name?.ifBlank { null },
            birthYear = profileData?.birthYear,
            goals = goalsProgress,
        )
        val model = resolveModel()

        val analysis = requestAnalysis(apiKey, type, model, userPrompt)
            ?: return ReportOutcome.Failed(EMPTY_ANALYSIS)

        val body = JSONObject().apply {
            put("user_id", userId)
            put("type", type)
            put("period_start", fromDate)
            put("period_end", periodEndIso)
            put("content", analysis)
        }
        val inserted = client.post(TABLE, body)
        if (!inserted.isSuccessful) {
            return ReportOutcome.Failed("save failed (${inserted.code})")
        }
        return ReportOutcome.Generated
    }

    // ── reads (user JWT, RLS-scoped) ────────────────────────────────────────

    /** The window's entries, oldest first; null when the read itself failed. */
    private fun readEntries(userId: String, fromDate: String): List<ReportEntry>? {
        val resp = client.get(
            "entries",
            mapOf(
                "user_id" to "eq.$userId",
                "date" to "gte.$fromDate",
                "select" to ENTRY_COLUMNS,
                "order" to "date.asc",
                // Far above the 31-day window; like every entries read in this
                // app it is explicit because PostgREST caps silently.
                "limit" to "400",
            ),
        )
        if (!resp.isSuccessful) return null
        val rows = resp.asJsonArray() ?: return null
        return (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let(::parseEntry)
        }
    }

    /** `scales.id` → `name`; a failure degrades to an empty map (route parity). */
    private fun readScaleNames(userId: String): Map<String, String> {
        return try {
            val resp = client.get("scales", mapOf("user_id" to "eq.$userId", "select" to "id,name"))
            if (!resp.isSuccessful) return emptyMap()
            val rows = resp.asJsonArray() ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            (0 until rows.length()).forEach { index ->
                val row = rows.optJSONObject(index) ?: return@forEach
                val id = row.plainString("id")
                val name = row.plainString("name")
                if (id.isNotBlank() && name.isNotBlank()) out[id] = name
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Catalogue defaults merged with the user's active rows (route's merge). */
    private fun readHabitMap(userId: String): Map<String, ReportHabit> {
        return try {
            val catalogResp = client.get(
                "habit_catalog",
                mapOf("is_default" to "eq.true", "select" to "key,label,is_negative"),
            )
            val userResp = client.get(
                "user_habits",
                mapOf("user_id" to "eq.$userId", "select" to "key,label,is_negative,is_active"),
            )
            if (!catalogResp.isSuccessful || !userResp.isSuccessful) return emptyMap()
            ReportPromptBuilder.mergeHabits(
                catalog = catalogResp.asJsonArray().objects(),
                userRows = userResp.asJsonArray().objects(),
            )
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ── model ───────────────────────────────────────────────────────────────

    /**
     * `app_settings.deepseek_model` when the row is readable, else
     * [DEFAULT_MODEL] — the route's DB → fallback resolution. The app reads it
     * with the user JWT; while `app_settings` carries RLS with no policy, the
     * read returns an empty array and the fallback applies (same value today).
     */
    private fun resolveModel(): String {
        return try {
            val resp = client.get(
                "app_settings",
                mapOf("key" to "eq.deepseek_model", "select" to "value", "limit" to "1"),
            )
            if (!resp.isSuccessful) return DEFAULT_MODEL
            val row = resp.asJsonArray()?.optJSONObject(0) ?: return DEFAULT_MODEL
            row.plainString("value").takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        } catch (_: Exception) {
            DEFAULT_MODEL
        }
    }

    // ── DeepSeek ────────────────────────────────────────────────────────────

    /**
     * The completion text, or null when the model stayed empty through
     * [MAX_ATTEMPTS]. An HTTP error fails immediately (route parity), while an
     * empty 200 is retried — a repeated call usually answers.
     */
    private suspend fun requestAnalysis(
        apiKey: String,
        type: String,
        model: String,
        userPrompt: String,
    ): String? {
        var analysis = ""
        for (attempt in 1..MAX_ATTEMPTS) {
            if (analysis.isNotEmpty()) break
            val resp = deepSeek.execute(
                HttpRequest(
                    method = "POST",
                    url = deepSeekUrl,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        // The user's own key; the only place it ever travels.
                        "Authorization" to "Bearer $apiKey",
                    ),
                    body = buildChatPayload(type, model, userPrompt).toString(),
                ),
            )
            if (!resp.isSuccessful) throw DeepSeekHttpError(resp.code)
            analysis = parseCompletion(resp.body)
            if (analysis.isEmpty() && attempt < MAX_ATTEMPTS) delay(retryDelayMs)
        }
        return analysis.ifEmpty { null }
    }

    /** The exact chat request body the route sends. */
    private fun buildChatPayload(type: String, model: String, userPrompt: String): JSONObject =
        JSONObject().apply {
            put("model", model)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", ReportPromptBuilder.systemPrompt(type)),
                    )
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                },
            )
            put("max_tokens", ReportPromptBuilder.maxTokens(type))
            put("temperature", 0.7)
        }

    /** `choices[0].message.content.trim()` — empty for anything malformed. */
    private fun parseCompletion(raw: String): String = try {
        JSONObject(raw)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.plainString("content")
            ?.trim()
            .orEmpty()
    } catch (_: Exception) {
        ""
    }

    companion object {

        /** Where generated rows go; the same table the screen reads. */
        const val TABLE = "ai_reports"

        /** Fallback when `app_settings` is not readable with the user JWT. */
        const val DEFAULT_MODEL = "deepseek-flash"

        /** DeepSeek answers empty on a 200 now and then; the route retries 3×. */
        const val MAX_ATTEMPTS = 3

        /** Pause between those retries — the route waits 4 s too. */
        const val RETRY_DELAY_MS = 4_000L

        /** Route window: `weekly` = today-7d..today, `monthly` = today-30d..today. */
        const val WEEKLY_DAYS = 7L
        const val MONTHLY_DAYS = 30L

        /** The route refuses to summarize fewer than two days. */
        const val MIN_ENTRIES = 2

        /** `select` list of the route's entries read, verbatim. */
        const val ENTRY_COLUMNS =
            "date, mood, mood_emoji, activities, stress, sleep_quality, habits, gratitude, note, phone_screen_time, scale_values"

        const val CONNECT_FAILED = "Nepodařilo se připojit k serveru."
        const val SIGNED_OUT = "Přihlášení vypršelo, přihlas se znovu."
        const val GENERATE_FAILED = "Generování přehledu se nezdařilo."
        const val EMPTY_ANALYSIS = "AI vrátila prázdnou odpověď i po opakování."

        /**
         * Production transport: the model can think for well over a minute, so
         * the read timeout is far above the shared client's 30 s — this call is
         * the slowest thing the app ever does.
         */
        fun defaultDeepSeekTransport(): HttpTransport = OkHttpTransport(
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build(),
        )
    }
}

// ── parsing helpers ─────────────────────────────────────────────────────────

/** A non-2xx DeepSeek answer; the code is worth keeping for the outcome reason. */
internal class DeepSeekHttpError(val code: Int) : IOException("DeepSeek error ($code)")

/** Maps one `entries` row onto [ReportEntry]; NULL numbers stay null. */
internal fun parseEntry(row: JSONObject): ReportEntry = ReportEntry(
    date = row.plainString("date"),
    mood = row.nullableInt("mood"),
    sleepQuality = row.nullableInt("sleep_quality"),
    stress = row.nullableInt("stress"),
    activities = row.optJSONArray("activities").stringList(),
    habits = row.optJSONObject("habits").booleanMap(),
    gratitude = row.optJSONArray("gratitude").stringList(),
    note = row.stringOrNull("note"),
    phoneScreenTime = row.nullableInt("phone_screen_time"),
    scaleValues = row.optJSONObject("scale_values").intMap(),
)

/** A JSON number that may legitimately be SQL NULL. */
private fun JSONObject.nullableInt(key: String): Int? =
    if (isNull(key)) null else optInt(key, 0)

/** JSON array → its object rows (tolerant, skips anything else). */
private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
