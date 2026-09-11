package cz.digitalnivedomi.diarium.core.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure fold behind the phone's stats — the arithmetic that used to be buried
 * inside `UsageStatsProvider.dayStats` and could only be checked on a real device.
 *
 * These tests pin the rules that decide the numbers the chart shows, including the
 * two regressions the legacy code carried: an unfinished day closing at midnight
 * (the "15h54m of screen time" bug) and sub-two-second app blips being counted.
 */
class DayUsageFolderTest {

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L

        /** An arbitrary fixed midnight — only the deltas between events matter. */
        const val DAY_START = 1_756_982_400_000L
        const val DAY_END = DAY_START + 24 * HOUR
    }

    private fun interactive(ts: Long) = UsageEvent(UsageEventType.SCREEN_INTERACTIVE, ts)
    private fun screenOff(ts: Long) = UsageEvent(UsageEventType.SCREEN_NON_INTERACTIVE, ts)
    private fun resumed(pkg: String, ts: Long) = UsageEvent(UsageEventType.ACTIVITY_RESUMED, ts, pkg)
    private fun paused(pkg: String, ts: Long) = UsageEvent(UsageEventType.ACTIVITY_PAUSED, ts, pkg)

    private fun fold(
        events: List<UsageEvent>,
        closeAt: Long = DAY_END,
        label: (String) -> String = { it },
    ): DayUsage = DayUsageFolder.fold(
        events = events,
        dayStart = DAY_START,
        closeAt = closeAt,
        label = label,
    )

    // ---------------------------------------------------------------- (a) closeAt

    /**
     * The 15h54m regression: a day still in progress must close at *now*.
     *
     * Seen live: at 15:54 today an open screen session (phone unlocked at 00:00)
     * was extrapolated to midnight, so the chart read "15h54m of screen time" and
     * Instagram "862 minutes". A running window ends at the clock, only a finished
     * one ends at midnight.
     */
    @Test
    fun `a day still running closes at now, not at midnight`() {
        val sixInTheMorning = DAY_START + 6 * HOUR

        assertEquals(sixInTheMorning, DayUsageFolder.closeAt(DAY_END, sixInTheMorning))

        // A session open since midnight counts the six hours so far, not 24.
        val usage = fold(listOf(interactive(DAY_START)), closeAt = sixInTheMorning)

        assertEquals(6 * 3600, usage.totalSec)
        assertTrue("6h is not 15h54m and not a full day", usage.totalSec < 24 * 3600)
    }

    @Test
    fun `a finished day closes at midnight however late it is pushed`() {
        assertEquals(DAY_END, DayUsageFolder.closeAt(DAY_END, DAY_END))
        // The 07:00 run for yesterday, or a backfill days later.
        assertEquals(DAY_END, DayUsageFolder.closeAt(DAY_END, DAY_END + 30 * 24 * HOUR))
    }

    @Test
    fun `an app still in the foreground at closeAt is credited only up to closeAt`() {
        val fiveMinutesIn = DAY_START + 5 * MINUTE

        val usage = fold(
            listOf(interactive(DAY_START), resumed("com.instagram.android", DAY_START + 1_000)),
            closeAt = fiveMinutesIn,
        )

        assertEquals(4 * 60 + 59, usage.apps.single().seconds) // 5 min − 1 s
        assertEquals(5 * 60, usage.totalSec)
    }

    // ------------------------------------------------- (b) the 2-second threshold

    @Test
    fun `an app interval shorter than two seconds is dropped`() {
        val usage = fold(
            listOf(
                interactive(DAY_START),
                resumed("com.example.dialog", DAY_START),
                paused("com.example.dialog", DAY_START + 1_999), // 1 s
            ),
        )

        assertTrue("a 1 s blip is not app time", usage.apps.isEmpty())
    }

    @Test
    fun `an interval of exactly two seconds counts`() {
        val usage = fold(
            listOf(
                interactive(DAY_START),
                resumed("com.example.small", DAY_START),
                paused("com.example.small", DAY_START + 2_000),
            ),
        )

        assertEquals(2, usage.apps.single().seconds)
    }

    @Test
    fun `an app still open at the window end is dropped when under two seconds`() {
        val usage = fold(
            listOf(interactive(DAY_START), resumed("com.example.small", DAY_START)),
            closeAt = DAY_START + 1_500,
        )

        assertTrue(usage.apps.isEmpty())
    }

    // ------------------------------------------------------ (c) ignored packages

    @Test
    fun `system packages, Play services and the launcher are never app time`() {
        val systemPackages = listOf(
            "com.google.android.gms",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.inputmethod.latin",
            "android",
            "com.android.systemui",
        )

        val events = mutableListOf(interactive(DAY_START))
        for (pkg in systemPackages) {
            events += resumed(pkg, DAY_START)
            events += paused(pkg, DAY_START + 10_000)
        }
        events += resumed("com.instagram.android", DAY_START)
        events += paused("com.instagram.android", DAY_START + 5_000)

        val usage = fold(events)

        assertEquals(
            "only the real app is credited",
            listOf("com.instagram.android"),
            usage.apps.map { it.packageName },
        )
    }

    @Test
    fun `the ignore set is overridable for callers that need a different one`() {
        val events = listOf(
            interactive(DAY_START),
            resumed("com.instagram.android", DAY_START),
            paused("com.instagram.android", DAY_START + 5_000),
        )

        val usage = DayUsageFolder.fold(
            events = events,
            dayStart = DAY_START,
            closeAt = DAY_END,
            label = { it },
            ignored = setOf("com.instagram.android"),
        )

        assertTrue(usage.apps.isEmpty())
    }

    // --------------------------------------------- (d) screen-off ends intervals

    /**
     * Going off-screen ends the open app interval — it is dropped, never carried
     * across the gap: the pause event 90 minutes later must not turn a 29-second
     * glance into 90 minutes.
     */
    @Test
    fun `screen-off ends an open app interval instead of carrying it forward`() {
        val usage = fold(
            listOf(
                interactive(DAY_START),
                resumed("com.instagram.android", DAY_START + 1_000),
                screenOff(DAY_START + 30_000),
                interactive(DAY_START + HOUR),
                paused("com.instagram.android", DAY_START + 2 * HOUR),
            ),
            closeAt = DAY_START + 3 * HOUR,
        )

        assertTrue("the pre-screen-off glance must not reappear", usage.apps.isEmpty())
        // Display-on time is the two lit windows only: 30 s + the 2 h since 13:00.
        assertEquals(30 + 2 * 3600, usage.totalSec)
    }

    @Test
    fun `app events while the screen is off are not app time`() {
        val usage = fold(
            listOf(
                resumed("com.example.background", DAY_START),
                paused("com.example.background", DAY_START + 10_000),
            ),
        )

        assertTrue(usage.apps.isEmpty())
        assertEquals(0, usage.totalSec)
    }

    // ------------------------------------------------------------- (e) unlocks

    @Test
    fun `unlocks count the screen-interactive events and nothing else`() {
        val usage = fold(
            listOf(
                interactive(DAY_START),
                screenOff(DAY_START + 10_000),
                interactive(DAY_START + 20_000),
                resumed("com.example.app", DAY_START + 20_000),
                screenOff(DAY_START + 30_000),
            ),
            closeAt = DAY_START + HOUR,
        )

        assertEquals(2, usage.unlocks)
    }

    // ------------------------------------------------------- (f) screen time only

    @Test
    fun `total screen time is display-on time only, idle gaps excluded`() {
        val usage = fold(
            listOf(
                interactive(DAY_START + 10_000),
                screenOff(DAY_START + 70_000), // 60 s on
                interactive(DAY_START + HOUR), // an hour of idle — not counted
                screenOff(DAY_START + HOUR + 30_000), // 30 s on
            ),
        )

        assertEquals(90, usage.totalSec)
    }

    // ------------------------------------------------------------ housekeeping

    @Test
    fun `a package with no label resolver output falls back to the package id`() {
        val usage = fold(
            listOf(
                interactive(DAY_START),
                resumed("com.example.unknown", DAY_START),
                paused("com.example.unknown", DAY_START + 5_000),
            ),
        )

        assertEquals("com.example.unknown", usage.apps.single().label)
    }

    @Test
    fun `the label resolver is only asked for apps that survive the thresholds`() {
        val asked = mutableListOf<String>()

        val usage = fold(
            listOf(
                interactive(DAY_START),
                resumed("com.example.blip", DAY_START),
                paused("com.example.blip", DAY_START + 1_000), // dropped
                resumed("com.example.real", DAY_START + 1_000),
                paused("com.example.real", DAY_START + 6_000),
            ),
            label = { asked += it; "Real" },
        )

        assertEquals(listOf("com.example.real"), asked)
        assertEquals("Real", usage.apps.single().label)
    }

    @Test
    fun `two intervals of the same app add up into one slice`() {
        val usage = fold(
            listOf(
                interactive(DAY_START),
                resumed("com.example.app", DAY_START + 1_000),
                paused("com.example.app", DAY_START + 11_000), // 10 s
                resumed("com.example.app", DAY_START + 61_000),
                paused("com.example.app", DAY_START + 81_000), // 20 s
            ),
        )

        assertEquals(1, usage.apps.size)
        assertEquals(30, usage.apps.single().seconds)
    }

    @Test
    fun `apps keep the order they were first credited in`() {
        // The payload builder slices the first 15 in this order, so it is part of
        // the contract, not an accident of the map implementation.
        val usage = fold(
            listOf(
                interactive(DAY_START),
                resumed("com.first", DAY_START),
                paused("com.first", DAY_START + 5_000),
                resumed("com.second", DAY_START + 10_000),
                paused("com.second", DAY_START + 25_000),
            ),
        )

        assertEquals(listOf("com.first", "com.second"), usage.apps.map { it.packageName })
    }

    @Test
    fun `an activity event with no package is ignored rather than crashing`() {
        val usage = fold(
            listOf(
                interactive(DAY_START),
                UsageEvent(UsageEventType.ACTIVITY_RESUMED, DAY_START, null),
                UsageEvent(UsageEventType.ACTIVITY_PAUSED, DAY_START + 5_000, null),
            ),
        )

        assertTrue(usage.apps.isEmpty())
    }

    @Test
    fun `an empty day folds to zeroes, not nulls`() {
        val usage = fold(emptyList())

        assertEquals(0, usage.totalSec)
        assertEquals(0, usage.unlocks)
        assertTrue(usage.apps.isEmpty())
    }
}
