package cz.digitalnivedomi.diarium.ui.stats

import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.data.StatsData
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.ui.home.DashboardMetricKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StatsExplorationStateTest {
    private fun data() = StatsData(
        today = "2026-11-15",
        days = (0..399).map { offset ->
            val date = LocalDate.parse("2026-11-15").minusDays((399 - offset).toLong()).toString()
            StatsDay(date = date, mood = if (offset % 3 == 0) 4 else 0)
        },
    )

    @Test fun `range and category changes are local and preserve data`() {
        val holder = StatsStateHolder()
        val loaded = data()
        holder.show(loaded)
        holder.selectRange(StatsRange.QUARTER)
        holder.selectCategory(StatsCategory.DIGITAL)
        assertEquals(loaded, holder.state.data)
        assertFalse(holder.state.loading)
        assertEquals(90, loaded.forRange(holder.state.range, holder.state.customDays).size)
    }

    @Test fun `custom range stays within explicit 400 day load ceiling`() {
        val holder = StatsStateHolder()
        holder.show(data())
        holder.setCustomDays(900)
        assertEquals(400, holder.state.customDays)
        assertEquals(400, holder.state.data!!.forRange(holder.state.range, holder.state.customDays).size)
        holder.setCustomDays(0)
        assertEquals(1, holder.state.customDays)
    }

    @Test fun `comparison is suppressed when previous calendar window falls outside loaded coverage`() {
        val end = LocalDate.parse("2026-11-15")
        val oldest = end.minusDays(399)
        val loaded = StatsData(
            today = end.toString(),
            days = (0..399).map { offset ->
                val date = oldest.plusDays(offset.toLong()).toString()
                StatsDay(date = date, mood = if (offset % 2 == 0) 4 else 0)
            },
        )
        assertFalse(loaded.hasComparablePrevious(StatsRange.CUSTOM, 400))
    }

    @Test fun `comparison requires at least two mood observations in both windows`() {
        val end = LocalDate.parse("2026-11-15")
        val days = (0..399).map { offset ->
            val date = end.minusDays((399 - offset).toLong()).toString()
            StatsDay(date = date, mood = if (offset == 395 || offset == 399) 4 else 0)
        }
        val loaded = StatsData(today = end.toString(), days = days)
        assertFalse(loaded.hasComparablePrevious(StatsRange.WEEK, 30))
    }

    @Test fun `previous comparison is equal length and cached-only`() {
        val loaded = data()
        val current = loaded.forRange(StatsRange.WEEK, 30)
        val previous = loaded.previousRange(StatsRange.WEEK, 30)
        assertEquals(7, current.size)
        assertEquals(7, previous.size)
        assertEquals("2026-11-08", previous.last().date)
        assertEquals("2026-11-09", current.first().date)
    }

    @Test fun `dashboard metrics hand off to the matching statistics category and week`() {
        assertEquals(StatsNavigationIntent(StatsRange.WEEK, StatsCategory.MOOD), StatsNavigationIntent.fromDashboard(DashboardMetricKind.MOOD))
        assertEquals(StatsNavigationIntent(StatsRange.WEEK, StatsCategory.DIGITAL), StatsNavigationIntent.fromDashboard(DashboardMetricKind.SCREEN_TIME))
        assertEquals(StatsNavigationIntent(StatsRange.WEEK, StatsCategory.DIGITAL), StatsNavigationIntent.fromDashboard(DashboardMetricKind.UNLOCKS))
    }

    @Test fun `saved category period and comparison restore across holder recreation`() {
        val restored = StatsStateHolder()
        restored.restore("CUSTOM", "HABITS", 123, true)
        assertEquals(StatsRange.CUSTOM, restored.state.range)
        assertEquals(StatsCategory.HABITS, restored.state.category)
        assertEquals(123, restored.state.customDays)
        assertTrue(restored.state.comparePrevious)
    }
}
