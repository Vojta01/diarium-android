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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.time.LocalDate

/**
 * The whole on-device generation path, with both HTTP edges faked: the Supabase
 * side (entries/scales/habits reads + the `ai_reports` insert) and the DeepSeek
 * side. The tests pin the wire contract — what is read from where, what the
 * model receives, and exactly what row gets written — without a network or a
 * real key.
 *
 * The period windows are driven by an injected clock, so `today` is
 * 2026-10-12 in every test: weekly runs `2026-10-05..2026-10-12`, monthly runs
 * `2026-09-12..2026-10-12`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReportGeneratorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ── fake Supabase ───────────────────────────────────────────────────────

    private val supabaseRequests = mutableListOf<HttpRequest>()
    private var entriesJson = "[]"
    private var scalesJson = "[]"
    private var catalogJson = "[]"
    private var userHabitsJson = "[]"
    private var appSettingsJson = "[]"
    private var insertResponse = HttpResponse(201, "[]")

    private val supabaseTransport = HttpTransport { request ->
        supabaseRequests += request
        val url = request.url
        when {
            url == "$BASE_URL/ai_reports" -> insertResponse
            url.startsWith("$BASE_URL/entries?") -> HttpResponse(200, entriesJson)
            url.startsWith("$BASE_URL/scales?") -> HttpResponse(200, scalesJson)
            url.startsWith("$BASE_URL/habit_catalog?") -> HttpResponse(200, catalogJson)
            url.startsWith("$BASE_URL/user_habits?") -> HttpResponse(200, userHabitsJson)
            url.startsWith("$BASE_URL/app_settings?") -> HttpResponse(200, appSettingsJson)
            else -> HttpResponse(404, "[]")
        }
    }

    // ── fake DeepSeek ───────────────────────────────────────────────────────

    private val deepSeekRequests = mutableListOf<HttpRequest>()
    private var deepSeekPlan: List<HttpResponse> = emptyList()

    private val deepSeekTransport = HttpTransport { request ->
        deepSeekRequests += request
        deepSeekPlan.getOrNull(deepSeekRequests.size - 1)
            ?: HttpResponse(200, completion("Výchozí odpověď"))
    }

    // ── happy paths ─────────────────────────────────────────────────────────

    @Test
    fun `weekly generation reads the window, calls DeepSeek with the user's key and inserts the row`() = runTest {
        useRealisticFixtures()
        deepSeekPlan = listOf(HttpResponse(200, completion("Týdenní shrnutí.")))

        val outcome = generator().generate("weekly")

        assertEquals(ReportOutcome.Generated, outcome)

        // 1) The entries read is scoped to the weekly window (today-7d..today).
        val entriesQuery = decoded(supabaseRequests.single { it.url.startsWith("$BASE_URL/entries?") })
        assertTrue(entriesQuery.contains("user_id=eq.$USER_ID"))
        assertTrue(entriesQuery.contains("date=gte.2026-10-05"))
        assertTrue(entriesQuery.contains("order=date.asc"))

        // 2) The DeepSeek call carries the user's own key and the route-shaped body.
        val call = deepSeekRequests.single()
        assertEquals("POST", call.method)
        assertEquals(DEEPSEEK_URL, call.url)
        assertEquals("Bearer sk-test-key", call.headers["Authorization"])
        val body = JSONObject(call.body!!)
        assertEquals("deepseek-flash", body.getString("model"))
        assertEquals(3072, body.getInt("max_tokens"))
        assertEquals(0.7, body.getDouble("temperature"), 0.001)
        val messages = body.getJSONArray("messages")
        assertEquals(ReportPromptBuilder.WEEKLY_SYSTEM, messages.getJSONObject(0).getString("content"))
        val userPrompt = messages.getJSONObject(1).getString("content")
        assertTrue(userPrompt.startsWith("Data za období: 2 dní\n"))
        assertTrue(userPrompt.contains("Průměrná nálada: 4.5/5"))
        assertTrue(userPrompt.contains("Škály: Energie: průměr 3.5/5"))
        assertTrue(userPrompt.contains("Návyky: Alkohol: 1d OK / 1d NE; Spánek: 2d OK / 0d NE"))
        assertTrue(userPrompt.contains("─── Denní přehled ───"))

        // 3) The row is inserted with the user JWT and the full period.
        val insert = supabaseRequests.single { it.method == "POST" }
        assertEquals("$BASE_URL/ai_reports", insert.url)
        assertEquals("Bearer jwt-access-token", insert.headers["Authorization"])
        val row = JSONObject(insert.body!!)
        assertEquals(USER_ID, row.getString("user_id"))
        assertEquals("weekly", row.getString("type"))
        assertEquals("2026-10-05", row.getString("period_start"))
        assertEquals("2026-10-12", row.getString("period_end"))
        assertEquals("Týdenní shrnutí.", row.getString("content"))
    }

    @Test
    fun `monthly uses the 30-day window, the monthly system prompt and the larger budget`() = runTest {
        useRealisticFixtures()
        deepSeekPlan = listOf(HttpResponse(200, completion("Měsíční shrnutí.")))

        val outcome = generator().generate("monthly")

        assertEquals(ReportOutcome.Generated, outcome)
        val body = JSONObject(deepSeekRequests.single().body!!)
        assertEquals(4096, body.getInt("max_tokens"))
        assertEquals(
            ReportPromptBuilder.MONTHLY_SYSTEM,
            body.getJSONArray("messages").getJSONObject(0).getString("content"),
        )
        assertFalse(body.getJSONArray("messages").getJSONObject(1).getString("content").contains("Denní přehled"))

        val row = JSONObject(supabaseRequests.single { it.method == "POST" }.body!!)
        assertEquals("monthly", row.getString("type"))
        assertEquals("2026-09-12", row.getString("period_start"))
        assertEquals("2026-10-12", row.getString("period_end"))
    }

    @Test
    fun `the model comes from app_settings when the row is readable`() = runTest {
        useRealisticFixtures()
        appSettingsJson = JSONArray().put(JSONObject().put("value", "deepseek-v4-pro")).toString()
        deepSeekPlan = listOf(HttpResponse(200, completion("ok")))

        assertEquals(ReportOutcome.Generated, generator().generate("weekly"))

        val body = JSONObject(deepSeekRequests.single().body!!)
        assertEquals("deepseek-v4-pro", body.getString("model"))
    }

    @Test
    fun `an empty answer is retried and a later success is used`() = runTest {
        useRealisticFixtures()
        deepSeekPlan = listOf(
            HttpResponse(200, completion("")),
            HttpResponse(200, completion("")),
            HttpResponse(200, completion("Napotřetí.")),
        )

        assertEquals(ReportOutcome.Generated, generator().generate("weekly"))

        assertEquals(3, deepSeekRequests.size)
        val row = JSONObject(supabaseRequests.single { it.method == "POST" }.body!!)
        assertEquals("Napotřetí.", row.getString("content"))
    }

    // ── failure paths ───────────────────────────────────────────────────────

    @Test
    fun `empty through all three attempts fails without inserting`() = runTest {
        useRealisticFixtures()
        deepSeekPlan = List(3) { HttpResponse(200, completion("")) }

        val outcome = generator().generate("weekly")

        assertTrue(outcome is ReportOutcome.Failed)
        assertEquals(3, deepSeekRequests.size)
        assertFalse(supabaseRequests.any { it.method == "POST" })
    }

    @Test
    fun `a DeepSeek HTTP error fails without inserting`() = runTest {
        useRealisticFixtures()
        deepSeekPlan = listOf(HttpResponse(401, """{"error":"invalid key"}"""))

        val outcome = generator().generate("weekly")

        assertTrue(outcome is ReportOutcome.Failed)
        assertTrue("the status must survive into the reason", (outcome as ReportOutcome.Failed).reason.contains("401"))
        assertFalse(supabaseRequests.any { it.method == "POST" })
    }

    @Test
    fun `a failed insert is reported as a failure`() = runTest {
        useRealisticFixtures()
        insertResponse = HttpResponse(500, """{"message":"boom"}""")
        deepSeekPlan = listOf(HttpResponse(200, completion("Text.")))

        val outcome = generator().generate("weekly")

        assertTrue(outcome is ReportOutcome.Failed)
        assertTrue((outcome as ReportOutcome.Failed).reason.contains("500"))
    }

    @Test
    fun `fewer than two days never reaches DeepSeek`() = runTest {
        entriesJson = JSONArray().put(entryRow("2026-10-10", 4)).toString()

        val outcome = generator().generate("weekly")

        assertEquals(ReportOutcome.NotEnoughData, outcome)
        assertTrue(deepSeekRequests.isEmpty())
        assertFalse(supabaseRequests.any { it.method == "POST" })
    }

    @Test
    fun `without a key nothing is sent anywhere`() = runTest {
        val outcome = generator(keyStore = AiKeyStore(FakeSlot(null))).generate("weekly")

        assertEquals(ReportOutcome.NoKey, outcome)
        assertTrue(supabaseRequests.isEmpty())
        assertTrue(deepSeekRequests.isEmpty())
    }

    @Test
    fun `the API key never touches a Supabase request`() = runTest {
        useRealisticFixtures()
        deepSeekPlan = listOf(HttpResponse(200, completion("ok")))

        generator().generate("weekly")

        val flat = supabaseRequests.joinToString(" ") { "${it.url} ${it.headers} ${it.body}" }
        assertFalse("the key must not leak into the data-layer calls", flat.contains("sk-test-key"))
        // The DeepSeek call is the one and only place it travels.
        assertTrue(deepSeekRequests.single().headers.getValue("Authorization").contains("sk-test-key"))
    }

    // ── fixtures / helpers ──────────────────────────────────────────────────

    private fun useRealisticFixtures() {
        entriesJson = JSONArray()
            .put(
                entryRow("2026-10-05", 4)
                    .put("sleep_quality", 2)
                    .put("stress", 3)
                    .put("activities", JSONArray(listOf("práce", "sport")))
                    .put("habits", JSONObject().put("alkohol", false).put("spanek", true))
                    .put("gratitude", JSONArray(listOf("rodina")))
                    .put("note", " dobrý den ")
                    .put("phone_screen_time", 3600)
                    .put("scale_values", JSONObject().put("id-energie", 4)),
            )
            .put(
                entryRow("2026-10-06", 5)
                    .put("sleep_quality", 3)
                    .put("activities", JSONArray(listOf("sport")))
                    .put("habits", JSONObject().put("alkohol", true).put("spanek", true))
                    .put("scale_values", JSONObject().put("id-energie", 3).put("id-produkt", 2)),
            )
            .toString()
        scalesJson = JSONArray().put(JSONObject().put("id", "id-energie").put("name", "Energie")).toString()
        catalogJson = JSONArray()
            .put(JSONObject().put("key", "alkohol").put("label", "Alkohol").put("is_negative", true))
            .put(JSONObject().put("key", "spanek").put("label", "Spánek").put("is_negative", false))
            .toString()
        userHabitsJson = "[]"
        appSettingsJson = "[]"
        insertResponse = HttpResponse(201, "[]")
    }

    private fun entryRow(date: String, mood: Int): JSONObject = JSONObject()
        .put("date", date)
        .put("mood", mood)

    /** `choices[0].message.content` — the shape DeepSeek answers with. */
    private fun completion(content: String): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content))))
        .toString()

    private fun generator(
        keyStore: AiKeyStore = AiKeyStore(FakeSlot("sk-test-key")),
        today: LocalDate = LocalDate.of(2026, 10, 12),
    ): ReportGenerator = ReportGenerator(
        client = SupabaseClient(
            sessionStore = signedInStore(),
            transport = supabaseTransport,
            baseUrl = BASE_URL,
            anonKey = "anon-test-key",
        ),
        keyStore = keyStore,
        session = SessionContext(signedInStore()),
        deepSeek = deepSeekTransport,
        deepSeekUrl = DEEPSEEK_URL,
        today = { today },
        retryDelayMs = 0,
    )

    /** In-memory slot so the generator test needs no Keystore. */
    private class FakeSlot(private var value: String?) : SecretSlot {
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }

        override fun erase() {
            value = null
        }
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

    private fun decoded(request: HttpRequest): String =
        URLDecoder.decode(request.url.substringAfter('?'), Charsets.UTF_8.name())

    private companion object {
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
        const val DEEPSEEK_URL = "https://deepseek.test/chat/completions"
    }
}
