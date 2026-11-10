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
        listOf("diarium_session", UsageSyncConsentStore.PREFS_NAME, "diarium_notifications")
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        requests.clear()
        sessionStore = SessionStore(context)
        consentStore = UsageSyncConsentStore(context)
    }

    @Test
    fun `reboot backfill captures its owner and retries with the same owner token`() = runBlocking {
        sessionStore.save(session(OWNER_A))
        consentStore.setEnabledFor(OWNER_A, true)
        val networkAttempt = AtomicInteger()
        val repository = UsageSyncRepository(
            store = sessionStore,
            consentStore = consentStore,
            client = SupabaseClient(
                sessionStore = sessionStore,
                transport = HttpTransport { request ->
                    requests += request
                    if (networkAttempt.getAndIncrement() == 0) HttpResponse(503, "temporary")
                    else HttpResponse(200, "{}")
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

        assertEquals(2, requests.size)
        assertTrue(requests.all { it.headers["Authorization"] == "Bearer ${jwt(OWNER_A)}" })
        assertTrue(requests.all {
            org.json.JSONObject(it.body!!).getJSONObject("p_payload").getString("user_id") == OWNER_A
        })
        assertFalse(requests.any { it.headers["Authorization"] == "Bearer ${jwt(OWNER_B)}" })
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
        const val DATE = "2026-11-08"
    }
}
