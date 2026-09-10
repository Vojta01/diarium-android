package cz.digitalnivedomi.diarium.core.stats

import cz.digitalnivedomi.diarium.core.data.PhoneTopApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * The statistics maths behind Statistiky — no Compose, no Android, no network.
 *
 * Every expected number below is worked out by hand in a comment next to the
 * assertion, because the point of this file is that the arithmetic is a fact about
 * the numbers and not a snapshot of whatever the code happened to print. The web
 * app (`src/lib/stats.ts`, `src/components/AdvancedStats.tsx`,
 * `src/components/ScreenTimeChart.tsx`) is the source of truth for the
 * definitions. One place where the port had drifted (the activity worst-day
 * tie-break) is asserted against the web on purpose, with the web's rule in the
 * comment so the next reader can check it.
 *
 * The rule that runs through all of it: a stored mood of 0 means "the picker was
 * never answered", so it is dropped from every average and every count — never
 * treated as a zero. A `null` screen time ("no sync") is likewise kept apart from
 * a real 0.
 */
class StatsMathTest {

    // ── Mood ──────────────────────────────────────────────────────────────────

    @Test
    fun `mood distribution counts each answered value 1 to 5`() {
        val days = listOf(
            day("2026-09-01", mood = 1),
            day("2026-09-02", mood = 1),
            day("2026-09-03", mood = 3),
            day("2026-09-04", mood = 5),
            day("2026-09-05", mood = 5),
            day("2026-09-06", mood = 5),
        )

        // 1 → 2 days, 2 → 0, 3 → 1, 4 → 0, 5 → 3.
        assertEquals(
            mapOf(1 to 2, 2 to 0, 3 to 1, 4 to 0, 5 to 3),
            StatsMath.moodDistribution(days),
        )
    }

    @Test
    fun `mood distribution never counts the unanswered 0 and keeps all five keys`() {
        val days = listOf(
            day("2026-09-01", mood = 0),
            day("2026-09-02", mood = 0),
            day("2026-09-03", mood = 4),
        )

        // The two 0s are "not answered", so they land in no bar at all.
        assertEquals(
            mapOf(1 to 0, 2 to 0, 3 to 0, 4 to 1, 5 to 0),
            StatsMath.moodDistribution(days),
        )
        assertEquals(1, StatsMath.answeredDays(days))
    }

    @Test
    fun `an empty window still has the five bars at zero`() {
        assertEquals(listOf(1, 2, 3, 4, 5), StatsMath.MOOD_VALUES)
        assertEquals(
            mapOf(1 to 0, 2 to 0, 3 to 0, 4 to 0, 5 to 0),
            StatsMath.moodDistribution(emptyList()),
        )
        assertEquals(0, StatsMath.answeredDays(emptyList()))
    }

    @Test
    fun `a stored mood of 0 means not answered, so days 3 0 5 average to 4 point 0`() {
        val days = listOf(
            day("2026-09-01", mood = 3),
            day("2026-09-02", mood = 0),
            day("2026-09-03", mood = 5),
        )

        // (3 + 5) / 2 = 4.0 — the 0 is dropped, not counted as a mood.
        // (Had the 0 been averaged in it would be (3 + 0 + 5) / 3 = 2.67, which is
        // what the web's year card does — see StatsMath's class doc, difference 1.)
        assertEquals(4.0, StatsMath.averageMood(days)!!, 1e-9)
        assertEquals(2, StatsMath.answeredDays(days))
    }

    @Test
    fun `average mood is null when no day was answered`() {
        assertNull(StatsMath.averageMood(listOf(day("2026-09-01"), day("2026-09-02"))))
        assertNull(StatsMath.averageMood(emptyList()))
    }

    // ── Moving average ────────────────────────────────────────────────────────

