package cz.digitalnivedomi.diarium.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Draft persistence, the native twin of the web's `diarium_draft_{date}`
 * localStorage keys: autosave per date, restore when the DB has nothing,
 * clear after a successful save.
 *
 * Gratitude is asserted with an empty slot in the middle on purpose — the web
 * keeps `data.gratitude[i]` by index, so a draft that compacts the array would
 * move the user's text into the wrong box when the app is reopened.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DraftStoreTest {

    /**
     * A store plus the scope backing it. DataStore refuses to open one file
     * twice while the first instance is alive, so [close] has to be called
     * before a test reopens the same file — which is exactly what the
     * "survives a reopening" test means.
     */
    private class Handle(val store: DraftStore, private val scope: CoroutineScope) {
        suspend fun close() {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private fun newStore(file: File): Handle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        return Handle(
            DraftStore(
                PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = { file },
                ),
            ),
            scope,
        )
    }

    private fun tempFile(): File =
        File.createTempFile("diarium_draft_test", ".preferences_pb").also { it.delete() }

    @Test
    fun `save restore and clear round trip a draft`() = runBlocking {
        val handle = newStore(tempFile())
        val store = handle.store
        val date = "2026-09-10"
        val entry = DiaryEntry(
            mood = 4,
            moodEmoji = "🙂",
            sleepQuality = 3,
            stress = 2,
            activities = listOf("🏋️ Cvičení"),
            habits = mapOf("alkohol" to true),
            gratitude = listOf("Rodina", "", "Práce"),
            note = "rozepsaná poznámka",
            scaleValues = mapOf("energy" to 5),
        )

        // Nothing stored yet.
        assertNull(store.load(date))

        store.save(date, entry)
        val restored = store.load(date)

        assertNotNull(restored)
        assertEquals(entry, restored)
        assertEquals(4, restored?.mood)
        assertEquals("rozepsaná poznámka", restored?.note)
        // The empty slot stays in the middle: box 3 must not slide into box 2.
        assertEquals("Rodina", restored?.gratitude?.get(0))
        assertEquals("", restored?.gratitude?.get(1))
        assertEquals("Práce", restored?.gratitude?.get(2))

        // A successful save clears the draft.
        store.clear(date)
        assertNull(store.load(date))

        handle.close()
    }

    @Test
    fun `drafts are scoped per date`() = runBlocking {
        val handle = newStore(tempFile())
        val store = handle.store

        store.save("2026-09-10", DiaryEntry(mood = 5, moodEmoji = "😄"))
        store.save("2026-09-09", DiaryEntry(mood = 1, moodEmoji = "😡"))

        assertEquals(5, store.load("2026-09-10")?.mood)
        assertEquals(1, store.load("2026-09-09")?.mood)

        store.clear("2026-09-10")

        assertNull(store.load("2026-09-10"))
        assertNotNull(store.load("2026-09-09"))

        handle.close()
    }

    @Test
    fun `draft key matches the web localStorage naming`() {
        assertEquals("diarium_draft_2026-09-10", DraftStore.key("2026-09-10"))
    }

    @Test
    fun `drafts survive a store reopening`() = runBlocking {
        val file = tempFile()

        val first = newStore(file)
        first.store.save("2026-09-10", DiaryEntry(mood = 3, moodEmoji = "😐", note = "trvá"))
        // Release the file so the read below starts from disk, like a cold start.
        first.close()

        val reopened = newStore(file)
        assertEquals(3, reopened.store.load("2026-09-10")?.mood)
        assertEquals("trvá", reopened.store.load("2026-09-10")?.note)
        reopened.close()
    }
}
