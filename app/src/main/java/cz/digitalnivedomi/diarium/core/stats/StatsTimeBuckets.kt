package cz.digitalnivedomi.diarium.core.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Inclusive calendar interval. Reversed ranges are rejected rather than silently swapped. */
data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    init { require(!endInclusive.isBefore(start)) { "Date range end must be on or after start" } }
    val dayCount: Int get() = (endInclusive.toEpochDay() - start.toEpochDay() + 1).toInt()
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)
}

enum class BucketCadence { DAILY, WEEKLY, MONTHLY }

data class DatedValue(val date: LocalDate, val value: Double?)

data class TimeBucket(
    /** Bucket's actual calendar boundary, clipped to the requested inclusive range. */
    val start: LocalDate,
    val endInclusive: LocalDate,
    val expectedDays: Int,
    /** Distinct days with at least one valid numeric observation. */
    val observedDays: Int,
    /** Number of valid numeric observations used in [mean]. */
    val observationCount: Int,
    /** Mean of non-null numeric observations only; null means no synced observations. */
    val mean: Double?,
) {
    val coverageDays: Int get() = observedDays
}

/**
 * Calendar-aware, pure aggregations. The default display policy is daily through 30
 * inclusive days, weekly for 31–90 days, then monthly for 91+ days. This keeps 7/30
 * ranges precise, 90-day charts readable, and yearly/long custom charts compact.
 */
object StatsTimeBuckets {
    fun cadenceFor(inclusiveDayCount: Int): BucketCadence {
        require(inclusiveDayCount > 0) { "Range must contain at least one day" }
        return when {
            inclusiveDayCount <= 30 -> BucketCadence.DAILY
            inclusiveDayCount <= 90 -> BucketCadence.WEEKLY
            else -> BucketCadence.MONTHLY
        }
    }

    /** Build aligned calendar buckets, clipped at both inclusive range boundaries. */
    fun aggregate(range: DateRange, cadence: BucketCadence, observations: List<DatedValue>): List<TimeBucket> {
        val groups = mutableListOf<Pair<LocalDate, LocalDate>>()
        var cursor = range.start
        while (!cursor.isAfter(range.endInclusive)) {
            val naturalStart: LocalDate
            val naturalEnd: LocalDate
            when (cadence) {
                BucketCadence.DAILY -> { naturalStart = cursor; naturalEnd = cursor }
                BucketCadence.WEEKLY -> {
                    naturalStart = cursor.with(DayOfWeek.MONDAY)
                    naturalEnd = naturalStart.plusDays(6)
                }
                BucketCadence.MONTHLY -> {
                    val month = YearMonth.from(cursor)
                    naturalStart = month.atDay(1)
                    naturalEnd = month.atEndOfMonth()
                }
            }
            val start = maxOf(range.start, naturalStart)
            val end = minOf(range.endInclusive, naturalEnd)
            groups += start to end
            cursor = end.plusDays(1)
        }
        return groups.map { (start, end) ->
            val values = observations.asSequence().filter { it.date >= start && it.date <= end }
                .mapNotNull { it.value?.takeIf(Double::isFinite) }.toList()
            val coveredDates = observations.asSequence()
                .filter { it.date >= start && it.date <= end && it.value?.isFinite() == true }
                .map { it.date }.toSet()
            TimeBucket(start, end, (end.toEpochDay() - start.toEpochDay() + 1).toInt(),
                coveredDates.size, values.size, values.takeIf { it.isNotEmpty() }?.average())
        }
    }

    /** Return the containing bucket index, or -1 when [date] is outside all buckets. */
    fun bucketIndexForDate(buckets: List<TimeBucket>, date: LocalDate): Int =
        buckets.indexOfFirst { date >= it.start && date <= it.endInclusive }

    /** Rank observed activity groups against days without that activity (descriptive only). */
    fun rankActivityMoodAssociations(
        days: List<ActivityMoodDay>,
        minimumPerGroup: Int = 5,
    ): List<ActivityMoodAssociation> {
        require(minimumPerGroup > 0) { "Minimum sample size must be positive" }
        val valid = days.filter { it.mood?.isFinite() == true }
        val names = valid.flatMap { it.activities }.filter { it.isNotBlank() }.toSortedSet()
        return names.map { name ->
            val with = valid.filter { name in it.activities }.mapNotNull { it.mood }
            val without = valid.filter { name !in it.activities }.mapNotNull { it.mood }
            val meanWith = with.takeIf { it.isNotEmpty() }?.average()
            val meanWithout = without.takeIf { it.isNotEmpty() }?.average()
            val difference = if (with.size >= minimumPerGroup && without.size >= minimumPerGroup)
                meanWith!! - meanWithout!! else null
            ActivityMoodAssociation(name, with.size, without.size, meanWith, meanWithout, difference,
                if (difference == null) AssociationStatus.Insufficient(minimumPerGroup) else AssociationStatus.Sufficient)
        }.sortedWith(compareBy<ActivityMoodAssociation> { it.meanDifference == null }
            .thenByDescending { it.meanDifference ?: Double.NEGATIVE_INFINITY }
            .thenByDescending { it.withCount }.thenBy { it.activity })
    }
}

data class ActivityMoodDay(val date: LocalDate, val mood: Double?, val activities: Set<String>)

sealed interface AssociationStatus {
    data object Sufficient : AssociationStatus
    data class Insufficient(val requiredPerGroup: Int) : AssociationStatus
}

data class ActivityMoodAssociation(
    val activity: String,
    val withCount: Int,
    val withoutCount: Int,
    val withMean: Double?,
    val withoutMean: Double?,
    /** Mean(with) - mean(without); null unless both control groups meet minimum size. */
    val meanDifference: Double?,
    val status: AssociationStatus,
) {
    /** Explicit wording for consumers; this is an association, not a causal estimate. */
    val interpretation: String get() = "Descriptive mood association; not causal"
}