    @Test
    fun `the moving average ramps up over the first days of a short series`() {
        val days = listOf(
            day("2026-09-01", mood = 4),
            day("2026-09-02", mood = 2),
            day("2026-09-03", mood = 5),
        )

        val series = StatsMath.movingAverage(days)

        assertEquals(3, series.size)
        assertEquals(4.0, series[0]!!, 1e-9)          // [4]        → 4 / 1
        assertEquals(3.0, series[1]!!, 1e-9)          // [4, 2]     → 6 / 2
        assertEquals(11.0 / 3.0, series[2]!!, 1e-9)   // [4, 2, 5]  → 11 / 3
    }

    @Test
    fun `the moving average skips unanswered gaps and stays null over an all-unanswered window`() {
        val days = listOf(
            day("2026-09-01", mood = 0),
            day("2026-09-02", mood = 0),
            day("2026-09-03", mood = 4),
        )

        val series = StatsMath.movingAverage(days)

        assertNull(series[0])                        // [0]        → nothing answered → null
        assertNull(series[1])                        // [0, 0]     → nothing answered → null
        assertEquals(4.0, series[2]!!, 1e-9)         // [0, 0, 4]  → only the 4 counts → 4 / 1
    }

    @Test
    fun `the moving average window is seven days wide`() {
        val days = (1..8).map { index ->
            day("2026-09-%02d".format(index), mood = if (index == 8) 1 else 5)
        }

        val series = StatsMath.movingAverage(days)

        assertEquals(8, series.size)
        assertEquals(5.0, series[6]!!, 1e-9)         // days 1..7, all mood 5
        // At the 8th day the window slides to days 2..8: six 5s and one 1.
        assertEquals(31.0 / 7.0, series[7]!!, 1e-9)  // (6 × 5 + 1) / 7
    }

    // ── Per-weekday averages ──────────────────────────────────────────────────

    @Test
    fun `weekday averages split the answered moods by day of week`() {
        // 2026-09-10 is a Thursday, so 09-07 and 09-14 are Mondays and 09-11 a Friday.
        val days = listOf(
            day("2026-09-07", mood = 2),   // Monday
            day("2026-09-14", mood = 4),   // Monday
            day("2026-09-10", mood = 5),   // Thursday
            day("2026-09-11", mood = 0),   // Friday, unanswered
        )

        val averages = StatsMath.weekdayAverages(days)

        assertEquals(2, averages.size)                       // Friday has no answered day
        assertEquals(3.0, averages[DayOfWeek.MONDAY]!!, 1e-9)  // (2 + 4) / 2
        assertEquals(5.0, averages[DayOfWeek.THURSDAY]!!, 1e-9)
        assertNull(averages[DayOfWeek.FRIDAY])
        assertTrue(StatsMath.weekdayAverages(emptyList()).isEmpty())
    }

    // ── Best and worst day ────────────────────────────────────────────────────

    @Test
    fun `best day is the earliest of the tied highest moods`() {
        val days = listOf(
            day("2026-09-01", mood = 3),
            day("2026-09-02", mood = 5),   // tie for the highest
            day("2026-09-03", mood = 0),   // unanswered, never "best"
            day("2026-09-04", mood = 5),   // tie for the highest
        )

        // 2026-09-02 and 09-04 both hold a 5; the earliest wins.
        assertEquals(MoodPoint("2026-09-02", 5), StatsMath.bestDay(days))
    }

    @Test
    fun `worst day is the latest of the tied lowest moods`() {
        val days = listOf(
            day("2026-09-01", mood = 2),
            day("2026-09-02", mood = 1),   // tie for the lowest
            day("2026-09-03", mood = 2),
            day("2026-09-04", mood = 1),   // tie for the lowest
        )

        // 2026-09-02 and 09-04 both hold a 1; the latest wins (the web's sort reads
        // the last element of a stable descending sort).
        assertEquals(MoodPoint("2026-09-04", 1), StatsMath.worstDay(days))
    }

