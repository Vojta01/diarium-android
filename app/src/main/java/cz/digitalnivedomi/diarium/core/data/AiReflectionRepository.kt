package cz.digitalnivedomi.diarium.core.data

import android.util.Log
import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.auth.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Generates the day's AI reflection **and stores it with the day**.
 *
 * The DeepSeek key lives only on our own server, so the app never talks to the
 * model: it POSTs the day's data plus the signed-in user's JWT to
 * `/api/ai/reflect` — the very endpoint the web app calls — and gets Czech prose
 * back. That endpoint only *reads* the last 7 days from Supabase and *returns*
 * the text; it writes nothing (verified against
 * `src/app/api/ai/reflect/route.ts`). Persisting is the caller's job — the web
 * frontend does it by carrying `ai_reflection` inside its save payload — so this
 * repository does it too, with a small authenticated PostgREST PATCH right after
 * the server answers. Without that write the user would read a freshly generated
 * reflection that the database never received.
 *
 * The write goes out with the signed-in user's own JWT and relies on the RLS
 * policy `entries_own` (an owner may update their rows). No `apikey`/`service_role`
 * header exists here on purpose: the JWT alone is what the endpoint and PostgREST
 * authenticate with, the same way the browser posts it, and a service key must
 * never ship inside the APK.
 *
 * Request building, response parsing and the persistence URL/body are all plain
 * functions ([buildReflectPayload], [parseReflection], [reflectionPatchUrl],
 * [reflectionPatchBody]) so the whole contract is unit-testable without a network,
 * exactly like [SupabaseClient].
 */
