package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
    fun `a signed-out client sends no authorization header`() {
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
        const val BASE_URL = "https://example.supabase.co/rest/v1"
    }
}