    @Test
    fun `no answered day means no best and no worst day`() {
        val unanswered = listOf(day("2026-09-01"), day("2026-09-02"))

        assertNull(StatsMath.bestDay(unanswered))
        assertNull(StatsMath.worstDay(unanswered))
        assertNull(StatsMath.bestDay(emptyList()))
        assertNull(StatsMath.worstDay(emptyList()))
    }

    @Test
    fun `the day functions sort by date themselves, so input order does not matter`() {
        val chronological = listOf(day("2026-09-01", mood = 5), day("2026-09-02", mood = 1))
        val shuffled = listOf(day("2026-09-02", mood = 1), day("2026-09-01", mood = 5))

        assertEquals(StatsMath.bestDay(chronological), StatsMath.bestDay(shuffled))
        assertEquals(StatsMath.worstDay(chronological), StatsMath.worstDay(shuffled))
    }

    // ── Longest streak ────────────────────────────────────────────────────────

    @Test
    fun `longest streak counts consecutive calendar days`() {
        val days = listOf(
            day("2026-09-01"), day("2026-09-02"), day("2026-09-03"),   // run of 3
            day("2026-09-10"),                                          // gap
            day("2026-09-11"), day("2026-09-12"), day("2026-09-13"),   // run of 4
        )

        // Note the moods are all 0: the streak counts days that have an entry, not
        // answered moods.
        assertEquals(4, StatsMath.longestStreak(days))
    }

    @Test
    fun `an empty window has a longest streak of zero`() {
        assertEquals(0, StatsMath.longestStreak(emptyList()))
        assertEquals(1, StatsMath.longestStreak(listOf(day("2026-09-01"))))
    }

    @Test
    fun `two rows for one date count once in the streak`() {
        val days = listOf(
            day("2026-09-01"),
            day("2026-09-01"),
            day("2026-09-02"),
        )

        assertEquals(2, StatsMath.longestStreak(days))
    }

    @Test
    fun `a malformed date is ignored rather than breaking the streak`() {
        val days = listOf(day("not-a-date"), day("2026-09-01"), day("2026-09-02"))

        assertEquals(2, StatsMath.longestStreak(days))
    }

    // ── Screen time ───────────────────────────────────────────────────────────

    @Test
    fun `screen time per day keeps null apart from zero`() {
        val days = listOf(
            day("2026-09-01", screenTime = 3600, unlocks = 5),
            day("2026-09-02", screenTime = 0, unlocks = 0),   // the phone said nothing happened
            day("2026-09-03"),                                // the worker never reported
        )

        val byDay = StatsMath.screenTimeByDay(days)

        assertEquals(3, byDay.size)
        assertEquals(3600, byDay[0].seconds)
        assertEquals(0, byDay[1].seconds)
        assertNull(byDay[2].seconds)
        assertEquals(5, byDay[0].unlocks)
        assertNull(byDay[2].unlocks)
    }

    @Test
    fun `screen time summary averages and totals the window the web way`() {
        val window = StatsMath.screenTimeWindow(
            days = listOf(
                day("2026-09-08", screenTime = 0),                      // reported zero → not averaged
                day("2026-09-09", screenTime = 3600, unlocks = 5),
                day("2026-09-10", screenTime = 6000, unlocks = 10),
            ),
            windowDays = 7,
            endDate = "2026-09-10",
        )

        assertEquals(7, window.size)   // 2026-09-04 .. 2026-09-10, gaps kept as placeholders

        val summary = StatsMath.screenTimeSummary(window)

        assertEquals(4800.0, summary.averageSeconds!!, 1e-9)   // (3600 + 6000) / 2
        assertEquals(9600, summary.totalSeconds)               // 3600 + 6000, zeros add nothing
        assertEquals(6000, summary.maxSeconds)
        assertEquals("2026-09-10", summary.maxDate)
    }

