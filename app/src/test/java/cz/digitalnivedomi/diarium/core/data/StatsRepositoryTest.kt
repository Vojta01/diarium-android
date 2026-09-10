package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.stats.StatsMath
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
 * The statistics read: the 400-day range it asks for, under whose token, what a
 * failure does to the screen, and that the rows it returns are the input [StatsMath]
 * expects.
 *
 * The transport is faked (no network) and the session is a real Robolectric-backed
 * [SessionStore], so the request asserted here is the one the app would send. The
 * arithmetic itself lives in [cz.digitalnivedomi.diarium.core.stats.StatsMathTest] —
 * this file only covers the boundary: the range, the filters, the token, and that an
 * error is an error and never an empty statistics screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StatsRepositoryTest {

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
    fun `the statistics read covers four hundred days ending today in one request`() = runTest {
        val result = repository().load(TODAY)

        assertTrue(result.isSuccess)
        assertEquals(1, sent.size)   // one round-trip for every range

        val request = sent.single()
        assertEquals("GET", request.method)
        assertTrue(request.url.startsWith("$BASE_URL/entries?"))

        // Decoded, so the assertion is about the filter PostgREST receives and not
        // about which characters the encoder happens to escape.
        val query = URLDecoder.decode(request.url.substringAfter('?'), Charsets.UTF_8.name())
        assertTrue(query.contains("user_id=eq.$USER_ID"))
        // 400 days ending 2026-09-10: the left edge is today − 399 days = 2025-08-07.
        assertTrue(query.contains("and=(date.gte.2025-08-07,date.lte.2026-09-10)"))
        assertTrue(query.contains("select=*"))
        assertTrue(query.contains("order=date.desc"))   // week-last, like the web
        assertTrue(query.contains("limit=${StatsRepository.LOAD_LIMIT}"))
    }

    @Test
    fun `the read carries the user's own JWT and the anon key, never a server key`() = runTest {
        repository().load(TODAY)

        val request = sent.single()
        assertEquals("Bearer jwt-access-token", request.headers["Authorization"])
        assertEquals("anon-test-key", request.headers["apikey"])

        // The service_role key and any AI key stay on the server; the app reads its own
        // rows with the anon key plus the user's JWT, exactly like the browser does.
        val flat = "${request.url} ${request.headers} ${request.body}".lowercase()
        assertFalse(flat.contains("service_role"))
        assertFalse(flat.contains("sb_secret"))
        assertFalse(flat.contains("deepseek"))
    }

    @Test
    fun `a server error renders as a Czech failure, never as a zeroed statistics screen`() = runTest {
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
    fun `an expired session surfaces the server's own message`() = runTest {
        response = HttpResponse(401, """{"message":"JWT expired"}""")

        assertEquals("JWT expired", repository().load(TODAY).exceptionOrNull()?.message)
    }

    @Test
    fun `a broken response body is a failure, not an empty statistics screen`() = runTest {
        response = HttpResponse(200, "<html>502 Bad Gateway</html>")

        assertTrue(repository().load(TODAY).isFailure)
    }

    @Test
    fun `a flaky network fails with the app's Czech connect sentence`() = runTest {
        failure = IOException("no route to host")

        val result = repository().load(TODAY)

        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
        // Czech, not the transport's own English sentence: the screen shows this text.
        assertEquals("Nepodařilo se připojit k serveru.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a signed-out statistics read never hits the network`() = runTest {
        val result = repository(SessionStore(context).apply { clear() }).load(TODAY)

        assertEquals(
            "Přihlášení vypršelo, přihlas se znovu.",
            result.exceptionOrNull()?.message,
        )
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an empty range is a valid empty window, not a failure`() = runTest {
        val result = repository().load(TODAY)

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(TODAY, data.today)
        assertTrue(data.days.isEmpty())
        // Nothing to average is null, never a zero.
        assertNull(StatsMath.averageMood(data.days))
    }

    @Test
    fun `the rows are mapped into the days the statistics maths reads`() = runTest {
        response = HttpResponse(200, rows().toString())

        val data = repository().load(TODAY).getOrThrow()

        assertEquals(TODAY, data.today)
        // The server sends date.desc; the maths wants an unordered set, so the days
        // come back oldest first whatever order the rows arrived in.
        assertEquals(
            listOf("2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10"),
            data.days.map { it.date },
        )

        // The mood-0 row is "not answered": (1 + 3 + 5) / 3 = 3.0, not 9 / 4.
        assertEquals(3.0, StatsMath.averageMood(data.days)!!, 1e-9)
        assertEquals(3, StatsMath.answeredDays(data.days))
        assertEquals(
            mapOf(1 to 1, 2 to 0, 3 to 1, 4 to 0, 5 to 1),
            StatsMath.moodDistribution(data.days),
        )

        // 2026-09-09 (3600 s) and 2026-09-10 (6000 s) reported screen time.
        val summary = StatsMath.screenTimeSummary(StatsMath.screenTimeWindow(data.days, 7, TODAY))
        assertEquals(9600, summary.totalSeconds)                  // 3600 + 6000
        assertEquals(4800.0, summary.averageSeconds!!, 1e-9)      // 9600 / 2
        assertEquals(6000, summary.maxSeconds)
        assertEquals("2026-09-10", summary.maxDate)

        // Instagram only: 42 min × 60 = 2520 s of the 6000 s captured on 09-10.
        val apps = StatsMath.topApps(data.days)!!
        assertEquals("Instagram", apps.apps.single().name)
        assertEquals(2520, apps.apps.single().seconds)
        assertEquals(6000, apps.totalSeconds)
        assertEquals(3480, apps.otherSeconds)                     // 6000 − 2520
    }

    @Test
    fun `a duplicate date keeps the row with the most screen time`() = runTest {
        response = HttpResponse(
            200,
            JSONArray()
                .put(JSONObject().put("date", "2026-09-10").put("mood", 5).put("phone_screen_time", 6000))
                .put(JSONObject().put("date", "2026-09-10").put("mood", 1).put("phone_screen_time", 1200))
                .toString(),
        )

        val data = repository().load(TODAY).getOrThrow()

        // 1200 is not > 6000, so the first row wins — one day, never two bars.
        assertEquals(1, data.days.size)
        assertEquals(5, data.days.single().mood)
        assertEquals(6000, data.days.single().screenTimeSeconds)
    }

    @Test
    fun `a day that never synced keeps null screen time, not zero`() = runTest {
        response = HttpResponse(
            200,
            JSONArray().put(JSONObject().put("date", "2026-09-10").put("mood", 4)).toString(),
        )

        val data = repository().load(TODAY).getOrThrow()

        val day = data.days.single()
        assertNull(day.screenTimeSeconds)
        assertNull(day.unlocks)
        assertTrue(day.topApps.isEmpty())
        // A read with no screen time has no screen-time chart at all.
        assertTrue(StatsMath.screenTimeWindow(data.days, 7).isEmpty())
    }

    private fun repository(store: SessionStore = signedInStore()): StatsRepository {
        val client = SupabaseClient(
            sessionStore = store,
            transport = transport,
            baseUrl = BASE_URL,
            anonKey = "anon-test-key",
        )
        return StatsRepository(EntriesRepository(client, SessionContext(store)))
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

    /**
     * Four days `date.desc`: a mood-0 "not answered" day, screen time and top apps on
     * the newest day. One of them is unanswered on purpose, so the mapping is checked
     * against anything that might treat 0 as a real mood.
     */
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
        .put(JSONObject().put("date", "2026-09-07").put("mood", 0))

    private companion object {
        const val TODAY = "2026-09-10"
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
    }
}
