package cz.digitalnivedomi.diarium.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.data.HttpRequest
import cz.digitalnivedomi.diarium.core.data.HttpResponse
import cz.digitalnivedomi.diarium.core.data.HttpTransport
import cz.digitalnivedomi.diarium.core.data.SupabaseClient
import cz.digitalnivedomi.diarium.core.data.UsagePushOutcome
import cz.digitalnivedomi.diarium.core.data.UsageSyncRepository
import cz.digitalnivedomi.diarium.core.data.UsageSyncRequestStore
import cz.digitalnivedomi.diarium.core.stats.DayAppUsage
import cz.digitalnivedomi.diarium.core.stats.DayUsage
import cz.digitalnivedomi.diarium.stats.UsageStatsSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Reboot work and WorkManager retries keep the original account pin. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UsageSyncWorkerIsolationTest {

    private lateinit var context: Context
    private lateinit var sessionStore: SessionStore
    private lateinit var consentStore: UsageSyncConsentStore
    private val requests = mutableListOf<HttpRequest>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        listOf("diarium_session", UsageSyncConsentStore.PREFS_NAME, "diarium_notifications", UsageSyncRequestStore.PREFS_NAME)
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        requests.clear()
        sessionStore = SessionStore(context)
        consentStore = UsageSyncConsentStore(context)
    }

    @Test
    fun `reboot backfill captures its owner and retries with the same owner token`() = runBlocking {
        sessionStore.save(session(OWNER_A))
        consentStore.setEnabledFor(OWNER_A, true)
        var rowRevision = 7L
        val writeAttempt = AtomicInteger()
        val repository = UsageSyncRepository(
            store = sessionStore,
            consentStore = consentStore,
            client = SupabaseClient(
                sessionStore = sessionStore,
                transport = HttpTransport { request ->
                    requests += request
                    when {
                        request.method == "GET" && request.url.contains("/entries?") -> HttpResponse(
                            200,
                            org.json.JSONArray().put(
                                org.json.JSONObject().put("id", ROW_ID).put("revision", rowRevision),
                            ).toString(),
                        )
                        request.url.endsWith("/rpc/save_daily_entry_v2") && writeAttempt.getAndIncrement() == 0 -> {
                            rowRevision = 8L // Synthetic commit; its response is lost.
                            throw java.io.IOException("lost conditional-save response")
                        }
                        request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                            200,
                            org.json.JSONObject().put("status", "saved").put("entry_id", ROW_ID)
                                .put("revision", 8).put("replayed", true).toString(),
                        )
                        else -> HttpResponse(500, "unexpected endpoint")
                    }
                },
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
            requestStore = UsageSyncRequestStore(context),
        )

        // SyncBootReceiver builds this exact input after reboot; a retry reuses the
        // persisted WorkRequest input instead of resolving the live session again.
        val bootInput = SyncScheduler.bootBackfillInputData(OWNER_A)
        val capturedOwner = UsageSyncWorker.ownerFromInput(bootInput)
        assertEquals(OWNER_A, capturedOwner)

        assertEquals(
            UsageSyncAttempt.RETRY,
            UsageSyncWorker.runUsageSync(capturedOwner, listOf(DATE), repository::pushDay),
        )
        assertEquals(
            UsageSyncAttempt.SUCCESS,
            UsageSyncWorker.runUsageSync(capturedOwner, listOf(DATE), repository::pushDay),
        )

        val writes = requests.filter { it.url.endsWith("/rpc/save_daily_entry_v2") }
        assertEquals(4, requests.size)
        assertEquals(2, writes.size)
        assertEquals(writes[0].body, writes[1].body)
        assertEquals(
            org.json.JSONObject(writes[0].body!!).getString("p_request_id"),
            org.json.JSONObject(writes[1].body!!).getString("p_request_id"),
        )
        assertTrue(requests.all { it.headers["Authorization"] == "Bearer ${jwt(OWNER_A)}" })
        assertTrue(writes.all {
            org.json.JSONObject(it.body!!).getJSONObject("p_payload").getString("user_id") == OWNER_A
        })
        assertFalse(requests.any { it.url.endsWith("/rpc/save_daily_entry") })
        assertFalse(requests.any { it.headers["Authorization"] == "Bearer ${jwt(OWNER_B)}" })
    }

    @Test
    fun `v2 unavailability retries the same durable request without a legacy write`() = runBlocking {
        sessionStore.save(session(OWNER_A))
        consentStore.setEnabledFor(OWNER_A, true)
        val repository = UsageSyncRepository(
            store = sessionStore,
            consentStore = consentStore,
            client = SupabaseClient(
                sessionStore = sessionStore,
                transport = HttpTransport { request ->
                    requests += request
                    when {
                        request.method == "GET" && request.url.contains("/entries?") -> HttpResponse(
                            200,
                            org.json.JSONArray().put(
                                org.json.JSONObject().put("id", ROW_ID).put("revision", 7),
                            ).toString(),
                        )
                        request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(404, "RPC unavailable")
                        else -> HttpResponse(500, "unexpected endpoint")
                    }
                },
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
            requestStore = UsageSyncRequestStore(context),
        )

        assertEquals(
            UsageSyncAttempt.RETRY,
            UsageSyncWorker.runUsageSync(OWNER_A, listOf(DATE), repository::pushDay),
        )
        assertEquals(
            UsageSyncAttempt.RETRY,
            UsageSyncWorker.runUsageSync(OWNER_A, listOf(DATE), repository::pushDay),
        )

        val writes = requests.filter { it.url.endsWith("/rpc/save_daily_entry_v2") }
        assertEquals(2, writes.size)
        assertEquals(writes[0].body, writes[1].body)
        assertFalse(requests.any { it.url.endsWith("/rpc/save_daily_entry") })
    }

    @Test
    fun `ownerless legacy work input is ignored rather than rebound to the live account`() {
        sessionStore.save(session(OWNER_B))

        assertEquals(null, UsageSyncWorker.ownerFromInput(androidx.work.Data.Builder().build()))
        var dispatched = false
        assertEquals(
            UsageSyncAttempt.SUCCESS,
            runBlocking {
                UsageSyncWorker.runUsageSync(ownerId = null, dates = listOf(DATE)) { _, _ ->
                    dispatched = true
                    UsagePushOutcome.SENT
                }
            },
        )
        assertFalse("ownerless legacy work must not attach to account B", dispatched)
    }

    private fun session(ownerId: String): org.json.JSONObject = org.json.JSONObject().apply {
        put("access_token", jwt(ownerId))
        put("refresh_token", "refresh-$ownerId")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("user", org.json.JSONObject().put("id", ownerId))
    }

    private fun jwt(ownerId: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"$ownerId"}""".toByteArray())
        return "$header.$payload.fake-signature"
    }

    private companion object {
        const val OWNER_A = "account-a"
        const val OWNER_B = "account-b"
        const val ROW_ID = "11111111-1111-4111-8111-111111111111"
        const val DATE = "2026-11-08"
    }
}