    @Test
    fun `the max screen-time day is the earliest one that reached it`() {
        val window = StatsMath.screenTimeWindow(
            days = listOf(
                day("2026-09-09", screenTime = 5000),
                day("2026-09-10", screenTime = 5000),
            ),
            windowDays = 3,
            endDate = "2026-09-10",
        )

        val summary = StatsMath.screenTimeSummary(window)

        assertEquals(5000, summary.maxSeconds)
        assertEquals("2026-09-09", summary.maxDate)   // the web's `reduce` keeps the first
    }

    @Test
    fun `a window with nothing reported has no average and no max, but a zero total`() {
        val window = StatsMath.screenTimeWindow(emptyList(), windowDays = 7, endDate = "2026-09-10")
        val summary = StatsMath.screenTimeSummary(window)

        assertEquals(7, window.size)   // a pinned end still yields the seven placeholder days
        assertNull(summary.averageSeconds)
        assertNull(summary.maxSeconds)
        assertNull(summary.maxDate)
        assertEquals(0, summary.totalSeconds)
    }

    @Test
    fun `the screen-time window is empty when nothing ever reported data`() {
        assertTrue(StatsMath.screenTimeWindow(emptyList(), windowDays = 7).isEmpty())
        // Real days, but none of them synced — no column to draw.
        assertTrue(StatsMath.screenTimeWindow(listOf(day("2026-09-10")), windowDays = 7).isEmpty())
    }

    @Test
    fun `the screen-time window anchors on the most recent reported day, not on today`() {
        val days = listOf(day("2026-09-08", screenTime = 1200))

        val window = StatsMath.screenTimeWindow(days, windowDays = 7)

        assertEquals(7, window.size)
        assertEquals("2026-09-02", window.first().date)   // 09-08 − 6 days
        assertEquals("2026-09-08", window.last().date)    // the anchor, not the real "today"
    }

