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
 * The export read: *all* of the user's entries, under their own token, in order.
 *
 * Two things are being pinned here. First, the paging contract — every request carries
 * an explicit `limit` (PostgREST caps one response at 1000 rows, so a select without one
 * is silently truncated) and the loop keeps asking with a growing `offset` until a short
 * page comes back, which is the difference between "exports the diary" and "exports the
 * oldest 1000 days". Second, that the rows land in the export shape unchanged: an empty
 * array is not the same as no data, a blank slot is kept, and a JSON null is a JSON null.
 *
 * The transport is faked, so no network; the session is a real Robolectric-backed
 * [SessionStore], so the request asserted here is the one the app would send.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExportRepositoryPagingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sent = mutableListOf<HttpRequest>()

    /** Responses in call order; the last one repeats, so "always this" needs one entry. */
    private var responses: List<HttpResponse> = listOf(HttpResponse(200, "[]"))
    private var failure: IOException? = null

    private val transport = HttpTransport { request ->
        val call = sent.size
        sent += request
        failure?.let { throw it }
        responses[minOf(call, responses.size - 1)]
    }

    @Test
    fun `a short first page ends the export after one request`() = runTest {
        responses = listOf(page(3, mood = 3))

        val entries = repository().loadAllEntries().getOrThrow()

        assertEquals(3, entries.size)
        assertEquals(1, sent.size)

        val request = sent.single()
        assertEquals("GET", request.method)
        assertTrue(request.url.startsWith("$BASE_URL/entries?"))
        val query = query(request)
        assertTrue(query.contains("user_id=eq.$USER_ID"))
        assertTrue(query.contains("select=${ExportRepository.SELECT}"))
        assertTrue(query.contains("order=date.asc"))
        // The explicit limit is the whole point: no request may go out without one.
        assertTrue(query.contains("limit=${ExportRepository.PAGE_SIZE}"))
        assertTrue(query.contains("offset=0"))
        assertEquals("Bearer jwt-access-token", request.headers["Authorization"])
        assertEquals("anon-test-key", request.headers["apikey"])
    }

    @Test
    fun `a full page is followed by another request until a short page arrives`() = runTest {
        responses = listOf(
            page(count = ExportRepository.PAGE_SIZE, mood = 3),
            page(count = 5, mood = 5, date = "2026-01-02"),
        )

        val entries = repository().loadAllEntries().getOrThrow()

        assertEquals(ExportRepository.PAGE_SIZE + 5, entries.size)
        assertEquals(2, sent.size)
        assertTrue(query(sent[0]).contains("offset=0"))
        assertTrue(query(sent[1]).contains("offset=${ExportRepository.PAGE_SIZE}"))
        // Every request, not just the first, asks with an explicit limit.
        assertTrue(sent.all { query(it).contains("limit=${ExportRepository.PAGE_SIZE}") })
        // Oldest first, pages concatenated in the order they arrived.
        assertEquals("2026-01-01", entries.first().date)
        assertEquals("2026-01-02", entries.last().date)
    }

    @Test
    fun `an exactly full last page is followed by one more request and then stops`() = runTest {
        // A page that is exactly full cannot be told from "there is more", so the loop
        // must ask once more — and the empty answer must end it.
        responses = listOf(
            page(count = ExportRepository.PAGE_SIZE, mood = 4),
            page(count = 0, mood = 0),
        )

        val entries = repository().loadAllEntries().getOrThrow()

        assertEquals(ExportRepository.PAGE_SIZE, entries.size)
        assertEquals(2, sent.size)
    }

    @Test
    fun `an empty account is a successful empty export`() = runTest {
        responses = listOf(page(0, mood = 0))

        val result = repository().loadAllEntries()

        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().size)
        assertEquals(1, sent.size)
    }

    @Test
    fun `a server that ignores offset cannot make the export loop forever`() = runTest {
        // Small pages, always full: the safety bound must cut it off.
        responses = listOf(page(2, mood = 2))

        val entries = repository(pageSize = 2).loadAllEntries().getOrThrow()

        assertEquals(ExportRepository.MAX_PAGES, sent.size)
        assertEquals(ExportRepository.MAX_PAGES * 2, entries.size)
    }

    @Test
    fun `a bounded export carries the inclusive date range the web endpoint takes`() = runTest {
        responses = listOf(page(1, mood = 1))

        repository().loadAllEntries(from = "2026-01-01", to = "2026-12-31").getOrThrow()

        assertTrue(
            query(sent.single())
                .contains("and=(date.gte.2026-01-01,date.lte.2026-12-31)"),
        )
    }

    @Test
    fun `an open-ended export carries only the lower bound`() = runTest {
        responses = listOf(page(1, mood = 1))

        repository().loadAllEntries(from = "2026-01-01").getOrThrow()

        assertTrue(query(sent.single()).contains("and=(date.gte.2026-01-01)"))
    }

    @Test
    fun `an unbounded export sends no date filter at all`() = runTest {
        responses = listOf(page(1, mood = 1))

        repository().loadAllEntries().getOrThrow()

        assertFalse(query(sent.single()).contains("and=("))
        assertFalse(query(sent.single()).contains("date.gte"))
    }

    @Test
    fun `rows land in the export shape exactly as the database holds them`() = runTest {
        responses = listOf(
            HttpResponse(
                200,
                JSONArray().put(
                    JSONObject()
                        .put("date", "2026-09-12")
                        .put("mood", 4)
                        .put("mood_emoji", "🙂")
                        .put("sleep_quality", 3)
                        .put("stress", 2)
                        .put("activities", JSONArray().put("práce").put(" "))
                        .put("habits", JSONObject().put("reading", true).put("run", false))
                        .put("gratitude", JSONArray().put("káva").put(JSONObject.NULL))
                        .put("note", " ")
                        .put("scale_values", JSONObject().put("energy", 4)),
                ).toString(),
            ),
        )

        val entry = repository().loadAllEntries().getOrThrow().single()

        assertEquals("2026-09-12", entry.date)
        assertEquals(4, entry.mood)
        assertEquals("🙂", entry.moodEmoji)
        assertEquals(3, entry.sleepQuality)
        assertEquals(2, entry.stress)
        // A blank slot is kept (the form model would have dropped it); a JSON null stays a null.
        assertEquals(listOf<Any?>("práce", " "), entry.activities)
        assertEquals(listOf<Any?>("káva", null), entry.gratitude)
        assertEquals(mapOf<String, Any?>("reading" to true, "run" to false), entry.habits)
        assertEquals(mapOf<String, Any?>("energy" to 4), entry.scaleValues)
        // Not trimmed: the web writes `String(val)`, so one space stays one space.
        assertEquals(" ", entry.note)
    }

    @Test
    fun `a missing column and a null column both become an empty field, an empty array does not`() = runTest {
        responses = listOf(
            HttpResponse(
                200,
                JSONArray()
                    .put(JSONObject().put("date", "2026-01-01"))
                    .put(
                        JSONObject()
                            .put("date", "2026-01-02")
                            .put("mood", JSONObject.NULL)
                            .put("activities", JSONArray())
                            .put("habits", JSONObject()),
                    )
                    .toString(),
            ),
        )

        val entries = repository().loadAllEntries().getOrThrow()

        assertNull(entries[0].mood)
        assertNull(entries[0].activities)
        assertNull(entries[1].mood)
        // An empty array/object is data: the mobile export must write `[]`/`{}`, not "".
        assertEquals(emptyList<Any?>(), entries[1].activities)
        assertEquals(emptyMap<String, Any?>(), entries[1].habits)
    }

    @Test
    fun `a server error becomes a Czech sentence carrying the status`() = runTest {
        responses = listOf(HttpResponse(503, ""))

        val result = repository().loadAllEntries()

        assertTrue(result.isFailure)
        assertEquals("Načtení zápisů se nezdařilo (503)", result.exceptionOrNull()?.message)
    }

    @Test
    fun `an expired session surfaces the server's own message`() = runTest {
        responses = listOf(HttpResponse(401, """{"message":"JWT expired"}"""))

        assertEquals("JWT expired", repository().loadAllEntries().exceptionOrNull()?.message)
    }

    @Test
    fun `a broken response body is a failure, not an empty export`() = runTest {
        responses = listOf(HttpResponse(200, "<html>502 Bad Gateway</html>"))

        val result = repository().loadAllEntries()

        assertTrue(result.isFailure)
        assertEquals("Server vrátil neplatnou odpověď.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a flaky network is reported in Czech`() = runTest {
        failure = IOException("no route to host")

        val result = repository().loadAllEntries()

        assertTrue(result.isFailure)
        assertEquals("Nepodařilo se připojit k serveru.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a signed-out export never hits the network`() = runTest {
        val result = repository(SessionStore(context).apply { clear() }).loadAllEntries()

        assertEquals(
            "Přihlášení vypršelo, přihlas se znovu.",
            result.exceptionOrNull()?.message,
        )
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the export request carries no server key`() = runTest {
        responses = listOf(page(1, mood = 1))

        repository().loadAllEntries().getOrThrow()

        // The web endpoint reads with the service_role key; the app never can, so the
        // export goes through RLS with the user's own JWT and the public anon key.
        val flat = sent.single().let { "${it.url} ${it.headers} ${it.body}" }.lowercase()
        assertFalse(flat.contains("service_role"))
        assertFalse(flat.contains("sb_secret"))
        assertFalse(flat.contains("deepseek"))
    }

    private fun repository(
        store: SessionStore = signedInStore(),
        pageSize: Int = ExportRepository.PAGE_SIZE,
    ): ExportRepository {
        val client = SupabaseClient(
            sessionStore = store,
            transport = transport,
            baseUrl = BASE_URL,
            anonKey = "anon-test-key",
        )
        return ExportRepository(client, SessionContext(store), pageSize)
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

    /** A PostgREST page of [count] rows, each carrying [mood] in the `mood` column. */
    private fun page(count: Int, mood: Int, date: String = "2026-01-01"): HttpResponse {
        val body = JSONArray()
        for (index in 0 until count) {
            body.put(JSONObject().put("date", date).put("mood", mood))
        }
        return HttpResponse(200, body.toString())
    }

    /** Decoded, so the assertion is about the filter PostgREST receives. */
    private fun query(request: HttpRequest): String =
        URLDecoder.decode(request.url.substringAfter('?'), Charsets.UTF_8.name())

    private companion object {
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
    }
}
