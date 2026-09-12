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
import java.net.URLDecoder

/**
 * The AI reports read: the newest report of one type, under the user's own token, and
 * the generate-and-wait dance the screen's button performs.
 *
 * Three things are pinned here. First, that a report row reaches [AiReport] column for
 * column — the screen renders the markdown-ish Czech text verbatim, so nothing may be
 * trimmed or rewritten on the way. Second, that "no report yet" is an empty array and a
 * *success*, while a broken body is a failure: a new account and a broken server must
 * not look the same. Third, that `generate` never hands back the report it was asked to
 * replace — the difference between a button that works and a button that looks dead.
 *
 * The transport is faked (no network) and the session is a real Robolectric-backed
 * [SessionStore], so the request asserted here is the one the app would send and
 * `validAccessToken()` behaves as it does in production.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AiReportsRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sent = mutableListOf<HttpRequest>()

    /** `request`/`read` in the order they happened, to prove the hop comes first. */
    private val events = mutableListOf<String>()

    /** Responses in call order; the last one repeats, so "always this" needs one entry. */
    private var responses: List<HttpResponse> = listOf(HttpResponse(200, "[]"))

    /** What [ReportRequester] answers, and what it was asked for. */
    private var requesterResult = true
    private val requestedTypes = mutableListOf<String>()

    private val transport = HttpTransport { request ->
        val call = sent.size
        sent += request
        events += "read"
        responses[minOf(call, responses.size - 1)]
    }

    private val requester = ReportRequester { type ->
        requestedTypes += type
        events += "request"
        requesterResult
    }

    @Test
    fun `a report row is mapped into AiReport column for column`() = runTest {
        responses = listOf(
            jsonBody(
                reportRow(
                    id = REPORT_ID,
                    type = "monthly",
                    periodStart = "2026-08-01",
                    periodEnd = "2026-08-31",
                    content = "**Srpen** v kostce 😊",
                    createdAt = "2026-09-01T06:15:00.123456+00:00",
                ),
            ),
        )

        val report = repository().latest("monthly").getOrThrow()

        assertEquals(REPORT_ID, report?.id)
        assertEquals("monthly", report?.type)
        assertEquals("2026-08-01", report?.periodStart)
        assertEquals("2026-08-31", report?.periodEnd)
        // Verbatim: the screen renders this markdown-ish text as it is.
        assertEquals("**Srpen** v kostce 😊", report?.content)
        assertEquals("2026-09-01T06:15:00.123456+00:00", report?.createdAt)
    }

    @Test
    fun `the newest report is read straight off the table under the user's own token`() = runTest {
        responses = listOf(jsonBody(reportRow()))

        repository().latest("weekly").getOrThrow()

        val request = sent.single()
        assertEquals("GET", request.method)
        assertTrue(request.url.startsWith("$BASE_URL/ai_reports?"))
        val query = query(request)
        // `eq.` is PostgREST's equality operator; the value arrives decoded here.
        assertEquals("eq.weekly", query["type"])
        assertEquals("*", query["select"])
        assertEquals("created_at.desc", query["order"])
        // Exactly one row: this screen wants the newest report, not the history.
        assertEquals("1", query["limit"])
        // The read is scoped by RLS (`auth.uid() = user_id`), not by a service key.
        assertEquals("Bearer jwt-access-token", request.headers["Authorization"])
        assertEquals("anon-test-key", request.headers["apikey"])
    }

    @Test
    fun `an account with no report yet is a success with null, not an error`() = runTest {
        responses = listOf(HttpResponse(200, "[]"))

        val result = repository().latest("weekly")

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
        assertEquals(1, sent.size)
    }

    @Test
    fun `a server error becomes a Czech sentence carrying the status`() = runTest {
        responses = listOf(HttpResponse(503, ""))

        val result = repository().latest("weekly")

        assertTrue(result.isFailure)
        assertEquals("Načtení přehledů se nezdařilo (503).", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a body that is not JSON is a failure, never an exception out of the read`() = runTest {
        responses = listOf(HttpResponse(200, "<html>502 Bad Gateway</html>"))

        val result = repository().latest("weekly")

        assertTrue(result.isFailure)
        assertEquals("Server vrátil neplatnou odpověď.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `generate asks the server, then returns the new report and not the stale one`() = runTest {
        responses = listOf(
            jsonBody(reportRow(id = PREVIOUS_ID, content = "Starý přehled")),
            jsonBody(reportRow(id = NEW_ID, content = "Nový přehled 📈")),
        )

        val result = repository().generate("weekly", previousId = PREVIOUS_ID, attempts = 3, delayMs = 0)

        assertTrue(result.isSuccess)
        // The whole point: the report already on screen is never handed back as "new".
        assertEquals(NEW_ID, result.getOrThrow()?.id)
        assertEquals("Nový přehled 📈", result.getOrThrow()?.content)
        assertEquals(listOf("weekly"), requestedTypes)
        // The server hop happens before the first look, so the loop cannot miss a row
        // that was written while the request was still in flight.
        assertEquals(listOf("request", "read", "read"), events)
    }

    @Test
    fun `generate fails when the server refuses to mint a report`() = runTest {
        requesterResult = false

        val result = repository().generate("monthly", previousId = null, attempts = 3, delayMs = 0)

        assertTrue(result.isFailure)
        assertEquals("Přehled se nepodařilo vygenerovat.", result.exceptionOrNull()?.message)
        assertEquals(listOf("monthly"), requestedTypes)
        // No row can have appeared, so polling would be pure noise.
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `generate gives up with null when nothing newer appears in time`() = runTest {
        // Always the same report: the model never wrote a new one.
        responses = listOf(jsonBody(reportRow(id = PREVIOUS_ID)))

        val result = repository().generate("weekly", previousId = PREVIOUS_ID, attempts = 3, delayMs = 0)

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
        assertEquals(3, sent.size)
    }

    @Test
    fun `a failing poll is reported instead of pretending a report arrived`() = runTest {
        responses = listOf(HttpResponse(500, ""))

        val result = repository().generate("weekly", previousId = PREVIOUS_ID, attempts = 3, delayMs = 0)

        assertTrue(result.isFailure)
        assertEquals("Načtení přehledů se nezdařilo (500).", result.exceptionOrNull()?.message)
        // A server that cannot be read is not retried three times over.
        assertEquals(1, sent.size)
    }

    @Test
    fun `generate without a session never asks the server`() = runTest {
        val result = repository(store = SessionStore(context).apply { clear() })
            .generate("weekly", previousId = null, attempts = 3, delayMs = 0)

        assertTrue(result.isFailure)
        assertEquals("Přihlášení vypršelo, přihlas se znovu.", result.exceptionOrNull()?.message)
        assertTrue(requestedTypes.isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the report read carries no server key`() = runTest {
        responses = listOf(jsonBody(reportRow()))

        repository().latest("weekly").getOrThrow()

        // The cron route mints the report with the service_role key; the app never can,
        // so it reads through RLS with the public anon key and the user's own JWT.
        val flat = sent.single().let { "${it.url} ${it.headers} ${it.body}" }.lowercase()
        assertFalse(flat.contains("service_role"))
        assertFalse(flat.contains("sb_secret"))
        assertFalse(flat.contains("deepseek"))
    }

    private fun repository(store: SessionStore = signedInStore()): AiReportsRepository =
        AiReportsRepository(
            client = SupabaseClient(
                sessionStore = store,
                transport = transport,
                baseUrl = BASE_URL,
                anonKey = "anon-test-key",
            ),
            session = SessionContext(store),
            requester = requester,
        )

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

    /** One `ai_reports` row with every column the screen reads. */
    private fun reportRow(
        id: String = REPORT_ID,
        type: String = "weekly",
        periodStart: String = "2026-09-01",
        periodEnd: String = "2026-09-07",
        content: String = "**Týden** v kostce 😊",
        createdAt: String = "2026-09-08T06:15:00+00:00",
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("period_start", periodStart)
        put("period_end", periodEnd)
        put("content", content)
        put("created_at", createdAt)
    }

    private fun jsonBody(vararg rows: JSONObject): HttpResponse {
        val body = JSONArray()
        rows.forEach { body.put(it) }
        return HttpResponse(200, body.toString())
    }

    /** Decoded into a map, so the assertion is about the query PostgREST receives. */
    private fun query(request: HttpRequest): Map<String, String> =
        URLDecoder.decode(request.url.substringAfter('?'), Charsets.UTF_8.name())
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair -> pair.substringBefore('=') to pair.substringAfter('=') }

    private companion object {
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
        const val REPORT_ID = "11111111-1111-1111-1111-111111111111"
        const val PREVIOUS_ID = "22222222-2222-2222-2222-222222222222"
        const val NEW_ID = "33333333-3333-3333-3333-333333333333"
    }
}
