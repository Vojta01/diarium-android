package cz.digitalnivedomi.diarium.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure JVM tests for the schedulers. No `LocalDate.now()` anywhere — every test
 * pins a fixed instant so the results are deterministic.
 */
class NotificationSchedulerLogicTest {

    private val prague: ZoneId = ZoneId.of("Europe/Prague")

    /** Epoch millis for a Prague wall-clock time (safe for non-gap times). */
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(prague).toInstant().toEpochMilli()

    private fun local(epoch: Long) = Instant.ofEpochMilli(epoch).atZone(prague)

    // ── Daily ──────────────────────────────────────────────────

    @Test
    fun daily_sameDay_whenTimeStillAhead() {
        val now = at(2026, 9, 11, 12, 0) // Friday noon
        val next = NotificationSchedulerLogic.nextDailyMillis(now, 19 * 60, setOf(1, 2, 3, 4, 5, 6, 7), prague)!!
        val z = local(next)
        assertEquals(2026, z.year)
        assertEquals(9, z.monthValue)
        assertEquals(11, z.dayOfMonth)
        assertEquals(19, z.hour)
        assertEquals(0, z.minute)
        assertTrue(next > now)
    }

    @Test
    fun daily_rollsToTomorrow_whenTimePassed() {
        val now = at(2026, 9, 11, 20, 0) // Friday 20:00, reminder was 19:00
        val next = NotificationSchedulerLogic.nextDailyMillis(now, 19 * 60, setOf(1, 2, 3, 4, 5, 6, 7), prague)!!
        val z = local(next)
        assertEquals(12, z.dayOfMonth)
        assertEquals(19, z.hour)
        assertTrue(next > now)
    }

    @Test
    fun daily_skipsDaysNotSelected() {
        val now = at(2026, 9, 11, 12, 0) // Friday
        val next = NotificationSchedulerLogic.nextDailyMillis(now, 19 * 60, setOf(1), prague)!!
        val z = local(next)
        assertEquals(1, z.dayOfWeek.value) // Monday
        assertEquals(14, z.dayOfMonth)
    }

    @Test
    fun daily_onlyTodaySelectedAndPassed_movesOneWeekOut() {
        val now = at(2026, 9, 11, 20, 0) // Friday
        val next = NotificationSchedulerLogic.nextDailyMillis(now, 19 * 60, setOf(5), prague)!!
        val z = local(next)
        assertEquals(5, z.dayOfWeek.value)
        assertEquals(18, z.dayOfMonth)
    }

    @Test
    fun daily_emptyDays_returnsNull() {
        val now = at(2026, 9, 11, 12, 0)
        assertNull(NotificationSchedulerLogic.nextDailyMillis(now, 19 * 60, emptySet(), prague))
    }

    @Test
    fun daily_crossesYearBoundary() {
        val now = at(2026, 12, 31, 22, 0) // reminder 19:00 already passed
        val next = NotificationSchedulerLogic.nextDailyMillis(now, 19 * 60, setOf(1, 2, 3, 4, 5, 6, 7), prague)!!
        val z = local(next)
        assertEquals(2027, z.year)
        assertEquals(1, z.monthValue)
        assertEquals(1, z.dayOfMonth)
        assertEquals(19, z.hour)
    }

    // ── Weekly ─────────────────────────────────────────────────

    @Test
    fun weekly_nextSunday() {
        val now = at(2026, 9, 11, 12, 0) // Friday
        val next = NotificationSchedulerLogic.nextWeeklyMillis(now, 20 * 60, 7, prague)
        val z = local(next)
        assertEquals(7, z.dayOfWeek.value) // Sunday
        assertEquals(13, z.dayOfMonth)
        assertEquals(20, z.hour)
    }

    @Test
    fun weekly_todayPassed_goesToNextWeek() {
        val now = at(2026, 9, 13, 21, 0) // Sunday 21:00, report was 20:00
        val next = NotificationSchedulerLogic.nextWeeklyMillis(now, 20 * 60, 7, prague)
        val z = local(next)
        assertEquals(7, z.dayOfWeek.value)
        assertEquals(20, z.dayOfMonth)
    }

    // ── Monthly ────────────────────────────────────────────────

    @Test
    fun monthly_firstOfNextMonth() {
        val now = at(2026, 9, 11, 12, 0)
        val next = NotificationSchedulerLogic.nextMonthlyMillis(now, 9 * 60, 1, prague)
        val z = local(next)
        assertEquals(10, z.monthValue)
        assertEquals(1, z.dayOfMonth)
        assertEquals(9, z.hour)
    }

