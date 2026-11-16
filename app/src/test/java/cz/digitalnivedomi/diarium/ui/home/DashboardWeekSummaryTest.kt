package cz.digitalnivedomi.diarium.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.data.DashboardDay
import cz.digitalnivedomi.diarium.core.data.DashboardRepository
import cz.digitalnivedomi.diarium.core.data.DatedEntry
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.Goal
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.core.data.PhoneTopApp
import cz.digitalnivedomi.diarium.ui.theme.DiariumTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DashboardWeekSummaryTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test
    fun `complete windows exclude today and cross month and year boundaries`() {
        assertEquals(
            listOf("2026-11-08", "2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12", "2026-11-13", "2026-11-14"),
            dashboardCompletedWeek("2026-11-15"),
        )
        assertEquals(
            listOf("2025-12-25", "2025-12-26", "2025-12-27", "2025-12-28", "2025-12-29", "2025-12-30", "2025-12-31"),
            dashboardCompletedWeek("2026-01-01"),
        )
        assertEquals(LocalDate.parse("2025-12-24"), LocalDate.parse(dashboardCompletedWeek("2026-01-01", 1).last()))
        assertEquals(
            listOf("2024-02-23", "2024-02-24", "2024-02-25", "2024-02-26", "2024-02-27", "2024-02-28", "2024-02-29"),
            dashboardCompletedWeek("2024-03-01"),
        )
    }

    @Test
    fun `completed metric distinguishes missing from zero and withholds partial comparisons`() {
        val today = "2026-11-15"
        val rows = listOf(
            day("2026-11-08", mood = 5, seconds = 0, unlocks = 0),
            day("2026-11-09", mood = 3, seconds = 3_600, unlocks = null),
            day("2026-11-10", mood = 0, seconds = null, unlocks = null),
            day("2026-11-11", mood = 4, seconds = 1_800, unlocks = 2),
            day("2026-11-14", mood = 1, seconds = 0, unlocks = 0),
            day("2026-11-15", mood = 5, seconds = 86_400, unlocks = 99),
            day("2026-11-01", mood = 2, seconds = 600, unlocks = 1),
            day("2026-11-02", mood = 0, seconds = null, unlocks = null),
            day("2026-11-03", mood = 3, seconds = 1_200, unlocks = 2),
            day("2026-10-31", mood = 5, seconds = 99_999, unlocks = 99),
        )
        val mood = dashboardWeekMetric(today, rows, rows, DashboardMetricKind.MOOD)
        assertEquals(3.25, mood.value!!, 0.0)
        assertEquals(4, mood.coveredDays)
        assertEquals(2.5, mood.previousValue!!, 0.0)
        assertEquals(2, mood.previousCoveredDays)
        assertNull(mood.difference)

        val screen = dashboardWeekMetric(today, rows, rows, DashboardMetricKind.SCREEN_TIME)
        assertEquals(90.0, screen.value!!, 0.0)
        assertEquals(4, screen.coveredDays)
        assertEquals(30.0, screen.previousValue!!, 0.0)
        assertNull(screen.difference)
        assertFalse(hasComparableCoverage(screen.coveredDays, screen.previousCoveredDays))
        assertEquals("Srovnání skryto · pokrytí 4/7 vs. 2/7 dní", coverageComparisonHint(4, 2))
    }

    @Test
    fun `empty and zero-only screen time are different states`() {
        val today = "2026-05-10"
        val empty = dashboardWeekMetric(today, emptyList(), emptyList(), DashboardMetricKind.SCREEN_TIME)
        assertNull(empty.value)
        assertEquals(0, empty.coveredDays)
        val zeros = dashboardCompletedWeek(today).map { day(it, seconds = 0, unlocks = 0) }
        val zeroMetric = dashboardWeekMetric(today, zeros, emptyList(), DashboardMetricKind.SCREEN_TIME)
        assertEquals(0.0, zeroMetric.value!!, 0.0)
        assertEquals(7, zeroMetric.coveredDays)
        assertEquals(0, dashboardAppCoverage(today, emptyMap()))
    }

    @Test
    fun `fully covered windows expose signed difference and direction eligibility`() {
        val today = "2026-01-01"
        val current = dashboardCompletedWeek(today).mapIndexed { index, date -> day(date, mood = 4, seconds = index * 60, unlocks = 1) }
        val previous = dashboardCompletedWeek(today, 1).mapIndexed { index, date -> day(date, mood = 2, seconds = index * 120, unlocks = 1) }
        val metric = dashboardWeekMetric(today, current + previous, current + previous, DashboardMetricKind.SCREEN_TIME)
        assertEquals(21.0, metric.value!!, 0.0)
        assertEquals(42.0, metric.previousValue!!, 0.0)
        assertEquals(-21.0, metric.difference!!, 0.0)
        assertTrue(hasComparableCoverage(metric.coveredDays, metric.previousCoveredDays))
        assertNull(coverageComparisonHint(7, 7))
    }

    @Test
    fun `trend is regression over observed bars and preserves calendar gaps`() {
        val trend = dashboardLinearTrend(listOf(1.0, 100.0, null, 4.0, 7.0))!!
        assertEquals(0, trend.startIndex)
        assertEquals(4, trend.endIndex)
        // The fitted trend smooths the outlier instead of joining raw bar values.
        assertTrue(trend.startValue > 1.0)
        assertTrue(trend.startValue < 100.0)
        assertTrue(trend.endValue > 0.0)
        assertTrue(trend.endValue != 7.0)
        assertNull(dashboardLinearTrend(listOf(null, 0.0, null)))
    }

    @Test
    fun `app aggregation is inclusive over completed days normalizes keys ranks and caps five`() {
        val today = "2026-03-01"
        val entries = linkedMapOf(
            "2026-02-22" to DiaryEntry(phoneTopApps = listOf(PhoneTopApp(" Chat ", 12.5), PhoneTopApp("Maps", 4))),
            "2026-02-23" to DiaryEntry(phoneTopApps = listOf(PhoneTopApp("chat", 7.5), PhoneTopApp("Mail", 9))),
            "2026-02-24" to DiaryEntry(phoneTopApps = listOf(PhoneTopApp("Video", 8), PhoneTopApp("Music", 7))),
            "2026-02-25" to DiaryEntry(phoneTopApps = listOf(PhoneTopApp("Books", 6))),
            "2026-02-27" to DiaryEntry(phoneTopApps = listOf(PhoneTopApp(" ", Double.NaN))),
            "2026-02-28" to DiaryEntry(phoneTopApps = listOf(PhoneTopApp("Games", 5))),
            "2026-03-01" to DiaryEntry(phoneTopApps = listOf(PhoneTopApp("Today only", 999))),
        )
        val apps = aggregateDashboardTopApps(today, entries)
        assertEquals(5, apps.size)
        assertEquals("Chat", apps.first().app)
        assertEquals(20.0, apps.first().minutes, 0.0)
        assertFalse(apps.any { it.app == "Today only" })
        assertEquals(listOf("Chat", "Mail", "Video", "Music", "Books"), apps.map { it.app })
        assertEquals(5, dashboardAppCoverage(today, entries))
    }

    @Test
    fun `digital chart interaction keeps statistics navigation callback`() {
        val date = "2026-11-15"
        val data = DashboardRepository.derive(date, listOf(DatedEntry("2026-11-14", DiaryEntry(phoneScreenTime = 0))))
        var opened: DashboardMetricKind? = null
        compose.setContent {
            DiariumTheme { DigitalSummaryCard(data, onOpenMetric = { opened = it }) }
        }
        compose.onNodeWithTag("dashboard_digital_chart").performClick()
        assertEquals(DashboardMetricKind.SCREEN_TIME, opened)
    }

    @Test
    fun `all active goal rows render and footer preserves goals navigation`() {
        val goals = (1..6).map { index ->
            GoalProgress(
                goal = Goal("goal-$index", "activity-$index", "Synthetic goal $index", 3, "weekly", true),
                count = index % 3,
                streak = 0,
            )
        }
        var destination: String? = null
        compose.setContent {
            DiariumTheme { GoalsCard(goals, onOpen = { destination = it }) }
        }
        (1..6).forEach { index -> compose.onAllNodesWithTag("dashboard_goal_goal-$index").assertCountEquals(1) }
        compose.onNodeWithText("Všechny cíle →").performClick()
        assertEquals(cz.digitalnivedomi.diarium.ui.nav.Routes.GOALS, destination)
    }

    private fun day(date: String, mood: Int = 0, seconds: Int? = null, unlocks: Int? = null) =
        DashboardDay(date, mood, hasEntry = mood > 0 || seconds != null || unlocks != null, screenTimeSeconds = seconds, unlocks = unlocks)
}
