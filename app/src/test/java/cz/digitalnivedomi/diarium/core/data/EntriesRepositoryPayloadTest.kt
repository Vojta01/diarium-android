package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payload contract for `save_daily_entry(jsonb)`.
 *
 * The keys asserted here are the ones the SQL function reads (`p_payload->>'mood'`
 * etc.), so a rename in Kotlin silently breaks the save — this is the guard.
 */
class EntriesRepositoryPayloadTest {

    private fun payload(entry: DiaryEntry) = EntriesRepository.buildPayload(entry, "user-1", "2026-09-10")

    @Test
    fun `maps every form field onto its rpc key`() {
        val entry = DiaryEntry(
            mood = 5,
            moodEmoji = "😄",
            sleepQuality = 2,
            stress = 4,
            activities = listOf("🏋️ Cvičení", "📚 Čtení"),
            habits = mapOf("alkohol" to true, "cviceni" to false),
            gratitude = listOf("Rodina", "", "Práce"),
            note = "Dobrý den",
            weather = listOf("Slunečno"),
            scaleValues = mapOf("energy" to 4),
            photoPath = "https://example.supabase.co/storage/v1/object/public/diary-photos/user-1/2026-09-10.jpg",
        )

        val json = payload(entry)

        assertEquals("user-1", json.getString("user_id"))
        assertEquals("2026-09-10", json.getString("date"))
        assertEquals(5, json.getInt("mood"))
        assertEquals("😄", json.getString("mood_emoji"))
        assertEquals(2, json.getInt("sleep_quality"))
        assertEquals(4, json.getInt("stress"))
        assertEquals(2, json.getJSONArray("activities").length())
        assertEquals("🏋️ Cvičení", json.getJSONArray("activities").getString(0))
        assertTrue(json.getJSONObject("habits").getBoolean("alkohol"))
        assertFalse(json.getJSONObject("habits").getBoolean("cviceni"))
        assertEquals("Dobrý den", json.getString("note"))
        assertEquals("Slunečno", json.getJSONArray("weather").getString(0))
        assertTrue(json.getString("photo_path").endsWith("2026-09-10.jpg"))

        // The worker owns these columns. Sending them as null would make the RPC
        // write `excluded.*` and wipe the screen-time data, so they must be absent.
        assertFalse(json.has("phone_screen_time"))
        assertFalse(json.has("phone_unlocks"))
        assertFalse(json.has("phone_top_apps"))
        assertFalse(json.has("ai_reflection"))
    }

    @Test
    fun `gratitude blanks are filtered out`() {
        val json = payload(DiaryEntry(gratitude = listOf("A", "", "B")))
        val gratitude = json.getJSONArray("gratitude")

        assertEquals(2, gratitude.length())
        assertEquals("A", gratitude.getString(0))
        assertEquals("B", gratitude.getString(1))
    }

    @Test
    fun `scale values are included and zero values skipped`() {
        val json = payload(DiaryEntry(scaleValues = mapOf("energy" to 4, "mood_scale" to 0, "sleep" to 2)))
        val scales = json.getJSONObject("scale_values")

        assertEquals(2, scales.length())
        assertEquals(4, scales.getInt("energy"))
        assertEquals(2, scales.getInt("sleep"))
        assertFalse(scales.has("mood_scale"))
    }

    @Test
    fun `photo_path is omitted when the day has no photo`() {
        assertFalse(payload(DiaryEntry()).has("photo_path"))
    }

    @Test
    fun `achievement payload reports photo and scale presence`() {
        val withBoth = EntriesRepository.buildAchievementsPayload(
            DiaryEntry(photoPath = "https://x/p.jpg", scaleValues = mapOf("energy" to 3)),
            "user-1",
        )
        assertEquals("user-1", withBoth.getString("p_user_id"))
        assertTrue(withBoth.getBoolean("p_has_photo"))
        assertTrue(withBoth.getBoolean("p_has_scale"))

        val withNeither = EntriesRepository.buildAchievementsPayload(DiaryEntry(), "user-1")
        assertFalse(withNeither.getBoolean("p_has_photo"))
        assertFalse(withNeither.getBoolean("p_has_scale"))
    }
}
