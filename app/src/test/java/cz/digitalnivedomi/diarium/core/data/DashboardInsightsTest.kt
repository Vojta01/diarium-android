package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Přehled" screen's pure derivations — the rules a defect report was filed
 * about, tested directly instead of through the screen.
 *
 * Three of them matter enough to pin here:
 *
 * - **top apps** were rendered in raw database order, so the real leaders never
 *   appeared;
 * - **the reflection card** keyed off the newest day that *has* a reflection, so it
 *   could sit on an older day while a newer entry went unreflected;
 * - **the mood row** collapsed "logged without a mood" into the same empty mark as
 *   "no entry", which read as a broken grey circle.
 *
 * [rankTopApps], [formatTopAppDuration], [moodDiscState] and
 * [DashboardRepository.newestEntry] are plain functions, so no transport, session
 * or clock is involved.
 */
class DashboardInsightsTest {

    // ── Top apps ───────────────────────────────────────────────────────────────

    /**
     * The 2026-09-10 `phone_top_apps` array exactly as it is stored, in its raw
     * database order. The trailing comment is the value the worker actually
     * recorded; the model holds whole minutes (`JSONObject.optInt`), so the
     * fractional ones arrive truncated — 36.92 → 36, 0.5 → 0.
     */
    private fun realTopApps(): List<PhoneTopApp> = listOf(
        PhoneTopApp("Hermes WebUI", 8), // 8.38
        PhoneTopApp("Telegram", 0), // 0.50
        PhoneTopApp("Zpravy", 0), // 0.72
        PhoneTopApp("Gmail", 3), // 3.10
        PhoneTopApp("Signal", 1), // 1.53
        PhoneTopApp("Home Assistant", 2), // 2.02
        PhoneTopApp("Netatmo", 1), // 1.25
        PhoneTopApp("Brave", 7), // 7.15
        PhoneTopApp("Mini Metro", 9), // 9.27
        PhoneTopApp("Instagram", 22), // 22.72
        PhoneTopApp("WhatsApp", 23), // 23.27
        PhoneTopApp("Bakalari OnLine", 2), // 2.63
        PhoneTopApp("Snooker", 36), // 36.92
        PhoneTopApp("Telefon", 3), // 3.85
        PhoneTopApp("Chrome", 0), // 0.97
    )

    @Test
    fun `the raw database order is not the ranking`() {
        // The defect: the first five rows were drawn verbatim, and the day's real
        // leaders were not among them.
        val rawFirstFive = realTopApps().take(5).map { it.app }

        assertEquals(
            listOf("Hermes WebUI", "Telegram", "Zpravy", "Gmail", "Signal"),
            rawFirstFive,
        )
        assertTrue(rawFirstFive.none { it == "Snooker" })
    }

    @Test
    fun `top apps are ranked by minutes, so the real leaders survive`() {
        val ranked = rankTopApps(realTopApps()).map { it.app }

        assertEquals(
            listOf("Snooker", "WhatsApp", "Instagram", "Mini Metro", "Hermes WebUI"),
            ranked,
        )
    }

    @Test
    fun `top apps drop the sub-minute noise`() {
        val ranked = rankTopApps(realTopApps()).map { it.app }

        // Telegram 0.5, Zpravy 0.72 and Chrome 0.97 are launch noise, not usage.
        assertTrue(ranked.none { it in listOf("Telegram", "Zpravy", "Chrome") })
    }

    @Test
    fun `top apps keep only the five most used`() {
        assertEquals(TOP_APPS_LIMIT, rankTopApps(realTopApps()).size)
        assertEquals(
            listOf("Snooker", "WhatsApp", "Instagram", "Mini Metro", "Hermes WebUI"),
            rankTopApps(realTopApps()).map { it.app },
        )
    }

    @Test
    fun `top apps with the same minutes are ordered by name`() {
        val ranked = rankTopApps(
            listOf(
                PhoneTopApp("Zebra", 5),
                PhoneTopApp("Andula", 5),
                PhoneTopApp("Marek", 1),
            ),
        )

        assertEquals(listOf("Andula", "Zebra", "Marek"), ranked.map { it.app })
    }

    @Test
    fun `top apps of a day with nothing worth ranking are empty`() {
        assertTrue(rankTopApps(emptyList()).isEmpty())
        assertTrue(rankTopApps(listOf(PhoneTopApp("Telegram", 0))).isEmpty())
    }

