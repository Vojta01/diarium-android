package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.stats.DayAppUsage
import cz.digitalnivedomi.diarium.core.stats.DayUsage
import cz.digitalnivedomi.diarium.stats.UsageStatsSource
import cz.digitalnivedomi.diarium.sync.UsageSyncConsentStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.util.Base64

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UsageSyncConditionalWriteTest {
    private lateinit var context: Context
    private lateinit var session: SessionStore
    private lateinit var consent: UsageSyncConsentStore
    private val requests = mutableListOf<HttpRequest>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf(
            "diarium_session",
            UsageSyncConsentStore.PREFS_NAME,
            "diarium_notifications",
            UsageSyncRequestStore.PREFS_NAME,
        ).forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        requests.clear()
        session = SessionStore(context)
        consent = UsageSyncConsentStore(context)
        session.save(sessionJson(OWNER))
        consent.setEnabledFor(OWNER, true)
    }

    @Test
    fun `existing diary row is updated with conditional phone-only payload`() = runBlocking {
        val diaryRow = JSONObject()
            .put("id", ROW_ID)
            .put("revision", 7)
            .put("mood", 4)
            .put("note", "saved diary note")
            .put("photo_path", "https://example.test/photo.jpg")
            .put("activities", JSONArray().put("walk"))
            .put("scale_values", JSONObject().put("energy", 3))
            .put("ai_reflection", "generated reflection")
        val repository = repository { request ->
            requests += request
            when {
                request.method == "GET" && request.url.contains("/entries?") -> HttpResponse(
                    200,
                    JSONArray().put(
                        JSONObject().put("id", diaryRow.getString("id")).put("revision", diaryRow.getLong("revision")),
                    ).toString(),
                )
                request.url.endsWith("/rpc/save_daily_entry_v2") -> {
                    val payload = JSONObject(request.body!!).getJSONObject("p_payload")
                    payload.keys().forEach { key -> diaryRow.put(key, payload.get(key)) }
                    diaryRow.put("revision", 8)
                    HttpResponse(
                        200,
                        JSONObject()
                            .put("status", "saved")
                            .put("entry_id", ROW_ID)
                            .put("revision", 8)
                            .put("replayed", false)
                            .toString(),
                    )
                }
                else -> HttpResponse(200, "{}")
            }
        }

        assertEquals(UsagePushOutcome.SENT, repository.pushDay(OWNER, DATE))

        val read = requests.single { it.method == "GET" && it.url.contains("/entries?") }
        val query = read.url.substringAfter('?').split('&').associate { component ->
            val parts = component.split('=', limit = 2)
            URLDecoder.decode(parts[0], Charsets.UTF_8.name()) to
                URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
        }
        assertEquals("eq.$OWNER", query["user_id"])
        assertEquals("eq.$DATE", query["date"])
        assertEquals("id,revision", query["select"])
        assertEquals("2", query["limit"])

        val write = requests.single { it.method == "POST" && it.url.endsWith("/rpc/save_daily_entry_v2") }
        assertEquals("Bearer ${jwt(OWNER)}", write.headers["Authorization"])
        val body = JSONObject(write.body!!)
        assertEquals(setOf("p_payload", "p_expected_exists", "p_expected_id", "p_expected_revision", "p_request_id"), body.keys().asSequence().toSet())
        assertTrue(body.getBoolean("p_expected_exists"))
        assertEquals(ROW_ID, body.getString("p_expected_id"))
        assertEquals(7L, body.getLong("p_expected_revision"))
        assertTrue(body.getString("p_request_id").matches(Regex("[0-9a-fA-F-]{36}")))
        val payload = body.getJSONObject("p_payload")
        assertEquals(
            setOf("user_id", "date", "phone_screen_time", "phone_unlocks", "phone_top_apps"),
            payload.keys().asSequence().toSet(),
        )
        assertEquals(OWNER, payload.getString("user_id"))
        assertEquals(DATE, payload.getString("date"))
        assertEquals(600L, payload.getLong("phone_screen_time"))
        assertEquals(3L, payload.getLong("phone_unlocks"))
        assertEquals("Example", payload.getJSONArray("phone_top_apps").getJSONObject(0).getString("app"))
        assertEquals(2.0, payload.getJSONArray("phone_top_apps").getJSONObject(0).getDouble("minutes"), 0.0)
        assertEquals(4, diaryRow.getInt("mood"))
        assertEquals("saved diary note", diaryRow.getString("note"))
        assertEquals("https://example.test/photo.jpg", diaryRow.getString("photo_path"))
        assertEquals("walk", diaryRow.getJSONArray("activities").getString(0))
        assertEquals(3, diaryRow.getJSONObject("scale_values").getInt("energy"))
        assertEquals("generated reflection", diaryRow.getString("ai_reflection"))
        assertFalse(requests.any { it.url.endsWith("/rpc/save_daily_entry") })
    }

    @Test
    fun `missing diary row is never created by screen-time sync`() = runBlocking {
        val repository = repository { request ->
            requests += request
            if (request.method == "GET" && request.url.contains("/entries?")) {
                HttpResponse(200, "[]")
            } else {
                HttpResponse(200, "{}")
            }
        }

        repository.pushDay(OWNER, DATE)

        assertTrue("a partial phone update must not create a blank diary entry", requests.none { it.method == "POST" })
    }

    @Test
    fun `consent withdrawal after baseline read stops before the conditional write`() = runBlocking {
        val repository = repository { request ->
            requests += request
            if (request.method == "GET" && request.url.contains("/entries?")) {
                consent.setEnabledFor(OWNER, false)
                HttpResponse(200, JSONArray().put(JSONObject().put("id", ROW_ID).put("revision", 7)).toString())
            } else {
                HttpResponse(200, "{}")
            }
        }

        assertEquals(UsagePushOutcome.CONSENT_DISABLED, repository.pushDay(OWNER, DATE))
        assertTrue("revoked consent must stop before every later network operation", requests.none { it.method == "POST" })
    }

    @Test
    fun `owner switch after baseline read cannot send the old owner's phone data`() = runBlocking {
        val repository = repository { request ->
            requests += request
            if (request.method == "GET" && request.url.contains("/entries?")) {
                session.save(sessionJson(OTHER_OWNER))
                HttpResponse(200, JSONArray().put(JSONObject().put("id", ROW_ID).put("revision", 7)).toString())
            } else {
                HttpResponse(200, "{}")
            }
        }

        assertEquals(UsagePushOutcome.OWNER_CHANGED, repository.pushDay(OWNER, DATE))
        assertTrue("an account replacement must stop before the conditional write", requests.none { it.method == "POST" })
        assertTrue(requests.all { it.headers["Authorization"] != "Bearer ${jwt(OTHER_OWNER)}" })
    }

    @Test
    fun `a conflict rereads the row and retries the same phone snapshot with a new request id`() = runBlocking {
        var rowRevision = 7L
        var writes = 0
        val repository = repository { request ->
            requests += request
            when {
                request.method == "GET" && request.url.contains("/entries?") -> HttpResponse(
                    200,
                    JSONArray().put(JSONObject().put("id", ROW_ID).put("revision", rowRevision)).toString(),
                )
                request.url.endsWith("/rpc/save_daily_entry_v2") && writes++ == 0 -> {
                    rowRevision = 8L
                    HttpResponse(200, JSONObject().put("status", "conflict").put("reason", "stale_revision").toString())
                }
                request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                    200,
                    JSONObject().put("status", "saved").put("entry_id", ROW_ID).put("revision", 9).put("replayed", false).toString(),
                )
                else -> HttpResponse(200, "{}")
            }
        }

        assertEquals(UsagePushOutcome.SENT, repository.pushDay(OWNER, DATE))

        val writesSent = requests.filter { it.method == "POST" && it.url.endsWith("/rpc/save_daily_entry_v2") }
        assertEquals(2, writesSent.size)
        val first = JSONObject(writesSent[0].body!!)
        val second = JSONObject(writesSent[1].body!!)
        assertEquals(7L, first.getLong("p_expected_revision"))
        assertEquals(8L, second.getLong("p_expected_revision"))
        assertFalse(first.getString("p_request_id") == second.getString("p_request_id"))
        assertEquals(first.getJSONObject("p_payload").toString(), second.getJSONObject("p_payload").toString())
        assertEquals(2, requests.count { it.method == "GET" && it.url.contains("/entries?") })
        assertTrue(requests.none { it.url.endsWith("/rpc/save_daily_entry") })
    }

    @Test
    fun `ambiguous response replays exact request after repository recreation`() = runBlocking {
        var rowRevision = 7L
        var firstWrite = true
        val repository = repository { request ->
            requests += request
            when {
                request.method == "GET" && request.url.contains("/entries?") -> HttpResponse(
                    200,
                    JSONArray().put(JSONObject().put("id", ROW_ID).put("revision", rowRevision)).toString(),
                )
                request.url.endsWith("/rpc/save_daily_entry_v2") && firstWrite -> {
                    firstWrite = false
                    rowRevision = 8L // The server committed, but the reply was lost.
                    throw java.io.IOException("synthetic lost response")
                }
                request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                    200,
                    JSONObject().put("status", "saved").put("entry_id", ROW_ID).put("revision", 8)
                        .put("replayed", true).toString(),
                )
                else -> HttpResponse(200, "{}")
            }
        }
        val initial = runCatching { repository.pushDay(OWNER, DATE) }
        assertTrue(initial.exceptionOrNull() is java.io.IOException)
        val requestBeforeRestart = requests.single { it.url.endsWith("/rpc/save_daily_entry_v2") }

        val recreated = repository(usage = object : UsageStatsSource {
            override fun hasUsageAccess(): Boolean = true
            override fun dayUsage(date: String) = DayUsage(
                totalSec = 1,
                unlocks = 99,
                apps = listOf(DayAppUsage("different.package", "Different", 60)),
            )
        }) { request ->
            requests += request
            when {
                request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                    200,
                    JSONObject().put("status", "saved").put("entry_id", ROW_ID).put("revision", 8)
                        .put("replayed", true).toString(),
                )
                request.method == "GET" && request.url.contains("/entries?") -> HttpResponse(
                    200,
                    JSONArray().put(JSONObject().put("id", ROW_ID).put("revision", 8)).toString(),
                )
                else -> HttpResponse(200, "{}")
            }
        }

        assertEquals(UsagePushOutcome.SENT, recreated.pushDay(OWNER, DATE))

        val writes = requests.filter { it.url.endsWith("/rpc/save_daily_entry_v2") }
        assertEquals(2, writes.size)
        assertEquals(requestBeforeRestart.body, writes[1].body)
        assertEquals(
            JSONObject(writes[0].body!!).getString("p_request_id"),
            JSONObject(writes[1].body!!).getString("p_request_id"),
        )
        assertTrue(requests.none { it.url.endsWith("/rpc/save_daily_entry") })
    }

    @Test
    fun `an unchanged phone payload is not sent twice`() = runBlocking {
        var revision = 7L
        val repository = repository { request ->
            requests += request
            when {
                request.method == "GET" && request.url.contains("/entries?") -> HttpResponse(
                    200,
                    JSONArray().put(JSONObject().put("id", ROW_ID).put("revision", revision)).toString(),
                )
                request.url.endsWith("/rpc/save_daily_entry_v2") -> {
                    revision += 1
                    HttpResponse(
                        200,
                        JSONObject()
                            .put("status", "saved")
                            .put("entry_id", ROW_ID)
                            .put("revision", revision)
                            .put("replayed", false)
                            .toString(),
                    )
                }
                else -> HttpResponse(200, "{}")
            }
        }

        assertEquals(UsagePushOutcome.SENT, repository.pushDay(OWNER, DATE))
        assertEquals(1, requests.count { it.url.endsWith("/rpc/save_daily_entry_v2") })

        assertEquals(UsagePushOutcome.UNCHANGED, repository.pushDay(OWNER, DATE))

        assertEquals(1, requests.count { it.url.endsWith("/rpc/save_daily_entry_v2") })
        assertEquals(2, requests.count { it.method == "GET" })
    }

    private fun repository(
        usage: UsageStatsSource = object : UsageStatsSource {
            override fun hasUsageAccess(): Boolean = true
            override fun dayUsage(date: String) = DayUsage(
                totalSec = 600,
                unlocks = 3,
                apps = listOf(DayAppUsage("com.example.app", "Example", 120)),
            )
        },
        transport: (HttpRequest) -> HttpResponse,
    ): UsageSyncRepository = UsageSyncRepository(
        store = session,
        consentStore = consent,
        client = SupabaseClient(
            sessionStore = session,
            transport = HttpTransport(transport),
            baseUrl = "https://example.supabase.co/rest/v1",
            anonKey = "anon-test-key",
        ),
        usageStats = usage,
        requestStore = UsageSyncRequestStore(context),
    )

    private fun sessionJson(ownerId: String): JSONObject = JSONObject().apply {
        put("access_token", jwt(ownerId))
        put("refresh_token", "refresh-$ownerId")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("user", JSONObject().put("id", ownerId))
    }

    private fun jwt(ownerId: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"$ownerId"}""".toByteArray())
        return "$header.$payload.fake-signature"
    }

    private companion object {
        const val OWNER = "account-a"
        const val OTHER_OWNER = "account-b"
        const val ROW_ID = "11111111-1111-4111-8111-111111111111"
        const val DATE = "2026-11-08"
    }
}
