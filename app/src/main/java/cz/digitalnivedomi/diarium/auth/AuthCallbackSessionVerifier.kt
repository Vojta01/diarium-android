package cz.digitalnivedomi.diarium.auth

import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.core.data.HttpRequest
import cz.digitalnivedomi.diarium.core.data.HttpTransport
import cz.digitalnivedomi.diarium.core.data.OkHttpTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Result of verifying and atomically accepting an OAuth callback session. */
sealed interface AuthCallbackOutcome {
    data class Verified(val ownerId: String) : AuthCallbackOutcome
    data object Rejected : AuthCallbackOutcome
}

/**
 * Confirms callback access tokens against Supabase Auth before they can replace
 * the persisted session. The JWT payload is never an identity source: the user id
 * returned by `/auth/v1/user` is authoritative, while a present `sub` is checked
 * only to reject an internally inconsistent token/response pair.
 */
internal class AuthCallbackSessionVerifier(
    private val sessionStore: SessionStore,
    private val transport: HttpTransport = OkHttpTransport(),
    supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val supabaseUrl = supabaseUrl.trimEnd('/')
    private val attemptLock = Any()
    private var latestAttempt = 0L

    /**
     * Verify a parsed candidate off the main thread, then persist only if this is
     * still the latest callback and the session has not changed since verification
     * began. A null candidate still supersedes any older in-flight callback.
     */
    suspend fun verifyAndSave(
        candidate: JSONObject?,
        expectedSession: SessionStore.Snapshot = sessionStore.snapshot(),
        commitVerifiedSession: ((JSONObject) -> Boolean)? = null,
    ): AuthCallbackOutcome {
        val attempt = synchronized(attemptLock) {
            latestAttempt += 1
            latestAttempt
        }
        if (candidate == null) return AuthCallbackOutcome.Rejected

        val verified = withContext(ioDispatcher) { verify(candidate) }
            ?: return AuthCallbackOutcome.Rejected

        val commit = commitVerifiedSession ?: { session: JSONObject ->
            sessionStore.saveVerifiedSessionIfUnchanged(
                expected = expectedSession,
                session = session,
            )
        }
        val saved = synchronized(attemptLock) {
            attempt == latestAttempt && commit(verified.session)
        }
        return if (saved) AuthCallbackOutcome.Verified(verified.ownerId) else AuthCallbackOutcome.Rejected
    }

    private fun verify(candidate: JSONObject): VerifiedSession? {
        return try {
            val accessToken = candidate.optString("access_token")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: return null
            val response = transport.execute(
                HttpRequest(
                    method = "GET",
                    url = "$supabaseUrl/auth/v1/user",
                    headers = mapOf(
                        "apikey" to anonKey,
                        "Authorization" to "Bearer $accessToken",
                        "Accept" to "application/json",
                    ),
                ),
            )
            if (!response.isSuccessful) return null

            val user = response.asJsonObject() ?: return null
            val verifiedId = user.optString("id")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: return null
            val claims = AuthManager.decodeJwtPayload(accessToken)
            if (claims?.has("sub") == true) {
                val tokenSubject = claims.optString("sub")
                if (tokenSubject.isBlank() || tokenSubject == "null" || tokenSubject != verifiedId) return null
            }

            val verifiedSession = JSONObject(candidate.toString()).apply {
                put("user", user)
                put("verified_user_id", verifiedId)
            }
            VerifiedSession(verifiedId, verifiedSession)
        } catch (_: Exception) {
            // Network failures, invalid responses and parse errors are recoverable login failures.
            // Never include the URL token or response body in an exception/log message.
            null
        }
    }

    private data class VerifiedSession(val ownerId: String, val session: JSONObject)
}
