package cz.digitalnivedomi.diarium.core.stats

import cz.digitalnivedomi.diarium.core.data.PhoneTopApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsExplorationTest {
    @Test fun `typed windows are bounded and previous ranges are adjacent equal length across leap day`() {
        val current = StatsDateRange.of(LocalDate.parse("2024-03-01"), 3)
        assertEquals(LocalDate.parse("2024-02-28"), current.startInclusive)
        assertEquals(StatsDateRange.of(LocalDate.parse("2024-02-27"), 3), current.previous())
        assertEquals(LocalDate.parse("2024-02-27"), current.previous().endInclusive)
        assertEquals(LocalDate.parse("2024-02-29"), current.startInclusive.plusDays(1))
        val yearBoundary = StatsDateRange.of(LocalDate.parse("2025-01-01"), 3)
        assertEquals(LocalDate.parse("2024-12-30"), yearBoundary.startInclusive)
        assertEquals(LocalDate.parse("2024-12-29"), yearBoundary.previous().endInclusive)
        assertNull(StatsDateRange.tryOf(LocalDate.parse("2024-01-01"), 401))
        assertNull(StatsDateRange.tryOf(LocalDate.parse("2024-01-01"), 0))
    }

    @Test fun `coverage counts reported dates rather than fabricating missing values`() {
        val range = StatsDateRange.of(LocalDate.parse("2024-02-29"), 3)
        val data = listOf(StatsDay("2024-02-27", screenTimeSeconds = 0), StatsDay("2024-02-29", screenTimeSeconds = null))
        assertEquals(2, StatsExploration.coverage(data, range).reportedDays)
        assertEquals(3, StatsExploration.coverage(data, range).windowDays)
        assertEquals(1, StatsExploration.screenTimeCoverage(data, range).reportedDays)
        assertEquals(0, StatsExploration.digitalTotals(data, range).totalSeconds)
        assertEquals(1, StatsExploration.digitalTotals(data, range).reportedScreenTimeDays)
    }

    @Test fun `habit absent is distinct from false and absent mood from answered mood`() {
        val days = listOf(StatsDay("2024-01-01", mood = 0), StatsDay("2024-01-02", mood = 3, habits = mapOf("walk" to false)), StatsDay("2024-01-03", habits = mapOf("walk" to true)))
        val dist = StatsExploration.habitDistribution(days, "walk")
        assertEquals(1, dist.yes)
        assertEquals(1, dist.no)
        assertEquals(1, dist.unanswered)
        assertEquals(1, StatsExploration.moodDistribution(days)[3])
        assertEquals(2, StatsExploration.moodDistribution(days)[0])
    }

    @Test fun `partial windows and weekday mood profile preserve null days`() {
        val range = StatsDateRange.of(LocalDate.parse("2024-01-07"), 7)
        val days = listOf(StatsDay("2024-01-01", mood = 4), StatsDay("2024-01-07", mood = 2))
        assertEquals(2, StatsExploration.coverage(days, range).reportedDays)
        assertEquals(4.0, StatsExploration.weekdayMood(days)[java.time.DayOfWeek.MONDAY]!!.averageMood!!, 0.0)
        assertNull(StatsExploration.weekdayMood(days)[java.time.DayOfWeek.TUESDAY]!!.averageMood)
    }

    @Test fun `scale sleep and stress distributions exclude missing and zero values`() {
        val days = listOf(StatsDay("2024-01-01", sleepQuality = 0, stress = 3), StatsDay("2024-01-02", sleepQuality = 4, stress = 0, scaleValues = mapOf("energy" to 2)), StatsDay("2024-01-03", scaleValues = mapOf("energy" to 2)))
        assertEquals(mapOf(4 to 1), StatsExploration.sleepDistribution(days))
        assertEquals(mapOf(3 to 1), StatsExploration.stressDistribution(days))
        assertEquals(mapOf(2 to 2), StatsExploration.scaleDistribution(days, "energy"))
    }

    @Test fun `correlation is explicitly descriptive and reports correct group sample counts`() {
        val days = listOf(StatsDay("2024-01-01", mood=4, habits=mapOf("run" to true)), StatsDay("2024-01-02", mood=5, habits=mapOf("run" to true)), StatsDay("2024-01-03", mood=1, habits=mapOf("run" to false)), StatsDay("2024-01-04", mood=2, habits=mapOf("run" to false)))
        val result = StatsExploration.habitMoodAssociation(days, "run")
        assertEquals(2, result.nWith)
        assertEquals(2, result.nWithout)
        assertEquals(CorrelationStatus.DESCRIPTIVE_ONLY, result.status)
        assertTrue(result.correlation != null)
        assertEquals(CorrelationStatus.NOT_ENOUGH_DATA, StatsExploration.habitMoodAssociation(days.take(2), "run").status)
    }

    @Test fun `daily app aggregation sums seconds only for reported days`() {
        val days = listOf(StatsDay("2024-01-01", screenTimeSeconds=120, topApps=listOf(PhoneTopApp("A", 1))), StatsDay("2024-01-02", screenTimeSeconds=null, topApps=listOf(PhoneTopApp("B", 2))))
        val range = StatsDateRange.of(LocalDate.parse("2024-01-02"), 2)
        assertEquals(120, StatsExploration.digitalTotals(days, range).totalSeconds)
        assertFalse(StatsExploration.dailyApps(days, range).last().apps.isNotEmpty())
        assertEquals(mapOf("A" to 60), StatsExploration.dailyApps(days, range).first().apps)
    }
}
