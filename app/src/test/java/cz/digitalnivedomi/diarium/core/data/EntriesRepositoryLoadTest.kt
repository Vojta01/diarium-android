package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The load contract the check-in screen depends on: "no row" and "the read failed"
 * must be different answers. Conflating them was the first half of the data-loss
 * bug — a 500 or an offline phone looked exactly like an empty day, and saving the
 * fallback then overwrote the stored entry.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EntriesRepositoryLoadTest {

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
        context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `no row is a successful null, not a failure`() = runTest {
        respond = { HttpResponse(200, "[]") }

        val result = repository().loadEntry(DATE)

        assertTrue("a genuinely absent day must still open as an empty form", result.isSuccess)
        assertNull(result.getOrNull())
    }

    @Test
    fun `a stored row is returned`() = runTest {
        respond = {
            HttpResponse(
                200,
                JSONArray()
                    .put(JSONObject().put("mood", 4).put("note", "den").put("stress", 2))
                    .toString(),
            )
        }

        val entry = repository().loadEntry(DATE).getOrNull()

        assertEquals(4, entry?.mood)
        assertEquals(2, entry?.stress)
        assertEquals("den", entry?.note)
    }

    @Test
    fun `a successful day snapshot carries the id and revision token`() = runTest {
        respond = {
            HttpResponse(
                200,
                JSONArray().put(
                    JSONObject()
                        .put("id", ROW_ID)
                        .put("revision", 12)
                        .put("updated_at", "2026-09-10T10:00:00Z")
                        .put("date", DATE)
                        .put("mood", 4)
                        .put("note", "den"),
                ).toString(),
            )
        }

        val snapshot = repository().loadRemoteEntrySnapshot(DATE).getOrThrow()

        assertEquals(ROW_ID, snapshot?.id)
        assertEquals(12L, snapshot?.revision)
        assertEquals("den", snapshot?.entry?.note)
    }

    @Test
    fun `an absent successful snapshot is distinct from a failed read`() = runTest {
        respond = { HttpResponse(200, "[]") }

        val absent = repository().loadRemoteEntrySnapshot(DATE)

        assertTrue(absent.isSuccess)
        assertNull(absent.getOrNull())
        respond = { throw IOException("offline") }
        val failure = repository().loadRemoteEntrySnapshot(DATE)
        assertTrue(failure.isFailure)
    }

    @Test
    fun `an HTTP error is a failure, not an empty day`() = runTest {
        respond = { HttpResponse(500, """{"message":"boom"}""") }

        val result = repository().loadEntry(DATE)

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `an unreachable server is a retryable failure`() = runTest {
        respond = { throw IOException("no route to host") }

        val result = repository().loadEntry(DATE)

        assertTrue(result.isFailure)
        assertEquals("Nepodařilo se připojit k serveru.", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a body that is not a JSON array is a failure`() = runTest {
        respond = { HttpResponse(200, "not-json") }

        val result = repository().loadEntry(DATE)

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `a signed-out read never hits the network`() = runTest {
        sessionStore.clear()

        val result = repository().loadEntry(DATE)

        assertTrue(result.isFailure)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a queued offline save is a success even while the read fails`() = runTest {
        // The outbox is the newest state of the day: it must keep the form usable
        // and savable, because the user's own last save is what is being shown.
        val pending = PendingSaveStore.from(context, USER_ID)
        val queued = DiaryEntry(mood = 5, note = "v telefonu")
        pending.enqueue(DATE, queued)
        respond = { throw IOException("offline") }

        val result = repository(pending).loadEntry(DATE)

        assertTrue(result.isSuccess)
        assertEquals(queued, result.getOrNull())
    }

    @Test
    fun `a failed load only ever sends a GET`() = runTest {
        // The screen test asserts the button is disabled; this pins the repository
        // side of "a read failure cannot write anything".
        respond = { HttpResponse(500, """{"message":"boom"}""") }

        repository().loadEntry(DATE)

        assertEquals(1, sent.size)
        assertEquals("GET", sent.single().method)
    }

    @Test
    fun `switching from account A to B reads only B remote and pending entries`() = runTest {
        val pendingA = PendingSaveStore.from(context, USER_ID)
        pendingA.enqueue(DATE, DiaryEntry(mood = 5, note = "private A outbox"))
        sessionStore.save(sessionFor(OTHER_USER_ID))
        respond = {
            HttpResponse(
                200,
                JSONArray().put(JSONObject()
                    .put("date", DATE)
                    .put("mood", 2)
                    .put("note", "B server entry"))
                    .toString(),
            )
        }

        val entry = repository(PendingSaveStore.from(context, OTHER_USER_ID), OTHER_USER_ID)
            .loadEntry(DATE).getOrThrow()

        assertEquals("B server entry", entry?.note)
        assertEquals(1, sent.size)
        assertEquals("user_id=eq.$OTHER_USER_ID", sent.single().url.substringAfter('?').split('&')
            .first { it.startsWith("user_id=") })
        assertNull(PendingSaveStore.from(context, OTHER_USER_ID).peek(DATE))
        assertEquals("private A outbox", pendingA.peek(DATE)?.note)
    }

    @Test
    fun `stale account A repository sends no reads or writes after switch to B`() = runTest {
        val pendingA = PendingSaveStore.from(context, USER_ID)
        val accountARepository = repository(pendingA, USER_ID)
        sessionStore.save(sessionFor(OTHER_USER_ID))

        val load = accountARepository.loadEntry(DATE)
        val save = accountARepository.save(DiaryEntry(mood = 5, note = "must stay local"), DATE)

        assertTrue(load.isFailure)
        assertTrue(save.isFailure)
        assertTrue("no request may be issued under the new account", sent.isEmpty())
        assertNull(pendingA.peek(DATE))
    }

    @Test
    fun `late range GET cannot overwrite a newer local save in cache`() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("entries-range-race").toFile()
        val cacheFile = java.io.File(directory, "owner.json")
        val cache = EntriesCacheStore(cacheFile, USER_ID)
        val saveCache = EntriesCacheStore(cacheFile.canonicalFile, USER_ID)
        cache.upsertRows(listOf(
            JSONObject().put("user_id", USER_ID).put("date", DATE).put("mood", 2).put("note", "cached before GET"),
        ))
        val enteredGet = CountDownLatch(1)
        val releaseGet = CountDownLatch(1)
        val raceTransport = HttpTransport { request ->
            when (request.method) {
                "GET" -> {
                    enteredGet.countDown()
                    check(releaseGet.await(5, TimeUnit.SECONDS)) { "test did not release the stale GET" }
                    HttpResponse(200, JSONArray().put(
                        JSONObject().put("user_id", USER_ID).put("date", DATE).put("mood", 1).put("note", "old GET"),
                    ).toString())
                }
                "POST" -> HttpResponse(200, "{}")
                else -> error("unexpected request ${request.method}")
            }
        }
        val repository = EntriesRepository(
            client = SupabaseClient(sessionStore, raceTransport, BASE_URL, "anon-test-key"),
            session = SessionContext(sessionStore),
            cache = cache,
        )
        val saveRepository = EntriesRepository(
            client = SupabaseClient(sessionStore, raceTransport, BASE_URL, "anon-test-key"),
            session = SessionContext(sessionStore),
            cache = saveCache,
        )

        val delayedRead = async(Dispatchers.Default) { repository.loadRange(DATE, NEW_DATE) }
        assertTrue("range GET did not start", enteredGet.await(5, TimeUnit.SECONDS))
        val newer = DiaryEntry(mood = 5, note = "new local save")
        assertTrue(saveRepository.save(newer, NEW_DATE).isSuccess)
        releaseGet.countDown()
        assertTrue(delayedRead.await().isSuccess)

        val cached = cache.readRange(DATE, NEW_DATE)!!.associateBy { it.date }
        assertEquals(2, cached[DATE]?.entry?.mood)
        assertEquals("cached before GET", cached[DATE]?.entry?.note)
        assertEquals(5, cached[NEW_DATE]?.entry?.mood)
        assertEquals("new local save", cached[NEW_DATE]?.entry?.note)
    }

    @Test
    fun `late loadDates response cannot overwrite a save through another repository`() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("entries-dates-race").toFile()
        val file = java.io.File(directory, "owner.json")
        val readCache = EntriesCacheStore(file, USER_ID)
        val saveCache = EntriesCacheStore(file, USER_ID)
        val enteredGet = CountDownLatch(1)
        val releaseGet = CountDownLatch(1)
        val readTransport = HttpTransport { request ->
            if (request.method == "GET") {
                enteredGet.countDown()
                check(releaseGet.await(5, TimeUnit.SECONDS)) { "test did not release stale dates GET" }
                HttpResponse(200, JSONArray().put(
                    JSONObject().put("user_id", USER_ID).put("date", DATE).put("mood", 1).put("note", "old dates GET"),
                ).toString())
            } else HttpResponse(200, "{}")
        }
        val readRepository = EntriesRepository(
            SupabaseClient(sessionStore, readTransport, BASE_URL, "anon-test-key"),
            SessionContext(sessionStore),
            cache = readCache,
        )
        val saveRepository = EntriesRepository(
            SupabaseClient(sessionStore, readTransport, BASE_URL, "anon-test-key"),
            SessionContext(sessionStore),
            cache = saveCache,
        )

        val delayedRead = async(Dispatchers.Default) { readRepository.loadDates(listOf(DATE)) }
        assertTrue("dates GET did not start", enteredGet.await(5, TimeUnit.SECONDS))
        assertTrue(saveRepository.save(DiaryEntry(mood = 5, note = "new save"), DATE).isSuccess)
        releaseGet.countDown()
        assertEquals(1, delayedRead.await()[DATE]?.mood)

        val cached = readCache.readRange(DATE, DATE)?.single()?.entry
        assertEquals(5, cached?.mood)
        assertEquals("new save", cached?.note)
    }

    @Test
    fun `partial successful range response never evicts unseen cached rows`() = runTest {
        val directory = java.nio.file.Files.createTempDirectory("entries-truncated-range").toFile()
        val cache = EntriesCacheStore(java.io.File(directory, "owner.json"), USER_ID)
        cache.upsertRows(listOf(
            JSONObject().put("user_id", USER_ID).put("date", "2026-09-09").put("mood", 2),
            JSONObject().put("user_id", USER_ID).put("date", DATE).put("mood", 3),
        ))
        respond = {
            HttpResponse(200, JSONArray().put(
                JSONObject().put("user_id", USER_ID).put("date", DATE).put("mood", 4),
            ).toString())
        }
        val repository = repository(cache = cache)

        assertTrue(repository.loadRange("2026-09-01", DATE, limit = 1).isSuccess)

        val cached = cache.readRange("2026-09-01", DATE)!!.associate { it.date to it.entry.mood }
        assertEquals(mapOf("2026-09-09" to 2, DATE to 4), cached)
    }

    private fun repository(
        pending: PendingSaveStore? = null,
        ownerId: String? = null,
        cache: EntriesCacheStore? = null,
    ): EntriesRepository =
        EntriesRepository(
            client = SupabaseClient(
                sessionStore = sessionStore,
                transport = transport,
                baseUrl = BASE_URL,
                anonKey = "anon-test-key",
            ),
            session = SessionContext(sessionStore),
            pending = pending,
            expectedOwnerId = ownerId,
            cache = cache,
        )

    private fun sessionFor(userId: String) = JSONObject().apply {
        put("access_token", "jwt-$userId")
        put("refresh_token", "refresh-$userId")
        put("expires_at", System.currentTimeMillis() / 1000 + 3600)
        put("user", JSONObject().put("id", userId))
    }

    private companion object {
        const val DATE = "2026-09-10"
        const val NEW_DATE = "2026-09-11"
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val ROW_ID = "ecaf917a-7c84-4ad5-b657-cc5d9af10a36"
        const val OTHER_USER_ID = "9e0a6e91-36c0-4e84-a6ed-f05d92e0d521"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
    }
}
