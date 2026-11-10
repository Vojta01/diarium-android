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
        listOf("diarium_session", UsageSyncConsentStore.PREFS_NAME, "diarium_notifications")
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        requests.clear()
        session = SessionStore(context)
        consent = UsageSyncConsentStore(context)
        session.save(sessionJson(OWNER))
        consent.setEnabledFor(OWNER, true)
    }

    @Test
    fun `existing diary row is updated with conditional phone-only payload`() = runBlocking {
        val repository = repository { request ->
            requests += request
            when {
                request.method == "GET" && request.url.contains("/entries?") -> HttpResponse(
                    200,
                    JSONArray().put(
                        JSONObject().put("id", ROW_ID).put("revision", 7),
                    ).toString(),
                )
                request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                    200,
                    JSONObject()
                        .put("status", "saved")
                        .put("entry_id", ROW_ID)
                        .put("revision", 8)
                        .put("replayed", false)
                        .toString(),
                )
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
        assertFalse(requests.any { it.url.endsWith("/rpc/save_daily_entry") })
    }

    private fun repository(transport: (HttpRequest) -> HttpResponse): UsageSyncRepository = UsageSyncRepository(
        store = session,
        consentStore = consent,
        client = SupabaseClient(
            sessionStore = session,
            transport = HttpTransport(transport),
            baseUrl = "https://example.supabase.co/rest/v1",
            anonKey = "anon-test-key",
        ),
        usageStats = object : UsageStatsSource {
            override fun hasUsageAccess(): Boolean = true
            override fun dayUsage(date: String) = DayUsage(
                totalSec = 600,
                unlocks = 3,
                apps = listOf(DayAppUsage("com.example.app", "Example", 120)),
            )
        },
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
        const val ROW_ID = "11111111-1111-4111-8111-111111111111"
        const val DATE = "2026-11-08"
    }
}
