package cz.digitalnivedomi.diarium.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

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
}
