package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.URLDecoder

/**
 * The dashboard's one round-trip: which rows it asks for, under whose token, and what
 * a failure does to the screen.
 *
 * The transport is faked (no network) and the session is a real Robolectric-backed
 * [SessionStore], so the request asserted here is the one the app would send. The
 * arithmetic itself lives in [DashboardRepositoryTest] — this file only covers the
 * boundary: the range, the filters, the token, and that an error is an error.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DashboardRepositoryRangeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sent = mutableListOf<HttpRequest>()
    private var response: HttpResponse = HttpResponse(200, "[]")
    private var failure: IOException? = null

    private val transport = HttpTransport { request ->
        sent += request
        failure?.let { throw it }
        response
    }

    @Test
    fun `the dashboard reads thirty days ending today in one request`() = runTest {
        val result = repository().load(TODAY)

        assertTrue(result.isSuccess)

        val request = sent.single()
        assertEquals("GET", request.method)
        assertTrue(request.url.startsWith("$BASE_URL/entries?"))

        // Decoded, so the assertion is about the filter PostgREST receives and not
        // about which characters the encoder happens to escape.
        val query = URLDecoder.decode(request.url.substringAfter('?'), Charsets.UTF_8.name())
        assertTrue(query.contains("user_id=eq.$USER_ID"))
        assertTrue(query.contains("and=(date.gte.2026-08-12,date.lte.2026-09-10)"))
        assertTrue(query.contains("select=*"))
        assertTrue(query.contains("order=date.desc"))
        assertTrue(query.contains("limit=${EntriesRepository.RANGE_LIMIT}"))

        // The same RLS story as the check-in: the user's own JWT, never a server key.
        assertEquals("Bearer jwt-access-token", request.headers["Authorization"])
        assertEquals("anon-test-key", request.headers["apikey"])
    }

    @Test
    fun `the numbers come from the rows the range read returned`() = runTest {
        response = HttpResponse(200, rows().toString())

        val data = repository().load(TODAY).getOrThrow()

        assertEquals(TODAY, data.today)
        assertEquals(3, data.streak)
        assertEquals(7, data.week.size)
        assertEquals(5, data.todayEntry?.mood)
        assertEquals(3.0, data.averageMood ?: -1.0, 0.0001)
        // 6000 s + 3600 s ≈ 160 min over the window.
        assertEquals(160, data.screenTimeMinutes)
        assertEquals(15, data.unlocks)
        assertEquals("2026-09-10", data.topApps?.date)
    }

    @Test
    fun `an empty range is a valid empty dashboard, not a failure`() = runTest {
        val result = repository().load(TODAY)

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(0, data.streak)
        assertNull(data.todayEntry)
        assertNull(data.averageMood)
        assertNull(data.screenTimeMinutes)
        assertNull(data.unlocks)
        assertEquals(7, data.week.size)
    }

    @Test
    fun `a server error renders as a Czech failure, never as a zeroed dashboard`() = runTest {
        response = HttpResponse(503, "")

        val result = repository().load(TODAY)

        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
        assertEquals(
            "Načtení přehledu se nezdařilo (503)",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `an expired session surfaces the server's message`() = runTest {
        response = HttpResponse(401, """{"message":"JWT expired"}""")

        assertEquals("JWT expired", repository().load(TODAY).exceptionOrNull()?.message)
    }

    @Test
    fun `a broken response body is a failure, not an empty history`() = runTest {
        response = HttpResponse(200, "<html>502 Bad Gateway</html>")

        assertTrue(repository().load(TODAY).isFailure)
    }

    @Test
    fun `a flaky network propagates instead of showing zeros`() = runTest {
        failure = IOException("no route to host")

        val result = repository().load(TODAY)

        assertTrue(result.isFailure)
        // Czech, not the transport's own English sentence: the card shows this text.
        assertEquals("Nepodařilo se připojit k serveru.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a signed-out dashboard never hits the network`() = runTest {
        val result = repository(SessionStore(context).apply { clear() }).load(TODAY)

        assertEquals(
            "Přihlášení vypršelo, přihlas se znovu.",
            result.exceptionOrNull()?.message,
        )
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the dashboard request carries no server key`() = runTest {
        repository().load(TODAY)

        // The service_role key and any AI key stay on the server; the app reads its own
        // rows with the anon key plus the user's JWT, exactly like the browser does.
        val flat = sent.single().let { "${it.url} ${it.headers} ${it.body}" }.lowercase()
        assertFalse(flat.contains("service_role"))
        assertFalse(flat.contains("sb_secret"))
        assertFalse(flat.contains("deepseek"))
    }

    private fun repository(store: SessionStore = signedInStore()): DashboardRepository {
        val client = SupabaseClient(
            sessionStore = store,
            transport = transport,
            baseUrl = BASE_URL,
            anonKey = "anon-test-key",
        )
        return DashboardRepository(EntriesRepository(client, SessionContext(store)))
    }

    /** A signed-in session; a cleared one means "before login / after logout". */
    private fun signedInStore(): SessionStore = SessionStore(context).apply {
        save(
            JSONObject().apply {
                put("access_token", "jwt-access-token")
                put("refresh_token", "refresh-1")
                put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                put("user", JSONObject().put("id", USER_ID))
            },
        )
    }

    /** Three consecutive days with a mood, screen time and top apps on the newest. */
    private fun rows(): JSONArray = JSONArray()
        .put(
            JSONObject()
                .put("date", "2026-09-10")
                .put("mood", 5)
                .put("phone_screen_time", 6000)
                .put("phone_unlocks", 10)
                .put(
                    "phone_top_apps",
                    JSONArray().put(JSONObject().put("app", "Instagram").put("minutes", 42)),
                ),
        )
        .put(
            JSONObject()
                .put("date", "2026-09-09")
                .put("mood", 3)
                .put("phone_screen_time", 3600)
                .put("phone_unlocks", 5),
        )
        .put(JSONObject().put("date", "2026-09-08").put("mood", 1))

    private companion object {
        const val TODAY = "2026-09-10"
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
    }
}