    @Test
    fun monthly_day31_clampedToLastDayOfFebruary() {
        val now = at(2026, 2, 10, 9, 0)
        val next = NotificationSchedulerLogic.nextMonthlyMillis(now, 9 * 60, 31, prague)
        val z = local(next)
        assertEquals(2026, z.year)
        assertEquals(2, z.monthValue)
        assertEquals(28, z.dayOfMonth) // Feb 2026 has 28 days
        assertEquals(9, z.hour)
    }

    @Test
    fun monthly_day31_afterClampedFebruary_goesToMarch31() {
        val now = at(2026, 2, 28, 10, 0)
        val next = NotificationSchedulerLogic.nextMonthlyMillis(now, 9 * 60, 31, prague)
        val z = local(next)
        assertEquals(3, z.monthValue)
        assertEquals(31, z.dayOfMonth)
    }

    // ── DST (Europe/Prague) ────────────────────────────────────

    @Test
    fun dst_springForward_gapTimeIsShiftedForward() {
        // 2026-03-29 02:00 CET → 03:00 CEST; a 02:30 reminder does not exist.
        val now = at(2026, 3, 28, 12, 0)
        val next = NotificationSchedulerLogic.nextDailyMillis(now, 2 * 60 + 30, setOf(1, 2, 3, 4, 5, 6, 7), prague)!!
        val z = local(next)
        assertEquals(29, z.dayOfMonth)
        // java.time shifts the nonexistent wall time forward by the gap length → 03:30 CEST.
        assertEquals(Instant.parse("2026-03-29T01:30:00Z").toEpochMilli(), next)
        assertEquals(3, z.hour)
    }

    @Test
    fun dst_fallBack_ambiguousTimeUsesEarlierOffset() {
        // 2026-10-25 03:00 CEST → 02:00 CET; 02:30 occurs twice.
        val now = at(2026, 10, 24, 12, 0)
        val next = NotificationSchedulerLogic.nextDailyMillis(now, 2 * 60 + 30, setOf(1, 2, 3, 4, 5, 6, 7), prague)!!
        val z = local(next)
        assertEquals(25, z.dayOfMonth)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z").toEpochMilli(), next)
    }

    // ── Morning reminder ("včerejšek není vyplněn") ────────────

    private val morningToday = "2026-09-12"

    @Test
    fun morning_disabled_doesNotNotify() {
        val prefs = NotificationPrefs(morningReminderEnabled = false)
        assertFalse(MorningReminder.shouldNotify(prefs, morningToday, hasYesterdayMood = false))
    }

    @Test
    fun morning_alreadyRemindedToday_doesNotNotify() {
        val prefs = NotificationPrefs(lastMorningReminderDate = morningToday)
        assertFalse(MorningReminder.shouldNotify(prefs, morningToday, hasYesterdayMood = false))
    }

    @Test
    fun morning_yesterdayMoodRecorded_doesNotNotify() {
        val prefs = NotificationPrefs()
        assertFalse(MorningReminder.shouldNotify(prefs, morningToday, hasYesterdayMood = true))
    }

    @Test
    fun morning_yesterdayMoodMissing_notifies() {
        val prefs = NotificationPrefs()
        assertTrue(MorningReminder.shouldNotify(prefs, morningToday, hasYesterdayMood = false))
    }

    @Test
    fun morning_yesterdayUnknown_staysSilent() {
        val prefs = NotificationPrefs()
        assertFalse(MorningReminder.shouldNotify(prefs, morningToday, hasYesterdayMood = null))
    }

    @Test
    fun morning_markerFromAnotherDate_notifies() {
        val prefs = NotificationPrefs(lastMorningReminderDate = "2026-09-11")
        assertTrue(MorningReminder.shouldNotify(prefs, morningToday, hasYesterdayMood = false))
    }

    // ── Report freshness (as it is a fresh row, do not regenerate) ──

    private val nowInstant: Instant = Instant.parse("2026-10-11T18:00:00Z")

    @Test
    fun freshness_recentReport_isFresh() {
        // The alarm runs at 20:00 Prague = 18:00 UTC; a row generated right then is fresh.
        assertTrue(ReportFreshness.isFresh("2026-10-11T17:59:00+00:00", nowInstant, 24))
    }

    @Test
    fun freshness_reportOlderThanWindow_isNotFresh() {
        assertFalse(ReportFreshness.isFresh("2026-10-10T17:00:00+00:00", nowInstant, 24))
    }

