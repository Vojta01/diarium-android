package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.auth.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Generates the day's AI reflection.
 *
 * The DeepSeek key lives only on our own server, so the app never talks to the
 * model: it POSTs the day's data plus the signed-in user's JWT to
 * `/api/ai/reflect` — the very endpoint the web app calls — and gets Czech prose
 * back. The endpoint reads the last 7 days from Supabase itself and stores the
 * result in `entries.ai_reflection`, which is why the body's `todayData` is only
 * a fallback for a day that is not in the database yet.
 *
 * No `apikey`/`service_role` header exists here on purpose: the JWT alone is what
 * the endpoint authenticates with, the same way the browser posts it.
 *
 * Request building and response parsing are both plain functions
 * ([buildReflectPayload], [parseReflection]) so the whole contract is unit-testable
 * without a network, exactly like [SupabaseClient].
 */
class AiReflectionRepository(
    private val sessionStore: SessionStore? = null,
    private val transport: HttpTransport = OkHttpTransport(),
    private val endpoint: String = BuildConfig.AI_REFLECT_URL,
) {

    /**
     * Asks the server for [date]'s reflection and returns its text, or the Czech
     * message that belongs to the failure. [entry] travels as the fallback data
     * set; [userName] is optional because the app only knows it when the session
     * carries `user_metadata.full_name`.
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
            parseReflection(response)
        }

    /**
     * The signed-in user's display name for the prompt, from the session — null
     * when it is not there, never invented and never fetched over the network.
     */
    fun userName(): String? = SessionContext(sessionStore).userName()

    companion object {

        /** User-facing Czech messages, one per failure mode the endpoint can return. */
        const val MESSAGE_SIGNED_OUT = "Přihlášení vypršelo, přihlas se prosím znovu."
        const val MESSAGE_RESTRICTED = "AI reflexe jsou povolené jen pro vybrané účty."
        const val MESSAGE_COOLDOWN = "Zkus to prosím za chvíli."
        const val MESSAGE_AI_FAILED = "AI teď neodpovídá, zkuste to znovu."
        const val MESSAGE_CONNECT = "Nepodařilo se připojit k serveru."
        const val MESSAGE_EMPTY = "AI vrátila prázdnou odpověď."
        const val MESSAGE_GENERIC = "Reflexi se nepodařilo vygenerovat"

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
                val reflection = response.asJsonObject()?.optString("reflection")?.trim().orEmpty()
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

        /** Our endpoint answers `{ "error": "..." }`; `message` is accepted too. */
        private fun serverMessage(response: HttpResponse): String? =
            response.asJsonObject()
                ?.let { it.optString("error").ifBlank { it.optString("message") } }
                ?.takeIf { it.isNotBlank() }
    }
}
