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
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthIdentityVerificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `forged callback claiming the current local owner is rejected without replacing session`() = runBlocking {
        val store = freshStore()
        store.save(legacySession(OWNER_A))
        val before = store.sessionJson()
        val requestSeen = mutableListOf<HttpRequest>()
        val verifier = verifier(store, HttpTransport { request ->
            requestSeen += request
            HttpResponse(401, "{\"message\":\"invalid token\"}")
        })

        val result = verifier.verifyAndSave(candidate(OWNER_A))

        assertEquals(AuthCallbackOutcome.Rejected, result)
        assertEquals(before, store.sessionJson())
        assertEquals(OWNER_A, store.userSubject())
        assertEquals(1, requestSeen.size)
        assertEquals("GET", requestSeen.single().method)
        assertEquals("https://supabase.test/auth/v1/user", requestSeen.single().url)
        assertEquals("test-anon-key", requestSeen.single().headers["apikey"])
        assertEquals("Bearer ${jwt(OWNER_A)}", requestSeen.single().headers["Authorization"])
    }

    @Test
    fun `valid callback binds owner to the auth user response`() = runBlocking {
        val store = freshStore()
        val verifier = verifier(store, HttpTransport {
            HttpResponse(200, authUser(OWNER_A).toString())
        })

        val result = verifier.verifyAndSave(candidate(OWNER_A))

        assertEquals(AuthCallbackOutcome.Verified(OWNER_A), result)
        assertEquals(OWNER_A, store.userSubject())
        val saved = JSONObject(store.sessionJson()!!)
        assertEquals(OWNER_A, saved.getString("verified_user_id"))
        assertEquals(OWNER_A, saved.getJSONObject("user").getString("id"))
    }

    @Test
    fun `server response is authoritative when token has no subject claim`() = runBlocking {
        val store = freshStore()
        val verifier = verifier(store, HttpTransport {
            HttpResponse(200, authUser(OWNER_A).toString())
        })

        val result = verifier.verifyAndSave(candidateWithoutJwtSubject())

        assertEquals(AuthCallbackOutcome.Verified(OWNER_A), result)
        assertEquals(OWNER_A, store.userSubject())
    }

    @Test
    fun `verified server identity that disagrees with token subject is rejected`() = runBlocking {
        val store = freshStore()
        val verifier = verifier(store, HttpTransport {
            HttpResponse(200, authUser(OWNER_B).toString())
        })

        val result = verifier.verifyAndSave(candidate(OWNER_A))

        assertEquals(AuthCallbackOutcome.Rejected, result)
        assertNull(store.sessionJson())
        assertNull(store.userSubject())
    }

    @Test
    fun `network failure leaves an existing offline session intact`() = runBlocking {
        val store = freshStore()
        store.save(legacySession(OWNER_A))
        val before = store.sessionJson()
        val verifier = verifier(store, HttpTransport { throw IOException("offline") })

        val result = verifier.verifyAndSave(candidate(OWNER_B))

        assertEquals(AuthCallbackOutcome.Rejected, result)
        assertEquals(before, store.sessionJson())
        assertEquals(OWNER_A, store.userSubject())
    }

    @Test
    fun `account switch during asynchronous verification rejects stale callback`() = runBlocking {
        val store = freshStore()
        store.save(legacySession(OWNER_A))
        val requestStarted = CountDownLatch(1)
        val finishRequest = CountDownLatch(1)
        val verifier = verifier(store, HttpTransport {
            requestStarted.countDown()
            check(finishRequest.await(5, TimeUnit.SECONDS))
            HttpResponse(200, authUser(OWNER_A).toString())
        })

        val pending = async(Dispatchers.Default) { verifier.verifyAndSave(candidate(OWNER_A)) }
        assertTrue("verification request did not start", requestStarted.await(5, TimeUnit.SECONDS))
        store.save(legacySession(OWNER_B))
        val switchedSession = store.sessionJson()
        finishRequest.countDown()

        assertEquals(AuthCallbackOutcome.Rejected, pending.await())
        assertEquals(switchedSession, store.sessionJson())
        assertEquals(OWNER_B, store.userSubject())
    }

    @Test
    fun `newer callback wins when an older verification returns late`() = runBlocking {
        val store = freshStore()
        val oldStarted = CountDownLatch(1)
        val finishOldRequest = CountDownLatch(1)
        val verifier = verifier(store, HttpTransport { request ->
            when (request.headers["Authorization"]) {
                "Bearer ${jwt(OWNER_A)}" -> {
                    oldStarted.countDown()
                    check(finishOldRequest.await(5, TimeUnit.SECONDS))
                    HttpResponse(200, authUser(OWNER_A).toString())
                }
                "Bearer ${jwt(OWNER_B)}" -> HttpResponse(200, authUser(OWNER_B).toString())
                else -> error("unexpected authorization header")
            }
        })

        val older = async(Dispatchers.Default) { verifier.verifyAndSave(candidate(OWNER_A)) }
        assertTrue("older verification request did not start", oldStarted.await(5, TimeUnit.SECONDS))
        assertEquals(AuthCallbackOutcome.Verified(OWNER_B), verifier.verifyAndSave(candidate(OWNER_B)))
        finishOldRequest.countDown()

        assertEquals(AuthCallbackOutcome.Rejected, older.await())
        assertEquals(OWNER_B, store.userSubject())
        assertEquals(OWNER_B, JSONObject(store.sessionJson()!!).getString("verified_user_id"))
    }

    @Test
    fun `legacy stored session remains available offline and is not rewritten`() {
        val store = freshStore()
        val legacy = legacySession(OWNER_A)
        store.save(legacy)
        val before = store.sessionJson()

        val state = AuthStateHolder(store)

        assertEquals(AuthStatus.AUTHENTICATED, state.status.value)
        assertEquals(OWNER_A, state.ownerId.value)
        assertEquals(before, store.sessionJson())
        assertFalse(JSONObject(store.sessionJson()!!).has("verified_user_id"))
    }

    private fun verifier(store: SessionStore, transport: HttpTransport) = AuthCallbackSessionVerifier(
        sessionStore = store,
        transport = transport,
        supabaseUrl = "https://supabase.test",
        anonKey = "test-anon-key",
    )

    private fun candidate(ownerId: String): JSONObject = JSONObject().apply {
        put("access_token", jwt(ownerId))
        put("refresh_token", "refresh-test")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("expires_in", 3600)
        put("token_type", "bearer")
    }

    private fun candidateWithoutJwtSubject(): JSONObject = JSONObject().apply {
        put("access_token", "opaque-test-token")
        put("refresh_token", "refresh-test")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("expires_in", 3600)
        put("token_type", "bearer")
    }

    private fun legacySession(ownerId: String): JSONObject = JSONObject().apply {
        put("access_token", jwt(ownerId))
        put("refresh_token", "legacy-refresh")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("user", JSONObject().put("id", ownerId).put("email", "offline@example.test"))
    }

    private fun authUser(ownerId: String): JSONObject = JSONObject().apply {
        put("id", ownerId)
        put("email", "verified@example.test")
        put("aud", "authenticated")
        put("role", "authenticated")
    }

    private fun jwt(subject: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"$subject"}""".toByteArray())
        return "$header.$payload.forged-test-signature"
    }

    private fun freshStore(): SessionStore {
        context.getSharedPreferences("diarium_session", Context.MODE_PRIVATE).edit().clear().commit()
        return SessionStore(context)
    }

    private companion object {
        const val OWNER_A = "8f14e45f-ceea-467a-9c6b-1a0b9a8d1234"
        const val OWNER_B = "2d91f3a6-8e70-4ba6-93f4-613c202711aa"
    }
}
