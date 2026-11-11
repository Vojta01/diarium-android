package cz.digitalnivedomi.diarium.auth

import android.content.Context
import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.core.data.AppClients
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Stores the Supabase session locally (SharedPreferences) so the native side
 * can (1) authenticate REST calls through [validAccessToken] and (2) authenticate
 * the /api/save-entry pushes from background WorkManager sync jobs.
 */
class SessionStore(
    context: Context,
    private val refreshResponseProvider: ((String) -> JSONObject?)? = null,
) {

    private val prefs = context.getSharedPreferences("diarium_session", Context.MODE_PRIVATE)

    fun save(sessionJson: JSONObject) {
        synchronized(refreshLock) {
            prefs.edit().putString("session", sessionJson.toString()).apply()
            sessionRevision += 1
        }
    }

    fun sessionJson(): String? = prefs.getString("session", null)

    fun hasSession(): Boolean = !sessionJson().isNullOrEmpty()

    /** A revisioned snapshot lets callback verification reject account-switch races. */
    internal data class Snapshot(val rawSession: String?, val revision: Long)

    internal fun snapshot(): Snapshot = synchronized(refreshLock) {
        Snapshot(prefs.getString("session", null), sessionRevision)
    }

    /** Persist a server-verified callback only while its original session is current. */
    internal fun saveVerifiedSessionIfUnchanged(expected: Snapshot, session: JSONObject): Boolean =
        synchronized(refreshLock) {
            val ownerId = usableUserSubject(session) ?: return@synchronized false
            if (session.optString("verified_user_id") != ownerId) return@synchronized false
            if (sessionRevision != expected.revision || prefs.getString("session", null) != expected.rawSession) {
                return@synchronized false
            }
            prefs.edit().putString("session", session.toString()).apply()
            sessionRevision += 1
            true
        }

    /** Resolve the local owner pin, preferring a server-verified callback id. */
    fun userSubject(): String? = readSession()?.let(::usableUserSubject)

    /** Resolve the locally retained, server-verified owner used for offline recovery gates. */
    fun verifiedUserSubject(): String? {
        val session = readSession() ?: return null
        val verified = session.optString("verified_user_id")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: return null
        return verified.takeIf { usableUserSubject(session) == it }
    }

    fun accessToken(): String? {
        val s = sessionJson() ?: return null
        return try {
            JSONObject(s).optString("access_token").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun expiresAt(): Long {
        val s = sessionJson() ?: return 0L
        return try {
            JSONObject(s).optLong("expires_at", 0L)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Returns an access token that is still valid for at least ~1 minute,
     * transparently refreshing it via Supabase when it has expired. Background
     * sync jobs MUST use this instead of [accessToken] — a raw stored token is
     * dead after ~1 h and every push would fail with 401 forever.
     *
     * One refresh at a time for the whole process: 12+ SessionStore instances
     * share this session file, and GoTrue rotates the refresh token — two
     * parallel refreshes would race, and whoever persists last could store a
     * token the other side already invalidated. The lock lives on the companion
     * so it covers every instance; inside it the expiry is re-checked, because
     * whoever waited on the lock may find the token already refreshed.
     */
    fun validAccessToken(): String? = validAccessTokenFor(expectedOwnerId = null)

    /**
     * Returns a live token only if the session that supplies it still belongs to
     * [expectedOwnerId]. Repositories use this at HTTP dispatch, not just when
     * they initially resolve the owner: an account switch between those steps
     * therefore fails closed instead of sending account A's query/body with B's JWT.
     */
    fun validAccessTokenForUser(expectedOwnerId: String): String? {
        if (expectedOwnerId.isBlank()) return null
        return validAccessTokenFor(expectedOwnerId)
    }

    /**
     * Hold the session mutation lock across a quick network handoff. This lets an
     * owner change happen either before dispatch (and be rejected) or after the
     * request has started; it can never swap the token between validation and start.
     */
    internal fun <T> withCurrentOwnerLock(expectedOwnerId: String, block: () -> T): T? =
        synchronized(refreshLock) {
            if (expectedOwnerId.isBlank()) return@synchronized null
            val session = readSession() ?: return@synchronized null
            if (sessionOwnerId(session) != expectedOwnerId || !tokenSubjectMatchesSession(session)) {
                return@synchronized null
            }
            block()
        }

    private fun validAccessTokenFor(expectedOwnerId: String?): String? = synchronized(refreshLock) {
        val session = readSession() ?: return@synchronized null
        val owner = sessionOwnerId(session)
        if (expectedOwnerId != null && owner != expectedOwnerId) return@synchronized null
        if (!tokenSubjectMatchesSession(session)) return@synchronized null

        val token = session.optString("access_token").takeIf { it.isNotBlank() }
            ?: return@synchronized null
        if (session.optLong("expires_at", 0L) > System.currentTimeMillis() / 1000 + 60) {
            return@synchronized token
        }

        val refreshed = refreshAccessTokenLocked(session, expectedOwnerId) ?: return@synchronized null
        val refreshedSession = readSession() ?: return@synchronized null
        val refreshedOwner = sessionOwnerId(refreshedSession)
        if (expectedOwnerId != null && refreshedOwner != expectedOwnerId) return@synchronized null
        if (!tokenSubjectMatchesSession(refreshedSession)) return@synchronized null
        refreshed
    }

    private fun readSession(): JSONObject? = try {
        sessionJson()?.let(::JSONObject)
    } catch (_: Exception) {
        null
    }

    /** Session metadata, optional token claims and a server-verified id must not disagree. */
    private fun sessionOwnerId(session: JSONObject): String? {
        val verified = session.optString("verified_user_id")
            .takeIf { it.isNotBlank() && it != "null" }
        val stored = session.optJSONObject("user")?.optString("id")
            ?.takeIf { it.isNotBlank() && it != "null" }
        val subject = jwtSubject(session.optString("access_token"))
        if (verified != null) {
            if ((stored != null && stored != verified) || (subject != null && subject != verified)) return null
            return verified
        }
        if (stored != null && subject != null && stored != subject) return null
        return stored ?: subject
    }

    private fun tokenSubjectMatchesSession(session: JSONObject): Boolean {
        val verified = session.optString("verified_user_id")
            .takeIf { it.isNotBlank() && it != "null" }
        val stored = session.optJSONObject("user")?.optString("id")
            ?.takeIf { it.isNotBlank() && it != "null" }
        val subject = jwtSubject(session.optString("access_token"))
        if (verified != null) {
            return (stored == null || stored == verified) && (subject == null || subject == verified)
        }
        return stored == null || subject == null || stored == subject
    }

    private fun jwtSubject(token: String): String? {
        return try {
            val segment = token.split(".").getOrNull(1) ?: return null
            val padded = when (segment.length % 4) {
                2 -> "$segment=="
                3 -> "$segment="
                else -> segment
            }
            JSONObject(String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8))
                .optString("sub")
                .takeIf { it.isNotBlank() && it != "null" }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Exchanges the stored refresh_token for a fresh access_token (Supabase
     * /auth/v1/token?grant_type=refresh_token) and persists the updated session.
     * Returns the new access token, or null on any failure.
     */
    fun refreshAccessToken(): String? = synchronized(refreshLock) {
        val session = readSession() ?: return@synchronized null
        refreshAccessTokenLocked(session, expectedOwnerId = null)
    }

    /** Refresh only while the stored JWT subject still matches the captured owner. */
    fun refreshAccessTokenForUser(expectedOwnerId: String): String? = synchronized(refreshLock) {
        if (expectedOwnerId.isBlank()) return@synchronized null
        val session = readSession() ?: return@synchronized null
        if (usableUserSubject(session) != expectedOwnerId) return@synchronized null

        val refreshed = refreshAccessTokenLocked(session, expectedOwnerId) ?: return@synchronized null
        val refreshedSession = readSession() ?: return@synchronized null
        if (usableUserSubject(refreshedSession) != expectedOwnerId) return@synchronized null
        refreshed
    }

    private fun refreshAccessTokenLocked(session: JSONObject, expectedOwnerId: String?): String? {
        val refreshToken = session.optString("refresh_token")
        if (refreshToken.isBlank()) return null
        return try {
            val json = if (refreshResponseProvider != null) {
                refreshResponseProvider.invoke(refreshToken) ?: return null
            } else {
                // The shared, process-wide client (AppClients) — a fresh OkHttpClient
                // per refresh leaked a thread pool per call.
                val client = AppClients.defaultHttpClient
                val body = JSONObject().put("refresh_token", refreshToken).toString()
                    .toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url("${BuildConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .header("Content-Type", "application/json")
                    .post(body)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    JSONObject(resp.body?.string() ?: return null)
                }
            }
            val newAccess = json.optString("access_token")
            if (newAccess.isBlank()) return null
            val oldOwner = sessionOwnerId(session) ?: return null
            val requiredOwner = expectedOwnerId ?: oldOwner
            if (oldOwner != requiredOwner) return null
            val newSubject = jwtSubject(newAccess) ?: return null
            if (newSubject != requiredOwner) return null
            // A login/logout/account switch may have replaced the session while the
            // refresh response was in flight. Never let this stale response overwrite it.
            val currentSession = readSession() ?: return null
            if (currentSession.toString() != session.toString()) return null
            val newRefresh = json.optString("refresh_token").ifBlank { refreshToken }
            val expiresIn = json.optLong("expires_in", 3600)
            val updated = JSONObject().apply {
                put("access_token", newAccess)
                put("refresh_token", newRefresh)
                put("expires_at", System.currentTimeMillis() / 1000 + expiresIn)
                put("expires_in", expiresIn)
                put("token_type", "bearer")
                session.optJSONObject("user")?.let { put("user", it) }
                session.optString("verified_user_id")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { put("verified_user_id", it) }
            }
            // save() shares this re-entrant lock with logout/login, so an old
            // refresh can never overwrite a newer account session.
            save(updated)
            newAccess
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        synchronized(refreshLock) {
            prefs.edit().remove("session").apply()
            sessionRevision += 1
        }
    }

    companion object {
        /**
         * Return an owner id from a locally stored session. New callback sessions
         * carry a server-verified id; pre-existing sessions retain their local id
         * for offline access without authorizing future callbacks.
         */
        internal fun usableUserSubject(session: JSONObject): String? {
            val token = session.optString("access_token")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: return null
            val verified = session.optString("verified_user_id")
                .takeIf { it.isNotBlank() && it != "null" }
            val storedId = session.optJSONObject("user")?.optString("id")
                ?.takeIf { it.isNotBlank() && it != "null" }
            val subject = decodeJwtSubject(token)

            if (verified != null) {
                if ((storedId != null && storedId != verified) || (subject != null && subject != verified)) return null
                return verified
            }
            if (storedId != null && subject != null && storedId != subject) return null
            // Older locally persisted sessions remain usable offline; their identity
            // is never used to accept a new callback, which always requires Auth API verification.
            return storedId ?: subject
        }

        private fun decodeJwtSubject(token: String): String? {
            return try {
                val segment = token.split(".").getOrNull(1) ?: return null
                val padded = when (segment.length % 4) {
                    2 -> "$segment=="
                    3 -> "$segment="
                    else -> segment
                }
                JSONObject(String(java.util.Base64.getUrlDecoder().decode(padded), Charsets.UTF_8))
                    .optString("sub")
                    .takeIf { it.isNotBlank() && it != "null" }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * One refresh lock for every instance — they all share the same
         * "diarium_session" file, so the lock must not live on an instance.
         */
        private var sessionRevision = 0L
        private val refreshLock = Any()
    }
}