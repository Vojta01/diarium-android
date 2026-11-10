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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The seed's repository contract: additive only, and never executed on a failed
 * read. The pure plan is covered by [ScalesRepositoryTest]; this pins the actual
 * requests — no DELETE may ever be sent by "Výchozí".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScalesRepositorySeedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<HttpRequest>()
    private var respond: (HttpRequest) -> HttpResponse = { HttpResponse(200, "[]") }
    private val transport = HttpTransport { request ->
        sent += request
        respond(request)
    }

    private lateinit var sessionStore: SessionStore

    @Before
    fun setUp() {
        sessionStore = SessionStore(context).apply {
            save(
                JSONObject().apply {
                    put("access_token", "jwt-access-token")
                    put("refresh_token", "refresh-1")
                    put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                    put("user", JSONObject().put("id", USER_ID))
                },
            )
        }
    }

    @Test
    fun `a failed list aborts before any write`() = runTest {
        respond = { HttpResponse(500, """{"message":"boom"}""") }

        val seeded = repository().seedDefaults()

        assertFalse(seeded)
        assertTrue(
            "a read failure must never be treated as an empty account",
            sent.none { it.method == "POST" || it.method == "DELETE" || it.method == "PATCH" },
        )
        assertEquals(1, sent.count { it.method == "GET" })
    }

    @Test
    fun `an unreachable server aborts before any write`() = runTest {
        respond = { throw java.io.IOException("offline") }

        assertFalse(repository().seedDefaults())
        assertTrue(sent.none { it.method == "POST" || it.method == "DELETE" || it.method == "PATCH" })
    }

    @Test
    fun `seeding existing defaults writes nothing and deletes nothing`() = runTest {
        respond = { HttpResponse(200, rows(energie(), produktivita()).toString()) }

        assertTrue(repository().seedDefaults())
        assertEquals(1, sent.size)
        assertEquals("GET", sent.single().method)
    }

    @Test
    fun `a custom scale is never deleted or overwritten`() = runTest {
        respond = { request ->
            if (request.method == "GET") {
                HttpResponse(200, rows(energie(), produktivita(), custom()).toString())
            } else {
                HttpResponse(201, "")
            }
        }

        assertTrue(repository().seedDefaults())

        assertTrue(sent.none { it.method == "DELETE" })
        assertTrue(sent.none { it.method == "PATCH" })
        assertTrue(sent.none { it.method == "POST" })
    }

    @Test
    fun `only the missing default is created`() = runTest {
        respond = { request ->
            if (request.method == "GET") {
                HttpResponse(200, rows(energie(), custom()).toString())
            } else {
                HttpResponse(201, "")
            }
        }

        assertTrue(repository().seedDefaults())

        val posts = sent.filter { it.method == "POST" }
        assertEquals(1, posts.size)
        assertEquals("Produktivita", JSONObject(posts.single().body!!).getString("name"))
        assertTrue(sent.none { it.method == "DELETE" })
    }

    @Test
    fun `a drifted default is left as the user configured it`() = runTest {
        respond = { request ->
            if (request.method == "GET") {
                HttpResponse(
                    200,
                    rows(
                        JSONObject()
                            .put("id", "e1")
                            .put("name", "Energie")
                            .put("min_value", 1)
                            .put("max_value", 10),
                        produktivita(),
                    ).toString(),
                )
            } else {
                HttpResponse(201, "")
            }
        }

        assertTrue(repository().seedDefaults())

        assertTrue("deleting it would cascade every stored value away", sent.none { it.method == "DELETE" })
        assertTrue(sent.none { it.method == "POST" })
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun rows(vararg scales: JSONObject): JSONArray = JSONArray().apply {
        scales.forEach { put(it) }
    }

    private fun energie() = JSONObject()
        .put("id", "e1")
        .put("name", "Energie")
        .put("min_value", 1)
        .put("max_value", 5)

    private fun produktivita() = JSONObject()
        .put("id", "p1")
        .put("name", "Produktivita")
        .put("min_value", 1)
        .put("max_value", 5)

    private fun custom() = JSONObject()
        .put("id", "x1")
        .put("name", "Nálada")
        .put("min_value", 1)
        .put("max_value", 5)

    private fun repository(): ScalesRepository = ScalesRepository(
        client = SupabaseClient(
            sessionStore = sessionStore,
            transport = transport,
            baseUrl = BASE_URL,
            anonKey = "anon-test-key",
        ),
        session = SessionContext(sessionStore),
    )

    private companion object {
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
    }
}
