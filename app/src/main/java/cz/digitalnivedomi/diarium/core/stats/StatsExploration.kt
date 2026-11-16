package cz.digitalnivedomi.diarium.core.stats

import java.time.DayOfWeek
import java.time.LocalDate

/** Inclusive calendar range; every interactive range is limited to the one-read 400-day cache. */
data class StatsDateRange private constructor(val startInclusive: LocalDate, val endInclusive: LocalDate) {
    val dayCount: Int get() = (endInclusive.toEpochDay() - startInclusive.toEpochDay() + 1).toInt()
    fun previous(): StatsDateRange = StatsDateRange(startInclusive.minusDays(dayCount.toLong()), startInclusive.minusDays(1))

    companion object {
        const val MAX_DAYS = 400
        fun of(endInclusive: LocalDate, days: Int): StatsDateRange {
            require(days in 1..MAX_DAYS) { "Statistics ranges must be 1..$MAX_DAYS days" }
            return StatsDateRange(endInclusive.minusDays((days - 1).toLong()), endInclusive)
        }
        fun between(startInclusive: LocalDate, endInclusive: LocalDate): StatsDateRange {
            require(startInclusive <= endInclusive)
            return StatsDateRange(startInclusive, endInclusive)
        }
        fun tryOf(endInclusive: LocalDate, days: Int): StatsDateRange? =
            if (days in 1..MAX_DAYS) of(endInclusive, days) else null
    }
}

data class WindowCoverage(val reportedDays: Int, val windowDays: Int) {
    val missingDays: Int get() = windowDays - reportedDays
}
data class CountDistribution(val counts: Map<Int, Int>)
data class MoodWeekday(val averageMood: Double?, val answeredDays: Int)
data class HabitDistribution(val yes: Int, val no: Int, val unanswered: Int)
data class DigitalTotals(val totalSeconds: Int, val reportedScreenTimeDays: Int, val reportedUnlockDays: Int)
data class DailyApps(val date: LocalDate, val apps: Map<String, Int>)
data class Association(val nWith: Int, val nWithout: Int, val correlation: Double?, val status: CorrelationStatus)
enum class CorrelationStatus { DESCRIPTIVE_ONLY, NOT_ENOUGH_DATA }

/** Pure, cache-only functions used by future interactive statistics. No repository or IO calls. */
object StatsExploration {
    private fun inRange(days: List<StatsDay>, range: StatsDateRange) = days.filter {
        val date = runCatching { LocalDate.parse(it.date) }.getOrNull()
        date != null && date >= range.startInclusive && date <= range.endInclusive
    }

    /** Coverage of dates with a row, not answered moods; denominator includes missing dates. */
    fun coverage(days: List<StatsDay>, range: StatsDateRange): WindowCoverage {
        val dates = inRange(days, range).map { it.date }.toSet()
        return WindowCoverage(dates.size.coerceAtMost(range.dayCount), range.dayCount)
    }
    fun screenTimeCoverage(days: List<StatsDay>, range: StatsDateRange): WindowCoverage =
        WindowCoverage(inRange(days, range).map { it.date to it.screenTimeSeconds }.distinctBy { it.first }.count { it.second != null }, range.dayCount)

    /** Mood keys 0..5 where 0 explicitly means no valid 1..5 mood answer. */
    fun moodDistribution(days: List<StatsDay>): Map<Int, Int> = (0..5).associateWith { value ->
        days.count { if (value == 0) !it.answered else it.mood == value }
    }
    fun weekdayMood(days: List<StatsDay>): Map<DayOfWeek, MoodWeekday> = DayOfWeek.values().associateWith { dow ->
        val moods = days.filter { runCatching { LocalDate.parse(it.date).dayOfWeek == dow }.getOrDefault(false) && it.answered }.map { it.mood }
        MoodWeekday(moods.takeIf { it.isNotEmpty() }?.average(), moods.size)
    }
    fun habitDistribution(days: List<StatsDay>, habitId: String): HabitDistribution {
        val values = days.mapNotNull { it.habits[habitId] }
        val yes = values.count { it }; val no = values.size - yes
        return HabitDistribution(yes, no, days.size - yes - no)
    }
    fun sleepDistribution(days: List<StatsDay>): Map<Int, Int> = days.filter { it.sleepQuality > 0 }.groupingBy { it.sleepQuality }.eachCount().toSortedMap()
    fun stressDistribution(days: List<StatsDay>): Map<Int, Int> = days.filter { it.stress > 0 }.groupingBy { it.stress }.eachCount().toSortedMap()
    fun scaleDistribution(days: List<StatsDay>, scaleId: String): Map<Int, Int> = days.mapNotNull { it.scaleValues[scaleId]?.takeIf { value -> value > 0 } }.groupingBy { it }.eachCount().toSortedMap()

    /** Descriptive point-biserial association; requires >=2 answered days in each group. No causal interpretation. */
    fun habitMoodAssociation(days: List<StatsDay>, habitId: String): Association {
        val eligible = days.filter { it.answered && habitId in it.habits }
        val yes = eligible.filter { it.habits[habitId] == true }.map { it.mood.toDouble() }
        val no = eligible.filter { it.habits[habitId] == false }.map { it.mood.toDouble() }
        if (yes.size < 2 || no.size < 2) return Association(yes.size, no.size, null, CorrelationStatus.NOT_ENOUGH_DATA)
        val all = yes + no
        val mean = all.average()
        val variance = all.sumOf { (it - mean) * (it - mean) } / all.size
        val p = yes.size.toDouble() / all.size; val q = no.size.toDouble() / all.size
        val coefficient = if (variance == 0.0) null else (yes.average() - no.average()) * kotlin.math.sqrt(p * q) / kotlin.math.sqrt(variance)
        return Association(yes.size, no.size, coefficient, CorrelationStatus.DESCRIPTIVE_ONLY)
    }

    /** Missing synchronization is excluded; a synced zero remains an actual zero. */
    fun digitalTotals(days: List<StatsDay>, range: StatsDateRange): DigitalTotals {
        val selected = inRange(days, range)
        return DigitalTotals(selected.mapNotNull { it.screenTimeSeconds }.sum(), selected.count { it.screenTimeSeconds != null }, selected.count { it.unlocks != null })
    }
    /** App usage in seconds for each calendar day; dates with no sync have empty maps. */
    fun dailyApps(days: List<StatsDay>, range: StatsDateRange): List<DailyApps> {
        val selected = inRange(days, range).associateBy { it.date }
        return (0 until range.dayCount).map { offset ->
            val date = range.startInclusive.plusDays(offset.toLong())
            val day = selected[date.toString()]
            val apps = day?.takeIf { it.screenTimeSeconds != null }?.topApps.orEmpty().filter { it.app.isNotBlank() }.groupingBy { it.app }.fold(0) { total, app -> total + kotlin.math.round(app.minutes * 60).toInt() }
            DailyApps(date, apps)
        }
    }
}
