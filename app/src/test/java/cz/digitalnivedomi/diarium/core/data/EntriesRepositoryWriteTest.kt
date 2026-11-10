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
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The write half of the check-in: explicit photo removal, cleared scales, and the
 * source-day cleanup of a move/delete. Every test drives the real repository over a
 * fake transport, so the exact requests the app would send are asserted — no live
 * Supabase project is touched.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EntriesRepositoryWriteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sent = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private var respond: (HttpRequest) -> HttpResponse? = { null }
    private val transport = HttpTransport { request ->
        sent += request
        respond(request) ?: defaultResponse(request)
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
        context.getSharedPreferences(PhotoCandidateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ── photo removal ───────────────────────────────────────────────────────

    @Test
    fun `a removed photo is omitted from the payload and cleared with a null patch`() = runTest {
        val storedPhoto = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-webnonce.jpg"
        val result = repository().save(
            DiaryEntry(mood = 4, photoPath = storedPhoto, clearPhoto = true),
            DATE,
        )

        assertTrue(result.isSuccess)
        val payload = savePayload()
        assertFalse("the RPC must not write photo_path at all", payload.has("photo_path"))

        val patch = sent.single { it.method == "PATCH" }
        assertTrue(patch.url.contains("/entries?"))
        assertTrue(decoded(patch).contains("user_id=eq.$USER_ID"))
        assertTrue(decoded(patch).contains("date=eq.$DATE"))
        val body = JSONObject(patch.body!!)
        assertTrue("a null is what actually clears the column", body.isNull("photo_path"))
        assertEquals(1, body.length())

        // The exact stored URL (including the web client's nonce) is deleted only
        // after the row no longer references it.
        val storageDelete = sent.single { it.method == "DELETE" && it.url.contains("/storage/v1/object/") }
        assertTrue(storageDelete.url.endsWith("/diary-photos/$USER_ID/$DATE-webnonce.jpg"))
        assertTrue(sent.indexOf(patch) < sent.indexOf(storageDelete))
    }

    @Test
    fun `a successful replacement removes the prior stored object only after saving the new URL`() = runTest {
        val oldPhoto = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-oldnonce.jpg"
        val newPhoto = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-newnonce.jpg"
        val replacement = DiaryEntry.fromJson(
            JSONObject()
                .put("mood", 4)
                .put("photoPath", newPhoto)
                .put("photoPathToDelete", oldPhoto),
        )

        val result = repository().save(replacement, DATE)

        assertTrue(result.isSuccess)
        assertEquals(newPhoto, savePayload().getString("photo_path"))
        val saveRequest = sent.single { it.url.contains("/rpc/save_daily_entry") }
        val storageDelete = sent.single { it.method == "DELETE" && it.url.contains("/storage/v1/object/") }
        assertTrue(storageDelete.url.endsWith("/diary-photos/$USER_ID/$DATE-oldnonce.jpg"))
        assertTrue(sent.indexOf(saveRequest) < sent.indexOf(storageDelete))
    }

    @Test
    fun `legacy Android photo URL remains deletable`() = runTest {
        val legacyPhoto = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE.jpg"

        val result = repository().save(
            DiaryEntry(mood = 4, photoPath = legacyPhoto, clearPhoto = true),
            DATE,
        )

        assertTrue(result.isSuccess)
        val storageDelete = sent.single { it.method == "DELETE" && it.url.contains("/storage/v1/object/") }
        assertTrue(storageDelete.url.endsWith("/diary-photos/$USER_ID/$DATE.jpg"))
    }

    @Test
    fun `a failed entry save preserves the prior photo object`() = runTest {
        val oldPhoto = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-oldnonce.jpg"
        val newPhoto = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-newnonce.jpg"
        respond = { request ->
            if (request.url.contains("/rpc/save_daily_entry")) HttpResponse(500, "{}") else null
        }
        val replacement = DiaryEntry.fromJson(
            JSONObject()
                .put("mood", 4)
                .put("photoPath", newPhoto)
                .put("photoPathToDelete", oldPhoto),
        )

        val result = repository().save(replacement, DATE)

        assertTrue(result.isFailure)
        assertTrue(sent.none { it.method == "DELETE" && it.url.contains("/storage/v1/object/") })
    }

    @Test
    fun `an offline replacement retains the cleanup path and does not delete before flush`() = runTest {
        context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val oldPhoto = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-oldnonce.jpg"
        val newPhoto = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-newnonce.jpg"
        val entry = DiaryEntry(
            mood = 4,
            photoPath = newPhoto,
            photoPathToDelete = oldPhoto,
        )
        respond = { request ->
            if (request.url.contains("/rpc/save_daily_entry")) throw IOException("offline") else null
        }
        val pending = PendingSaveStore.from(context, USER_ID)

        val result = repository(pending).save(entry, DATE)

        assertTrue(result.exceptionOrNull() is QueuedOfflineException)
        assertEquals(entry, pending.peek(DATE))
        assertTrue(sent.none { it.method == "DELETE" && it.url.contains("/storage/v1/object/") })
    }

    @Test
    fun `foreground save replaces a queued transaction without racing its worker`() = runTest {
        context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val pending = PendingSaveStore.from(context, USER_ID)
        pending.enqueue(DATE, DiaryEntry(mood = 2, note = "older queued save"), queuedAt = 7L)
        val newer = DiaryEntry(mood = 5, note = "newer foreground save")

        val result = repository(pending).save(newer, DATE)

        assertTrue(result.exceptionOrNull() is QueuedOfflineException)
        assertEquals(newer, pending.peek(DATE))
        assertTrue("the foreground request must not race the queued worker", sent.isEmpty())
        assertEquals(newer, PendingSaveStore.from(context, USER_ID).peek(DATE))
    }

    @Test
    fun `offline move keeps its source and never queues only the target`() = runTest {
        context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        respond = { request ->
            if (request.url.contains("/rpc/save_daily_entry")) throw IOException("offline") else null
        }
        val pending = PendingSaveStore.from(context, USER_ID)

        val result = repository(pending).move(
            DiaryEntry(mood = 4, note = "source check-in"),
            fromDate = DATE,
            toDate = TARGET_DATE,
        )

        assertTrue("a two-write move cannot report success offline", result.isFailure)
        assertFalse(result.exceptionOrNull() is QueuedOfflineException)
        assertNull("the target half must not be queued", pending.peek(TARGET_DATE))
        assertTrue("the source row must not be deleted", sent.none {
            it.method == "DELETE" && it.url.contains("/entries?")
        })
    }

    @Test
    fun `a queued write is never dispatched and a newer foreground edit remains local`() = runTest {
        context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val old = DiaryEntry(mood = 2, note = "queued client A")
        val newer = DiaryEntry(mood = 5, note = "newer local edit")
        val pending = PendingSaveStore.from(context, USER_ID)
        pending.enqueue(DATE, old, queuedAt = 1L, baselineUpdatedAt = "revision-1", baselineExists = true)
        var dispatchCount = 0

        val flush = pending.flushRecord(
            date = DATE,
            expectedOwnerId = USER_ID,
            currentOwnerId = { USER_ID },
            dispatch = {
                dispatchCount++
                Result.success(Unit)
            },
        )
        val foreground = repository(pending).save(newer, DATE)

        assertTrue(flush is PendingSaveFlush.RecoveryRequired)
        assertEquals(0, dispatchCount)
        assertTrue(foreground.exceptionOrNull() is QueuedOfflineException)
        assertEquals(newer, pending.peek(DATE))
        assertTrue("neither the worker nor foreground path may send the stale snapshot", sent.isEmpty())
    }

    @Test
    fun `move refuses an outbox source because replay is not atomic`() = runTest {
        context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val pending = PendingSaveStore.from(context, USER_ID)
        val source = DiaryEntry(mood = 4, note = "latest queued source")
        pending.enqueue(DATE, source)

        val result = repository(pending).move(
            source,
            fromDate = DATE,
            toDate = TARGET_DATE,
        )

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is QueuedOfflineException)
        assertEquals(source, pending.peek(DATE))
        assertNull(pending.peek(TARGET_DATE))
        assertTrue("no half-move request may be dispatched", sent.isEmpty())
    }

    @Test
    fun `move refuses a concurrently modified target and preserves both server entries`() = runTest {
        val server = mutableMapOf(
            DATE to DiaryEntry(mood = 2, note = "source entry"),
            TARGET_DATE to DiaryEntry(mood = 5, note = "newer target from client B"),
        )
        respond = { request ->
            when {
                request.url.contains("/rpc/save_daily_entry") -> {
                    val payload = JSONObject(request.body!!).getJSONObject("p_payload")
                    server[payload.getString("date")] = EntriesRepository.fromRow(payload)
                    HttpResponse(200, "{}")
                }
                request.method == "DELETE" && request.url.contains("/entries?") -> {
                    val date = decoded(request).substringAfter("date=eq.").substringBefore('&')
                    server.remove(date)
                    HttpResponse(204, "")
                }
                else -> null
            }
        }

        val result = repository().move(
            DiaryEntry(mood = 2, note = "source entry"),
            fromDate = DATE,
            toDate = TARGET_DATE,
        )

        assertTrue("a client cannot safely overwrite a target without server CAS", result.isFailure)
        assertEquals("source entry", server[DATE]?.note)
        assertEquals("newer target from client B", server[TARGET_DATE]?.note)
        assertTrue("unsafe move must issue no write or delete", sent.none {
            it.method == "POST" || (it.method == "DELETE" && it.url.contains("/entries?"))
        })
    }

    @Test
    fun `move refuses a concurrently modified source and preserves both server entries`() = runTest {
        val server = mutableMapOf(
            DATE to DiaryEntry(mood = 5, note = "newer source from client B"),
        )
        respond = { request ->
            when {
                request.url.contains("/rpc/save_daily_entry") -> {
                    val payload = JSONObject(request.body!!).getJSONObject("p_payload")
                    server[payload.getString("date")] = EntriesRepository.fromRow(payload)
                    HttpResponse(200, "{}")
                }
                request.method == "DELETE" && request.url.contains("/entries?") -> {
                    val date = decoded(request).substringAfter("date=eq.").substringBefore('&')
                    server.remove(date)
                    HttpResponse(204, "")
                }
                else -> null
            }
        }

        val result = repository().move(
            DiaryEntry(mood = 2, note = "stale source from client A"),
            fromDate = DATE,
            toDate = TARGET_DATE,
        )

        assertTrue("source deletion cannot be made atomic with a stale snapshot", result.isFailure)
        assertEquals("newer source from client B", server[DATE]?.note)
        assertNull(server[TARGET_DATE])
        assertTrue("unsafe move must issue no write or delete", sent.none {
            it.method == "POST" || (it.method == "DELETE" && it.url.contains("/entries?"))
        })
    }

    @Test
    fun `unsafe move does not create a partial target when source delete would fail`() = runTest {
        respond = { request ->
            when {
                request.url.contains("/rpc/save_daily_entry") -> HttpResponse(200, "{}")
                request.url.contains("/entries?") && request.method == "DELETE" -> HttpResponse(503, "{}")
                else -> null
            }
        }

        val result = repository().move(
            DiaryEntry(mood = 4, note = "source check-in"),
            fromDate = DATE,
            toDate = TARGET_DATE,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UnsafeMoveException)
        assertTrue("both dates stay untouched because the move is disabled", sent.isEmpty())
    }

    @Test
    fun `a target write for a date move is not queued when the network is offline`() = runTest {
        context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        respond = { request ->
            if (request.url.contains("/rpc/save_daily_entry")) throw IOException("offline") else null
        }
        val pending = PendingSaveStore.from(context, USER_ID)

        val result = repository(pending).save(
            DiaryEntry(mood = 4, note = "move target"),
            DATE,
            allowOfflineQueue = false,
        )

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is QueuedOfflineException)
        assertNull(pending.peek(DATE))
    }

    @Test
    fun `storage cleanup failure does not turn a persisted removal into data loss`() = runTest {
        val storedPhoto = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE.jpg"
        respond = { request ->
            if (request.method == "DELETE" && request.url.contains("/storage/v1/object/")) {
                HttpResponse(500, "{}")
            } else {
                null
            }
        }

        val result = repository().save(
            DiaryEntry(mood = 4, photoPath = storedPhoto, clearPhoto = true),
            DATE,
        )

        assertTrue("the row is cleared even if an orphan object remains", result.isSuccess)
        val patch = sent.single { it.method == "PATCH" && it.url.contains("/entries?") }
        val storageDelete = sent.single { it.method == "DELETE" && it.url.contains("/storage/v1/object/") }
        assertTrue(sent.indexOf(patch) < sent.indexOf(storageDelete))
    }

    @Test
    fun `an offline worker can defer cleanup until it removes the matching outbox item`() = runTest {
        val storedPhoto = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-webnonce.jpg"

        val result = repository().save(
            DiaryEntry(mood = 4, photoPath = storedPhoto, clearPhoto = true),
            DATE,
            cleanupPhotoAfterPersistence = false,
        )

        assertTrue(result.isSuccess)
        assertTrue(sent.any { it.method == "PATCH" && it.url.contains("/entries?") })
        assertTrue("the worker owns cleanup after its outbox compare-and-remove", sent.none {
            it.method == "DELETE" && it.url.contains("/storage/v1/object/")
        })
    }

    @Test
    fun `malformed and cross-owner stored photo URLs are never sent to storage delete`() = runTest {
        val unsafePaths = listOf(
            "https://attacker.example/storage/v1/object/public/diary-photos/$USER_ID/$DATE.jpg",
            "https://example.supabase.co/storage/v1/object/public/diary-photos/other-user/$DATE.jpg",
            "not a url",
            "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/%2e%2e/other.jpg",
        )

        for (path in unsafePaths) {
            sent.clear()
            val result = repository().save(
                DiaryEntry(mood = 4, photoPath = path, clearPhoto = true),
                DATE,
            )
            assertTrue(result.isSuccess)
            assertTrue("must not delete an unvalidated object for $path", sent.none {
                it.method == "DELETE" && it.url.contains("/storage/v1/object/")
            })
        }
    }

    @Test
    fun `replacing staged P1 with P2 cleans only the unreferenced owner-scoped P1`() = runTest {
        context.getSharedPreferences(PhotoCandidateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val candidates = PhotoCandidateStore.from(context, USER_ID)
        val photos = repository(photoCandidates = candidates)
        val first = photos.uploadPhoto(DATE, byteArrayOf(1, 2, 3)).url!!
        val second = photos.uploadPhoto(DATE, byteArrayOf(4, 5, 6)).url!!
        assertEquals(setOf(first, second), candidates.forDate(DATE).toSet())

        photos.cleanupPhotoCandidates(DATE, preservePaths = setOf(second))

        val deletes = sent.filter { it.method == "DELETE" && it.url.contains("/storage/v1/object/") }
        assertEquals(1, deletes.size)
        assertTrue(deletes.single().url.endsWith(first.substringAfter("/storage/v1/object/public/")))
        assertEquals(listOf(second), candidates.forDate(DATE))
    }

    @Test
    fun `candidate cleanup keeps and forgets a path already referenced by an entry`() = runTest {
        context.getSharedPreferences(PhotoCandidateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val path = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-saved.jpg"
        val candidates = PhotoCandidateStore.from(context, USER_ID)
        candidates.track(DATE, path)
        respond = { request ->
            if (request.method == "GET" && request.url.contains("photo_path")) {
                HttpResponse(200, JSONArray().put(JSONObject().put("date", DATE)).toString())
            } else null
        }

        repository(photoCandidates = candidates).cleanupPhotoCandidates(DATE)

        assertTrue(sent.none { it.method == "DELETE" && it.url.contains("/storage/v1/object/") })
        assertTrue(candidates.forDate(DATE).isEmpty())
    }

    @Test
    fun `candidate cleanup fails closed when reference read fails`() = runTest {
        context.getSharedPreferences(PhotoCandidateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val path = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-candidate.jpg"
        val candidates = PhotoCandidateStore.from(context, USER_ID)
        candidates.track(DATE, path)
        respond = { request ->
            if (request.method == "GET" && request.url.contains("photo_path")) {
                HttpResponse(500, "{}")
            } else null
        }

        repository(photoCandidates = candidates).cleanupPhotoCandidates(DATE)

        assertTrue(sent.none { it.method == "DELETE" && it.url.contains("/storage/v1/object/") })
        assertEquals(listOf(path), candidates.forDate(DATE))
    }

    @Test
    fun `uploads use unique owner-scoped staging paths without upsert`() = runTest {
        val first = repository().uploadPhoto(DATE, byteArrayOf(1, 2, 3))
        val second = repository().uploadPhoto(DATE, byteArrayOf(4, 5, 6))

        assertTrue(first.url != null)
        assertTrue(second.url != null)
        val uploads = sent.filter { it.method == "POST" && it.url.contains("/storage/v1/object/diary-photos/") }
        assertEquals(2, uploads.size)
        val paths = uploads.map { it.url.substringAfter("/storage/v1/object/diary-photos/") }
        assertTrue(paths.all { it.startsWith("$USER_ID/$DATE-") && it.endsWith(".jpg") })
        assertTrue("each upload must get an independent object", paths[0] != paths[1])
        assertTrue("a staging upload must never overwrite a stored object", uploads.all {
            it.headers["x-upsert"] != "true"
        })
    }

    @Test
    fun `upload transport errors return a recoverable result`() = runTest {
        respond = { request ->
            if (request.url.contains("/storage/v1/object/diary-photos/")) {
                throw IOException("offline")
            }
            null
        }

        val result = repository().uploadPhoto(DATE, byteArrayOf(1, 2, 3))

        assertEquals(null, result.url)
        assertTrue(result.error?.contains("připojit") == true)
    }

    @Test
    fun `tracking failure never returns the uploaded candidate or deletes it`() = runTest {
        val failingRegistry = object : PhotoCandidateRegistry {
            override val ownerId: String = USER_ID
            override fun track(date: String, path: String): Boolean = false
            override fun forDate(date: String): List<String> = emptyList()
            override fun remove(date: String, path: String): Boolean = false
        }

        val result = repository(photoCandidates = failingRegistry).uploadPhoto(DATE, byteArrayOf(1, 2, 3))

        assertNull(result.url)
        assertTrue(result.error?.contains("bezpečně") == true)
        assertEquals(1, sent.count { it.method == "POST" && it.url.contains("/storage/v1/object/diary-photos/") })
        assertTrue("the untracked object must never be deleted as if it were safe", sent.none {
            it.method == "DELETE" && it.url.contains("/storage/v1/object/")
        })
    }

    @Test
    fun `candidate survives process recreation and failed reference cleanup`() = runTest {
        context.getSharedPreferences(PhotoCandidateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val firstStore = PhotoCandidateStore.from(context, USER_ID)
        val uploaded = repository(photoCandidates = firstStore).uploadPhoto(DATE, byteArrayOf(7, 8, 9))
        val path = uploaded.url ?: error("upload should succeed with durable tracking")
        val restartedStore = PhotoCandidateStore.from(context, USER_ID)
        sent.clear()
        respond = { request ->
            if (request.method == "GET" && request.url.contains("photo_path")) HttpResponse(503, "{}") else null
        }

        repository(photoCandidates = restartedStore).cleanupPhotoCandidates(DATE)

        assertEquals(listOf(path), restartedStore.forDate(DATE))
        assertTrue(sent.none { it.method == "DELETE" && it.url.contains("/storage/v1/object/") })
    }

    @Test
    fun `photo replacement is rejected before save when original path cannot be durably tracked`() = runTest {
        val oldPath = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-old.jpg"
        val newPath = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE-new.jpg"
        val failingRegistry = object : PhotoCandidateRegistry {
            override val ownerId: String = USER_ID
            override fun track(date: String, path: String): Boolean = false
            override fun forDate(date: String): List<String> = emptyList()
            override fun remove(date: String, path: String): Boolean = false
        }

        val result = repository(photoCandidates = failingRegistry).save(
            DiaryEntry(mood = 4, photoPath = newPath, photoPathToDelete = oldPath),
            DATE,
        )

        assertTrue(result.isFailure)
        assertTrue("the remote row must keep its original photo", sent.none {
            it.url.contains("/rpc/save_daily_entry") || it.method == "PATCH"
        })
        assertTrue(sent.none { it.method == "DELETE" && it.url.contains("/storage/v1/object/") })
    }

    @Test
    fun `an ordinary edit keeps the photo and never patches it`() = runTest {
        val url = "https://example.supabase.co/storage/v1/object/public/diary-photos/$USER_ID/$DATE.jpg"

        val result = repository().save(DiaryEntry(mood = 4, photoPath = url), DATE)

        assertTrue(result.isSuccess)
        assertEquals(url, savePayload().getString("photo_path"))
        assertTrue("no explicit removal means no clearing write", sent.none { it.method == "PATCH" })
        assertTrue(sent.none { it.method == "DELETE" && it.url.contains("/storage/") })
    }

    @Test
    fun `a failed photo clear is reported and the object is kept`() = runTest {
        respond = { request ->
            if (request.method == "PATCH" && request.url.contains("/entries?")) {
                HttpResponse(500, """{"message":"nope"}""")
            } else {
                null
            }
        }

        val result = repository().save(DiaryEntry(mood = 4, clearPhoto = true), DATE)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("fotku"))
        assertTrue("the row still points at it, so the object must survive", sent.none {
            it.method == "DELETE" && it.url.contains("/storage/")
        })
    }

    // ── scale clearing ──────────────────────────────────────────────────────

    @Test
    fun `cleared scales delete only their own scale_entries rows`() = runTest {
        val entry = DiaryEntry(
            mood = 4,
            scaleValues = mapOf("energy" to 4),
            clearedScaleIds = setOf("focus"),
        )

        val result = repository().save(entry, DATE)

        assertTrue(result.isSuccess)
        val scales = savePayload().getJSONObject("scale_values")
        assertEquals(4, scales.getInt("energy"))
        assertFalse("a cleared scale is not part of the payload", scales.has("focus"))

        val deletes = sent.filter { it.method == "DELETE" && it.url.contains("/scale_entries") }
        assertEquals(1, deletes.size)
        val url = decoded(deletes.single())
        assertTrue(url.contains("user_id=eq.$USER_ID"))
        assertTrue(url.contains("scale_id=eq.focus"))
        assertTrue(url.contains("date=eq.$DATE"))
        assertFalse("the kept scale must not be deleted", url.contains("scale_id=eq.energy"))
    }

    @Test
    fun `a failed save never deletes scale rows`() = runTest {
        respond = { request ->
            if (request.url.contains("/rpc/save_daily_entry")) {
                HttpResponse(500, """{"message":"boom"}""")
            } else {
                null
            }
        }

        val result = repository().save(
            DiaryEntry(mood = 4, clearedScaleIds = setOf("focus")),
            DATE,
        )

        assertTrue(result.isFailure)
        assertTrue(sent.none { it.method == "DELETE" && it.url.contains("/scale_entries") })
    }

    @Test
    fun `a failed scale cleanup is reported so the stale stat is not silently accepted`() = runTest {
        respond = { request ->
            if (request.method == "DELETE" && request.url.contains("/scale_entries")) {
                HttpResponse(500, """{"message":"nope"}""")
            } else {
                null
            }
        }

        val result = repository().save(
            DiaryEntry(mood = 4, clearedScaleIds = setOf("focus")),
            DATE,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("škál"))
    }

    // ── move / delete cleanup ───────────────────────────────────────────────

    @Test
    fun `deleting a day also deletes its scale_entries`() = runTest {
        val result = repository().delete(DATE)

        assertTrue(result.isSuccess)
        val deletes = sent.filter { it.method == "DELETE" }
        assertEquals(2, deletes.size)
        val entries = deletes.single { it.url.contains("/entries?") }
        val scales = deletes.single { it.url.contains("/scale_entries") }
        assertTrue(decoded(entries).contains("date=eq.$DATE"))
        assertTrue(decoded(scales).contains("date=eq.$DATE"))
        assertTrue(decoded(scales).contains("user_id=eq.$USER_ID"))
    }

    @Test
    fun `a failed day delete leaves its scale rows alone`() = runTest {
        respond = { request ->
            if (request.method == "DELETE" && request.url.contains("/entries?")) {
                HttpResponse(500, """{"message":"boom"}""")
            } else {
                null
            }
        }

        val result = repository().delete(DATE)

        assertTrue(result.isFailure)
        assertTrue(sent.none { it.method == "DELETE" && it.url.contains("/scale_entries") })
    }

    @Test
    fun `a failed scale cleanup after a successful delete is reported`() = runTest {
        respond = { request ->
            if (request.method == "DELETE" && request.url.contains("/scale_entries")) {
                HttpResponse(500, """{"message":"nope"}""")
            } else {
                null
            }
        }

        val result = repository().delete(DATE)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("škál"))
    }

    @Test
    fun `foreground save uses the conditional v2 rpc`() = runTest {
        val result = repository().save(
            DiaryEntry(mood = 4, note = "synthetic"),
            DATE,
            baselineExists = false,
        )

        assertTrue(result.isSuccess)
        assertTrue("normal check-in saves must use the conditional RPC", sent.any {
            it.method == "POST" && it.url.endsWith("/rpc/save_daily_entry_v2")
        })
        assertTrue("foreground saves must never fall back to blind writes", sent.none {
            it.url.endsWith("/rpc/save_daily_entry")
        })
    }

    @Test
    fun `foreground save includes the immutable baseline and request id`() = runTest {
        val result = repository().save(
            DiaryEntry(mood = 4, note = "synthetic"),
            DATE,
            baselineExists = false,
            baselineId = null,
            baselineRevision = null,
            requestId = REQUEST_CREATE,
        )

        assertTrue(result.isSuccess)
        val request = sent.single { it.url.endsWith("/rpc/save_daily_entry_v2") }
        val body = JSONObject(request.body!!)
        assertEquals(5, body.length())
        assertEquals(false, body.getBoolean("p_expected_exists"))
        assertTrue(body.has("p_expected_id") && body.isNull("p_expected_id"))
        assertTrue(body.has("p_expected_revision") && body.isNull("p_expected_revision"))
        assertEquals(REQUEST_CREATE, body.getString("p_request_id"))
        assertTrue(body.getJSONObject("p_payload").has("note"))
        assertTrue(sent.none { it.url.endsWith("/rpc/save_daily_entry") })
    }

    @Test
    fun `stale revision conflict returns server state and preserves the unsent payload`() = runTest {
        val serverRow = JSONObject()
            .put("id", ROW_ID)
            .put("date", DATE)
            .put("revision", 8)
            .put("mood", 2)
            .put("note", "server copy")
        respond = { request ->
            when {
                request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                    200,
                    JSONObject()
                        .put("status", "conflict")
                        .put("reason", "stale_revision")
                        .put("date", DATE)
                        .put("current_id", ROW_ID)
                        .put("current_revision", 8)
                        .put("replayed", false)
                        .toString(),
                )
                request.method == "GET" && request.url.contains("/entries?") ->
                    HttpResponse(200, JSONArray().put(serverRow).toString())
                else -> null
            }
        }
        val local = DiaryEntry(mood = 5, note = "unsent local edit")

        val result = repository().save(
            local,
            DATE,
            baselineExists = true,
            baselineId = ROW_ID,
            baselineRevision = 7,
            requestId = REQUEST_STALE,
        )

        val conflict = result.exceptionOrNull() as ConditionalSaveConflictException
        assertEquals("stale_revision", conflict.reason)
        assertEquals("server copy", conflict.currentSnapshot?.entry?.note)
        assertEquals(ROW_ID, conflict.currentSnapshot?.id)
        assertEquals(8L, conflict.currentSnapshot?.revision)
        val sentPayload = JSONObject(sent.single { it.url.endsWith("/rpc/save_daily_entry_v2") }.body!!)
            .getJSONObject("p_payload")
        assertEquals("unsent local edit", sentPayload.getString("note"))
        assertTrue("conflicts must not trigger a destructive fallback", sent.none {
            it.url.endsWith("/rpc/save_daily_entry") || it.method == "PATCH" ||
                (it.method == "DELETE" && it.url.contains("/scale_entries"))
        })
    }

    @Test
    fun `two absent-baseline foreground creates leave the first row untouched`() = runTest {
        var created = false
        val winner = JSONObject()
            .put("id", ROW_ID)
            .put("date", DATE)
            .put("revision", 1)
            .put("mood", 3)
            .put("note", "first create")
        respond = { request ->
            when {
                request.url.endsWith("/rpc/save_daily_entry_v2") && !created -> {
                    created = true
                    HttpResponse(200, savedResponse(ROW_ID, 1).toString())
                }
                request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                    200,
                    JSONObject()
                        .put("status", "conflict")
                        .put("reason", "row_exists")
                        .put("date", DATE)
                        .put("current_id", ROW_ID)
                        .put("current_revision", 1)
                        .put("replayed", false)
                        .toString(),
                )
                request.method == "GET" && request.url.contains("/entries?") ->
                    HttpResponse(200, JSONArray().put(winner).toString())
                else -> null
            }
        }

        val first = repository().save(
            DiaryEntry(mood = 3, note = "first create"), DATE,
            baselineExists = false, requestId = REQUEST_CREATE,
        )
        val second = repository().save(
            DiaryEntry(mood = 5, note = "racing create"), DATE,
            baselineExists = false, requestId = REQUEST_RACE,
        )

        assertTrue(first.isSuccess)
        assertEquals("first create", (second.exceptionOrNull() as ConditionalSaveConflictException)
            .currentSnapshot?.entry?.note)
        assertEquals(2, sent.count { it.url.endsWith("/rpc/save_daily_entry_v2") })
        assertTrue(sent.none { it.url.endsWith("/rpc/save_daily_entry") })
    }

    @Test
    fun `an unknown network outcome retries the identical request id and body`() = runTest {
        var firstAttempt = true
        respond = { request ->
            when {
                request.url.endsWith("/rpc/save_daily_entry_v2") && firstAttempt -> {
                    firstAttempt = false // Synthetic server committed before the reply was lost.
                    throw IOException("reply lost after commit")
                }
                request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                    200,
                    savedResponse(ROW_ID, 1, replayed = true, includeEntry = false).toString(),
                )
                else -> null
            }
        }

        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 4, note = "retry-safe"),
            baseline = EntryBaseline(exists = false, id = null, revision = null),
            requestId = REQUEST_RETRY,
        )
        val repository = repository()
        val first = repository.save(request)
        val second = repository.save(request)

        assertTrue(first.exceptionOrNull() is ConditionalSaveUnknownException)
        assertTrue(second.isSuccess)
        assertTrue(second.getOrThrow().replayed)
        val requests = sent.filter { it.url.endsWith("/rpc/save_daily_entry_v2") }
        assertEquals(2, requests.size)
        assertEquals(requests[0].body, requests[1].body)
        assertEquals(REQUEST_RETRY, JSONObject(requests[0].body!!).getString("p_request_id"))
        assertTrue(sent.none { it.url.endsWith("/rpc/save_daily_entry") })
    }

    @Test
    fun `worker replays a previous-row update with the original CAS baseline`() = runTest {
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 5, note = "queued update"),
            baseline = EntryBaseline(exists = true, id = ROW_ID, revision = 7L),
            requestId = REQUEST_RETRY,
        )
        val store = PendingSaveStore.from(context, USER_ID)
        assertTrue(store.enqueueConditional(request))

        val result = flushThroughRepository(store, repository(store))

        assertTrue(result is PendingSaveFlush.Saved)
        val body = JSONObject(sent.single { it.url.endsWith("/rpc/save_daily_entry_v2") }.body!!)
        assertEquals(true, body.getBoolean("p_expected_exists"))
        assertEquals(ROW_ID, body.getString("p_expected_id"))
        assertEquals(7L, body.getLong("p_expected_revision"))
        assertEquals(request.requestId, body.getString("p_request_id"))
        assertEquals("queued update", body.getJSONObject("p_payload").getString("note"))
        assertNull(store.peekRecord(DATE))
        assertTrue(sent.none { it.url.endsWith("/rpc/save_daily_entry") })
    }

    @Test
    fun `missing-day creation race keeps queued copy and the other client's row`() = runTest {
        val serverRow = JSONObject()
            .put("id", ROW_ID)
            .put("date", DATE)
            .put("revision", 1)
            .put("mood", 2)
            .put("note", "other client's first row")
        respond = { request ->
            when {
                request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                    200,
                    JSONObject()
                        .put("status", "conflict")
                        .put("reason", "row_exists")
                        .put("date", DATE)
                        .put("current_id", ROW_ID)
                        .put("current_revision", 1)
                        .put("replayed", false)
                        .toString(),
                )
                request.method == "GET" && request.url.contains("/entries?") ->
                    HttpResponse(200, JSONArray().put(serverRow).toString())
                else -> null
            }
        }
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 4, note = "queued create"),
            baseline = EntryBaseline(exists = false, id = null, revision = null),
            requestId = REQUEST_CREATE,
        )
        val store = PendingSaveStore.from(context, USER_ID)
        store.enqueueConditional(request)

        val result = flushThroughRepository(store, repository(store))
        val retained = store.peekRecord(DATE)

        assertTrue(result is PendingSaveFlush.RecoveryRequired)
        assertEquals(request, retained?.conditionalRequest)
        assertEquals("queued create", retained?.entry?.note)
        assertTrue(retained?.recoveryReason?.contains("row_exists") == true)
        assertEquals("other client's first row", serverRow.getString("note"))
        assertTrue(sent.none { it.url.endsWith("/rpc/save_daily_entry") || it.method == "PATCH" })
    }

    @Test
    fun `older queued revision cannot overwrite newer foreground save from another client`() = runTest {
        val newerForegroundRow = JSONObject()
            .put("id", ROW_ID)
            .put("date", DATE)
            .put("revision", 9)
            .put("mood", 5)
            .put("note", "newer foreground save")
        respond = { request ->
            when {
                request.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                    200,
                    JSONObject()
                        .put("status", "conflict")
                        .put("reason", "stale_revision")
                        .put("date", DATE)
                        .put("current_id", ROW_ID)
                        .put("current_revision", 9)
                        .put("replayed", false)
                        .toString(),
                )
                request.method == "GET" && request.url.contains("/entries?") ->
                    HttpResponse(200, JSONArray().put(newerForegroundRow).toString())
                else -> null
            }
        }
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 1, note = "older offline copy"),
            baseline = EntryBaseline(exists = true, id = ROW_ID, revision = 8L),
            requestId = REQUEST_STALE,
        )
        val store = PendingSaveStore.from(context, USER_ID)
        store.enqueueConditional(request)

        val result = flushThroughRepository(store, repository(store))
        val retained = store.peekRecord(DATE)

        assertTrue(result is PendingSaveFlush.RecoveryRequired)
        assertEquals(request, retained?.conditionalRequest)
        assertEquals("older offline copy", retained?.entry?.note)
        assertEquals("newer foreground save", newerForegroundRow.getString("note"))
        assertTrue(retained?.recoveryReason?.contains("stale_revision") == true)
        assertTrue(sent.none { it.url.endsWith("/rpc/save_daily_entry") || it.method == "PATCH" })
    }

    @Test
    fun `unknown worker response retries exact request after process restart`() = runTest {
        var firstAttempt = true
        val acceptedRow = JSONObject()
            .put("id", ROW_ID)
            .put("date", DATE)
            .put("revision", 1)
            .put("mood", 4)
            .put("note", "unknown response survives")
        respond = { request ->
            when {
                request.url.endsWith("/rpc/save_daily_entry_v2") && firstAttempt -> {
                    firstAttempt = false // Commit happened but the response was lost.
                    throw IOException("response timeout")
                }
                request.url.endsWith("/rpc/save_daily_entry_v2") ->
                    HttpResponse(200, savedResponse(ROW_ID, 1, replayed = true, includeEntry = false).toString())
                request.method == "GET" && request.url.contains("/entries?") ->
                    HttpResponse(200, JSONArray().put(acceptedRow).toString())
                else -> null
            }
        }
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 4, note = "unknown response survives", aiReflection = "generated before timeout"),
            baseline = EntryBaseline(exists = false, id = null, revision = null),
            requestId = REQUEST_RETRY,
            writeAiReflection = true,
        )
        val initialStore = PendingSaveStore.from(context, USER_ID)
        val initialRepository = repository(initialStore)
        val initial = initialRepository.save(request)
        val afterRestartStore = PendingSaveStore.from(context, USER_ID)
        val afterRestartRepository = repository(afterRestartStore)

        assertTrue(initial.exceptionOrNull() is ConditionalSaveUnknownException)
        assertEquals(request, afterRestartStore.peekRecord(DATE)?.conditionalRequest)
        val replay = flushThroughRepository(afterRestartStore, afterRestartRepository)
        val writes = sent.filter { it.url.endsWith("/rpc/save_daily_entry_v2") }

        assertTrue(replay is PendingSaveFlush.Saved)
        assertEquals(2, writes.size)
        assertEquals(writes[0].body, writes[1].body)
        assertEquals(REQUEST_RETRY, JSONObject(writes[0].body!!).getString("p_request_id"))
        assertEquals(
            "generated before timeout",
            JSONObject(writes[1].body!!).getJSONObject("p_payload").getString("ai_reflection"),
        )
        assertNull(afterRestartStore.peekRecord(DATE))
        assertTrue(sent.none { it.url.endsWith("/rpc/save_daily_entry") })
    }

    @Test
    fun `a replayed receipt is retained when a legacy write advanced the server revision`() = runTest {
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 4, note = "accepted CAS payload"),
            baseline = EntryBaseline(exists = false, id = null, revision = null),
            requestId = REQUEST_RETRY,
        )
        val pending = PendingSaveStore.from(context, USER_ID)
        assertTrue(pending.enqueueConditional(request))
        val current = JSONObject()
            .put("id", ROW_ID)
            .put("date", DATE)
            .put("revision", 2)
            .put("mood", 5)
            .put("note", "intervening legacy write")
        respond = { sentRequest ->
            when {
                sentRequest.url.endsWith("/rpc/save_daily_entry_v2") ->
                    HttpResponse(200, savedResponse(ROW_ID, 1, replayed = true, includeEntry = false).toString())
                sentRequest.method == "GET" && sentRequest.url.contains("/entries?") ->
                    HttpResponse(200, JSONArray().put(current).toString())
                else -> null
            }
        }

        val result = flushThroughRepository(pending, repository(pending))
        val retained = pending.peekRecord(DATE)

        assertTrue("a historical receipt is not proof the current row still matches", result is PendingSaveFlush.RecoveryRequired)
        assertEquals(request, retained?.conditionalRequest)
        assertEquals("accepted CAS payload", retained?.entry?.note)
        assertTrue(retained?.recoveryReason.orEmpty().contains("replayed_receipt"))
        assertEquals("intervening legacy write", current.getString("note"))
        assertEquals(1, sent.count { it.url.endsWith("/rpc/save_daily_entry_v2") })
        assertTrue("reconciliation must not issue a blind fallback", sent.none {
            it.url.endsWith("/rpc/save_daily_entry") || it.method == "PATCH"
        })
    }

    @Test
    fun `a replayed receipt is retained when the current row identity differs`() = runTest {
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 4, note = "accepted CAS payload"),
            baseline = EntryBaseline(exists = false, id = null, revision = null),
            requestId = REQUEST_RACE,
        )
        val pending = PendingSaveStore.from(context, USER_ID)
        assertTrue(pending.enqueueConditional(request))
        val current = JSONObject()
            .put("id", OTHER_ROW_ID)
            .put("date", DATE)
            .put("revision", 1)
            .put("mood", 5)
            .put("note", "replacement row")
        respond = { sentRequest ->
            when {
                sentRequest.url.endsWith("/rpc/save_daily_entry_v2") ->
                    HttpResponse(200, savedResponse(ROW_ID, 1, replayed = true, includeEntry = false).toString())
                sentRequest.method == "GET" && sentRequest.url.contains("/entries?") ->
                    HttpResponse(200, JSONArray().put(current).toString())
                else -> null
            }
        }

        val result = flushThroughRepository(pending, repository(pending))

        assertTrue(result is PendingSaveFlush.RecoveryRequired)
        assertEquals(request, pending.peekRecord(DATE)?.conditionalRequest)
        assertTrue(pending.peekRecord(DATE)?.recoveryReason.orEmpty().contains("replayed_receipt"))
        assertEquals(OTHER_ROW_ID, current.getString("id"))
        assertTrue(sent.none { it.url.endsWith("/rpc/save_daily_entry") || it.method == "PATCH" })
    }

    @Test
    fun `a replayed receipt is retained when the current row cannot be read`() = runTest {
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 4, note = "accepted CAS payload"),
            baseline = EntryBaseline(exists = false, id = null, revision = null),
            requestId = REQUEST_STALE,
        )
        val pending = PendingSaveStore.from(context, USER_ID)
        assertTrue(pending.enqueueConditional(request))
        respond = { sentRequest ->
            when {
                sentRequest.url.endsWith("/rpc/save_daily_entry_v2") ->
                    HttpResponse(200, savedResponse(ROW_ID, 1, replayed = true, includeEntry = false).toString())
                sentRequest.method == "GET" && sentRequest.url.contains("/entries?") ->
                    HttpResponse(503, """{"message":"read unavailable"}""")
                else -> null
            }
        }

        val result = flushThroughRepository(pending, repository(pending))
        val retained = pending.peekRecord(DATE)

        assertTrue(result is PendingSaveFlush.RecoveryRequired)
        assertEquals(request, retained?.conditionalRequest)
        assertTrue(retained?.recoveryReason.orEmpty().contains("replayed_receipt"))
        assertTrue(sent.none { it.url.endsWith("/rpc/save_daily_entry") || it.method == "PATCH" })
    }

    @Test
    fun `a replayed receipt remains saved only when the server confirms its identity and revision`() = runTest {
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 4, note = "accepted CAS payload"),
            baseline = EntryBaseline(exists = false, id = null, revision = null),
            requestId = REQUEST_RETRY,
        )
        val pending = PendingSaveStore.from(context, USER_ID)
        assertTrue(pending.enqueueConditional(request))
        val current = JSONObject()
            .put("id", ROW_ID)
            .put("date", DATE)
            .put("revision", 1)
            .put("mood", 4)
            .put("note", "accepted CAS payload")
        respond = { sentRequest ->
            when {
                sentRequest.url.endsWith("/rpc/save_daily_entry_v2") ->
                    HttpResponse(200, savedResponse(ROW_ID, 1, replayed = true, includeEntry = false).toString())
                sentRequest.method == "GET" && sentRequest.url.contains("/entries?") ->
                    HttpResponse(200, JSONArray().put(current).toString())
                else -> null
            }
        }

        val result = flushThroughRepository(pending, repository(pending))

        assertTrue("the accepted write is synced after its current receipt is verified", result is PendingSaveFlush.Saved)
        assertNull(pending.peekRecord(DATE))
        assertEquals(1, sent.count { it.url.endsWith("/rpc/save_daily_entry_v2") })
        assertTrue(sent.any { it.method == "GET" && it.url.contains("/entries?") })
    }

    @Test
    fun `worker stops without sending when the account changes`() = runTest {
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 4, note = "private A copy"),
            baseline = EntryBaseline(exists = false, id = null, revision = null),
            requestId = REQUEST_CREATE,
        )
        val store = PendingSaveStore.from(context, USER_ID)
        store.enqueueConditional(request)
        sessionStore.save(
            JSONObject().apply {
                put("access_token", "jwt-account-b")
                put("refresh_token", "refresh-b")
                put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                put("user", JSONObject().put("id", OTHER_USER_ID))
            },
        )

        val result = flushThroughRepository(store, repository(store))

        assertEquals(PendingSaveFlush.SessionChanged, result)
        assertEquals(request, store.peekRecord(DATE)?.conditionalRequest)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `an unknown reflection-save outcome replays the identical reflection payload`() = runTest {
        var firstAttempt = true
        respond = { request ->
            when {
                request.url.endsWith("/rpc/save_daily_entry_v2") && firstAttempt -> {
                    firstAttempt = false
                    throw IOException("reply lost after commit")
                }
                request.url.endsWith("/rpc/save_daily_entry_v2") ->
                    HttpResponse(200, savedResponse(ROW_ID, 1, replayed = true, includeEntry = false).toString())
                else -> null
            }
        }
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 4, note = "reflection owner", aiReflection = "Generated reflection"),
            baseline = EntryBaseline(exists = false, id = null, revision = null),
            requestId = REQUEST_REFLECTION,
            writeAiReflection = true,
        )

        val first = repository().save(request)
        val second = repository().save(request)

        assertTrue(first.exceptionOrNull() is ConditionalSaveUnknownException)
        assertTrue(second.isSuccess)
        val requests = sent.filter { it.url.endsWith("/rpc/save_daily_entry_v2") }
        assertEquals(2, requests.size)
        assertEquals(requests[0].body, requests[1].body)
        assertEquals(
            "Generated reflection",
            JSONObject(requests[0].body!!).getJSONObject("p_payload").getString("ai_reflection"),
        )
    }

    @Test
    fun `legacy saves leave CAS and malformed recovery records untouched`() = runTest {
        val pending = PendingSaveStore.from(context, USER_ID)
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 2, note = "original CAS payload"),
            baseline = EntryBaseline(exists = false, id = null, revision = null),
            requestId = REQUEST_RETRY,
        )
        assertTrue(pending.enqueueConditional(request))
        val blockedWhileReplayable = repository(pending).save(DiaryEntry(mood = 5, note = "new legacy edit"), DATE)
        assertTrue(blockedWhileReplayable.isFailure)
        assertEquals(request, pending.peekRecord(DATE)?.conditionalRequest)
        assertNull(pending.peekRecord(DATE)?.recoveryReason)
        assertTrue(sent.isEmpty())

        val conflict = pending.flushRecord(
            date = DATE,
            expectedOwnerId = USER_ID,
            currentOwnerId = { sessionStore.userSubject() },
            dispatch = { Result.failure(ConditionalSaveConflictException("stale_revision", null)) },
        )
        assertTrue(conflict is PendingSaveFlush.RecoveryRequired)

        val blocked = repository(pending).save(DiaryEntry(mood = 5, note = "new legacy edit"), DATE)
        assertTrue(blocked.isFailure)
        assertEquals(request, pending.peekRecord(DATE)?.conditionalRequest)
        assertTrue(pending.peekRecord(DATE)?.recoveryReason?.contains("stale_revision") == true)
        assertTrue(sent.isEmpty())

        val malformed = "not-json"
        context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PendingSaveStore.key(USER_ID, DATE), malformed).commit()
        val malformedAttempt = repository(pending).save(DiaryEntry(mood = 1, note = "must not replace"), DATE)
        assertTrue(malformedAttempt.isFailure)
        assertEquals(malformed, context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PendingSaveStore.key(USER_ID, DATE), null))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a server without the v2 rpc fails closed without a legacy write`() = runTest {
        respond = { request ->
            if (request.url.endsWith("/rpc/save_daily_entry_v2")) {
                HttpResponse(404, """{"message":"Could not find the function"}""")
            } else null
        }

        val result = repository().save(
            DiaryEntry(mood = 4, note = "kept as draft"), DATE,
            baselineExists = false, requestId = REQUEST_OLD_SERVER,
        )

        assertTrue(result.isFailure)
        assertEquals(1, sent.count { it.url.endsWith("/rpc/save_daily_entry_v2") })
        assertTrue(sent.none { it.url.endsWith("/rpc/save_daily_entry") })
        assertTrue(sent.none { it.method == "PATCH" || it.method == "DELETE" })
    }

    @Test
    fun `an incomplete or malformed stored-row token is rejected before dispatch`() = runTest {
        val repo = repository()
        val incomplete = repo.save(
            ConditionalEntrySaveRequest(
                ownerId = USER_ID,
                date = DATE,
                entry = DiaryEntry(mood = 4, note = "kept locally"),
                baseline = EntryBaseline(exists = true, id = ROW_ID, revision = null),
                requestId = REQUEST_STALE,
            ),
        )
        val malformedId = repo.save(
            ConditionalEntrySaveRequest(
                ownerId = USER_ID,
                date = DATE,
                entry = DiaryEntry(mood = 4, note = "also kept locally"),
                baseline = EntryBaseline(exists = true, id = "not-a-uuid", revision = 2L),
                requestId = REQUEST_CREATE,
            ),
        )

        assertTrue(incomplete.exceptionOrNull() is ConditionalSaveUnavailableException)
        assertTrue(malformedId.exceptionOrNull() is ConditionalSaveUnavailableException)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a request pinned to account A sends nothing after switching to B`() = runTest {
        sessionStore.save(
            JSONObject().apply {
                put("access_token", "jwt-account-b")
                put("refresh_token", "refresh-b")
                put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                put("user", JSONObject().put("id", OTHER_USER_ID))
            },
        )
        val request = ConditionalEntrySaveRequest(
            ownerId = USER_ID,
            date = DATE,
            entry = DiaryEntry(mood = 4, note = "A's private draft"),
            baseline = EntryBaseline(exists = false, id = null, revision = null),
            requestId = REQUEST_CREATE,
        )

        val result = repository().save(request)

        assertTrue(result.isFailure)
        assertTrue("account A's pinned request must not be sent as B", sent.isEmpty())
    }

    @Test
    fun `photo and scale clears travel inside the atomic foreground rpc`() = runTest {
        val result = repository().save(
            DiaryEntry(
                mood = 4,
                clearPhoto = true,
                clearedScaleIds = setOf("focus", "energy"),
            ),
            DATE,
            baselineExists = false,
        )

        assertTrue(result.isSuccess)
        val payload = savePayload()
        assertTrue(payload.getBoolean("clear_photo"))
        assertEquals(listOf("energy", "focus"), payload.getJSONArray("clear_scale_ids").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertTrue("row/mirror clears must not be separate destructive requests", sent.none {
            it.method == "PATCH" && it.url.contains("/entries?") ||
                it.method == "DELETE" && it.url.contains("/scale_entries")
        })
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private suspend fun flushThroughRepository(
        store: PendingSaveStore,
        repository: EntriesRepository,
    ): PendingSaveFlush = store.flushRecord(
        date = DATE,
        expectedOwnerId = USER_ID,
        currentOwnerId = { sessionStore.userSubject() },
        dispatch = { snapshot ->
            repository.replayPendingSave(requireNotNull(snapshot.conditionalRequest)).map { Unit }
        },
    )

    private fun savedResponse(
        entryId: String,
        revision: Long,
        replayed: Boolean = false,
        includeEntry: Boolean = true,
    ): JSONObject = JSONObject()
        .put("status", "saved")
        .put("entry_id", entryId)
        .put("revision", revision)
        .put("replayed", replayed)
        .apply {
            if (includeEntry) {
                put(
                    "entry",
                    JSONObject()
                        .put("id", entryId)
                        .put("date", DATE)
                        .put("revision", revision),
                )
            }
        }

    private fun savePayload(): JSONObject {
        val post = sent.single {
            it.url.endsWith("/rpc/save_daily_entry_v2") || it.url.endsWith("/rpc/save_daily_entry")
        }
        val body = JSONObject(post.body!!)
        return body.getJSONObject("p_payload")
    }

    private fun decoded(request: HttpRequest): String =
        URLDecoder.decode(request.url.substringAfter('?'), Charsets.UTF_8.name())

    private fun repository(
        pending: PendingSaveStore? = null,
        photoCandidates: PhotoCandidateRegistry? = PhotoCandidateStore.from(context, USER_ID),
    ): EntriesRepository = EntriesRepository(
        client = SupabaseClient(
            sessionStore = sessionStore,
            transport = transport,
            baseUrl = BASE_URL,
            anonKey = "anon-test-key",
        ),
        session = SessionContext(sessionStore),
        pending = pending,
        expectedOwnerId = USER_ID,
        photoCandidates = photoCandidates,
    )

    private fun defaultResponse(request: HttpRequest): HttpResponse = when {
        request.url.endsWith("/rpc/save_daily_entry_v2") -> {
            val body = JSONObject(request.body!!)
            val expectedRevision = if (body.isNull("p_expected_revision")) 0L else body.optLong("p_expected_revision")
            HttpResponse(200, savedResponse(ROW_ID, expectedRevision + 1, includeEntry = false).toString())
        }
        request.url.endsWith("/rpc/save_daily_entry") -> HttpResponse(200, "{}")
        request.url.contains("/rpc/check_achievements") -> HttpResponse(200, "{}")
        request.method == "PATCH" && request.url.contains("/entries?") -> HttpResponse(204, "")
        request.method == "DELETE" && request.url.contains("/scale_entries") -> HttpResponse(204, "")
        request.method == "DELETE" && request.url.contains("/entries?") -> HttpResponse(204, "")
        request.method == "DELETE" && request.url.contains("/storage/v1/object/") -> HttpResponse(200, "{}")
        else -> HttpResponse(200, "[]")
    }

    private companion object {
        const val DATE = "2026-09-10"
        const val TARGET_DATE = "2026-09-11"
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val OTHER_USER_ID = "9e0a6e91-36c0-4e84-a6ed-f05d92e0d521"
        const val ROW_ID = "ecaf917a-7c84-4ad5-b657-cc5d9af10a36"
        const val OTHER_ROW_ID = "ecaf917a-7c84-4ad5-b657-cc5d9af10a37"
        const val REQUEST_CREATE = "10000000-0000-4000-8000-000000000001"
        const val REQUEST_RACE = "10000000-0000-4000-8000-000000000002"
        const val REQUEST_STALE = "10000000-0000-4000-8000-000000000003"
        const val REQUEST_RETRY = "10000000-0000-4000-8000-000000000004"
        const val REQUEST_OLD_SERVER = "10000000-0000-4000-8000-000000000005"
        const val REQUEST_REFLECTION = "10000000-0000-4000-8000-000000000006"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
    }
}