    @Test
    fun `screen time weekday averages use only the days that reported time`() {
        // 2026-09-07 and 09-14 are Mondays, 09-10 a Thursday, 09-11 a Friday, 09-12 a Saturday.
        val days = listOf(
            day("2026-09-07", screenTime = 3600),   // Monday
            day("2026-09-14", screenTime = 7200),   // Monday
            day("2026-09-10", screenTime = 1800),   // Thursday
            day("2026-09-11", screenTime = 0),      // Friday, a real zero → excluded
            day("2026-09-12"),                       // Saturday, never synced → excluded
        )

        val averages = StatsMath.screenTimeWeekdayAverages(days)

        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), averages.keys)
        assertEquals(5400.0, averages[DayOfWeek.MONDAY]!!, 1e-9)   // (3600 + 7200) / 2
        assertEquals(1800.0, averages[DayOfWeek.THURSDAY]!!, 1e-9)
    }

    // ── Top apps ──────────────────────────────────────────────────────────────

    @Test
    fun `top apps sum each app across the window in seconds`() {
        val days = listOf(
            day(
                "2026-09-09",
                screenTime = 6000,
                topApps = listOf(PhoneTopApp("Instagram", 30), PhoneTopApp("Chrome", 10)),
            ),
            day("2026-09-10", screenTime = 3600, topApps = listOf(PhoneTopApp("Instagram", 20))),
        )

        val breakdown = StatsMath.topApps(days)!!

        // Instagram: (30 + 20) min × 60 = 3000 s; Chrome: 10 × 60 = 600 s.
        assertEquals(
            listOf("Instagram" to 3000, "Chrome" to 600),
            breakdown.apps.map { it.name to it.seconds },
        )
        // total = 6000 + 3600 = 9600 s; other = 9600 − (3000 + 600) = 6000 s.
        assertEquals(9600, breakdown.totalSeconds)
        assertEquals(6000, breakdown.otherSeconds)
        assertEquals(3000.0 / 9600.0, breakdown.apps[0].share, 1e-9)
    }

    @Test
    fun `apps tied on time are ordered by name`() {
        val days = listOf(
            day(
                "2026-09-10",
                screenTime = 3600,
                topApps = listOf(PhoneTopApp("Zebra", 10), PhoneTopApp("Alpha", 10)),
            ),
        )

        // Both 10 min × 60 = 600 s, so the tie breaks alphabetically.
        assertEquals(listOf("Alpha", "Zebra"), StatsMath.topApps(days)!!.apps.map { it.name })
    }

    @Test
    fun `top apps is null when no day captured an app`() {
        assertNull(StatsMath.topApps(emptyList()))
        assertNull(StatsMath.topApps(listOf(day("2026-09-10", screenTime = 3600))))
        // A blank app name is missing data, never a row.
        assertNull(
            StatsMath.topApps(
                listOf(day("2026-09-10", screenTime = 3600, topApps = listOf(PhoneTopApp("  ", 5)))),
            ),
        )
    }

    @Test
    fun `the breakdown adds up to the screen time of the days that captured apps`() {
        val days = listOf(
            day("2026-09-09", screenTime = 1000, topApps = listOf(PhoneTopApp("Maps", 5))),
            day("2026-09-10", topApps = listOf(PhoneTopApp("Maps", 5))),   // no screen-time row
        )

        val breakdown = StatsMath.topApps(days)!!

        // total = 1000 + 0 = 1000 s; Maps = (5 + 5) min × 60 = 600 s; other = 400 s.
        assertEquals(1000, breakdown.totalSeconds)
        assertEquals(600, breakdown.apps.single().seconds)
        assertEquals(400, breakdown.otherSeconds)
    }

    // ── Activities vs mood ────────────────────────────────────────────────────

    @Test
    fun `activity stats average only the answered days and count the mentions`() {
        val days = listOf(
            day("2026-09-01", mood = 5, activities = listOf("Sport")),
            day("2026-09-02", mood = 3, activities = listOf("Sport", "Práce")),
            day("2026-09-03", mood = 0, activities = listOf("Sport")),   // unanswered → not counted
            day("2026-09-04", mood = 1, activities = listOf("Práce")),
        )

        val stats = StatsMath.activityStats(days)

        val sport = stats.single { it.name == "Sport" }
        // Sport on the answered days 09-01 (5) and 09-02 (3): (5 + 3) / 2 = 4.0, count 2.
        assertEquals(4.0, sport.averageMood, 1e-9)
        assertEquals(2, sport.count)
        assertEquals(MoodPoint("2026-09-01", 5), sport.bestDay)
        assertEquals(MoodPoint("2026-09-02", 3), sport.worstDay)

        val prace = stats.single { it.name == "Práce" }
        // Práce on 09-02 (3) and 09-04 (1): (3 + 1) / 2 = 2.0.
        assertEquals(2.0, prace.averageMood, 1e-9)
        assertEquals(2, prace.count)
    }

    @Test
    fun `activity correlation is the point-biserial coefficient over answered days`() {
        val days = listOf(
            day("2026-09-01", mood = 2),
            day("2026-09-02", mood = 4, activities = listOf("Sport")),
            day("2026-09-03", mood = 2),
            day("2026-09-04", mood = 4, activities = listOf("Sport")),
        )

        // mentions x = [0, 1, 0, 1], moods y = [2, 4, 2, 4]
        //   meanX = 0.5, meanY = 3
        //   cov = (-0.5)(-1) + (0.5)(1) + (-0.5)(-1) + (0.5)(1) = 2
        //   varX = 1, varY = 4 → sqrt(1 × 4) = 2  ⇒  r = 2 / 2 = 1.0
        assertEquals(1.0, StatsMath.activityCorrelation(days, "Sport")!!, 1e-9)
    }

    @Test
    fun `a correlation with no variance is null, not a division by zero`() {
        // The activity is mentioned on every answered day → x is a constant column.
        val always = listOf(
            day("2026-09-01", mood = 1, activities = listOf("Sport")),
            day("2026-09-02", mood = 5, activities = listOf("Sport")),
        )
        assertNull(StatsMath.activityCorrelation(always, "Sport"))

        // The activity is never mentioned → x is a constant column too.
        assertNull(StatsMath.activityCorrelation(always, "Práce"))

        // Every answered mood is identical → y is a constant column.
        val flat = listOf(
            day("2026-09-01", mood = 3, activities = listOf("Sport")),
            day("2026-09-02", mood = 3),
        )
        assertNull(StatsMath.activityCorrelation(flat, "Sport"))

        // Fewer than two answered days is nothing to correlate.
        assertNull(
            StatsMath.activityCorrelation(
                listOf(day("2026-09-01", mood = 3, activities = listOf("Sport"))),
                "Sport",
            ),
        )
    }

    @Test
    fun `pearson returns null for a constant series and never divides by zero`() {
        assertNull(StatsMath.pearsonCorrelation(listOf(2.0, 2.0, 2.0), listOf(1.0, 3.0, 5.0)))
        assertNull(StatsMath.pearsonCorrelation(listOf(1.0, 2.0), listOf(4.0, 4.0)))
        assertNull(StatsMath.pearsonCorrelation(emptyList(), emptyList()))
        assertNull(StatsMath.pearsonCorrelation(listOf(1.0), listOf(2.0)))
        assertNull(StatsMath.pearsonCorrelation(listOf(1.0, 2.0), listOf(1.0)))   // mismatched lengths

        // x = [1,2,3,4], y = [1,3,3,1]: deviations are orthogonal → r = 0.0.
        assertEquals(
            0.0,
            StatsMath.pearsonCorrelation(listOf(1.0, 2.0, 3.0, 4.0), listOf(1.0, 3.0, 3.0, 1.0))!!,
            1e-9,
        )
    }

    @Test
    fun `activities are ranked by average mood and ties broken by name`() {
        val days = listOf(day("2026-09-01", mood = 4, activities = listOf("Běh", "Káva")))

        // Both appear once at mood 4 → equal average → alphabetical.
        assertEquals(listOf("Běh", "Káva"), StatsMath.activityStats(days).map { it.name })
    }

    @Test
    fun `activity stats are capped at the limit`() {
        val days = listOf(
            day("2026-09-01", mood = 3, activities = (1..12).map { "Akt$it" }),
        )

        assertEquals(StatsMath.ACTIVITY_LIMIT, StatsMath.activityStats(days).size)
        assertEquals(2, StatsMath.activityStats(days, limit = 2).size)
    }

    /**
     * Tie-break, pinned to the web. `AdvancedStats.tsx` walks the days oldest-first
     * and replaces the worst only on a strict `<` (`if (!worst || e.mood < worst.mood)`),
     * so the **first** day that hit the minimum wins. The port used to take the last
     * tied day and reported 2026-09-02 here; the web is the contract.
     */
    @Test
    fun `activity worst day on a tie is the earliest mention, as the web's running minimum`() {
        val days = listOf(
            day("2026-09-01", mood = 2, activities = listOf("Sport")),
            day("2026-09-02", mood = 2, activities = listOf("Sport")),
            day("2026-09-03", mood = 5, activities = listOf("Sport")),
        )

        val sport = StatsMath.activityStats(days).single()

        assertEquals(MoodPoint("2026-09-01", 2), sport.worstDay)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun day(
        date: String,
        mood: Int = 0,
        activities: List<String> = emptyList(),
        screenTime: Int? = null,
        unlocks: Int? = null,
        topApps: List<PhoneTopApp> = emptyList(),
    ) = StatsDay(
        date = date,
        mood = mood,
        activities = activities,
        screenTimeSeconds = screenTime,
        unlocks = unlocks,
        topApps = topApps,
    )
}
