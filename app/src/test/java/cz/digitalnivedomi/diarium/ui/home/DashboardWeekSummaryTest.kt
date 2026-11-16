package cz.digitalnivedomi.diarium.ui.home

import cz.digitalnivedomi.diarium.core.data.DashboardDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardWeekSummaryTest {
    @Test
    fun `week is exactly seven calendar days and metric coverage excludes missing values`() {
        val today = "2026-11-15"
        assertEquals(
            listOf(
                "2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12",
                "2026-11-13", "2026-11-14", "2026-11-15",
            ),
            dashboardCalendarWeek(today),
        )

        val current = listOf(
            day("2026-11-09", mood = 5, seconds = 0, unlocks = 0),
            day("2026-11-10", mood = 3, seconds = 3_600, unlocks = null),
            day("2026-11-11", mood = 0, seconds = null, unlocks = null),
            day("2026-11-15", mood = 0, seconds = null, unlocks = null),
            // Must not leak into the current seven-day window.
            day("2026-11-08", mood = 1, seconds = 60_000, unlocks = 99),
        )
        val previous = listOf(
            day("2026-11-02", mood = 2, seconds = 0, unlocks = 0),
            day("2026-11-03", mood = 0, seconds = null, unlocks = null),
        )

        val mood = dashboardWeekMetric(today, current, previous, DashboardMetricKind.MOOD)
        assertEquals(4.0, mood.value!!, 0.0)
        assertEquals(2, mood.coveredDays)
        assertEquals(2.0, mood.previousValue!!, 0.0)
        assertEquals(1, mood.previousCoveredDays)

        val screen = dashboardWeekMetric(today, current, previous, DashboardMetricKind.SCREEN_TIME)
        assertEquals(60.0, screen.value!!, 0.0)
        assertEquals(2, screen.coveredDays)
        assertEquals(0.0, screen.previousValue!!, 0.0)
        assertEquals(1, screen.previousCoveredDays)

        val unlocks = dashboardWeekMetric(today, current, previous, DashboardMetricKind.UNLOCKS)
        assertEquals(0.0, unlocks.value!!, 0.0)
        assertEquals(1, unlocks.coveredDays)
        assertEquals(0.0, unlocks.previousValue!!, 0.0)
        assertEquals(1, unlocks.previousCoveredDays)
    }

    @Test
    fun `missing week values remain missing rather than becoming zero`() {
        val days = dashboardCalendarWeek("2026-11-15").map { day(it) }
        val mood = dashboardWeekMetric("2026-11-15", days, emptyList(), DashboardMetricKind.MOOD)
        val screen = dashboardWeekMetric("2026-11-15", days, emptyList(), DashboardMetricKind.SCREEN_TIME)
        val unlocks = dashboardWeekMetric("2026-11-15", days, emptyList(), DashboardMetricKind.UNLOCKS)
        assertNull(mood.value)
        assertEquals(0, mood.coveredDays)
        assertNull(screen.value)
        assertEquals(0, screen.coveredDays)
        assertNull(unlocks.value)
        assertEquals(0, unlocks.coveredDays)
    }

    @Test
    fun `comparison requires full or equal nonzero coverage`() {
        assertEquals(true, hasComparableCoverage(7, 7))
        assertEquals(false, hasComparableCoverage(3, 3))
        assertEquals(false, hasComparableCoverage(3, 5))
        assertEquals(false, hasComparableCoverage(0, 0))
        assertEquals("Srovnání skryto · pokrytí 3/7 vs. 3/7 dní", coverageComparisonHint(3, 3))
        assertEquals("Srovnání skryto · pokrytí 3/7 vs. 5/7 dní", coverageComparisonHint(3, 5))
        assertNull(coverageComparisonHint(7, 7))
        assertEquals("Srovnání skryto · pokrytí 0/7 vs. 0/7 dní", coverageComparisonHint(0, 0))
        assertEquals("Srovnání skryto · pokrytí 0/7 vs. 5/7 dní", coverageComparisonHint(0, 5))
    }

    private fun day(
        date: String,
        mood: Int = 0,
        seconds: Int? = null,
        unlocks: Int? = null,
    ) = DashboardDay(date, mood, hasEntry = mood > 0 || seconds != null || unlocks != null, screenTimeSeconds = seconds, unlocks = unlocks)
}
