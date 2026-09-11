package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.core.achievements.ACHIEVEMENTS
import cz.digitalnivedomi.diarium.core.achievements.AchievementEntry
import cz.digitalnivedomi.diarium.core.achievements.AchievementStatus
import cz.digitalnivedomi.diarium.core.achievements.achievementsData
import cz.digitalnivedomi.diarium.core.achievements.computeProgress
import cz.digitalnivedomi.diarium.core.achievements.mergeProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for what the achievements repository adds on top of the domain
 * module: the panel ordering, the monotonic merge the load path relies on, and
 * the unlock-date pass-through. No Android, no Robolectric, no network — the
 * repository's HTTP layer is never touched here.
 *
 * The arithmetic itself (`computeMaxStreak`, `computeProgress`, …) lives in
 * `core/achievements/Achievements.kt` and is not re-tested here.
 */
class AchievementsRepositoryTest {

    private fun entry(
        date: String,
        mood: Int = 3,
        hasPhoto: Boolean = false,
        activities: List<String> = emptyList(),
    ) = AchievementEntry(date = date, mood = mood, hasPhoto = hasPhoto, activities = activities)

    // ── mergeProgress (the load path's "never regress" rule) ─────────────────

    @Test
    fun `mergeProgress keeps the stored progress when the computed one is smaller`() {
        val computed = mapOf("entries_10" to 4)
        val stored = mapOf("entries_10" to 10)
        assertEquals(10, mergeProgress(computed, stored)["entries_10"])
    }

    @Test
    fun `mergeProgress takes the computed progress when it is larger`() {
        val computed = mapOf("entries_10" to 10)
        val stored = mapOf("entries_10" to 4)
        assertEquals(10, mergeProgress(computed, stored)["entries_10"])
    }

    @Test
    fun `mergeProgress keeps keys that only one side knows about`() {
        val merged = mergeProgress(mapOf("first_entry" to 1), mapOf("streak_7" to 3))
        assertEquals(1, merged["first_entry"])
        assertEquals(3, merged["streak_7"])
    }

    // ── sortForPanel (what the grid wants) ───────────────────────────────────

    @Test
    fun `sortForPanel floats unlocked badges and keeps catalogue order inside each group`() {
        val statuses = listOf(
            AchievementStatus(ACHIEVEMENTS[0], 0, false),    // first_entry, locked
            AchievementStatus(ACHIEVEMENTS[1], 7, true),     // streak_7, unlocked
            AchievementStatus(ACHIEVEMENTS[2], 0, false),    // streak_30, locked
            AchievementStatus(ACHIEVEMENTS[3], 100, true),   // streak_100, unlocked
        )
        val ordered = sortForPanel(statuses).map { it.key }
        assertEquals(listOf("streak_7", "streak_100", "first_entry", "streak_30"), ordered)
    }

    @Test
    fun `sortForPanel leaves an all-locked panel in catalogue order`() {
        val statuses = listOf(
            AchievementStatus(ACHIEVEMENTS[2], 0, false),
            AchievementStatus(ACHIEVEMENTS[0], 0, false),
            AchievementStatus(ACHIEVEMENTS[1], 0, false),
        )
        assertEquals(listOf("streak_30", "first_entry", "streak_7"), sortForPanel(statuses).map { it.key })
    }

    // ── achievementsData: catalogue truth, stale rows, unlock dates ───────────

    @Test
    fun `the panel exposes all 17 definitions and the summary label`() {
        val data = achievementsData(emptyMap())
        assertEquals(17, data.total)
        assertEquals(0, data.unlockedCount)
        assertEquals("Odemčeno 0/17", data.unlockedLabel)
    }

    @Test
    fun `a stored key with no definition is dropped from the panel`() {
        val data = achievementsData(mapOf("use_template" to 1, "first_entry" to 1))
        assertEquals(17, data.total)
        assertNull(data.statusFor("use_template"))
        assertEquals(1, data.unlockedCount)
        assertEquals("Odemčeno 1/17", data.unlockedLabel)
    }

    @Test
    fun `the stored unlock date is carried through to the status`() {
        val stamp = "2025-01-02T10:00:00+01:00"
        val data = achievementsData(
            progress = mapOf("first_entry" to 1),
            unlockedAt = mapOf("first_entry" to stamp),
        )
        val status = data.statusFor("first_entry")!!
        assertTrue(status.unlocked)
        assertEquals(stamp, status.unlockedAt)
        assertNull(data.statusFor("streak_7")!!.unlockedAt)
    }

    // ── the whole load-path composition, without the network ─────────────────

    @Test
    fun `computed progress merged with a stale store still unlocks via the max rule`() {
        val entries = listOf(entry("2025-01-01"), entry("2025-01-02"))
        val computed = computeProgress(entries, emptyList())
        // A badge earned before the entries it came from were deleted: the store
        // still says 7, the recomputed streak says 2. The panel must keep 7.
        val merged = mergeProgress(computed, mapOf("first_entry" to 1, "streak_7" to 7))
        val data = achievementsData(merged)

        assertTrue(data.statusFor("first_entry")!!.unlocked)
        assertTrue(data.statusFor("streak_7")!!.unlocked)
        assertEquals(2, data.statusFor("entries_10")!!.progress)
        assertFalse(data.statusFor("entries_10")!!.unlocked)
    }

    @Test
    fun `an empty window still produces the full locked catalogue`() {
        val data = achievementsData(computeProgress(emptyList(), emptyList()))
        assertEquals(17, data.statuses.size)
        assertEquals(0, data.unlockedCount)
        assertTrue(data.statuses.none { it.unlocked })
    }
}
