package cz.digitalnivedomi.diarium.core.data

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Contract for [EntriesCacheStore] — the on-disk snapshot the dashboard renders
 * from before [EntriesRepository.loadRange] answers.
 *
 * Each test gets its own temp directory, so the "no cache yet" case is a
 * genuinely absent file and no case can leak state into the next one.
 */
class EntriesCacheStoreTest {

    /** A store on a fresh, empty temp directory, plus the file it writes. */
    private fun newStore(): Pair<EntriesCacheStore, File> {
        val directory = Files.createTempDirectory("cache-test").toFile()
        val file = File(directory, EntriesCacheStore.FILE_NAME)
        return EntriesCacheStore(file) to file
    }

    /** One `entries` row with just the fields a case cares about. */
    private fun row(date: String, mood: Int): JSONObject =
        JSONObject().put("date", date).put("mood", mood)

    @Test
    fun `readRange is null when no cache file exists yet`() = runTest {
        val (store, file) = newStore()

        assertFalse(file.exists())
        assertNull(store.readRange("2026-09-01", "2026-09-30"))
    }

    @Test
    fun `readRange returns only the window, newest first`() = runTest {
        val (store, _) = newStore()
        store.upsertRows(
            listOf(
                row("2026-08-31", 2), // the day before the window
                row("2026-09-01", 1), // first day — the bound is inclusive
                row("2026-09-05", 3),
                row("2026-09-10", 5), // last day — the bound is inclusive
                row("2026-09-11", 4), // the day after the window
            ),
        )

        val loaded = store.readRange("2026-09-01", "2026-09-10")

        assertEquals(listOf("2026-09-10", "2026-09-05", "2026-09-01"), loaded?.map { it.date })
        // Rows come back through EntriesRepository.fromRow, so the cache hands
        // the dashboard real DiaryEntry models, not raw JSON.
        assertEquals(5, loaded?.first()?.entry?.mood)
    }

    @Test
    fun `a second upsert overlays the stored row and keeps the keys it omits`() = runTest {
        val (store, _) = newStore()
        store.upsertRows(
            listOf(
                JSONObject()
                    .put("date", "2026-09-10")
                    .put("mood", 3)
                    .put("note", "Ráno běh")
                    .put("phone_screen_time", 5400),
            ),
        )

        // What a form save looks like: mood changed, nothing else. The worker's
        // screen-time column must survive — a replace would have wiped it.
        store.upsertRows(listOf(JSONObject().put("date", "2026-09-10").put("mood", 5)))

        val entry = store.readRange("2026-09-10", "2026-09-10")?.single()?.entry
        assertNotNull(entry)
        assertEquals(5, entry!!.mood) // shared key: the newer row wins
        assertEquals("Ráno běh", entry.note) // key only the first row carried: kept
        assertEquals(5400, entry.phoneScreenTime)
    }

    @Test
    fun `removeDate drops one day, leaves the rest, and tolerates a miss`() = runTest {
        val (store, _) = newStore()
        store.upsertRows(listOf(row("2026-09-09", 2), row("2026-09-10", 4)))

        store.removeDate("2026-09-10")
        store.removeDate("1999-01-01") // never cached — a no-op, not a throw

        assertEquals(
            listOf("2026-09-09"),
            store.readRange("2026-09-01", "2026-09-30")?.map { it.date },
        )
    }

    @Test
    fun `a malformed file reads as null and the next upsert repairs it`() = runTest {
        val (store, file) = newStore()

        file.writeText("{ this is not json")
        assertNull(store.readRange("2026-09-01", "2026-09-30"))

        // Valid JSON of the wrong shape degrades the same way: no rows object.
        file.writeText("""{"version":1,"rows":"nope"}""")
        assertNull(store.readRange("2026-09-01", "2026-09-30"))

        store.upsertRows(listOf(row("2026-09-10", 4)))

        assertEquals(
            listOf("2026-09-10"),
            store.readRange("2026-09-01", "2026-09-30")?.map { it.date },
        )
        // The repaired file is a real snapshot again, not merely readable.
        assertEquals(1, JSONObject(file.readText()).getInt("version"))
    }

    @Test
    fun `rows with a blank or missing date are skipped`() = runTest {
        val (store, file) = newStore()

        store.upsertRows(
            listOf(
                JSONObject().put("mood", 5), // no date key at all
                JSONObject().put("date", "   ").put("mood", 4), // whitespace only
                JSONObject().put("date", JSONObject.NULL).put("mood", 2), // JSON null
                row("2026-09-10", 3),
            ),
        )

        assertEquals(
            listOf("2026-09-10"),
            store.readRange("2026-09-01", "2026-09-30")?.map { it.date },
        )
        // Nothing landed under a blank key.
        assertEquals(1, JSONObject(file.readText()).getJSONObject("rows").length())
    }
}
