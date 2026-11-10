package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The offline outbox: a save that could not reach the server stays queued per
 * date, the newest local save wins, and reads stay lenient when a stored value
 * is corrupt.
 *
 * Robolectric with the real SharedPreferences on purpose — the store's whole
 * contract is "what the save path wrote is what the worker reads back", so a
 * fake map would test nothing that matters here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PendingSaveStoreTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var store: PendingSaveStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Start every test from an empty outbox: a leftover value must never
        // leak into peekAll/size assertions.
        prefs = context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = PendingSaveStore.from(context, OWNER_A)
    }

    @Test
    fun `worker does not dispatch owner A snapshot after account switch while waiting on its date lock`() = runBlocking {
        val date = "2026-09-10"
        val entry = DiaryEntry(mood = 5, note = "account A only")
        store.enqueue(date, entry)
        val lockEntered = CountDownLatch(1)
        val releaseLock = CountDownLatch(1)
        val held = async(Dispatchers.IO) {
            store.withDateLock(date) {
                lockEntered.countDown()
                check(releaseLock.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(lockEntered.await(5, TimeUnit.SECONDS))

        val currentOwner = AtomicReference(OWNER_A)
        val dispatchCount = AtomicInteger()
        val worker = async(Dispatchers.IO) {
            store.flushRecord(
                date,
                OWNER_A,
                currentOwnerId = { currentOwner.get() },
                dispatch = {
                    dispatchCount.incrementAndGet()
                    Result.success(Unit)
                },
            )
        }
        currentOwner.set(OWNER_B)
        releaseLock.countDown()

        assertEquals(PendingSaveFlush.SessionChanged, worker.await())
        held.await()
        assertEquals(0, dispatchCount.get())
        assertEquals(entry, store.peek(date))
        assertNull(PendingSaveStore.from(context, OWNER_B).peek(date))
    }

    @Test
    fun `automatic flush refuses unverified outbox rows and preserves a racing remote edit`() = runBlocking {
        val date = "2026-09-10"
        val local = DiaryEntry(mood = 2, note = "offline client A")
        store.enqueue(
            date = date,
            entry = local,
            baselineUpdatedAt = "revision-1",
            baselineExists = true,
        )
        val serverRevision = AtomicReference("revision-2")
        val serverEntry = AtomicReference(DiaryEntry(mood = 5, note = "newer client B edit"))
        val dispatchCount = AtomicInteger()

        val result = store.flushRecord(
            date = date,
            expectedOwnerId = OWNER_A,
            currentOwnerId = { OWNER_A },
            dispatch = { snapshot ->
                dispatchCount.incrementAndGet()
                // A blind full-row RPC would replace client B's revision here.
                serverEntry.set(snapshot.entry)
                serverRevision.set("revision-3")
                Result.success(Unit)
            },
        )

        assertFalse("no atomic server compare-and-swap is available", result is PendingSaveFlush.Saved)
        assertEquals(0, dispatchCount.get())
        assertEquals("revision-2", serverRevision.get())
        assertEquals("newer client B edit", serverEntry.get().note)
        assertEquals(local, store.peek(date))
        assertEquals("revision-1", store.peekRecord(date)?.baselineUpdatedAt)
    }

    @Test
    fun `enqueue then peek round trips mood note and gratitude`() {
        val date = "2026-09-10"
        val entry = DiaryEntry(
            mood = 4,
            moodEmoji = "🙂",
            sleepQuality = 3,
            stress = 2,
            activities = listOf("🏋️ Cvičení"),
            habits = mapOf("alkohol" to true),
            gratitude = listOf("Rodina", "", "Práce"),
            note = "uloženo offline",
            scaleValues = mapOf("energy" to 5),
        )

        store.enqueue(date, entry)

        val restored = store.peek(date)
        assertNotNull(restored)
        // The whole blob must survive the DiaryEntry codec, not just the fields
        // asserted below.
        assertEquals(entry, restored)
        assertEquals(4, restored?.mood)
        assertEquals("uloženo offline", restored?.note)
        // The empty slot stays in the middle: box 3 must not slide into box 2.
        assertEquals("Rodina", restored?.gratitude?.get(0))
        assertEquals("", restored?.gratitude?.get(1))
        assertEquals("Práce", restored?.gratitude?.get(2))
    }

    @Test
    fun `peek returns null for a date that was never queued`() {
        assertNull(store.peek("2026-09-10"))
    }

    @Test
    fun `enqueue overwrites so the newest local save wins`() {
        val date = "2026-09-10"
        store.enqueue(date, DiaryEntry(mood = 1, moodEmoji = "😡", note = "první verze"), queuedAt = 1_000L)
        store.enqueue(date, DiaryEntry(mood = 5, moodEmoji = "😄", note = "druhá verze"), queuedAt = 2_000L)

        assertEquals(1, store.size())
        assertEquals("druhá verze", store.peek(date)?.note)
        assertEquals(5, store.peek(date)?.mood)
        // The surviving blob carries the newest queue timestamp, not the first one.
        assertEquals(2_000L, store.peekAll().single().queuedAt)
    }

    @Test
    fun `a completed older flush cannot remove a newer queued photo edit`() {
        val date = "2026-09-10"
        val old = DiaryEntry(
            mood = 4,
            photoPath = "https://example.supabase.co/storage/v1/object/public/diary-photos/user/$date-old.jpg",
            photoPathToDelete = "https://example.supabase.co/storage/v1/object/public/diary-photos/user/$date-before.jpg",
        )
        val newer = old.copy(photoPath = "https://example.supabase.co/storage/v1/object/public/diary-photos/user/$date-new.jpg")
        store.enqueue(date, old, queuedAt = 1_000L)
        val workerSnapshot = store.peekAll().single()
        store.enqueue(date, newer, queuedAt = 2_000L)

        var staleCleanupRan = false
        assertFalse(store.removeIfUnchanged(workerSnapshot) { staleCleanupRan = true })
        assertFalse("stale photo cleanup must not run", staleCleanupRan)
        assertEquals(newer, store.peek(date))
    }

    @Test
    fun `post-flush cleanup callback runs only while removing the matching queued save`() {
        val date = "2026-09-10"
        val entry = DiaryEntry(mood = 4)
        store.enqueue(date, entry, queuedAt = 1_000L)
        val workerSnapshot = store.peekAll().single()
        var cleanupRan = false

        assertTrue(store.removeIfUnchanged(workerSnapshot) { cleanupRan = true })

        assertTrue(cleanupRan)
        assertNull(store.peek(date))
    }

    @Test
    fun `replacement cleanup path survives the outbox round trip`() {
        val entry = DiaryEntry(
            mood = 4,
            photoPath = "https://example.supabase.co/storage/v1/object/public/diary-photos/user/2026-09-10-new.jpg",
            photoPathToDelete = "https://example.supabase.co/storage/v1/object/public/diary-photos/user/2026-09-10-old.jpg",
        )

        store.enqueue("2026-09-10", entry)

        assertEquals(entry, store.peek("2026-09-10"))
    }

    @Test
    fun `identical replacement transaction cannot be removed by an in flight older flush`() {
        val date = "2026-09-10"
        val sameEntry = DiaryEntry(mood = 4, note = "same payload")
        store.enqueue(date, sameEntry, queuedAt = 1_000L)
        val workerSnapshot = store.peekAll().single()
        store.enqueue(date, sameEntry, queuedAt = 1_000L)

        assertFalse(store.removeIfUnchanged(workerSnapshot))
        assertEquals(sameEntry, store.peek(date))
        assertEquals(1, store.peekAll().size)
    }

    @Test
    fun `outbox records are isolated by authenticated owner`() {
        val date = "2026-09-10"
        store.enqueue(date, DiaryEntry(mood = 4, note = "A private note"), queuedAt = 7L)
        val accountB = PendingSaveStore.from(context, OWNER_B)

        assertNull(accountB.peek(date))
        assertTrue(accountB.peekAll().isEmpty())
        assertFalse(accountB.hasAny())
        assertEquals("A private note", store.peek(date)?.note)
        assertEquals(OWNER_A, store.peekAll().single().ownerId)
    }

    @Test
    fun `legacy ownerless outbox records stay quarantined and are never replayed`() {
        val date = "2026-09-10"
        val legacy = JSONObject()
            .put("queuedAt", 12L)
            .put("entry", DiaryEntry(mood = 5, note = "owner unknown").toJson())
            .toString()
        prefs.edit().putString(date, legacy).commit()
        val accountB = PendingSaveStore.from(context, OWNER_B)

        assertNull(store.peek(date))
        assertNull(accountB.peek(date))
        assertTrue(store.peekAll().isEmpty())
        assertTrue(accountB.peekAll().isEmpty())
        assertEquals("owner unknown", JSONObject(prefs.getString(date, null)!!)
            .getJSONObject("entry").getString("note"))
    }

    @Test
    fun `peekAll returns pending saves sorted by date ascending`() {
        // Queued out of order on purpose: insertion order must not leak through.
        store.enqueue("2026-09-11", DiaryEntry(mood = 3))
        store.enqueue("2026-09-09", DiaryEntry(mood = 1))
        store.enqueue("2026-09-10", DiaryEntry(mood = 2))

        val all = store.peekAll()
        assertEquals(listOf("2026-09-09", "2026-09-10", "2026-09-11"), all.map { it.date })
        assertEquals(listOf(1, 2, 3), all.map { it.entry.mood })
    }

    @Test
    fun `remove deletes one date and leaves the rest queued`() {
        store.enqueue("2026-09-09", DiaryEntry(mood = 1))
        store.enqueue("2026-09-10", DiaryEntry(mood = 2))
        assertEquals(2, store.size())

        store.remove("2026-09-09")

        assertEquals(1, store.size())
        assertNull(store.peek("2026-09-09"))
        assertEquals(2, store.peek("2026-09-10")?.mood)

        // Removing a date that is not queued is a harmless no-op.
        store.remove("2026-09-09")
        assertEquals(1, store.size())
    }

    @Test
    fun `malformed values decode to null and are skipped by peekAll`() {
        prefs.edit()
            // Not JSON at all.
            .putString(PendingSaveStore.key(OWNER_A, "2026-09-08"), "not json at all")
            // Valid JSON but no entry object — e.g. written by a future/older shape.
            .putString(
                PendingSaveStore.key(OWNER_A, "2026-09-09"),
                JSONObject().put("ownerId", OWNER_A).put("queuedAt", 42L).toString(),
            )
            .commit()
        store.enqueue("2026-09-11", DiaryEntry(mood = 4, note = "sends later"))

        assertNull(store.peek("2026-09-08"))
        assertNull(store.peek("2026-09-09"))

        val all = store.peekAll()
        assertEquals(listOf("2026-09-11"), all.map { it.date })
        assertEquals(1, all.size)
        // Corrupt values are not flushable saves, so they are not counted as work.
        assertEquals(1, store.size())
    }

    @Test
    fun `hasAny and size track what the outbox holds`() {
        assertFalse(store.hasAny())
        assertEquals(0, store.size())

        store.enqueue("2026-09-10", DiaryEntry(mood = 3))
        store.enqueue("2026-09-11", DiaryEntry(mood = 4))
        assertTrue(store.hasAny())
        assertEquals(2, store.size())

        store.remove("2026-09-10")
        assertEquals(1, store.size())

        store.remove("2026-09-11")
        assertFalse(store.hasAny())
        assertEquals(0, store.size())
    }

    @Test
    fun `prefs file name stays stable for the worker`() {
        assertEquals("diarium_pending_saves", PendingSaveStore.PREFS_NAME)
    }

    private companion object {
        const val OWNER_A = "account-a"
        const val OWNER_B = "account-b"
    }
}