class AiReflectionRepository(
    private val sessionStore: SessionStore? = null,
    private val transport: HttpTransport = OkHttpTransport(),
    private val endpoint: String = BuildConfig.AI_REFLECT_URL,
    /**
     * The authenticated PostgREST client that writes the generated text back to
     * the day's row. Defaults to a client over the same session, so the two
     * production wirings ([cz.digitalnivedomi.diarium.ui.checkin.CheckInDeps],
     * [cz.digitalnivedomi.diarium.ui.home.DashboardDeps]) keep working untouched;
     * tests inject a client whose transport is faked.
     */
    private val supabase: SupabaseClient? = sessionStore?.let { SupabaseClient(it) },
) {

    /**
     * Asks the server for [date]'s reflection, returns its text, and — once the
     * server answered — writes that text into the day's `entries` row as a side
     * effect, so no screen has to remember to persist it.
     *
     * A *generation* failure returns the Czech message that belongs to it. A
     * *write* failure never hides the text: the user keeps reading what the server
     * just produced and the failure is logged (see [persistReflection]).
     *
     * [entry] travels as the fallback data set; [userName] is optional because the
     * app only knows it when the session carries `user_metadata.full_name`.
     */
    suspend fun generate(date: String, entry: DiaryEntry, userName: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val token = sessionStore?.validAccessToken()
            val userId = SessionContext(sessionStore).userId()
            if (token == null || userId == null) {
                return@withContext Result.failure(IllegalStateException(MESSAGE_SIGNED_OUT))
            }
            val headers = linkedMapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            )
            val request = HttpRequest(
                method = "POST",
                url = endpoint,
                headers = headers,
                body = buildReflectPayload(entry, userId, date, userName).toString(),
            )
            val response = try {
                transport.execute(request)
            } catch (e: CancellationException) {
                // Cancellation is the caller walking away, not a failure to report.
                throw e
            } catch (_: IOException) {
                // Offline/flaky network is a normal state for a phone, not a bug.
                return@withContext Result.failure(IllegalStateException(MESSAGE_CONNECT))
            } catch (e: Exception) {
                // A malformed URL or an unexpected platform failure has to surface as
                // a Czech sentence, never as a crash inside the caller's coroutine.
                return@withContext Result.failure(
                    IllegalStateException("$MESSAGE_GENERIC (${e.javaClass.simpleName})."),
                )
            }
            val result = parseReflection(response)
            // The endpoint returns prose, it does not store it: write it back here,
            // for every caller at once, so the check-in's post-save flow and the
            // dashboard's "Vygenerovat reflexi" can never drop it again.
            result.onSuccess { text -> persistReflection(userId, date, text) }
            result
        }

    /**
     * The signed-in user's display name for the prompt, from the session — null
     * when it is not there, never invented and never fetched over the network.
     */
    fun userName(): String? = SessionContext(sessionStore).userName()

    /**
     * Writes [text] into the `(user_id, date)` row through PostgREST, with the
     * user's own JWT. Called only after the server answered with prose.
     *
     * The write can never cost the user their reflection: any failure (offline,
     * RLS, a stale token) is logged and swallowed, because the text is on screen
     * right now and losing it would be worse than it staying unpersisted. A day
     * with no row yet matches nothing — PostgREST answers 204 for a PATCH that
     * touches zero rows — so "no row" is quiet by construction.
     */
    private fun persistReflection(userId: String, date: String, text: String) {
        val client = supabase ?: return
        try {
            val response = client.patch(
                path = ENTRIES_PATH,
                body = reflectionPatchBody(text),
                query = reflectionPatchQuery(userId, date),
                extraHeaders = mapOf("Prefer" to PREFER_MINIMAL),
            )
            if (!response.isSuccessful) {
                Log.w(LOG_TAG, "reflection PATCH failed: HTTP ${response.code}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "reflection PATCH failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {

        /** User-facing Czech messages, one per failure mode the endpoint can return. */
        const val MESSAGE_SIGNED_OUT = "Přihlášení vypršelo, přihlas se prosím znovu."
        const val MESSAGE_RESTRICTED = "AI reflexe jsou povolené jen pro vybrané účty."
        const val MESSAGE_COOLDOWN = "Zkus to prosím za chvíli."
        const val MESSAGE_AI_FAILED = "AI teď neodpovídá, zkuste to znovu."
        const val MESSAGE_CONNECT = "Nepodařilo se připojit k serveru."
        const val MESSAGE_EMPTY = "AI vrátila prázdnou odpověď."
        const val MESSAGE_GENERIC = "Reflexi se nepodařilo vygenerovat"

        /** The PostgREST table the generated reflection is written back to. */
        const val ENTRIES_PATH = "entries"

        /**
         * Asks PostgREST for `return=minimal`, so a successful PATCH answers 204
         * with no body — the app needs to know *whether* the write worked, not to
         * read the row back.
         */
        const val PREFER_MINIMAL = "return=minimal"

        /** Log tag for a write that could not be persisted (never shown to the user). */
        const val LOG_TAG = "DiariumAI"

        /**
         * The exact body `/api/ai/reflect` expects: the web's snake_case field
         * names plus `lang = cs` so the server picks the Czech prompt.
         *
         * `phone_screen_time` is omitted while the worker has not synced a value,
         * otherwise an un-synced day would look like "0 minutes" to the model.
         */
        fun buildReflectPayload(
            entry: DiaryEntry,
            userId: String,
            date: String,
            userName: String? = null,
        ): JSONObject = JSONObject().apply {
            put("user_id", userId)
            put("date", date)
            put("lang", "cs")
            if (!userName.isNullOrBlank()) put("userName", userName)
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
            entry.phoneScreenTime?.let { put("phone_screen_time", it) }
        }

        /**
         * Maps the endpoint's reply onto [Result]: 2xx carries the reflection,
         * every other status carries the Czech sentence the user should read.
         * A 429 passes the server's own message through — it names the cooldown.
         */
        fun parseReflection(response: HttpResponse): Result<String> {
            if (response.isSuccessful) {
                val reflection = response.asJsonObject()?.plainString("reflection")?.trim().orEmpty()
                return if (reflection.isEmpty()) {
                    Result.failure(IllegalStateException(MESSAGE_EMPTY))
                } else {
                    Result.success(reflection)
                }
            }
            val message = when (response.code) {
                401 -> MESSAGE_SIGNED_OUT
                403 -> MESSAGE_RESTRICTED
                429 -> serverMessage(response) ?: MESSAGE_COOLDOWN
                502 -> MESSAGE_AI_FAILED
                else -> "$MESSAGE_GENERIC (chyba ${response.code})."
            }
            return Result.failure(IllegalStateException(message))
        }

        /**
         * The PostgREST query that scopes the write to exactly one owner's row for
         * one day. A plain ISO date (`yyyy-MM-dd`) survives
         * [SupabaseClient.buildUrl]'s encoding untouched (`-` is unreserved), which
         * is what keeps the `eq` filter matching the `date` column.
         */
        fun reflectionPatchQuery(userId: String, date: String): Map<String, String> =
            linkedMapOf("user_id" to "eq.$userId", "date" to "eq.$date")

        /**
         * The partial update written for a generated [text]: only `ai_reflection`,
         * never the form's own columns, so persisting a reflection can never touch
         * what the user typed.
         */
        fun reflectionPatchBody(text: String): JSONObject =
            JSONObject().put("ai_reflection", text)

        /**
         * The exact URL the persistence PATCH targets. Pure — it only joins strings
         * through [SupabaseClient.buildUrl] — so the contract (and the plain
         * `yyyy-MM-dd` date) is asserted in a plain JVM test.
         */
        fun reflectionPatchUrl(baseUrl: String, userId: String, date: String): String =
            SupabaseClient.buildUrl(baseUrl, ENTRIES_PATH, reflectionPatchQuery(userId, date))

        /** Our endpoint answers `{ "error": "..." }`; `message` is accepted too. */
        private fun serverMessage(response: HttpResponse): String? =
            response.asJsonObject()
                ?.let { it.plainString("error").ifBlank { it.plainString("message") } }
                ?.takeIf { it.isNotBlank() }
    }
}
