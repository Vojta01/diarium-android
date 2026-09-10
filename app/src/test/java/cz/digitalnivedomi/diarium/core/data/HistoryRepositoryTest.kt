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
 * The calendar's one round-trip: which days it asks for, under whose token, and what a
 * failure does to the screen.
 *
 * The transport is faked (no network) and the session is a real Robolectric-backed
 * [SessionStore], so the request asserted here is the one the app would send. The
 * calendar maths lives in `ui/history/HistoryCalendarTest` — this file only covers the
 * boundary: the range, the token, and that an error is an error rather than an empty
 * month ("you have not written anything in September" and "the request died" must not
 * look the same on a calendar).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryRepositoryTest {

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
    fun `the current month is read from its first day up to today`() = runTest {
        val result = repository().load(2026, 9, TODAY)

        assertTrue(result.isSuccess)

        val request = sent.single()
        assertEquals("GET", request.method)
        assertTrue(request.url.startsWith("$BASE_URL/entries?"))

        // Decoded, so the assertion is about the filter PostgREST receives and not
        // about which characters the encoder happens to escape.
        val query = decoded(request)
        assertTrue(query.contains("user_id=eq.$USER_ID"))
        assertTrue(query.contains("and=(date.gte.2026-09-01,date.lte.2026-09-10)"))
        assertTrue(query.contains("select=*"))

        // The same RLS story as the check-in: the user's own JWT, never a server key.
        assertEquals("Bearer jwt-access-token", request.headers["Authorization"])
        assertEquals("anon-test-key", request.headers["apikey"])
    }

    @Test
    fun `a finished month is read to its last day, leap February included`() = runTest {
        repository().load(2024, 2, TODAY)

        assertTrue(decoded(sent.single()).contains("and=(date.gte.2024-02-01,date.lte.2024-02-29)"))
    }

    @Test
    fun `a month of thirty-one days ends on the thirty-first`() = runTest {
        repository().load(2026, 8, TODAY)

        assertTrue(decoded(sent.single()).contains("and=(date.gte.2026-08-01,date.lte.2026-08-31)"))
    }

    @Test
    fun `today is never read from the clock, only from the caller`() = runTest {
        repository().load(2026, 9, "2026-09-05")

        assertTrue(decoded(sent.single()).contains("date.lte.2026-09-05"))
    }

    @Test
    fun `a month that has not started yet costs no request`() = runTest {
        val result = repository().load(2026, 10, TODAY)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().entries.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the month is keyed by iso date so a cell can find its day`() = runTest {
        response = HttpResponse(200, rows().toString())

        val data = repository().load(2026, 9, TODAY).getOrThrow()

        assertEquals(2026, data.year)
        assertEquals(9, data.month)
        assertEquals(TODAY, data.today)
        assertEquals(30, data.daysInMonth)
        assertEquals(setOf("2026-09-10", "2026-09-09", "2026-09-08"), data.entries.keys)
        assertEquals(5, data.moodOn("2026-09-10"))
        assertEquals(3, data.entryOn("2026-09-09")?.mood)
        // A day with no check-in has no marker at all — the cell is drawn empty.
        assertNull(data.moodOn("2026-09-07"))
    }

    @Test
    fun `a row without a date is dropped instead of landing under an empty key`() {
        val data = HistoryRepository.derive(
            year = 2026,
            month = 9,
            today = TODAY,
            rows = listOf(
                DatedEntry("2026-09-10", DiaryEntry(mood = 4)),
                DatedEntry("", DiaryEntry(mood = 2)),
            ),
        )

        assertEquals(mapOf("2026-09-10" to 4), data.entries.mapValues { it.value.mood })
    }

    @Test
    fun `an impossible month is refused instead of thrown at`() = runTest {
        val result = repository().load(2026, 13, TODAY)

        assertTrue(result.isFailure)
        assertEquals("Neplatné období (13/2026).", result.exceptionOrNull()?.message)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a server error renders as a Czech failure, never as an empty month`() = runTest {
        response = HttpResponse(503, "")

        val result = repository().load(2026, 9, TODAY)

        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
        assertEquals("Načtení přehledu se nezdařilo (503)", result.exceptionOrNull()?.message)
    }

    @Test
    fun `an expired session surfaces the server's message`() = runTest {
        response = HttpResponse(401, """{"message":"JWT expired"}""")

        assertEquals("JWT expired", repository().load(2026, 9, TODAY).exceptionOrNull()?.message)
    }

    @Test
    fun `a broken response body is a failure, not an empty calendar`() = runTest {
        response = HttpResponse(200, "<html>502 Bad Gateway</html>")

        assertTrue(repository().load(2026, 9, TODAY).isFailure)
    }

    @Test
    fun `a flaky network propagates instead of showing an empty month`() = runTest {
        failure = IOException("no route to host")

        val result = repository().load(2026, 9, TODAY)

        assertTrue(result.isFailure)
        // Czech, not the transport's own English sentence: the calendar shows this text.
        assertEquals("Nepodařilo se připojit k serveru.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a signed-out calendar never hits the network`() = runTest {
        val result = repository(SessionStore(context).apply { clear() }).load(2026, 9, TODAY)

        assertEquals("Přihlášení vypršelo, přihlas se znovu.", result.exceptionOrNull()?.message)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the calendar request carries no server key`() = runTest {
        repository().load(2026, 9, TODAY)

        // The service_role key and any AI key stay on the server; the app reads its own
        // rows with the anon key plus the user's JWT, exactly like the browser does.
        val flat = sent.single().let { "${it.url} ${it.headers} ${it.body}" }.lowercase()
        assertFalse(flat.contains("service_role"))
        assertFalse(flat.contains("sb_secret"))
        assertFalse(flat.contains("deepseek"))
    }

    private fun decoded(request: HttpRequest): String =
        URLDecoder.decode(request.url.substringAfter('?'), Charsets.UTF_8.name())

    private fun repository(store: SessionStore = signedInStore()): HistoryRepository {
        val client = SupabaseClient(
            sessionStore = store,
            transport = transport,
            baseUrl = BASE_URL,
            anonKey = "anon-test-key",
        )
        return HistoryRepository(EntriesRepository(client, SessionContext(store)))
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

    /** Three consecutive days, each with a mood; nothing on any other day. */
    private fun rows(): JSONArray = JSONArray()
        .put(JSONObject().put("date", "2026-09-10").put("mood", 5))
        .put(JSONObject().put("date", "2026-09-09").put("mood", 3))
        .put(JSONObject().put("date", "2026-09-08").put("mood", 1))

    private companion object {
        const val TODAY = "2026-09-10"
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
    }
}
