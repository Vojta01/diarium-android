package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The `push_tokens` contract — no network, no Firebase.
 *
 * The HTTP transport is faked, so the exact request the app would send is
 * asserted field by field, and the JWT comes from a real (Robolectric-backed)
 * [SessionStore] — the same production path MainActivity and the FCM service
 * drive. Everything Android-free (URL, query, body, timestamp format) is asserted
 * directly on the companion functions.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PushTokensRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sent = mutableListOf<HttpRequest>()
    private var response: HttpResponse = HttpResponse(201, "")
    private var failure: IOException? = null
    private val transport = HttpTransport { request ->
        sent += request
        failure?.let { throw it }
        response
    }

    /** Stands in for the device's SharedPreferences token memory. */
    private val tokens = InMemoryLastTokenStore()

    private fun repository(store: SessionStore = signedInStore()): PushTokensRepository =
        PushTokensRepository(
            sessionStore = store,
            client = SupabaseClient(
                sessionStore = store,
                transport = transport,
                baseUrl = BASE_URL,
                anonKey = "anon-test-key",
            ),
            tokens = tokens,
        )

    // ── register (upsert) ───────────────────────────────────────────────────

    @Test
    fun `register upserts the token for the signed-in user`() = runTest {
        val stored = repository().register(TOKEN)

        assertTrue(stored)
        val request = sent.single()
        assertEquals("POST", request.method)
        assertEquals("$BASE_URL/push_tokens?on_conflict=token", request.url)
        assertEquals("anon-test-key", request.headers["apikey"])
        assertEquals("Bearer jwt-access-token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("resolution=merge-duplicates", request.headers["Prefer"])
    }

    @Test
    fun `the upsert body carries exactly the schema contract`() = runTest {
        repository().register(TOKEN)

        val body = JSONObject(sent.single().body!!)
        assertEquals(USER_ID, body.getString("user_id"))
        assertEquals(TOKEN, body.getString("token"))
        assertEquals("android", body.getString("platform"))
        assertTrue(body.getString("updated_at").isNotBlank())
        assertEquals(4, body.length())
    }

    @Test
    fun `register remembers the token so logout can delete it`() = runTest {
        repository().register(TOKEN)

        assertEquals(TOKEN, tokens.read())
    }

    @Test
    fun `a different account on the same phone moves the token to the new user`() = runTest {
        // Same device token, another signed-in user: the body's user_id is what
        // moves the row (the conflict target is `token`), so no duplicate appears.
        repository(signedInStore(USER_ID)).register(TOKEN)
        repository(signedInStore(OTHER_USER_ID)).register(TOKEN)

        assertEquals(USER_ID, JSONObject(sent[0].body!!).getString("user_id"))
        assertEquals(OTHER_USER_ID, JSONObject(sent[1].body!!).getString("user_id"))
    }

    @Test
    fun `register sends nothing while signed out`() = runTest {
        val stored = repository(SessionStore(context).apply { clear() }).register(TOKEN)

        assertFalse(stored)
        assertTrue(sent.isEmpty())
        assertNull(tokens.read())
    }

    @Test
    fun `a blank token is never sent`() = runTest {
        assertFalse(repository().register("   "))

        assertTrue(sent.isEmpty())
        assertNull(tokens.read())
    }

    @Test
    fun `a rejected upsert is reported and not remembered`() = runTest {
        response = HttpResponse(401, """{"message":"JWT expired"}""")

        assertFalse(repository().register(TOKEN))
        assertNull(tokens.read())
    }

    @Test
    fun `an unreachable backend is a failure, not a remembered token`() = runTest {
        failure = IOException("no route to host")

        // The next start (or login) simply retries; nothing claims to be stored.
        assertFalse(repository().register(TOKEN))
        assertNull(tokens.read())
    }

    // ── unregister (logout) ─────────────────────────────────────────────────

    @Test
    fun `unregister deletes this device's token`() = runTest {
        tokens.write(TOKEN)

        assertTrue(repository().unregisterCurrentToken())

        val request = sent.single()
        assertEquals("DELETE", request.method)
        // The token's `:` travels percent-encoded so the `eq.` filter matches.
        assertEquals("$BASE_URL/push_tokens?token=eq.fAbC-123%3AAPA91bXYZ", request.url)
        assertEquals("Bearer jwt-access-token", request.headers["Authorization"])
        assertNull(tokens.read())
    }

    @Test
    fun `unregister with nothing remembered sends nothing`() = runTest {
        assertTrue(repository().unregisterCurrentToken())

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a failed delete keeps the token for a later retry`() = runTest {
        tokens.write(TOKEN)
        response = HttpResponse(500, """{"message":"boom"}""")

        assertFalse(repository().unregisterCurrentToken())
        assertEquals(TOKEN, tokens.read())
    }

    @Test
    fun `an offline logout keeps the token so it is not forgotten`() = runTest {
        tokens.write(TOKEN)
        failure = IOException("no route to host")

        assertFalse(repository().unregisterCurrentToken())
        assertEquals(TOKEN, tokens.read())
    }

    @Test
    fun `login then logout is an upsert followed by a delete`() = runTest {
        val repo = repository()
        repo.register(TOKEN)
        repo.unregisterCurrentToken()

        assertEquals(2, sent.size)
        assertEquals("POST", sent[0].method)
        assertEquals("DELETE", sent[1].method)
    }

    // ── pure request building ───────────────────────────────────────────────

    @Test
    fun `the upsert url pins the conflict target to the token column`() {
        assertEquals(
            "$BASE_URL/push_tokens?on_conflict=token",
            PushTokensRepository.upsertUrl(BASE_URL),
        )
    }

    @Test
    fun `an fcm token is percent-encoded in the delete filter`() {
        // Real tokens look like `fAbC-123:APA91b…`; the `:` must survive as `%3A`
        // or the `eq.` filter would not match the stored string.
        assertEquals(
            "$BASE_URL/push_tokens?token=eq.fAbC-123%3AAPA91b",
            PushTokensRepository.deleteUrl(BASE_URL, "fAbC-123:APA91b"),
        )
    }

    @Test
    fun `the payload builder emits the android platform and the given instant`() {
        val body = PushTokensRepository.buildPayload(USER_ID, TOKEN, "2026-09-11T19:00:00Z")

        assertEquals(USER_ID, body.getString("user_id"))
        assertEquals(TOKEN, body.getString("token"))
        assertEquals("android", body.getString("platform"))
        assertEquals("2026-09-11T19:00:00Z", body.getString("updated_at"))
    }

    @Test
    fun `the upsert timestamp is an iso-8601 utc instant`() {
        assertEquals("1970-01-01T00:00:00Z", PushTokensRepository.isoTimestamp(0L))
        assertEquals("2001-09-09T01:46:40Z", PushTokensRepository.isoTimestamp(1_000_000_000_000L))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A signed-in session with a valid (non-expiring) token and a known user id. */
    private fun signedInStore(userId: String = USER_ID): SessionStore = SessionStore(context).apply {
        save(
            JSONObject().apply {
                put("access_token", "jwt-access-token")
                put("refresh_token", "refresh-1")
                put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                put("user", JSONObject().put("id", userId))
            },
        )
    }

    private companion object {
        const val BASE_URL = "https://example.supabase.co/rest/v1"
        const val TOKEN = "fAbC-123:APA91bXYZ"
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val OTHER_USER_ID = "9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d"
    }
}
