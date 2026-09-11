package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dashboard's arithmetic — every number the screen shows, tested against the
 * definitions the web dashboard uses (`src/components/Dashboard.tsx`,
 * `src/lib/stats.ts`), because both clients read the same rows.
 *
 * [DashboardRepository.derive] is a pure function of the loaded rows, so these tests
 * need no transport, no session and no clock: they pass the dates in and assert the
 * result. That is the point of keeping the maths out of the composable.
 */
class DashboardRepositoryTest {

    private val today = "2026-09-10"

    private fun derive(vararg rows: Pair<String, DiaryEntry>) = DashboardRepository.derive(
        today,
        rows.map { DatedEntry(it.first, it.second) },
    )

    // ── Streak ─────────────────────────────────────────────────────────────────

    @Test
    fun `streak counts back from today across a gap-free run`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(mood = 4),
            "2026-09-09" to DiaryEntry(mood = 3),
            "2026-09-08" to DiaryEntry(mood = 2),
        )

        assertEquals(3, data.streak)
    }

    @Test
    fun `an unfinished today does not break a streak that ended yesterday`() {
        // The rule the screen must not get wrong: coming back in the morning before
        // writing anything must not read as "your streak is gone".
        val data = derive(
            "2026-09-09" to DiaryEntry(mood = 3),
            "2026-09-08" to DiaryEntry(mood = 2),
        )

        assertEquals(2, data.streak)
        assertNull(data.todayEntry)
    }

    @Test
    fun `a missing yesterday stops the streak at today`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(mood = 4),
            "2026-09-08" to DiaryEntry(mood = 2),
        )

        assertEquals(1, data.streak)
    }

    @Test
    fun `an empty history has no streak`() {
        val data = DashboardRepository.derive(today, emptyList())

        assertEquals(0, data.streak)
        assertEquals(0, data.longestStreak)
        assertNull(data.todayEntry)
    }

    @Test
    fun `an entry only today is a one-day streak`() {
        assertEquals(1, derive("2026-09-10" to DiaryEntry(mood = 5)).streak)
    }

    @Test
    fun `an entry only yesterday is a one-day streak`() {
        assertEquals(1, derive("2026-09-09" to DiaryEntry(mood = 5)).streak)
    }

    @Test
    fun `longest streak finds the longest run in the loaded window`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(mood = 4),
            "2026-09-09" to DiaryEntry(mood = 4),
            "2026-09-01" to DiaryEntry(mood = 4),
            "2026-08-31" to DiaryEntry(mood = 4),
            "2026-08-30" to DiaryEntry(mood = 4),
        )

        assertEquals(2, data.streak)
        assertEquals(3, data.longestStreak)
    }

    @Test
    fun `a synced day without a mood breaks the streak like a missing day`() {
        // 2026-09-08 has an entries row from the phone sync (5 h 58 min screen time,
        // 196 unlocks) but no mood. Under the owner's rule that is not a record, so
        // the run of records stops there: the mood-less day neither bridges 09-09
        // and 09-07 nor counts itself.
        val data = derive(
            "2026-09-10" to DiaryEntry(mood = 4),
            "2026-09-09" to DiaryEntry(mood = 3),
            "2026-09-08" to DiaryEntry(phoneScreenTime = 21480, phoneUnlocks = 196),
            "2026-09-07" to DiaryEntry(mood = 2),
        )

        assertEquals(2, data.streak)
        assertEquals(2, data.longestStreak)

        // The row is still there for the screen-time card — it just is not a record.
        val syncedDay = data.week.first { it.date == "2026-09-08" }
        assertTrue(syncedDay.hasEntry)
        assertEquals(0, syncedDay.mood)
        assertEquals(21480, syncedDay.screenTimeSeconds)
    }

    @Test
    fun `a synced mood-less today does not extend the streak`() {
        // Back in the morning the phone already synced today but the owner has not
        // written anything: like an unfinished today, this must leave the run from
        // yesterday intact and must not add a day.
        val data = derive(
            "2026-09-10" to DiaryEntry(phoneScreenTime = 21480, phoneUnlocks = 196),
            "2026-09-09" to DiaryEntry(mood = 3),
            "2026-09-08" to DiaryEntry(mood = 2),
        )

        assertEquals(2, data.streak)
        assertEquals(2, data.longestStreak)
        // The synced numbers still reach the screen-time card.
        assertEquals(358, data.screenTimeMinutes) // 21480 s ≈ 358 min
    }

    // ── Week window ────────────────────────────────────────────────────────────

    @Test
    fun `the week window is seven days ending today, oldest first`() {
        val data = derive("2026-09-08" to DiaryEntry(mood = 3))

        assertEquals(
            listOf(
                "2026-09-04", "2026-09-05", "2026-09-06", "2026-09-07",
                "2026-09-08", "2026-09-09", "2026-09-10",
            ),
            data.week.map { it.date },
        )
    }

    @Test
    fun `the week window includes today even when it has no entry`() {
        val data = derive("2026-09-09" to DiaryEntry(mood = 3))

        val last = data.week.last()
        assertEquals("2026-09-10", last.date)
        assertEquals(false, last.hasEntry)
        assertEquals(0, last.mood)
    }

    @Test
    fun `a day outside the week window is not in the week`() {
        val data = derive("2026-09-03" to DiaryEntry(mood = 5))

        assertTrue(data.week.none { it.date == "2026-09-03" })
        assertNull(data.todayEntry)
        assertEquals(0, data.streak)
        assertNull(data.averageMood)
    }

    @Test
    fun `a day with an entry reports the entry's mood`() {
        val data = derive("2026-09-10" to DiaryEntry(mood = 4))

        val todayCell = data.week.last()
        assertTrue(todayCell.hasEntry)
        assertEquals(4, todayCell.mood)
    }

    // ── Average mood ───────────────────────────────────────────────────────────

    @Test
    fun `average mood ignores days without an entry and days with mood zero`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(mood = 4),
            "2026-09-09" to DiaryEntry(mood = 0),
            "2026-09-08" to DiaryEntry(mood = 2),
        )

        assertEquals(3.0, data.averageMood ?: -1.0, 0.0001)
    }

    @Test
    fun `average mood is null when no day in the window has a mood`() {
        val data = derive("2026-09-10" to DiaryEntry(note = "Jen poznámka"))

        assertNull(data.averageMood)
    }

    @Test
    fun `average mood counts only the week window`() {
        // An old five-mood day must not pull the week's average up.
        val data = derive(
            "2026-09-10" to DiaryEntry(mood = 2),
            "2026-09-01" to DiaryEntry(mood = 5),
        )

        assertEquals(2.0, data.averageMood ?: -1.0, 0.0001)
    }

    // ── Screen time and unlocks ────────────────────────────────────────────────

    @Test
    fun `screen time totals every synced day of the window in minutes`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(phoneScreenTime = 6000),
            "2026-09-09" to DiaryEntry(phoneScreenTime = 3600),
        )

        // 9600 s over two days ≈ 160 min, the same rounding the web's
        // `formatScreenTime(Math.round(seconds / 60))` uses.
        assertEquals(160, data.screenTimeMinutes)
    }

    @Test
    fun `screen time is null when the worker never synced`() {
        val data = derive("2026-09-10" to DiaryEntry(mood = 4))

        assertNull(data.screenTimeMinutes)
        assertTrue(data.week.all { it.screenTimeSeconds == null })
    }

    @Test
    fun `a synced zero is data, not an empty day`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(phoneScreenTime = 0),
            "2026-09-09" to DiaryEntry(phoneScreenTime = 4500),
        )

        assertEquals(75, data.screenTimeMinutes)
    }

    @Test
    fun `unlocks are summed over the window`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(phoneUnlocks = 30),
            "2026-09-08" to DiaryEntry(phoneUnlocks = 12),
        )

        assertEquals(42, data.unlocks)
    }

    @Test
    fun `unlocks are null when the worker never synced`() {
        assertNull(derive("2026-09-10" to DiaryEntry(mood = 4)).unlocks)
    }

    // ── Top apps ───────────────────────────────────────────────────────────────

    @Test
    fun `top apps fall back to the latest day that captured any`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(mood = 4),
            "2026-09-08" to DiaryEntry(
                phoneTopApps = listOf(PhoneTopApp(app = "Instagram", minutes = 42)),
            ),
        )

        assertEquals("2026-09-08", data.topApps?.date)
        assertEquals(listOf(PhoneTopApp("Instagram", 42)), data.topApps?.apps)
    }

    @Test
    fun `top apps are null when no day captured any`() {
        assertNull(derive("2026-09-10" to DiaryEntry(mood = 4)).topApps)
    }

    // ── Today card, reflection, gratitude ──────────────────────────────────────

    @Test
    fun `the today card uses the entry stored for today and nothing else`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(mood = 5, note = "Dnes"),
            "2026-09-09" to DiaryEntry(mood = 1, note = "Včera"),
        )

        assertEquals(5, data.todayEntry?.mood)
        assertEquals("Dnes", data.todayEntry?.note)
    }

    @Test
    fun `today with no entry has no card content`() {
        assertNull(derive("2026-09-09" to DiaryEntry(mood = 5)).todayEntry)
    }

    @Test
    fun `the reflection shown is the newest day that has one`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(mood = 4),
            "2026-09-07" to DiaryEntry(aiReflection = "Starší reflexe."),
            "2026-09-09" to DiaryEntry(aiReflection = "Nejnovější reflexe."),
        )

        assertEquals("2026-09-09", data.reflection?.date)
        assertEquals("Nejnovější reflexe.", data.reflection?.text)
    }

    @Test
    fun `a blank reflection is not a reflection`() {
        val data = derive("2026-09-10" to DiaryEntry(aiReflection = "   "))

        assertNull(data.reflection)
    }

    @Test
    fun `the latest gratitude is the newest day with non-blank lines`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(mood = 4),
            "2026-09-09" to DiaryEntry(gratitude = listOf("Rodina", "", "Práce")),
            "2026-09-05" to DiaryEntry(gratitude = listOf("Starší", "", "")),
        )

        assertEquals("2026-09-09", data.lastGratitude?.date)
        assertEquals(listOf("Rodina", "Práce"), data.lastGratitude?.lines)
    }

    @Test
    fun `gratitude made of blanks alone is not the latest gratitude`() {
        val data = derive(
            "2026-09-10" to DiaryEntry(gratitude = listOf("", " ", "")),
            "2026-09-08" to DiaryEntry(gratitude = listOf("Klid", "", "")),
        )

        assertEquals("2026-09-08", data.lastGratitude?.date)
    }

    @Test
    fun `the derivation ignores a row without a date`() {
        // Defensive: a malformed row must not become the "today" entry.
        val data = derive(
            "" to DiaryEntry(mood = 5),
            "2026-09-10" to DiaryEntry(mood = 3),
        )

        assertEquals(3, data.todayEntry?.mood)
        assertEquals(1, data.streak)
    }
}
