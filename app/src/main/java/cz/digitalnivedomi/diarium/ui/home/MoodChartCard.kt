package cz.digitalnivedomi.diarium.ui.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cz.digitalnivedomi.diarium.core.data.DashboardDay
import cz.digitalnivedomi.diarium.core.data.MoodDiscState
import cz.digitalnivedomi.diarium.core.data.moodDiscState
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.MOOD_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.MoodTrendCaption
import cz.digitalnivedomi.diarium.ui.components.MoodTrendChart
import cz.digitalnivedomi.diarium.ui.components.MoodTrendSlot
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Czech numeric date, used for a day's title in the tap detail. */
private val shortDate = DateTimeFormatter.ofPattern("d. M. yyyy", Locale("cs", "CZ"))

/** "5. 9. 2026", or the raw value if it is not a date — the dashboard's own copy. */
private fun shortDateOf(date: String): String = CheckInDates.parse(date)?.format(shortDate) ?: date

/** The dashboard's own mood helpers, copied here the way StatsScreen copies them. */
private fun moodEmojiOf(mood: Int): String =
    MOOD_CHOICES.firstOrNull { it.value == mood }?.emoji ?: "⚪"

/** "🙂 Dobře", or the dashboard's "Bez odpovědi" for a day without a mood. */
private fun moodTextOf(day: DashboardDay): String {
    val label = MOOD_CHOICES.firstOrNull { it.value == day.mood }?.label
    return if (moodDiscState(day.mood) == MoodDiscState.Mood && label != null) {
        "${moodEmojiOf(day.mood)} $label"
    } else {
        "Bez odpovědi"
    }
}

/**
 * 📈 The dashboard's interactive mood chart: the same shared chart the statistics
 * screen draws, fed 30 days of dashboard data with a 7/30-day switch (owner request
 * 2026-09-24; Variant A — under the week strip, same style as Statistics).
 *
 * The columns come from the repository's own [month] window, so tapping one can open
 * the whole day through the dashboard's existing day-detail dialog; the week strip
 * above stays exactly as it was.
 */
@Composable
internal fun MoodChartCard(
    month: List<DashboardDay>,
    onOpenDay: (String) -> Unit,
) {
    var range by rememberSaveable { mutableStateOf(ChartRange.WEEK) }
    val days = if (range == ChartRange.WEEK) month.takeLast(7) else month

    val slots: List<MoodTrendSlot> = days.map { day ->
        MoodTrendSlot(
            key = day.date,
            label = shortDateOf(day.date),
            title = shortDateOf(day.date),
            mood = day.mood,
            // The week strip's own predicate: a mood is what makes a day written.
            written = moodDiscState(day.mood) == MoodDiscState.Mood,
            moodText = moodTextOf(day),
            detailRows = emptyList(),
        )
    }

    // The same 7-day moving average the statistics screen draws — seven days in both
    // windows, because that is what Statistics does.
    val average: List<Double?> = StatsMath.movingAverage(
        days.map { day -> StatsDay(date = day.date, mood = day.mood) },
    )

    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader(
            "📈 Vývoj nálady",
            trailing = {
                RangeToggle(range = range, onSelect = { selected -> range = selected })
            },
        )
        VSpace(4)
        SectionHint("Klepni na den pro detail.")
        VSpace(14)
        MoodTrendChart(
            slots = slots,
            average = average,
            averageLabel = "7denní klouzavý průměr",
            averageDetailLabel = "7denní průměr",
            caption = MoodTrendCaption.EdgeDates,
            onOpenDay = onOpenDay,
        )
    }
}
