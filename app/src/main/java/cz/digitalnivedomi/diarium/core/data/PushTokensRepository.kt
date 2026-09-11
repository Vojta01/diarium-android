package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import cz.digitalnivedomi.diarium.auth.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Keeps this device's FCM token in Supabase `push_tokens`, under the signed-in
 * account.
 *
 * Why direct PostgREST instead of the old `/api/push/subscribe` server hop:
 * `push_tokens` already carries RLS policies that let a user
 * select/insert/update/delete **their own** row (`migration_push_tokens.sql`),
 * and the app already holds the user's JWT — so the phone writes the row itself,
 * with no service-role key anywhere near the APK and no dependency on a Vercel
 * deploy. The token is therefore attached to a real account instead of being
 * posted to a server that has to be trusted to put it in the right place.
 *
 * Two rules from the schema drive the shape of the calls:
 *
 *  - the UNIQUE constraint is on `token`, so the write is an upsert
 *    (`on_conflict=token` + `Prefer: resolution=merge-duplicates`). That is also
 *    what makes signing in with a different account on the same phone **move**
 *    the row to the new `user_id` (the body carries it, the conflict target does
 *    not), instead of failing on a duplicate;
 *  - `updated_at` is sent explicitly on every upsert so a re-registration
 *    refreshes the timestamp instead of silently keeping the first one.
 *
 * The last token this device registered is remembered locally, so a logout can
 * delete exactly that row without asking Firebase for the token again. Every part
 * of the request (path, query, body, timestamp format) lives in pure companion
 * functions, so the contract is unit-tested; only the wiring touches Android.
 */
class PushTokensRepository internal constructor(
    private val sessionStore: SessionStore?,
    private val client: SupabaseClient,
    private val tokens: LastTokenStore = InMemoryLastTokenStore(),
) {

    /**
     * Production wiring: the real Supabase client (anon key + the user's JWT) and
     * the device's SharedPreferences. `PushTokensRepository(context)` is the only
     * constructor callers need.
     */
    constructor(context: Context) : this(
        sessionStore = SessionStore(context.applicationContext),
        client = SupabaseClient(SessionStore(context.applicationContext)),
        tokens = PrefsLastTokenStore(context.applicationContext),
    )

    private val session = SessionContext(sessionStore)

    /**
     * Upserts [token] for the signed-in user and remembers it for logout.
     *
     * Returns `false` — and sends nothing at all — when there is no session: a
     * token must never be attached to nobody. That is exactly the fresh-install
     * case the WebView-era code dropped on the floor (it registered before login),
     * so the caller simply re-runs this once the user is signed in.
     */
    suspend fun register(token: String): Boolean = withContext(Dispatchers.IO) {
        val clean = token.trim()
        if (clean.isEmpty()) return@withContext false
        val userId = session.userId() ?: return@withContext false

        val response = try {
            client.post(
                path = TABLE,
                body = buildPayload(userId, clean, isoTimestamp(System.currentTimeMillis())),
                query = UPSERT_QUERY,
                extraHeaders = mapOf("Prefer" to PREFER_MERGE_DUPLICATES),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline phone: report "not stored" so the caller can retry, rather
            // than remembering a token the database never received.
            return@withContext false
        }

        if (response.isSuccessful) tokens.write(clean)
        response.isSuccessful
    }

    /**
     * Deletes this device's token so the phone stops receiving push addressed to
     * the account that just left. RLS scopes the delete to the current user's own
     * rows, so a stale token can never delete someone else's row.
     *
     * Returns `true` when there is nothing left to delete (no remembered token, or
     * the row is gone). Call it **before** [SessionStore.clear] — the delete needs
     * the session that is about to be dropped.
     */
    suspend fun unregisterCurrentToken(): Boolean = withContext(Dispatchers.IO) {
        val clean = tokens.read() ?: return@withContext true

        val response = try {
            client.delete(TABLE, deleteQuery(clean))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext false
        }

        if (response.isSuccessful) tokens.clear()
        response.isSuccessful
    }

    companion object {

        /** The table from `migration_push_tokens.sql`. */
        const val TABLE = "push_tokens"

        /** The only platform this app ships on; the column defaults to it too. */
        const val PLATFORM_ANDROID = "android"

        /**
         * PostgREST upsert switch: with the conflict target `token` it turns the
         * INSERT into `ON CONFLICT (token) DO UPDATE`.
         */
        const val PREFER_MERGE_DUPLICATES = "resolution=merge-duplicates"

        /** `POST /rest/v1/push_tokens?on_conflict=token` — a retry is idempotent. */
        val UPSERT_QUERY: Map<String, String> = mapOf("on_conflict" to "token")

        /**
         * The exact upsert body. `user_id` comes from the session, which is what
         * moves the row on an account swap; `token` is the device's; `platform` is
         * fixed to what this build is; `updated_at` is an ISO-8601 instant.
         */
        fun buildPayload(userId: String, token: String, updatedAt: String): JSONObject =
            JSONObject().apply {
                put("user_id", userId)
                put("token", token)
                put("platform", PLATFORM_ANDROID)
                put("updated_at", updatedAt)
            }

        /** `DELETE /rest/v1/push_tokens?token=eq.<token>`. */
        fun deleteQuery(token: String): Map<String, String> = mapOf("token" to "eq.$token")

        /** The upsert URL, joined through the client's own encoder. */
        fun upsertUrl(baseUrl: String): String =
            SupabaseClient.buildUrl(baseUrl, TABLE, UPSERT_QUERY)

        /**
         * The delete URL. An FCM token contains `:` and `-`, so the value is
         * percent-encoded by [SupabaseClient.buildUrl] — the `eq.` filter has to
         * match the stored string exactly, not a mangled one.
         */
        fun deleteUrl(baseUrl: String, token: String): String =
            SupabaseClient.buildUrl(baseUrl, TABLE, deleteQuery(token))

        /**
         * `updated_at` the way PostgREST wants a timestamp: an ISO-8601 UTC
         * instant (`2026-09-11T19:00:00Z`). Pure, so the format is pinned by a
         * test instead of drifting with the device locale.
         */
        fun isoTimestamp(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(millis))
    }
}

/**
 * Remembers the token this device last registered successfully. Logout deletes
 * exactly this token, and it is why `unregisterCurrentToken()` does not have to
 * ask FirebaseMessaging for the current token while the user is signing out.
 */
internal interface LastTokenStore {
    fun read(): String?
    fun write(token: String)
    fun clear()
}

/** The production store, on the same SharedPreferences mechanism as the session. */
internal class PrefsLastTokenStore(context: Context) : LastTokenStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    override fun write(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    internal companion object {
        const val PREFS_NAME = "diarium_fcm"
        const val KEY_TOKEN = "last_token"
    }
}

/** Test double: keeps the token in memory, never on disk. */
internal class InMemoryLastTokenStore(private var token: String? = null) : LastTokenStore {
    override fun read(): String? = token?.takeIf { it.isNotBlank() }
    override fun write(token: String) {
        this.token = token
    }

    override fun clear() {
        token = null
    }
}