    @Test
    fun freshness_microsecondPrecision_parses() {
        // Supabase returns created_at with microsecond precision and a +00:00 offset.
        assertTrue(ReportFreshness.isFresh("2026-10-11T17:30:12.446936+00:00", nowInstant, 24))
    }

    @Test
    fun freshness_futureTimestampWithOffset_isFresh() {
        // Parsed as an instant — a non-UTC offset must not break the comparison.
        assertTrue(ReportFreshness.isFresh("2026-10-11T19:30:00+02:00", nowInstant, 24))
    }

    @Test
    fun freshness_unparseableTimestamp_isNotFresh() {
        assertFalse(ReportFreshness.isFresh("", nowInstant, 24))
        assertFalse(ReportFreshness.isFresh("not-a-date", nowInstant, 24))
    }

    // ── Report safety window (periodic rescue of a missed alarm) ──

    private fun z(y: Int, m: Int, d: Int, h: Int, min: Int): ZonedDateTime =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, prague)

    @Test
    fun safety_weekly_opensAfterTheChosenTime() {
        // Sunday 20:00 chosen; 20:29 is the alarm's own grace, 20:31 is the safety net's.
        assertFalse(ReportSafetyWindow.isWeeklyWindow(z(2026, 10, 11, 19, 45), 7, 20 * 60))
        assertTrue(ReportSafetyWindow.isWeeklyWindow(z(2026, 10, 11, 20, 31), 7, 20 * 60))
    }

    @Test
    fun safety_weekly_staysQuietAtNightAndCoversTheNextDay() {
        assertFalse(ReportSafetyWindow.isWeeklyWindow(z(2026, 10, 11, 23, 30), 7, 20 * 60))
        assertFalse(ReportSafetyWindow.isWeeklyWindow(z(2026, 10, 12, 3, 0), 7, 20 * 60))
        assertTrue(ReportSafetyWindow.isWeeklyWindow(z(2026, 10, 12, 10, 0), 7, 20 * 60))
        assertFalse(ReportSafetyWindow.isWeeklyWindow(z(2026, 10, 12, 21, 0), 7, 20 * 60))
    }

    @Test
    fun safety_weekly_followsTheChosenDay() {
        // Wednesday 21:00 chosen → the window is Wednesday (late) and Thursday (daytime).
        assertTrue(ReportSafetyWindow.isWeeklyWindow(z(2026, 10, 14, 21, 30), 3, 21 * 60))
        assertFalse(ReportSafetyWindow.isWeeklyWindow(z(2026, 10, 14, 21, 30), 7, 20 * 60))
        assertTrue(ReportSafetyWindow.isWeeklyWindow(z(2026, 10, 15, 9, 0), 3, 21 * 60))
    }

    @Test
    fun weeklyReportEnd_usesTheScheduledDay_notTheLateRunDay() {
        // Sunday 20:00 run → the report ends that Sunday.
        assertEquals(
            LocalDate.of(2026, 10, 11),
            ReportSafetyWindow.lastWeeklyOccurrence(z(2026, 10, 11, 20, 5), 7, 20 * 60),
        )
        // Monday 08:00 safety net → the report still ends on Sunday 10-11,
        // not on the Monday that just started (the 0/4 bug).
        assertEquals(
            LocalDate.of(2026, 10, 11),
            ReportSafetyWindow.lastWeeklyOccurrence(z(2026, 10, 12, 8, 1), 7, 20 * 60),
        )
        // Wednesday 21:00 chosen, Thursday morning rescue → Wednesday 10-14.
        assertEquals(
            LocalDate.of(2026, 10, 14),
            ReportSafetyWindow.lastWeeklyOccurrence(z(2026, 10, 15, 9, 0), 3, 21 * 60),
        )
    }

    @Test
    fun safety_monthly_coversFirstLateAndSecondDaytime() {
        assertFalse(ReportSafetyWindow.isMonthlyWindow(z(2026, 11, 1, 8, 0), 9 * 60))
        assertTrue(ReportSafetyWindow.isMonthlyWindow(z(2026, 11, 1, 9, 31), 9 * 60))
        assertFalse(ReportSafetyWindow.isMonthlyWindow(z(2026, 11, 1, 23, 30), 9 * 60))
        assertFalse(ReportSafetyWindow.isMonthlyWindow(z(2026, 11, 2, 3, 0), 9 * 60))
        assertTrue(ReportSafetyWindow.isMonthlyWindow(z(2026, 11, 2, 10, 0), 9 * 60))
        assertFalse(ReportSafetyWindow.isMonthlyWindow(z(2026, 11, 3, 10, 0), 9 * 60))
    }
}
