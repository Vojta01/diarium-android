package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import cz.digitalnivedomi.diarium.core.stats.DayAppUsage
import cz.digitalnivedomi.diarium.core.stats.DayUsage
import cz.digitalnivedomi.diarium.stats.UsageStatsSource
import cz.digitalnivedomi.diarium.sync.UsageSyncConsentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Owner/consent checks at the boundary between UsageStats and the fake REST network. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UsageSyncRepositoryIsolationTest {

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
    fun `owner switch after enqueue while usage is being read sends no request`() = runBlocking {
        sessionStore.save(session(OWNER_A))
        consentStore.setEnabledFor(OWNER_A, true)
        val readingStarted = CountDownLatch(1)
        val releaseReading = CountDownLatch(1)
        val repository = repository(
            usageStats = fakeUsage {
                readingStarted.countDown()
                check(releaseReading.await(5, TimeUnit.SECONDS))
            },
        )

        // This owner is what the enqueued WorkRequest captured; it must not be
        // replaced with whichever account is live when its worker eventually runs.
        val worker = async(Dispatchers.IO) { repository.pushDay(OWNER_A, DATE) }
        assertTrue("usage query never reached its barrier", readingStarted.await(5, TimeUnit.SECONDS))
        sessionStore.save(session(OWNER_B))
        releaseReading.countDown()

        assertEquals(UsagePushOutcome.OWNER_CHANGED, worker.await())
        assertTrue("account A's payload must not be sent with account B's session", requests.isEmpty())
    }

    @Test
    fun `opt out after enqueue is checked again immediately before network write`() = runBlocking {
        sessionStore.save(session(OWNER_A))
        consentStore.setEnabledFor(OWNER_A, true)
        val readingStarted = CountDownLatch(1)
        val releaseReading = CountDownLatch(1)
        val repository = repository(
            usageStats = fakeUsage {
                readingStarted.countDown()
                check(releaseReading.await(5, TimeUnit.SECONDS))
            },
        )

        val worker = async(Dispatchers.IO) { repository.pushDay(OWNER_A, DATE) }
        assertTrue("usage query never reached its barrier", readingStarted.await(5, TimeUnit.SECONDS))
        consentStore.setEnabledFor(OWNER_A, false)
        releaseReading.countDown()

        assertEquals(UsagePushOutcome.CONSENT_DISABLED, worker.await())
        assertTrue("revoked consent must stop before dispatch", requests.isEmpty())
    }

    @Test
    fun `opt out serializes with a started request and prevents a later upload`() = runBlocking {
        sessionStore.save(session(OWNER_A))
        consentStore.setEnabledFor(OWNER_A, true)
        val transportStarted = CountDownLatch(1)
        val callback = java.util.concurrent.atomic.AtomicReference<((Result<HttpResponse>) -> Unit)?>(null)
        val optOutCompleted = CountDownLatch(1)
        val transport = object : HttpTransport {
            override fun execute(request: HttpRequest): HttpResponse {
                synchronized(requests) { requests += request }
                return HttpResponse(500, "unexpected synchronous request")
            }

            override fun enqueue(
                request: HttpRequest,
                responseCallback: (Result<HttpResponse>) -> Unit,
            ): HttpCallHandle {
                synchronized(requests) { requests += request }
                callback.set(responseCallback)
                transportStarted.countDown()
                return HttpCallHandle { }
            }
        }
        val repository = repository(transportOverride = transport)

        val worker = async(Dispatchers.IO) { repository.pushDay(OWNER_A, DATE) }
        assertTrue("fake transport was not reached", transportStarted.await(5, TimeUnit.SECONDS))
        val optOut = async(Dispatchers.IO) {
            consentStore.setEnabledFor(OWNER_A, false)
            optOutCompleted.countDown()
        }
        assertTrue("opt-out must complete after the handoff without waiting for the response", optOutCompleted.await(5, TimeUnit.SECONDS))
        optOut.await()

        // An already handed-off request may complete after opt-out; it cannot be
        // recalled, but no subsequent request may cross the same owner gate.
        callback.get()!!.invoke(Result.success(HttpResponse(200, "{}")))
        assertEquals(UsagePushOutcome.SENT, worker.await())
        assertEquals("the in-flight handoff is allowed to finish", 1, requests.size)
        assertEquals("Bearer ${jwt(OWNER_A)}", requests.single().headers["Authorization"])

        assertEquals(UsagePushOutcome.CONSENT_DISABLED, repository.pushDay(OWNER_A, DATE))
        assertEquals("no upload may start after opt-out completes", 1, requests.size)
    }

    @Test
    fun `refresh followed by opt out and owner switch is rechecked at dispatch`() = runBlocking {
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        sessionStore = SessionStore(context) { _ ->
            refreshStarted.countDown()
            check(releaseRefresh.await(5, TimeUnit.SECONDS))
            org.json.JSONObject()
                .put("access_token", jwt(OWNER_A))
                .put("refresh_token", "refreshed-$OWNER_A")
                .put("expires_in", 3600)
        }
        sessionStore.save(session(OWNER_A, expiresAt = 0))
        consentStore.setEnabledFor(OWNER_A, true)

        val readingStarted = CountDownLatch(1)
        val releaseReading = CountDownLatch(1)
        val repository = repository(
            usageStats = fakeUsage {
                readingStarted.countDown()
                check(releaseReading.await(5, TimeUnit.SECONDS))
            },
        )

        val worker = async(Dispatchers.IO) { repository.pushDay(OWNER_A, DATE) }
        assertTrue("token refresh never reached its barrier", refreshStarted.await(5, TimeUnit.SECONDS))
        consentStore.setEnabledFor(OWNER_A, false)

        val switchStarted = CountDownLatch(1)
        val switched = async(Dispatchers.IO) {
            switchStarted.countDown()
            sessionStore.save(session(OWNER_B))
        }
        assertTrue("owner switch did not start", switchStarted.await(5, TimeUnit.SECONDS))
        releaseRefresh.countDown()
        assertTrue("usage read never started after refresh", readingStarted.await(5, TimeUnit.SECONDS))
        switched.await()
        releaseReading.countDown()

        assertEquals(UsagePushOutcome.OWNER_CHANGED, worker.await())
        assertEquals(OWNER_B, sessionStore.userSubject())
        assertTrue("neither a revoked nor an old-owner upload may dispatch", requests.isEmpty())
    }

    @Test
    fun `legitimate owner opt in writes only that owner's payload with that owner's token`() = runBlocking {
        sessionStore.save(session(OWNER_A))
        consentStore.setEnabledFor(OWNER_A, true)
        val repository = repository()

        assertEquals(UsagePushOutcome.SENT, repository.pushDay(OWNER_A, DATE))

        val request = requests.single()
        assertEquals("Bearer ${jwt(OWNER_A)}", request.headers["Authorization"])
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/rpc/save_daily_entry"))
        assertEquals(OWNER_A, org.json.JSONObject(request.body!!).getJSONObject("p_payload").getString("user_id"))
        assertEquals(DATE, org.json.JSONObject(request.body!!).getJSONObject("p_payload").getString("date"))
        assertFalse(requests.any { it.headers["Authorization"] == "Bearer ${jwt(OWNER_B)}" })
    }

    @Test
    fun `401 refreshes the same owner token and retries at most once`() = runBlocking {
        var refreshCount = 0
        sessionStore = SessionStore(context) { refreshToken ->
            assertEquals("refresh-$OWNER_A", refreshToken)
            refreshCount++
            org.json.JSONObject()
                .put("access_token", jwt(OWNER_A).replace("fake-signature", "rotated-signature"))
                .put("refresh_token", "rotated-refresh-$OWNER_A")
                .put("expires_in", 3600)
        }
        sessionStore.save(session(OWNER_A))
        consentStore.setEnabledFor(OWNER_A, true)
        var responseCount = 0
        val repository = repository(
            response = {
                if (responseCount++ == 0) HttpResponse(401, "unauthorized")
                else HttpResponse(200, "{}")
            },
        )

        assertEquals(UsagePushOutcome.SENT, repository.pushDay(OWNER_A, DATE))

        assertEquals("one 401 may cause only one refreshed retry", 2, requests.size)
        assertEquals(2, responseCount)
        assertEquals(1, refreshCount)
        assertEquals("Bearer ${jwt(OWNER_A)}", requests[0].headers["Authorization"])
        assertEquals(
            "Bearer ${jwt(OWNER_A).replace("fake-signature", "rotated-signature")}",
            requests[1].headers["Authorization"],
        )
        requests.forEach { request ->
            assertEquals(OWNER_A, org.json.JSONObject(request.body!!).getJSONObject("p_payload").getString("user_id"))
        }
    }

    @Test
    fun `owner change after 401 prevents a refresh or cross account retry`() = runBlocking {
        var refreshCount = 0
        sessionStore = SessionStore(context) { _ ->
            refreshCount++
            org.json.JSONObject()
                .put("access_token", jwt(OWNER_A))
                .put("refresh_token", "rotated-refresh-$OWNER_A")
                .put("expires_in", 3600)
        }
        sessionStore.save(session(OWNER_A))
        consentStore.setEnabledFor(OWNER_A, true)
        val requestStarted = CountDownLatch(1)
        val pendingCallback = java.util.concurrent.atomic.AtomicReference<((Result<HttpResponse>) -> Unit)?>(null)
        val transport = object : HttpTransport {
            override fun execute(request: HttpRequest): HttpResponse =
                error("the asynchronous transport path is expected")

            override fun enqueue(
                request: HttpRequest,
                callback: (Result<HttpResponse>) -> Unit,
            ): HttpCallHandle {
                requests += request
                pendingCallback.set(callback)
                requestStarted.countDown()
                return HttpCallHandle { }
            }
        }
        val repository = repository(transportOverride = transport)
        val worker = async(Dispatchers.IO) { repository.pushDay(OWNER_A, DATE) }

        assertTrue("first request was not dispatched", requestStarted.await(5, TimeUnit.SECONDS))
        assertEquals("Bearer ${jwt(OWNER_A)}", requests.single().headers["Authorization"])
        sessionStore.save(session(OWNER_B))
        pendingCallback.get()!!.invoke(Result.success(HttpResponse(401, "unauthorized")))

        assertEquals(UsagePushOutcome.OWNER_CHANGED, worker.await())
        assertEquals(0, refreshCount)
        assertEquals(1, requests.size)
        assertTrue(requests.none { it.headers["Authorization"] == "Bearer ${jwt(OWNER_B)}" })
        assertEquals(OWNER_B, sessionStore.userSubject())
    }

    @Test
    fun `opt out before a 401 response prevents refresh and retry`() = runBlocking {
        var refreshCount = 0
        sessionStore = SessionStore(context) { _ ->
            refreshCount++
            org.json.JSONObject()
                .put("access_token", jwt(OWNER_A))
                .put("refresh_token", "rotated-refresh-$OWNER_A")
                .put("expires_in", 3600)
        }
        sessionStore.save(session(OWNER_A))
        consentStore.setEnabledFor(OWNER_A, true)
        val requestStarted = CountDownLatch(1)
        val pendingCallback = java.util.concurrent.atomic.AtomicReference<((Result<HttpResponse>) -> Unit)?>(null)
        val transport = object : HttpTransport {
            override fun execute(request: HttpRequest): HttpResponse =
                error("the asynchronous transport path is expected")

            override fun enqueue(
                request: HttpRequest,
                callback: (Result<HttpResponse>) -> Unit,
            ): HttpCallHandle {
                requests += request
                pendingCallback.set(callback)
                requestStarted.countDown()
                return HttpCallHandle { }
            }
        }
        val repository = repository(transportOverride = transport)
        val worker = async(Dispatchers.IO) { repository.pushDay(OWNER_A, DATE) }
        assertTrue("first request was not dispatched", requestStarted.await(5, TimeUnit.SECONDS))

        consentStore.setEnabledFor(OWNER_A, false)
        pendingCallback.get()!!.invoke(Result.success(HttpResponse(401, "unauthorized")))

        assertEquals(UsagePushOutcome.CONSENT_DISABLED, worker.await())
        assertEquals("opt-out must prevent a token refresh", 0, refreshCount)
        assertEquals("opt-out must prevent a retry", 1, requests.size)
    }

    private fun repository(
        usageStats: UsageStatsSource = fakeUsage {},
        response: () -> HttpResponse = { HttpResponse(200, "{}") },
        transportOverride: HttpTransport? = null,
    ): UsageSyncRepository = UsageSyncRepository(
        store = sessionStore,
        consentStore = consentStore,
        client = SupabaseClient(
            sessionStore = sessionStore,
            transport = transportOverride ?: HttpTransport { request ->
                synchronized(requests) { requests += request }
                response()
            },
            baseUrl = "https://example.supabase.co/rest/v1",
            anonKey = "anon-test-key",
        ),
        usageStats = usageStats,
    )

    private fun fakeUsage(beforeRead: () -> Unit): UsageStatsSource = object : UsageStatsSource {
        override fun hasUsageAccess(): Boolean = true
        override fun dayUsage(date: String): DayUsage {
            beforeRead()
            return DayUsage(
                totalSec = 600,
                unlocks = 3,
                apps = listOf(DayAppUsage("com.example.app", "Example", 120)),
            )
        }
    }

    private fun session(ownerId: String, expiresAt: Long = System.currentTimeMillis() / 1000 + 3600): org.json.JSONObject = org.json.JSONObject().apply {
        put("access_token", jwt(ownerId))
        put("refresh_token", "refresh-$ownerId")
        put("expires_at", expiresAt)
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
