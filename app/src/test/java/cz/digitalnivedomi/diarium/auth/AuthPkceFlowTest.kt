package cz.digitalnivedomi.diarium.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cz.digitalnivedomi.diarium.core.data.HttpRequest
import cz.digitalnivedomi.diarium.core.data.HttpResponse
import cz.digitalnivedomi.diarium.core.data.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthPkceFlowTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `valid login exchanges code with matching verifier and verifies server identity`() = runBlocking {
        val attempts = MemoryAttemptStore()
        val store = sessionStore()
        val requests = mutableListOf<HttpRequest>()
        val transport = HttpTransport { request ->
            requests += request
            when {
                request.url.endsWith("/auth/v1/token?grant_type=pkce") -> {
                    val body = JSONObject(request.body!!)
                    assertEquals("issued-code", body.optString("auth_code"))
                    assertEquals(attempts.read()?.codeVerifier, body.optString("code_verifier"))
                    HttpResponse(200, tokenResponse(OWNER_A).toString())
                }
                request.url.endsWith("/auth/v1/user") -> {
                    val completedAttempt = attempts.read()
                    assertTrue(completedAttempt?.exchangeCompleted == true)
                    assertNull(completedAttempt?.codeVerifier)
                    assertEquals("Bearer ${jwt(OWNER_A)}", request.headers["Authorization"])
                    HttpResponse(200, authUser(OWNER_A).toString())
                }
                else -> error("Unexpected fake Auth endpoint")
            }
        }
        val flow = flow(attempts, transport, store)
        val authorizationUrl = flow.authorizationUrl()
        val attempt = attempts.read()!!

        assertTrue(authorizationUrl.startsWith("https://supabase.test/auth/v1/authorize?"))
        assertTrue(authorizationUrl.contains("code_challenge_method=s256"))
        assertTrue(authorizationUrl.contains("redirect_to=diarium%3A%2F%2Fauth-callback%3Fstate%3D${attempt.state}"))
        assertEquals(43, attempt.codeVerifier!!.length)
        assertEquals(AuthPkceFlow.codeChallenge(attempt.codeVerifier), queryParam(authorizationUrl, "code_challenge"))

        val result = flow.handleCallback("${attempt.redirectUri}&code=issued-code")

        assertEquals(AuthCallbackOutcome.Verified(OWNER_A), result)
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
        assertEquals("https://supabase.test/auth/v1/token?grant_type=pkce", requests.first().url)
        assertEquals("test-anon-key", requests.first().headers["apikey"])
        val saved = JSONObject(store.sessionJson()!!)
        assertEquals(OWNER_A, saved.getString("verified_user_id"))
        assertEquals(OWNER_A, saved.getJSONObject("user").getString("id"))
        assertNull(attempts.read())
    }

    @Test
    fun `forged token injection in a deep link is rejected without an Auth request`() = runBlocking {
        val attempts = MemoryAttemptStore()
        val store = sessionStore().apply { save(legacySession(OWNER_A)) }
        val original = store.sessionJson()
        val requests = mutableListOf<HttpRequest>()
        val flow = flow(attempts, HttpTransport { request ->
            requests += request
            error("Forged bearer tokens must never reach transport")
        }, store)
        flow.authorizationUrl()
        val attempt = attempts.read()!!

        val result = flow.handleCallback(
            "diarium://auth-callback?state=${attempt.state}&access_token=${jwt(OWNER_B)}&refresh_token=attacker-token",
        )

        assertEquals(AuthCallbackOutcome.Rejected, result)
        assertTrue(requests.isEmpty())
        assertEquals(original, store.sessionJson())
        assertEquals(OWNER_A, store.userSubject())
        assertNotNull(attempts.read()?.codeVerifier)
    }

    @Test
    fun `new authorization supersedes old state and verifier`() = runBlocking {
        val attempts = MemoryAttemptStore()
        val requests = AtomicInteger()
        val flow = flow(attempts, successfulTransport(requests))
        flow.authorizationUrl()
        val first = attempts.read()!!
        flow.authorizationUrl()
        val second = attempts.read()!!

        assertNotEquals(first.state, second.state)
        assertEquals(AuthCallbackOutcome.Rejected, flow.handleCallback("${first.redirectUri}&code=old-code"))
        assertEquals(0, requests.get())
        assertEquals(second.state, attempts.read()?.state)
        assertEquals(
            AuthCallbackOutcome.Verified(OWNER_A),
            flow.handleCallback("${second.redirectUri}&code=issued-code"),
        )
        assertEquals(2, requests.get())
    }

    @Test
    fun `owner switch before callback invalidates the pending authorization`() = runBlocking {
        val attempts = MemoryAttemptStore()
        val store = sessionStore()
        val requests = AtomicInteger()
        val flow = flow(attempts, successfulTransport(requests), store)
        flow.authorizationUrl()
        val attempt = attempts.read()!!
        store.save(legacySession(OWNER_B))

        assertEquals(
            AuthCallbackOutcome.Rejected,
            flow.handleCallback("${attempt.redirectUri}&code=issued-code"),
        )
        assertEquals(0, requests.get())
        assertNull(attempts.read())
        assertEquals(OWNER_B, store.userSubject())
    }

    @Test
    fun `wrong missing and reused state cannot exchange a callback`() = runBlocking {
        val attempts = MemoryAttemptStore()
        val requests = AtomicInteger()
        val flow = flow(attempts, successfulTransport(requests))
        flow.authorizationUrl()
        val attempt = attempts.read()!!

        assertEquals(
            AuthCallbackOutcome.Rejected,
            flow.handleCallback("diarium://auth-callback?state=wrong&code=issued-code"),
        )
        assertEquals(
            AuthCallbackOutcome.Rejected,
            flow.handleCallback("diarium://auth-callback?code=issued-code"),
        )
        assertEquals(0, requests.get())
        assertNotNull(attempts.read()?.codeVerifier)

        assertEquals(
            AuthCallbackOutcome.Verified(OWNER_A),
            flow.handleCallback("${attempt.redirectUri}&code=issued-code"),
        )
        assertEquals(AuthCallbackOutcome.Rejected, flow.handleCallback("${attempt.redirectUri}&code=issued-code"))
        assertEquals(2, requests.get())
        assertNull(attempts.read())
    }

    @Test
    fun `wrong callback URI host path fragment and extra token fields are rejected`() = runBlocking {
        val attempts = MemoryAttemptStore()
        val requests = AtomicInteger()
        val flow = flow(attempts, successfulTransport(requests))
        flow.authorizationUrl()
        val state = attempts.read()!!.state
        val invalidUris = listOf(
            "diarium://auth-callback.evil?state=$state&code=issued-code",
            "diarium://auth-callback/extra?state=$state&code=issued-code",
            "diarium://other-host?state=$state&code=issued-code",
            "diarium://auth-callback?state=$state&code=issued-code#fragment",
            "diarium://auth-callback?state=$state&code=issued-code&access_token=${jwt(OWNER_B)}",
        )

        invalidUris.forEach { uri ->
            assertEquals(AuthCallbackOutcome.Rejected, flow.handleCallback(uri))
        }
        assertEquals(0, requests.get())
        assertNotNull(attempts.read()?.codeVerifier)
    }

    @Test
    fun `intercepted code is not redeemable without the app stored verifier`() = runBlocking {
        val attempts = MemoryAttemptStore()
        var expectedVerifier: String? = null
        val transport = HttpTransport { request ->
            if (request.url.endsWith("/auth/v1/token?grant_type=pkce")) {
                val body = JSONObject(request.body!!)
                if (body.optString("auth_code") != "intercepted-code" ||
                    body.optString("code_verifier") != expectedVerifier
                ) {
                    HttpResponse(400, "{}")
                } else {
                    HttpResponse(200, tokenResponse(OWNER_A).toString())
                }
            } else {
                HttpResponse(200, authUser(OWNER_A).toString())
            }
        }
        val flow = flow(attempts, transport)
        flow.authorizationUrl()
        val attempt = attempts.read()!!
        expectedVerifier = attempt.codeVerifier

        // The intercepted authorization code alone cannot satisfy the token endpoint.
        val interceptedResponse = transport.execute(
            HttpRequest(
                method = "POST",
                url = "https://supabase.test/auth/v1/token?grant_type=pkce",
                headers = mapOf("Content-Type" to "application/json"),
                body = JSONObject().put("auth_code", "intercepted-code").toString(),
            ),
        )
        assertEquals(400, interceptedResponse.code)
        assertEquals(
            AuthCallbackOutcome.Verified(OWNER_A),
            flow.handleCallback("${attempt.redirectUri}&code=intercepted-code"),
        )
    }

    @Test
    fun `token endpoint timeout preserves attempt and allows callback retry`() = runBlocking {
        val attempts = MemoryAttemptStore()
        val tokenCalls = AtomicInteger()
        val transport = HttpTransport { request ->
            when {
                request.url.endsWith("/auth/v1/token?grant_type=pkce") -> {
                    if (tokenCalls.incrementAndGet() == 1) throw IOException("offline")
                    HttpResponse(200, tokenResponse(OWNER_A).toString())
                }
                request.url.endsWith("/auth/v1/user") -> HttpResponse(200, authUser(OWNER_A).toString())
                else -> error("Unexpected fake Auth endpoint")
            }
        }
        val flow = flow(attempts, transport)
        flow.authorizationUrl()
        val attempt = attempts.read()!!
        val verifierBefore = attempt.codeVerifier
        val callback = "${attempt.redirectUri}&code=issued-code"

        assertEquals(AuthCallbackOutcome.Rejected, flow.handleCallback(callback))
        assertEquals(verifierBefore, attempts.read()?.codeVerifier)
        assertEquals(AuthCallbackOutcome.Verified(OWNER_A), flow.handleCallback(callback))
        assertEquals(2, tokenCalls.get())
        assertNull(attempts.read())
    }

    @Test
    fun `terminal token exchange error deletes verifier and attempt`() = runBlocking {
        val attempts = MemoryAttemptStore()
        val flow = flow(attempts, HttpTransport { HttpResponse(400, "{}") })
        flow.authorizationUrl()
        val attempt = attempts.read()!!

        assertEquals(AuthCallbackOutcome.Rejected, flow.handleCallback("${attempt.redirectUri}&code=invalid-code"))
        assertNull(attempts.read())
    }

    @Test
    fun `account switch during server identity verification prevents stale session commit`() = runBlocking {
        val attempts = MemoryAttemptStore()
        val store = sessionStore().apply { save(legacySession(OWNER_A)) }
        val userRequestStarted = CountDownLatch(1)
        val finishUserRequest = CountDownLatch(1)
        val transport = HttpTransport { request ->
            if (request.url.endsWith("/auth/v1/token?grant_type=pkce")) {
                HttpResponse(200, tokenResponse(OWNER_A).toString())
            } else {
                userRequestStarted.countDown()
                check(finishUserRequest.await(5, TimeUnit.SECONDS))
                HttpResponse(200, authUser(OWNER_A).toString())
            }
        }
        val flow = flow(attempts, transport, store)
        flow.authorizationUrl()
        val attempt = attempts.read()!!
        val pending = async(Dispatchers.Default) { flow.handleCallback("${attempt.redirectUri}&code=issued-code") }

        assertTrue("Auth user request did not start", userRequestStarted.await(5, TimeUnit.SECONDS))
        store.save(legacySession(OWNER_B))
        val switchedSession = store.sessionJson()
        finishUserRequest.countDown()

        assertEquals(AuthCallbackOutcome.Rejected, pending.await())
        assertEquals(switchedSession, store.sessionJson())
        assertEquals(OWNER_B, store.userSubject())
        assertNull(attempts.read())
    }

    @Test
    fun `expired attempt clears verifier but malformed callback does not clear a live attempt`() = runBlocking {
        val attempts = MemoryAttemptStore()
        var now = 2_000_000L
        val requests = AtomicInteger()
        val flow = flow(attempts, successfulTransport(requests), nowMillis = { now })
        flow.authorizationUrl()
        val attempt = attempts.read()!!

        assertEquals(AuthCallbackOutcome.Rejected, flow.handleCallback("diarium://wrong-host?code=x&state=${attempt.state}"))
        assertNotNull(attempts.read()?.codeVerifier)
        now += AuthPkceFlow.ATTEMPT_TTL_MILLIS + 1
        assertEquals(AuthCallbackOutcome.Rejected, flow.handleCallback("${attempt.redirectUri}&code=issued-code"))
        assertNull(attempts.read())
        assertEquals(0, requests.get())
    }

    private fun flow(
        attempts: OAuthAttemptStore,
        transport: HttpTransport,
        store: SessionStore = sessionStore(),
        nowMillis: () -> Long = { 1_000_000L },
    ) = AuthPkceFlow(
        sessionStore = store,
        attempts = attempts,
        transport = transport,
        supabaseUrl = "https://supabase.test",
        anonKey = "test-anon-key",
        authScheme = "diarium",
        authHost = "auth-callback",
        nowMillis = nowMillis,
    )

    private fun successfulTransport(requests: AtomicInteger) = HttpTransport { request ->
        requests.incrementAndGet()
        when {
            request.url.endsWith("/auth/v1/token?grant_type=pkce") -> HttpResponse(200, tokenResponse(OWNER_A).toString())
            request.url.endsWith("/auth/v1/user") -> HttpResponse(200, authUser(OWNER_A).toString())
            else -> error("Unexpected fake Auth endpoint")
        }
    }

    private fun sessionStore(): SessionStore {
        context.getSharedPreferences("diarium_session", Context.MODE_PRIVATE).edit().clear().commit()
        return SessionStore(context)
    }

    private fun tokenResponse(ownerId: String): JSONObject = JSONObject().apply {
        put("access_token", jwt(ownerId))
        put("refresh_token", "fake-refresh-token")
        put("expires_in", 3600)
        put("token_type", "bearer")
        put("user", authUser(ownerId))
    }

    private fun legacySession(ownerId: String): JSONObject = JSONObject().apply {
        put("access_token", jwt(ownerId))
        put("refresh_token", "legacy-refresh-token")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("user", JSONObject().put("id", ownerId))
    }

    private fun authUser(ownerId: String): JSONObject = JSONObject().apply {
        put("id", ownerId)
        put("email", "verified@example.test")
    }

    private fun jwt(subject: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{}".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"$subject"}""".toByteArray())
        return "$header.$payload.synthetic-signature"
    }

    private fun queryParam(url: String, name: String): String? =
        url.substringAfter('?', "").split('&').mapNotNull { part ->
            val split = part.split('=', limit = 2)
            if (split.size == 2 && split[0] == name) split[1] else null
        }.singleOrNull()

    private class MemoryAttemptStore : OAuthAttemptStore {
        @Volatile private var attempt: OAuthAttempt? = null

        @Synchronized
        override fun save(attempt: OAuthAttempt): Boolean {
            this.attempt = attempt.copy()
            return true
        }

        @Synchronized
        override fun read(): OAuthAttempt? = attempt?.copy()

        @Synchronized
        override fun markExchangeCompleted(state: String): Boolean {
            val current = attempt ?: return false
            if (current.state != state || current.exchangeCompleted) return false
            attempt = current.copy(codeVerifier = null, exchangeCompleted = true)
            return true
        }

        @Synchronized
        override fun isExchangeCompleted(state: String): Boolean =
            attempt?.let { it.exchangeCompleted && it.state == state } ?: false

        @Synchronized
        override fun clearIfState(state: String): Boolean {
            if (attempt?.state != state) return false
            attempt = null
            return true
        }
    }

    private companion object {
        const val OWNER_A = "8f14e45f-ceea-467a-9c6b-1a0b9a8d1234"
        const val OWNER_B = "2d91f3a6-8e70-4ba6-93f4-613c202711aa"
    }
}
