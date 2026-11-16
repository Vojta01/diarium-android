package cz.digitalnivedomi.diarium.ui.stats

import cz.digitalnivedomi.diarium.core.stats.ScreenTimeDay
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Inclusive, chronological screen-time range. Invalid or >400-day ranges yield no data. */
internal object ScreenTimeDateRange {
    fun days(entries: List<StatsDay>, startDate: String, endDate: String): List<ScreenTimeDay> {
        val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return emptyList()
        val end = runCatching { LocalDate.parse(endDate) }.getOrNull() ?: return emptyList()
        val count = ChronoUnit.DAYS.between(start, end) + 1
        if (count !in 1..MAX_DAYS.toLong()) return emptyList()
        val byDate = entries.associateBy { it.date }
        return (0 until count).map { offset ->
            val date = start.plusDays(offset).toString()
            val entry = byDate[date]
            ScreenTimeDay(date, entry?.screenTimeSeconds, entry?.unlocks, entry?.topApps?.isNotEmpty() == true)
        }
    }

    const val MAX_DAYS = 400
}
