package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.BuildConfig
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
 * The `/api/ai/reflect` contract — no network, no DeepSeek key.
 *
 * The transport is faked, so the request the app would send is asserted field by
 * field, and the token comes from a real (Robolectric-backed) [SessionStore] —
 * the same path the check-in screen drives.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AiReflectionRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sent = mutableListOf<HttpRequest>()
    private var response: HttpResponse = HttpResponse(200, """{"reflection":"Dnes sis to užil."}""")
    private var failure: IOException? = null

    private val transport = HttpTransport { request ->
        sent += request
        failure?.let { throw it }
        response
    }

    private fun repository(store: SessionStore?): AiReflectionRepository = AiReflectionRepository(
        sessionStore = store,
        transport = transport,
        endpoint = BuildConfig.AI_REFLECT_URL,
    )

    private fun repository() = repository(signedInStore())

    @Test
    fun `sends the day as snake case json with the session bearer token`() = runTest {
        val entry = DiaryEntry(
            mood = 5,
            moodEmoji = "😄",
            sleepQuality = 2,
            stress = 4,
            activities = listOf("🏋️ Cvičení"),
            habits = mapOf("alkohol" to false),
            gratitude = listOf("Rodina", "", "Práce"),
            note = "Dobrý den",
            weather = listOf("Slunečno"),
            scaleValues = mapOf("energy" to 4),
            phoneScreenTime = 95,
        )

        val result = repository().generate("2026-09-10", entry, userName = "Vojta")

        assertEquals("Dnes sis to užil.", result.getOrNull())

        val request = sent.single()
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/api/ai/reflect"))
        assertEquals("Bearer jwt-access-token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])

        val body = JSONObject(request.body!!)
        assertEquals(USER_ID, body.getString("user_id"))
        assertEquals("2026-09-10", body.getString("date"))
        assertEquals("cs", body.getString("lang"))
        assertEquals("Vojta", body.getString("userName"))
        assertEquals(5, body.getInt("mood"))
        assertEquals("😄", body.getString("mood_emoji"))
        assertEquals(2, body.getInt("sleep_quality"))
        assertEquals(4, body.getInt("stress"))
        assertEquals("🏋️ Cvičení", body.getJSONArray("activities").getString(0))
        assertFalse(body.getJSONObject("habits").getBoolean("alkohol"))
        // Blank slots are dropped, exactly like the web's `gratitude.filter(Boolean)`.
        assertEquals(2, body.getJSONArray("gratitude").length())
        assertEquals("Rodina", body.getJSONArray("gratitude").getString(0))
        assertEquals("Práce", body.getJSONArray("gratitude").getString(1))
        assertEquals("Dobrý den", body.getString("note"))
        assertEquals("Slunečno", body.getJSONArray("weather").getString(0))
        assertEquals(4, body.getJSONObject("scale_values").getInt("energy"))
        assertEquals(95, body.getInt("phone_screen_time"))
    }

    @Test
    fun `the request never carries a server key`() = runTest {
        repository().generate(DATE, DiaryEntry(mood = 3))

        // The DeepSeek key and the service_role key stay on the server; the app
        // authenticates with the user's JWT alone, exactly like the browser does.
        val flat = sent.single().let { it.url + it.headers + it.body }
        assertFalse(flat.contains("apikey"))
        assertFalse(flat.contains("service_role"))
        assertFalse(flat.lowercase().contains("deepseek"))
    }

    @Test
    fun `a cached reflection is returned as the text`() = runTest {
        response = HttpResponse(200, """{"reflection":"Včera dobrý, dnes lepší.","cached":true}""")

        assertEquals("Včera dobrý, dnes lepší.", repository().generate(DATE, DiaryEntry()).getOrNull())
    }

    @Test
    fun `a blank reflection is a failure, not an empty card`() = runTest {
        response = HttpResponse(200, """{"reflection":"   "}""")

        assertEquals(
            AiReflectionRepository.MESSAGE_EMPTY,
            repository().generate(DATE, DiaryEntry()).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `401 asks the user to sign in again`() = runTest {
        response = HttpResponse(401, """{"error":"Unauthorized"}""")

        assertEquals(
            AiReflectionRepository.MESSAGE_SIGNED_OUT,
            repository().generate(DATE, DiaryEntry()).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `403 explains the account whitelist`() = runTest {
        response = HttpResponse(403, """{"error":"AI features are restricted to authorized users"}""")

        assertEquals(
            AiReflectionRepository.MESSAGE_RESTRICTED,
            repository().generate(DATE, DiaryEntry()).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `429 passes the server message through`() = runTest {
        response = HttpResponse(
            429,
            """{"error":"Regeneration cooldown active. Try again in 12 minute(s)."}""",
        )

        assertEquals(
            "Regeneration cooldown active. Try again in 12 minute(s).",
            repository().generate(DATE, DiaryEntry()).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `429 without a body falls back to the Czech cooldown line`() = runTest {
        response = HttpResponse(429, "")

        assertEquals(
            AiReflectionRepository.MESSAGE_COOLDOWN,
            repository().generate(DATE, DiaryEntry()).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `502 says the AI is unavailable`() = runTest {
        response = HttpResponse(502, """{"error":"AI failed"}""")

        assertEquals(
            AiReflectionRepository.MESSAGE_AI_FAILED,
            repository().generate(DATE, DiaryEntry()).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `an unexpected status keeps the code for support`() = runTest {
        response = HttpResponse(500, """{"error":"Internal error"}""")

        assertEquals(
            "Reflexi se nepodařilo vygenerovat (chyba 500).",
            repository().generate(DATE, DiaryEntry()).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `a flaky network is reported in Czech`() = runTest {
        failure = IOException("no route to host")

        assertEquals(
            AiReflectionRepository.MESSAGE_CONNECT,
            repository().generate(DATE, DiaryEntry()).exceptionOrNull()?.message,
        )
    }

    @Test
    fun `a signed-out app never calls the endpoint`() = runTest {
        val result = repository(signedOutStore()).generate(DATE, DiaryEntry())

        assertEquals(AiReflectionRepository.MESSAGE_SIGNED_OUT, result.exceptionOrNull()?.message)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the name is sent only when the session knows it`() = runTest {
        repository(SessionStore(context).withMetaName(null)).generate(DATE, DiaryEntry())
        assertFalse(JSONObject(sent.single().body!!).has("userName"))

        sent.clear()
        repository().generate(DATE, DiaryEntry(), userName = null)
        assertFalse(JSONObject(sent.single().body!!).has("userName"))
    }

    @Test
    fun `the display name comes from the session metadata, never invented`() {
        assertEquals("Vojta", repository().userName())
        assertNull(repository(signedOutStore()).userName())
    }

    @Test
    fun `an un-synced screen time is left out of the fallback data`() = runTest {
        repository().generate(DATE, DiaryEntry(mood = 3))

        assertFalse(JSONObject(sent.single().body!!).has("phone_screen_time"))
    }

    private fun signedInStore(): SessionStore = SessionStore(context).withMetaName("Vojta")

    /** No session at all — the app before login, or right after a logout. */
    private fun signedOutStore(): SessionStore = SessionStore(context).apply { clear() }

    /** A signed-in session; [name] null means the metadata carries no `full_name`. */
    private fun SessionStore.withMetaName(name: String?): SessionStore = apply {
        save(
            JSONObject().apply {
                put("access_token", "jwt-access-token")
                put("refresh_token", "refresh-1")
                put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                put(
                    "user",
                    JSONObject().apply {
                        put("id", USER_ID)
                        if (name != null) {
                            put("user_metadata", JSONObject().put("full_name", name))
                        }
                    },
                )
            },
        )
    }

    private companion object {
        const val DATE = "2026-09-10"
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
    }
}
