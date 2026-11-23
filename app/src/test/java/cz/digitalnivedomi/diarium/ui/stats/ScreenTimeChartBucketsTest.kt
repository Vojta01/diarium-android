package cz.digitalnivedomi.diarium.ui.stats

import cz.digitalnivedomi.diarium.core.stats.ScreenTimeDay
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScreenTimeChartBucketsTest {
    @Test fun `long range average is mean of unequal bucket totals`() {
        // Weekly totals represent seven and three days respectively; average remains on bucket-total scale.
        assertEquals(50.0, averageBucketTotal(listOf(70, 30))!!, 0.0001)
        assertNull(averageBucketTotal(listOf(null, null)))
    }

    @Test fun `captions determine partial today from injected date`() {
        val days = listOf(ScreenTimeDay("2026-11-23", 60, 2, hasApps = false))
        assertTrue(windowCaption(days, 7, "2026-11-23").contains("Dnešek je neúplné období."))
        assertTrue(unlockCaption(days, 7, "2026-11-23").contains("Dnešek je neúplné období."))
        assertTrue(!windowCaption(days, 7, "2026-11-24").contains("Dnešek je neúplné období."))
        assertTrue(!unlockCaption(days, 7, "2026-11-24").contains("Dnešek je neúplné období."))
    }

    @Test fun `linear trend uses observed periods and omits insufficient data`() {
        assertNull(digitalLinearTrend(listOf(5.0, null, null)))
        val trend = digitalLinearTrend(listOf(2.0, null, 6.0))!!
        assertEquals(2.0, trend.first(), 0.0001)
        assertEquals(6.0, trend.last(), 0.0001)
        val zeroTrend = digitalLinearTrend(listOf(0.0, null, 4.0))!!
        assertEquals(0.0, zeroTrend.first(), 0.0001)
        assertEquals(4.0, zeroTrend.last(), 0.0001)
        assertNull(digitalLinearTrend(listOf(null, null)))
    }

    @Test fun `inclusive global end allows today and rejects reversed or invalid bounds`() {
        assertEquals("2026-11-16", visibleScreenTimeEnd("2026-11-09", "2026-11-16", "2026-11-16"))
        assertEquals("2026-11-12", visibleScreenTimeEnd("2026-11-09", "2026-11-12", "2026-11-16"))
        assertNull(completedScreenTimeEnd("2026-11-17", "2026-11-16", "2026-11-16"))
        assertNull(completedScreenTimeEnd("bad", "2026-11-16", "2026-11-16"))
    }

    @Test fun `today is included in digital dates while an unsynced measurement stays absent`() {
        val today = LocalDate.parse("2026-11-23")
        val range = digitalStatsDateRange(StatsRange.WEEK, 7, today, null, 2026)
        assertEquals("2026-11-17", range?.start.toString())
        assertEquals("2026-11-23", range?.endInclusive.toString())
        val withToday = ScreenTimeDateRange.days(
            listOf(StatsDay("2026-11-23", screenTimeSeconds = 75_600, unlocks = null)),
            range!!.start.toString(), range.endInclusive.toString(),
        )
        assertEquals(75_600, withToday.last().seconds)
        assertNull(withToday.last().unlocks)
        assertEquals("2026-11-23", visibleScreenTimeEnd("2026-11-17", "2026-11-23", "2026-11-23"))
    }

    @Test fun `today reaches chart source bounds but not completed comparison and preserves zero versus missing`() {
        val today = LocalDate.parse("2026-11-23")
        val custom = ValidatedStatsDateRange(today.minusDays(2), today)
        val chartBounds = digitalStatsDateRange(StatsRange.CUSTOM, 30, today, custom, 2026)!!
        val completeBounds = completedDigitalStatsDateRange(StatsRange.CUSTOM, 30, today, custom, 2026)!!
        val source = listOf(
            StatsDay("2026-11-20", screenTimeSeconds = 777), // outside custom selection
            StatsDay("2026-11-21", screenTimeSeconds = 0, unlocks = 0),
            StatsDay("2026-11-22", screenTimeSeconds = null, unlocks = null),
            StatsDay("2026-11-23", screenTimeSeconds = 90, unlocks = null),
        )
        val visible = source.filter { runCatching { LocalDate.parse(it.date) }.getOrNull()?.let(chartBounds::contains) == true }
        val compared = source.filter { runCatching { LocalDate.parse(it.date) }.getOrNull()?.let(completeBounds::contains) == true }
        assertEquals(listOf("2026-11-21", "2026-11-22", "2026-11-23"), visible.map { it.date })
        assertEquals(listOf("2026-11-21", "2026-11-22"), compared.map { it.date })
        assertEquals(0, visible.first().screenTimeSeconds)
        assertNull(visible[1].screenTimeSeconds)
        assertEquals(90, visible.last().screenTimeSeconds)
        assertNull(visible.last().unlocks)
    }

    @Test fun `end before today remains unchanged`() {
        assertEquals("2026-11-20", completedScreenTimeEnd("2026-11-17", "2026-11-20", "2026-11-23"))
    }

    @Test fun `7 and 30 inclusive dates remain actual daily bars with distinct labels`() {
        listOf(7, 30).forEach { count ->
            val start = LocalDate.parse("2026-01-01")
            val days = (0 until count).map { offset ->
                val date = start.plusDays(offset.toLong()).toString()
                when (offset) {
                    0 -> ScreenTimeDay(date, seconds = 0, unlocks = 0, hasApps = false)
                    1 -> ScreenTimeDay(date, seconds = null, unlocks = null, hasApps = false)
                    else -> ScreenTimeDay(date, seconds = 60, unlocks = 1, hasApps = false)
                }
            }

            val buckets = screenTimeBuckets(days)
            assertEquals("$count dates should produce $count daily bars", count, buckets.size)
            assertEquals(days.map { it.date }, buckets.map { it.startDate })
            assertTrue(buckets.all { it.days.size == 1 && it.startDate == it.endDate && it.label.isNotBlank() })
            assertEquals(0, buckets.first().seconds)
            assertNull(buckets[1].seconds)
            assertEquals(days, buckets.flatMap { it.days })
        }
    }

    @Test fun `90 day timeline includes available today while summaries exclude it`() {
        val today = LocalDate.parse("2026-11-23")
        val days = (0 until 90).map { offset ->
            val date = today.minusDays((89 - offset).toLong()).toString()
            ScreenTimeDay(date, seconds = if (offset == 88) 120 else if (offset == 89) 60 else null,
                unlocks = if (offset == 89) 0 else null, hasApps = false)
        }
        val todayBucket = screenTimeBuckets(days, today = today.toString()).last()
        assertEquals(60, bucketObservedTotal(todayBucket.days) { it.seconds })
        assertNull(bucketCompletedTotal(todayBucket.days, today.toString()) { it.seconds })
        assertEquals(0, bucketObservedTotal(todayBucket.days) { it.unlocks })

        val missingToday = todayBucket.days.map { if (it.date == today.toString()) it.copy(seconds = null) else it }
        assertNull(bucketObservedTotal(missingToday) { it.seconds })
        assertNull(bucketObservedTotal(todayBucket.days.filterNot { it.date == today.toString() }) { it.seconds })
    }

    @Test fun `31 and 90 inclusive dates use calendar weeks with exact source days`() {
        listOf(31, 90).forEach { count ->
            val start = LocalDate.parse("2026-01-01")
            val days = (0 until count).map { offset ->
                val date = start.plusDays(offset.toLong()).toString()
                ScreenTimeDay(date, seconds = if (offset == 0) 0 else null, unlocks = if (offset == 0) 0 else null, hasApps = false)
            }
            val buckets = screenTimeBuckets(days)
            assertTrue("$count dates should be aggregated", buckets.size < count)
            assertEquals(days, buckets.flatMap { it.days })
            assertEquals(days.first().date, buckets.first().startDate)
            assertEquals(days.last().date, buckets.last().endDate)
            assertTrue(buckets.all { it.label.isNotBlank() })
            assertEquals(0, buckets.first().seconds)
            assertEquals(0, buckets.first().unlocks)
            assertNull(buckets.drop(1).firstOrNull()?.seconds)
        }
    }

    @Test fun `long ranges use calendar month buckets with exact source-day totals`() {
        val raw = (0..94).map { offset ->
            val date = LocalDate.parse("2026-01-20").plusDays(offset.toLong()).toString()
            ScreenTimeDay(date, seconds = if (offset == 0) 0 else null, unlocks = null, hasApps = false)
        }
        val buckets = screenTimeBuckets(raw)
        assertEquals(4, buckets.size)
        assertEquals(listOf("2026-01-20", "2026-02-01", "2026-03-01", "2026-04-01"), buckets.map { it.startDate })
        assertEquals(0, buckets.first().seconds)
        assertNull(buckets[1].seconds)
        assertEquals(raw, buckets.flatMap { it.days })
        assertTrue(buckets.all { it.label.isNotBlank() && it.endDate >= it.startDate })
    }
}
