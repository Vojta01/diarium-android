package cz.digitalnivedomi.diarium.core.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTimeBucketsTest {
    @Test fun inclusiveRangesClipCalendarWeeksMonthsAndKeepLeapDay() {
        val range = DateRange(LocalDate.parse("2024-02-27"), LocalDate.parse("2024-03-05"))
        val buckets = StatsTimeBuckets.aggregate(range, BucketCadence.WEEKLY,
            listOf(DatedValue(LocalDate.parse("2024-02-29"), 4.0)))
        assertEquals(2, buckets.size)
        assertEquals(LocalDate.parse("2024-02-27"), buckets[0].start)
        assertEquals(LocalDate.parse("2024-03-03"), buckets[0].endInclusive)
        assertEquals(6, buckets[0].expectedDays)
        assertEquals(2, buckets[1].expectedDays)
        assertEquals(1, buckets[0].observedDays)
        assertEquals(4.0, buckets[0].mean!!, 0.0)
        assertNull(buckets[1].mean)
    }

    @Test fun zeroIsObservedAndMissingIsNotAndDailyBoundariesAreInclusive() {
        val range = DateRange(LocalDate.parse("2023-12-31"), LocalDate.parse("2024-01-02"))
        val buckets = StatsTimeBuckets.aggregate(range, BucketCadence.DAILY, listOf(
            DatedValue(range.start, null), DatedValue(range.start.plusDays(1), 0.0),
            DatedValue(range.endInclusive, 8.0), DatedValue(range.endInclusive.plusDays(1), 100.0)))
        assertEquals(listOf(1, 1, 1), buckets.map { it.expectedDays })
        assertEquals(0, buckets[0].observedDays)
        assertNull(buckets[0].mean)
        assertEquals(1, buckets[1].observedDays)
        assertEquals(0.0, buckets[1].mean!!, 0.0)
        assertEquals(8.0, buckets[2].mean!!, 0.0)
    }

    @Test fun cadenceThresholdsAreExplicitAndBoundariesSelectionIsStable() {
        assertEquals(BucketCadence.DAILY, StatsTimeBuckets.cadenceFor(7))
        assertEquals(BucketCadence.DAILY, StatsTimeBuckets.cadenceFor(30))
        assertEquals(BucketCadence.WEEKLY, StatsTimeBuckets.cadenceFor(31))
        assertEquals(BucketCadence.WEEKLY, StatsTimeBuckets.cadenceFor(90))
        assertEquals(BucketCadence.MONTHLY, StatsTimeBuckets.cadenceFor(91))
        val range = DateRange(LocalDate.parse("2024-01-01"), LocalDate.parse("2024-02-01"))
        val buckets = StatsTimeBuckets.aggregate(range, BucketCadence.MONTHLY, emptyList())
        assertEquals(LocalDate.parse("2024-01-01"), buckets.first().start)
        assertEquals(LocalDate.parse("2024-01-31"), buckets.first().endInclusive)
        assertEquals(LocalDate.parse("2024-02-01"), buckets.last().start)
        assertEquals(1, StatsTimeBuckets.bucketIndexForDate(buckets, LocalDate.parse("2024-02-01")))
        assertEquals(-1, StatsTimeBuckets.bucketIndexForDate(buckets, LocalDate.parse("2024-02-02")))
    }

    @Test fun activityRankingUsesValidMoodControlGroupsAndExplicitInsufficientStatus() {
        val days = (1..12).map { i ->
            ActivityMoodDay(LocalDate.of(2024, 1, i), if (i % 2 == 0) 4.0 else 2.0,
                if (i <= 6) setOf("walk") else emptySet())
        } + listOf(ActivityMoodDay(LocalDate.of(2024, 1, 20), null, setOf("walk")))
        val result = StatsTimeBuckets.rankActivityMoodAssociations(days)
        val walk = result.single()
        assertEquals(6, walk.withCount)
        assertEquals(6, walk.withoutCount)
        assertEquals(3.0, walk.withMean!!, 0.0)
        assertEquals(3.0, walk.withoutMean!!, 0.0)
        assertEquals(0.0, walk.meanDifference!!, 0.0)
        assertTrue(walk.status is AssociationStatus.Sufficient)
        val small = StatsTimeBuckets.rankActivityMoodAssociations(days.take(8)).single()
        assertTrue(small.status is AssociationStatus.Insufficient)
        assertEquals(6, small.withCount)
        assertEquals(2, small.withoutCount)
        assertFalse("unrecorded mood participates", small.withCount > 6)
    }
}
