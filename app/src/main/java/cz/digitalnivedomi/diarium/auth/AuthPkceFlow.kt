package cz.digitalnivedomi.diarium.auth

import cz.digitalnivedomi.diarium.BuildConfig
import cz.digitalnivedomi.diarium.core.data.HttpRequest
import cz.digitalnivedomi.diarium.core.data.HttpTransport
import cz.digitalnivedomi.diarium.core.data.OkHttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Owns the native OAuth authorization-code PKCE round trip. */
internal class AuthPkceFlow(
    private val sessionStore: SessionStore,
    private val attempts: OAuthAttemptStore,
    private val transport: HttpTransport = OkHttpTransport(),
    supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY,
    private val authScheme: String = BuildConfig.AUTH_SCHEME,
    private val authHost: String = BuildConfig.AUTH_HOST,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val callbackVerifier: AuthCallbackSessionVerifier = AuthCallbackSessionVerifier(
        sessionStore = sessionStore,
        transport = transport,
        supabaseUrl = supabaseUrl,
        anonKey = anonKey,
        ioDispatcher = ioDispatcher,
    ),
) {
    private val supabaseUrl = supabaseUrl.trimEnd('/')
    private val callbackLock = Any()
    private var inFlightState: String? = null

    /** Generate, securely persist, and return the authorization URL for this login attempt. */
    fun authorizationUrl(): String = synchronized(callbackLock) {
        require(authScheme.matches(SCHEME_PATTERN) && authHost.matches(HOST_PATTERN)) {
            "Invalid configured OAuth callback"
        }
        val baseline = sessionStore.snapshot()
        val baselineOwner = ownerFromSnapshot(baseline)
        val state = randomToken()
        val verifier = randomToken()
        val redirectUri = "$authScheme://$authHost?state=$state"
        val attempt = OAuthAttempt(
            state = state,
            codeVerifier = verifier,
            redirectUri = redirectUri,
            createdAtMillis = nowMillis(),
            ownerIdAtStart = baselineOwner,
            hadSessionAtStart = !baseline.rawSession.isNullOrEmpty(),
        )
        check(attempts.save(attempt)) { "Unable to securely save OAuth attempt" }
        authorizationUrl(
            supabaseUrl = supabaseUrl,
            redirectUri = redirectUri,
            codeChallenge = codeChallenge(verifier),
        )
    }

    /**
     * Reject arbitrary deep links before any Auth request. A valid code is exchanged
     * only with the verifier from the matching outstanding attempt; the resulting
     * access token is then checked against `/auth/v1/user` before a session commit.
     */
    suspend fun handleCallback(uriString: String): AuthCallbackOutcome {
        val claimed = synchronized(callbackLock) {
            if (inFlightState != null) return@synchronized null
            val saved = attempts.read() ?: return@synchronized null
            if (saved.exchangeCompleted || saved.codeVerifier.isNullOrBlank()) return@synchronized null
            val age = nowMillis() - saved.createdAtMillis
            if (age !in 0L..ATTEMPT_TTL_MILLIS) {
                attempts.clearIfState(saved.state)
                return@synchronized null
            }
            if (parseCallback(uriString, saved, authScheme, authHost) == null) return@synchronized null
            val baseline = sessionStore.snapshot()
            if (!sessionMatchesAttempt(saved, baseline)) {
                attempts.clearIfState(saved.state)
                return@synchronized null
            }
            inFlightState = saved.state
            saved to baseline
        } ?: return AuthCallbackOutcome.Rejected
        val attempt = claimed.first
        val sessionSnapshot = claimed.second

        val callback = parseCallback(uriString, attempt, authScheme, authHost)
            ?: return releaseAndReject(attempt.state)
        val codeVerifier = attempt.codeVerifier ?: return releaseAndReject(attempt.state)
        var exchangeSucceeded = false
        try {
            val response = withContext(ioDispatcher) {
                transport.execute(
                    HttpRequest(
                        method = "POST",
                        url = "$supabaseUrl/auth/v1/token?grant_type=pkce",
                        headers = mapOf(
                            "apikey" to anonKey,
                            "Accept" to "application/json",
                            "Content-Type" to "application/json",
                        ),
                        body = JSONObject()
                            .put("auth_code", callback.code)
                            .put("code_verifier", codeVerifier)
                            .toString(),
                    ),
                )
            }
            if (!response.isSuccessful) {
                // Invalid, expired, or already-spent codes are terminal. Network,
                // rate-limit, and server errors retain the verifier for a retry.
                if (response.code in 400..499 && response.code != 408 && response.code != 429) {
                    synchronized(callbackLock) { attempts.clearIfState(attempt.state) }
                }
                return AuthCallbackOutcome.Rejected
            }

            val markedExchanged = synchronized(callbackLock) {
                attempts.markExchangeCompleted(attempt.state)
            }
            if (!markedExchanged) return AuthCallbackOutcome.Rejected
            exchangeSucceeded = true

            val candidate = sessionCandidate(response.body, nowMillis())
                ?: return AuthCallbackOutcome.Rejected
            return callbackVerifier.verifyAndSave(
                candidate = candidate,
                expectedSession = sessionSnapshot,
                commitVerifiedSession = { verifiedSession ->
                    synchronized(callbackLock) {
                        attempts.isExchangeCompleted(attempt.state) &&
                            sessionMatchesAttempt(attempt, sessionStore.snapshot()) &&
                            sessionStore.saveVerifiedSessionIfUnchanged(sessionSnapshot, verifiedSession)
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A transport timeout can happen after the server has consumed the code.
            // Keep the verifier for a retry; a spent code then receives a terminal 4xx.
            return AuthCallbackOutcome.Rejected
        } finally {
            synchronized(callbackLock) {
                if (exchangeSucceeded) attempts.clearIfState(attempt.state)
                if (inFlightState == attempt.state) inFlightState = null
            }
        }
    }

    private fun releaseAndReject(state: String): AuthCallbackOutcome {
        synchronized(callbackLock) {
            if (inFlightState == state) inFlightState = null
        }
        return AuthCallbackOutcome.Rejected
    }

    private fun sessionMatchesAttempt(
        attempt: OAuthAttempt,
        snapshot: SessionStore.Snapshot,
    ): Boolean =
        attempt.ownerIdAtStart == ownerFromSnapshot(snapshot) &&
            attempt.hadSessionAtStart == !snapshot.rawSession.isNullOrEmpty()

    private fun ownerFromSnapshot(snapshot: SessionStore.Snapshot): String? = try {
        snapshot.rawSession?.let { SessionStore.usableUserSubject(JSONObject(it)) }
    } catch (_: Exception) {
        null
    }

    private fun sessionCandidate(responseBody: String, receivedAtMillis: Long): JSONObject? {
        return try {
            val response = JSONObject(responseBody)
            val accessToken = response.optString("access_token")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: return null
            val refreshToken = response.optString("refresh_token")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: return null
            val expiresIn = response.optLong("expires_in", 0L).takeIf { it > 0L } ?: return null
            JSONObject().apply {
                put("access_token", accessToken)
                put("refresh_token", refreshToken)
                put("expires_in", expiresIn)
                put("expires_at", receivedAtMillis / 1000L + expiresIn)
                put("token_type", response.optString("token_type").ifBlank { "bearer" })
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(PKCE_RANDOM_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
        private val HOST_PATTERN = Regex("^[A-Za-z0-9.-]+$")
        private const val PKCE_RANDOM_BYTES = 32
        const val ATTEMPT_TTL_MILLIS = 10 * 60 * 1000L

        /** Supabase GoTrue's native PKCE endpoint shape, matching its Kotlin/JS clients. */
        internal fun authorizationUrl(supabaseUrl: String, redirectUri: String, codeChallenge: String): String =
            "${supabaseUrl.trimEnd('/')}/auth/v1/authorize" +
                "?provider=google" +
                "&redirect_to=${queryEncode(redirectUri)}" +
                "&code_challenge=${queryEncode(codeChallenge)}" +
                "&code_challenge_method=s256"

        internal fun codeChallenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            )

        /** Strictly require the exact configured URI and exactly one code and state. */
        internal fun parseCallback(
            uriString: String,
            attempt: OAuthAttempt,
            expectedScheme: String,
            expectedHost: String,
        ): AuthCodeCallback? {
            return try {
                val expected = URI(attempt.redirectUri)
                val actual = URI(uriString)
                if (
                    expected.scheme != expectedScheme ||
                    expected.rawAuthority != expectedHost ||
                    expected.rawPath.orEmpty().isNotEmpty() ||
                    expected.rawUserInfo != null || expected.port != -1 || expected.rawFragment != null ||
                    actual.scheme != expectedScheme ||
                    actual.rawAuthority != expectedHost ||
                    actual.rawPath.orEmpty().isNotEmpty() ||
                    actual.rawUserInfo != null || actual.port != -1 || actual.rawFragment != null
                ) return null

                val expectedQuery = parseQuery(expected.rawQuery) ?: return null
                if (expectedQuery.keys != setOf("state") || !sameState(expectedQuery["state"].orEmpty(), attempt.state)) {
                    return null
                }
                val callbackQuery = parseQuery(actual.rawQuery) ?: return null
                if (callbackQuery.keys != setOf("state", "code")) return null
                val returnedState = callbackQuery["state"]?.takeIf(String::isNotBlank) ?: return null
                val code = callbackQuery["code"]?.takeIf(String::isNotBlank) ?: return null
                if (!sameState(returnedState, attempt.state)) return null
                AuthCodeCallback(code = code, state = returnedState)
            } catch (_: Exception) {
                null
            }
        }

        private fun parseQuery(rawQuery: String?): Map<String, String>? {
            if (rawQuery.isNullOrEmpty()) return null
            val values = linkedMapOf<String, String>()
            for (part in rawQuery.split('&')) {
                if (part.isEmpty()) return null
                val separator = part.indexOf('=')
                if (separator <= 0) return null
                val key = URLDecoder.decode(part.substring(0, separator), Charsets.UTF_8.name())
                val value = URLDecoder.decode(part.substring(separator + 1), Charsets.UTF_8.name())
                if (key.isBlank() || values.put(key, value) != null) return null
            }
            return values
        }

        private fun queryEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun sameState(first: String, second: String): Boolean =
            MessageDigest.isEqual(first.toByteArray(Charsets.UTF_8), second.toByteArray(Charsets.UTF_8))
    }
}

internal data class AuthCodeCallback(val code: String, val state: String)
