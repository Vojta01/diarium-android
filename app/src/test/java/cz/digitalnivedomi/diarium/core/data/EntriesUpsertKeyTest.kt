package cz.digitalnivedomi.diarium.core.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Upsert contract: the RPC's conflict target is `(user_id, date)`, so two saves
 * for the same day must send an identical `(user_id, date)` pair — that is what
 * makes a re-save update the row instead of inserting a second one.
 */
class EntriesUpsertKeyTest {

    @Test
    fun `re-saving the same date keeps the same conflict key`() {
        val first = EntriesRepository.buildPayload(
            DiaryEntry(mood = 3, moodEmoji = "😐"),
            "user-1",
            "2026-09-10",
        )
        val second = EntriesRepository.buildPayload(
            DiaryEntry(mood = 5, moodEmoji = "😄", note = "jinak"),
            "user-1",
            "2026-09-10",
        )

        assertEquals(first.getString("user_id"), second.getString("user_id"))
        assertEquals(first.getString("date"), second.getString("date"))
        assertEquals("2026-09-10", second.getString("date"))

        // The payload really differs — an update-in-place, not a no-op.
        assertNotEquals(first.getInt("mood"), second.getInt("mood"))
    }

    @Test
    fun `a different day produces a different key`() {
        val monday = EntriesRepository.buildPayload(DiaryEntry(mood = 4), "user-1", "2026-09-10")
        val tuesday = EntriesRepository.buildPayload(DiaryEntry(mood = 4), "user-1", "2026-09-11")
        assertNotEquals(monday.getString("date"), tuesday.getString("date"))
    }

    @Test
    fun `payload is wrapped as the single p_payload jsonb argument`() {
        val body = JSONObject().put(
            "p_payload",
            EntriesRepository.buildPayload(DiaryEntry(mood = 1, moodEmoji = "😡"), "u", "2026-01-01"),
        )
        assertEquals(1, body.length())
        assertTrue(body.getJSONObject("p_payload").has("mood"))
    }
}
