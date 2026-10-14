package cz.digitalnivedomi.diarium.core.data

import java.time.LocalDate

/**
 * How far back a memory reaches — the three lanes of the dashboard's
 * "Tenhle den z historie" card.
 *
 * The declaration order is the ladder the picker walks: a year-old day is the
 * rarest, most surprising find; six months is the next best; a month ago is the
 * everyday fallback. [label] is the Czech caption the card prints next to the
 * memory's date.
 */
enum class MemoryHorizon(val label: String) {
    ONE_YEAR("Před rokem"),
    SIX_MONTHS("Před půl rokem"),
    ONE_MONTH("Před měsícem"),
}

/** One past day worth re-reading, with the lane it was found in. */
data class OnThisDayMemory(val date: String, val horizon: MemoryHorizon, val entry: DiaryEntry)

/**
 * The candidate dates for [today], in priority order: one year, six months and
 * one month back.
 *
 * All arithmetic goes through [LocalDate] so month-end clamping is the platform's
 * job: a 2028-02-29 "today" lands on 2027-02-28 a year back, and a 2026-03-31
 * "today" lands on 2026-02-28 a month back — never a rollover into the next
 * month, which is what a hand-rolled calendar would produce.
 *
 * [today] must be an ISO `yyyy-MM-dd` date. The app always passes one, so the
 * parse is strict on purpose — the same contract [DashboardRepository.derive]
 * takes, and a malformed value should fail loudly rather than silently hide the
 * card.
 */
fun targetMemoryDates(today: String): List<Pair<MemoryHorizon, String>> {
    val date = LocalDate.parse(today)
    return listOf(
        MemoryHorizon.ONE_YEAR to date.minusYears(1).toString(),
        MemoryHorizon.SIX_MONTHS to date.minusMonths(6).toString(),
        MemoryHorizon.ONE_MONTH to date.minusMonths(1).toString(),
    )
}

/**
 * Picks the most interesting memory for the dashboard: the first horizon (in
 * priority order) whose date holds a qualifying entry.
 *
 * A day qualifies only when it is a *recorded* day ([isRecordedDay]) that also
 * carries something worth re-reading — a non-blank note, at least one non-blank
 * gratitude line, or a photo. A mood-only day is deliberately not a memory: the
 * mood alone says how the day felt, not what happened, and re-showing a bare
 * number is not worth the card. Days the phone sync wrote without the owner
 * journaling them fail the first gate for the same reason they fail the streak
 * (see [isRecordedDay]).
 *
 * Returns null when no candidate date qualifies, so the card can hide instead of
 * rendering an empty memory.
 *
 * [today] must be an ISO `yyyy-MM-dd` date (see [targetMemoryDates]).
 */
fun pickBestMemory(today: String, byDate: Map<String, DiaryEntry>): OnThisDayMemory? {
    for ((horizon, date) in targetMemoryDates(today)) {
        val entry = byDate[date] ?: continue
        if (isMemoryWorthy(entry)) return OnThisDayMemory(date, horizon, entry)
    }
    return null
}

/**
 * Is this record something worth showing again?
 *
 * Two gates, both necessary: the owner actually journaled the day
 * ([isRecordedDay] — a row the phone sync wrote alone is not a record), and the
 * record holds content the card can show. Mood-only days pass the first gate but
 * not the second.
 */
private fun isMemoryWorthy(entry: DiaryEntry): Boolean =
    isRecordedDay(entry.mood) &&
        (entry.note.isNotBlank() ||
            entry.filteredGratitude().isNotEmpty() ||
            !entry.photoPath.isNullOrBlank())