    @Test
    fun `top apps keep the minutes they were ranked by`() {
        val top = rankTopApps(realTopApps()).first()

        assertEquals("Snooker", top.app)
        assertEquals(36, top.minutes)
    }

    // ── Duration format ────────────────────────────────────────────────────────

    @Test
    fun `a duration under a minute never reads as zero minutes`() {
        assertEquals("<1 min", formatTopAppDuration(0))
    }

    @Test
    fun `a duration uses the short Czech format`() {
        assertEquals("23 min", formatTopAppDuration(23))
        assertEquals("1 h", formatTopAppDuration(60))
        assertEquals("1 h 15 min", formatTopAppDuration(75))
    }

    // ── Mood disc ──────────────────────────────────────────────────────────────

    @Test
    fun `a day with a mood shows the mood mark`() {
        assertEquals(MoodDiscState.Mood, moodDiscState(hasEntry = true, mood = 4))
        assertEquals(MoodDiscState.Mood, moodDiscState(hasEntry = true, mood = 1))
        assertEquals(MoodDiscState.Mood, moodDiscState(hasEntry = true, mood = 5))
    }

    @Test
    fun `a logged day without a mood shows the neutral mark`() {
        // Monday 2026-09-07: an entry exists, the mood question was never answered.
        // This is the disc that used to look like a broken, empty grey circle.
        assertEquals(MoodDiscState.LoggedWithoutMood, moodDiscState(hasEntry = true, mood = 0))
    }

    @Test
    fun `a day with no entry stays quiet`() {
        assertEquals(MoodDiscState.NoEntry, moodDiscState(hasEntry = false, mood = 0))
    }

    @Test
    fun `a day with no entry is never a mood mark`() {
        // A mood without an entry is not data the screen can trust; the absence of
        // the entry wins.
        assertEquals(MoodDiscState.NoEntry, moodDiscState(hasEntry = false, mood = 5))
    }

    @Test
    fun `the three disc marks are all distinct`() {
        val states = MoodDiscState.values().toList()

        assertEquals(3, states.size)
        assertEquals(3, states.toSet().size)
    }

    // ── Newest entry (the reflection card's key) ───────────────────────────────

    @Test
    fun `the newest entry is the newest logged day, not the newest reflection`() {
        // 09-10 is logged with no reflection; 09-09 is an older day that has one.
        // The card must key off 09-10 — sitting on 09-09 was the reported defect.
        val newer = DiaryEntry(mood = 2)
        val older = DiaryEntry(aiReflection = "Starší reflexe.")
        val data = DashboardRepository.derive(
            today = "2026-09-10",
            rows = listOf(DatedEntry("2026-09-10", newer), DatedEntry("2026-09-09", older)),
        )

        assertEquals("2026-09-10", data.newestEntry?.date)
        assertNull(data.newestEntry?.reflection)
    }

    @Test
    fun `the newest entry carries its own reflection when it has one`() {
        val data = DashboardRepository.derive(
            today = "2026-09-10",
            rows = listOf(
                DatedEntry("2026-09-10", DiaryEntry(mood = 2, aiReflection = "Dnešní reflexe.")),
            ),
        )

        assertEquals("Dnešní reflexe.", data.newestEntry?.reflection)
    }

    @Test
    fun `a blank reflection on the newest entry counts as none`() {
        val data = DashboardRepository.derive(
            today = "2026-09-10",
            rows = listOf(DatedEntry("2026-09-10", DiaryEntry(aiReflection = "   "))),
        )

        assertNull(data.newestEntry?.reflection)
    }

    @Test
    fun `the newest entry is found whatever the row order is`() {
        val byDate = mapOf(
            "2026-09-07" to DiaryEntry(),
            "2026-09-10" to DiaryEntry(mood = 2),
            "2026-09-08" to DiaryEntry(mood = 3),
        )

        val newest = DashboardRepository.newestEntry(byDate)

        assertNotNull(newest)
        assertEquals("2026-09-10", newest?.date)
    }

    @Test
    fun `with no logged day there is no newest entry`() {
        assertNull(DashboardRepository.newestEntry(emptyMap()))
        assertNull(DashboardRepository.derive(today = "2026-09-10", rows = emptyList()).newestEntry)
    }
}
