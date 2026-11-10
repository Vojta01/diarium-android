package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * URL/header/body building of the REST client — no network. The transport is
 * faked, and the token comes from a real (Robolectric-backed) [SessionStore] so
 * the production path is what gets asserted.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SupabaseClientTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sent = mutableListOf<HttpRequest>()
    private val transport = HttpTransport { request ->
        sent += request
        HttpResponse(200, "[]")
    }

    private fun client(store: SessionStore?) = SupabaseClient(
        sessionStore = store,
        transport = transport,
        baseUrl = BASE_URL,
        anonKey = "anon-test-key",
    )

    private fun signedInStore(): SessionStore = SessionStore(context).apply {
        save(
            JSONObject().apply {
                put("access_token", "jwt-access-token")
                put("refresh_token", "refresh-1")
                put("expires_at", System.currentTimeMillis() / 1000 + 3600)
            },
        )
    }

    @Test
    fun `requests carry the anon key and the session bearer token`() {
        client(signedInStore()).get("entries")

        assertEquals(1, sent.size)
        val request = sent.first()
        assertEquals("GET", request.method)
        assertEquals("$BASE_URL/entries", request.url)
        assertEquals("anon-test-key", request.headers["apikey"])
        assertEquals("Bearer jwt-access-token", request.headers["Authorization"])
    }

    @Test
    fun `owner scoped client refuses dispatch after the signed in account changes`() {
        val store = SessionStore(context).apply { save(sessionFor(USER_A, "token-a")) }
        val scoped = client(store).forOwner(USER_A)
        store.save(sessionFor(USER_B, "token-b"))

        val failure = runCatching { scoped.get("entries") }.exceptionOrNull()

        assertNotNull("a stale owner-bound client must fail closed", failure)
        assertTrue("no request may use account B's token for account A data", sent.isEmpty())
    }

    @Test
    fun `owner scoped client refuses dispatch after logout and another account login`() {
        val store = SessionStore(context).apply { save(sessionFor(USER_A, "token-a")) }
        val scoped = client(store).forOwner(USER_A)
        store.clear()

        val signedOutFailure = runCatching { scoped.get("entries") }.exceptionOrNull()
        store.save(sessionFor(USER_B, "token-b"))
        val switchedFailure = runCatching { scoped.get("entries") }.exceptionOrNull()

        assertNotNull(signedOutFailure)
        assertNotNull(switchedFailure)
        assertTrue("the A-bound client must never dispatch with B's session", sent.isEmpty())
    }

    @Test
    fun `dispatched request keeps the captured owner's token during account switch`() {
        val enteredTransport = CountDownLatch(1)
        val releaseTransport = CountDownLatch(1)
        val captured = AtomicReference<HttpRequest>()
        val store = SessionStore(context).apply { save(sessionFor(USER_A, "token-a")) }
        val scoped = SupabaseClient(
            sessionStore = store,
            transport = HttpTransport { request ->
                captured.set(request)
                enteredTransport.countDown()
                check(releaseTransport.await(5, TimeUnit.SECONDS))
                HttpResponse(200, "[]")
            },
            baseUrl = BASE_URL,
            anonKey = "anon-test-key",
        ).forOwner(USER_A)
        val requestFailure = AtomicReference<Throwable?>()
        val thread = Thread {
            runCatching { scoped.get("entries") }.exceptionOrNull()?.let(requestFailure::set)
        }

        thread.start()
        assertTrue("fake network was not reached", enteredTransport.await(5, TimeUnit.SECONDS))
        store.save(sessionFor(USER_B, "token-b"))
        releaseTransport.countDown()
        thread.join(5_000)

        assertFalse("request thread should complete", thread.isAlive)
        assertNull(requestFailure.get())
        assertEquals("Bearer token-a", captured.get().headers["Authorization"])
    }

    @Test
    fun `a signed out client sends no authorization header`() {
        client(SessionStore(context)).get("entries")

        val request = sent.first()
        assertEquals("anon-test-key", request.headers["apikey"])
        assertFalse(request.headers.containsKey("Authorization"))
    }

    @Test
    fun `the anon key comes from BuildConfig, never a literal`() {
        // If this ever stops matching, a hardcoded key was introduced somewhere.
        assertEquals(cz.digitalnivedomi.diarium.BuildConfig.SUPABASE_ANON_KEY, anonymousClientAnonKey())
    }

    @Test
    fun `query parameters are encoded and appended to the table path`() {
        client(signedInStore()).get(
            path = "/entries",
            query = mapOf("user_id" to "eq.8f14e45f", "select" to "id,date", "limit" to "50"),
        )

        assertEquals(
            "$BASE_URL/entries?user_id=eq.8f14e45f&select=id%2Cdate&limit=50",
            sent.first().url,
        )
    }

    @Test
    fun `post sends a json body with a content type`() {
        client(signedInStore()).post(
            "entries",
            JSONObject().put("mood", 7),
            extraHeaders = mapOf("Prefer" to "resolution=merge-duplicates"),
        )

        val request = sent.first()
        assertEquals("POST", request.method)
        assertEquals("application/json", request.headers["Content-Type"])
        assertEquals("""{"mood":7}""", request.body)
        assertEquals("resolution=merge-duplicates", request.headers["Prefer"])
    }

    @Test
    fun `storage uploads do not upsert by default and public photo URLs stay public`() {
        val client = client(signedInStore())

        client.uploadObject("diary-photos/user/photo.jpg", byteArrayOf(1, 2, 3))

        val request = sent.single()
        assertEquals("POST", request.method)
        assertEquals("https://example.supabase.co/storage/v1/object/diary-photos/user/photo.jpg", request.url)
        assertNull(request.headers["x-upsert"])
        assertEquals("image/jpeg", request.headers["Content-Type"])
        assertEquals("https://example.supabase.co/storage/v1/object/public/diary-photos/user/photo.jpg",
            client.publicStorageUrl("diary-photos/user/photo.jpg"))
    }

    @Test
    fun `responses expose parsed json and error messages`() {
        assertTrue(HttpResponse(200, "[]").isSuccessful)
        assertNull(HttpResponse(200, "not json").asJsonArray())
        assertEquals("JWT expired", HttpResponse(401, """{"message":"JWT expired"}""").errorMessage)
        assertNull(HttpResponse(200, "[]").errorMessage)
    }

    @Test
    fun `base urls are normalized`() {
        assertEquals("$BASE_URL/entries", SupabaseClient.buildUrl(BASE_URL, "entries"))
        assertEquals("$BASE_URL/entries", SupabaseClient.buildUrl("$BASE_URL/", "/entries"))
    }

    private fun sessionFor(userId: String, token: String): JSONObject = JSONObject().apply {
        put("access_token", token)
        put("refresh_token", "refresh-$userId")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("user", JSONObject().put("id", userId))
    }

    private fun anonymousClientAnonKey(): String {
        val recorded = mutableListOf<HttpRequest>()
        SupabaseClient(
            sessionStore = SessionStore(context),
            transport = HttpTransport { request ->
                recorded += request
                HttpResponse(200, "[]")
            },
        ).get("entries")
        return recorded.first().headers.getValue("apikey")
    }

    private companion object {
        const val USER_A = "account-a"
        const val USER_B = "account-b"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
    }
}
