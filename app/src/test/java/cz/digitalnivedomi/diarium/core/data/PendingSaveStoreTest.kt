package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        store = PendingSaveStore.from(context)
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
            .putString("2026-09-08", "not json at all")
            // Valid JSON but no entry object — e.g. written by a future/older shape.
            .putString("2026-09-09", JSONObject().put("queuedAt", 42L).toString())
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
}
