package cz.digitalnivedomi.diarium.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.digitalnivedomi.diarium.core.data.DashboardDay
import cz.digitalnivedomi.diarium.core.data.DashboardTrends
import cz.digitalnivedomi.diarium.core.data.MetricTrend
import cz.digitalnivedomi.diarium.core.data.TrendDirection
import cz.digitalnivedomi.diarium.core.data.isRecordedDay
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.components.ReadOnlyRow
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.theme.Gradients
import cz.digitalnivedomi.diarium.ui.theme.InkDeep
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import cz.digitalnivedomi.diarium.ui.theme.WarningAmber
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Test tag on the metric window, so a test can assert it opened and closed. */
const val DASHBOARD_METRIC_DIALOG_TAG = "dashboard_metric_dialog"

/**
 * Which of the dashboard's hero numbers a [DashboardMetricDialog] breaks down.
 *
 * The three differ only in *how* a day's value is read off [DashboardDay] and how it
 * is printed, so one dialog with one kind parameter replaces three near-identical
 * windows.
 */
enum class DashboardMetricKind { SCREEN_TIME, UNLOCKS, MOOD }

/**
 * The tone of a trend line: for usage (time on screen, unlocks) *less* is the good
 * news, for mood it is *more* — so the same arrow has to be coloured the other way
 * round on the mood card.
 */
enum class TrendTone { USAGE, MOOD }

/** "192 min" counts days that actually carry the metric, never the empty ones. */
internal fun averagePerDay(days: List<DashboardDay>, kind: DashboardMetricKind): Double? {
    val values = days.mapNotNull { metricDayValue(it, kind) }
    return if (values.isEmpty()) null else values.sum() / values.size
}

/** The week figure the hero card shows, as a number the trend can compare. */
internal fun metricWeekValue(days: List<DashboardDay>, kind: DashboardMetricKind): Double? =
    when (kind) {
        DashboardMetricKind.SCREEN_TIME -> DashboardTrends.weekMinutes(days)?.toDouble()
        DashboardMetricKind.UNLOCKS -> DashboardTrends.weekUnlocks(days)?.toDouble()
        DashboardMetricKind.MOOD -> DashboardTrends.weekMood(days)
    }

/**
 * This week against the seven days before it — computed with the exact same
 * aggregation the card's own number uses, so a caption can never contradict the
 * figure above it.
 */
internal fun metricTrend(
    week: List<DashboardDay>,
    previousWeek: List<DashboardDay>,
    kind: DashboardMetricKind,
): MetricTrend = when (kind) {
    DashboardMetricKind.SCREEN_TIME -> DashboardTrends.of(
        DashboardTrends.weekMinutes(week),
        DashboardTrends.weekMinutes(previousWeek),
    )
    DashboardMetricKind.UNLOCKS -> DashboardTrends.of(
        DashboardTrends.weekUnlocks(week),
        DashboardTrends.weekUnlocks(previousWeek),
    )
    DashboardMetricKind.MOOD -> DashboardTrends.ofAverage(
        DashboardTrends.weekMood(week),
        DashboardTrends.weekMood(previousWeek),
    )
}

/** One day's value for the metric, or null when that day carries no data at all. */
internal fun metricDayValue(day: DashboardDay, kind: DashboardMetricKind): Double? =
    when (kind) {
        DashboardMetricKind.SCREEN_TIME -> day.screenTimeSeconds?.let { it / 60.0 }
        DashboardMetricKind.UNLOCKS -> day.unlocks?.toDouble()
        // A mood of 0 is "not answered", exactly as the week average treats it.
        DashboardMetricKind.MOOD -> day.mood.takeIf { isRecordedDay(it) }?.toDouble()
    }

/** The one-line trend under a hero number, in the exact Czech wordings. */
internal fun trendCaption(trend: MetricTrend, tone: TrendTone): String? {
    // Nothing on the current side means there is no figure to talk about; the card
    // already says the data has not synced yet.
    if (trend.current == null) return null
    val percent = trend.percentChange
    return when (trend.direction) {
        TrendDirection.UNKNOWN -> "bez srovnání – chybí minulý týden"
        TrendDirection.FLAT -> "≈ stejně jako minulý týden"
        // Both sides known, but last week was zero: a percentage would be invented.
        TrendDirection.UP -> if (percent == null) {
            "nově – minulý týden bez dat"
        } else {
            "↑ o ${abs(percent)} % ${if (tone == TrendTone.MOOD) "vyšší" else "více"} než minulý týden"
        }
        TrendDirection.DOWN -> if (percent == null) {
            "nově – minulý týden bez dat"
        } else {
            "↓ o ${abs(percent)} % ${if (tone == TrendTone.MOOD) "nižší" else "méně"} než minulý týden"
        }
    }
}

/** The arrow's colour: growth is good news for mood and bad news for usage. */
internal fun trendColor(trend: MetricTrend, tone: TrendTone): Color = when (trend.direction) {
    TrendDirection.FLAT, TrendDirection.UNKNOWN -> TextSecondary
    TrendDirection.UP -> if (tone == TrendTone.MOOD) SuccessGreen else WarningAmber
    TrendDirection.DOWN -> if (tone == TrendTone.MOOD) WarningAmber else SuccessGreen
}

/** "😄 / 🙂 / 😐 / 🙁" for a 1–5 average, or null when there is no average. */
internal fun moodFace(value: Double?): String? = value?.let {
    when {
        it >= 4.5 -> "😄"
        it >= 3.5 -> "🙂"
        it >= 2.5 -> "😐"
        else -> "🙁"
    }
}

/** "výborná / dobrá / průměrná / slabá" for a 1–5 average. */
internal fun moodWord(value: Double?): String? = value?.let {
    when {
        it >= 4.5 -> "výborná"
        it >= 3.5 -> "dobrá"
        it >= 2.5 -> "průměrná"
        else -> "slabá"
    }
}

/** How a value reads on the cards and in the list: minutes, a count, or "3.8 / 5". */
internal fun formatMetricValue(value: Double?, kind: DashboardMetricKind): String = when {
    value == null -> "—"
    kind == DashboardMetricKind.SCREEN_TIME -> formatMetricMinutes(value.roundToInt())
    kind == DashboardMetricKind.UNLOCKS -> value.roundToInt().toString()
    else -> "${String.format(Locale.US, "%.1f", value)} / 5"
}

/**
 * `formatMinutes` without the "min" once there are hours: 192 -> "3 h 12",
 * 45 -> "45 min". Used for the "Ø … na den" caption, where the trailing unit
 * would just be noise next to the card's own "min".
 */
internal fun formatMetricMinutes(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "$hours h" else "$hours h $rest"
}

/** The trend row itself: a coloured arrow, then the caption in secondary text. */
@Composable
internal fun TrendLine(trend: MetricTrend, tone: TrendTone, modifier: Modifier = Modifier) {
    val caption = trendCaption(trend, tone) ?: return
    val arrow = caption.take(1)
    val colored = arrow == "↑" || arrow == "↓" || arrow == "≈"
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (colored) {
            Text(
                text = arrow,
                style = MaterialTheme.typography.labelSmall,
                color = trendColor(trend, tone),
            )
            Spacer(Modifier.width(Spacing.tiny / 2))
        }
        Text(
            text = if (colored) caption.drop(1).trimStart() else caption,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

/** Height of the bar track: the tallest day of the fourteen fills it. */
private val DIALOG_BAR_TRACK = 88.dp

/** "d. M." — the date half of a row label. */
private val dialogShortDate = DateTimeFormatter.ofPattern("d. M.", Locale("cs", "CZ"))

private fun dialogDateOf(date: String): String =
    CheckInDates.parse(date)?.format(dialogShortDate) ?: date

/** "pátek" — the weekday half of a row label. */
private fun weekdayNameOf(date: String): String = when (CheckInDates.parse(date)?.dayOfWeek) {
    DayOfWeek.MONDAY -> "pondělí"
    DayOfWeek.TUESDAY -> "úterý"
    DayOfWeek.WEDNESDAY -> "středa"
    DayOfWeek.THURSDAY -> "čtvrtek"
    DayOfWeek.FRIDAY -> "pátek"
    DayOfWeek.SATURDAY -> "sobota"
    DayOfWeek.SUNDAY -> "neděle"
    null -> ""
}

/** "pátek, 12. 9." */
private fun dialogRowLabel(date: String): String {
    val weekday = weekdayNameOf(date)
    val day = dialogDateOf(date)
    return if (weekday.isEmpty()) day else "$weekday, $day"
}

/** The two-letter weekday under a bar (the same abbreviations the week strip uses). */
private fun dialogBarLabel(date: String, today: String): String {
    if (date == today) return "Dnes"
    return when (CheckInDates.parse(date)?.dayOfWeek) {
        DayOfWeek.MONDAY -> "Po"
        DayOfWeek.TUESDAY -> "Út"
        DayOfWeek.WEDNESDAY -> "St"
        DayOfWeek.THURSDAY -> "Čt"
        DayOfWeek.FRIDAY -> "Pá"
        DayOfWeek.SATURDAY -> "So"
        DayOfWeek.SUNDAY -> "Ne"
        null -> ""
    }
}

/**
 * The fourteen-day breakdown behind one of the dashboard's hero numbers.
 *
 * Opened by tapping a hero card (see `HeroStats`). It shows the same figure the card
 * shows, this week against last week, then every one of the fourteen days — the bars
 * for the older week drawn dimmer so "which seven are this week" needs no legend.
 *
 * A tap on a day in the list closes this window and hands the date back through
 * [onOpenDay], so the caller can open the full day ([cz.digitalnivedomi.diarium.ui.components.DayDetailDialog])
 * for a day the owner noticed here. Like that dialog, this one never fetches:
 * everything it draws comes out of the two week lists it is given.
 */
@Composable
fun DashboardMetricDialog(
    title: String,
    kind: DashboardMetricKind,
    accent: Color,
    week: List<DashboardDay>,
    previousWeek: List<DashboardDay>,
    today: String,
    onDismiss: () -> Unit,
    onOpenDay: (String) -> Unit,
) {
    val tone = if (kind == DashboardMetricKind.MOOD) TrendTone.MOOD else TrendTone.USAGE
    val trend = metricTrend(week, previousWeek, kind)
    val days = previousWeek + week
    val startOfThisWeek = previousWeek.size
    val maxValue = days.mapNotNull { metricDayValue(it, kind) }.maxOrNull() ?: 0.0

    val weekValue = metricWeekValue(week, kind)
    val previousValue = metricWeekValue(previousWeek, kind)
    val perDay = averagePerDay(week, kind)
    val points = week.mapNotNull { day -> metricDayValue(day, kind)?.let { day to it } }
    val best = points.maxByOrNull { it.second }
    val worst = points.minByOrNull { it.second }
    val percent = trend.percentChange

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter)
                .testTag(DASHBOARD_METRIC_DIALOG_TAG)
                .clip(RoundedCornerShape(24.dp))
                .background(InkDeep.copy(alpha = 0.98f))
                .border(1.dp, Outline, RoundedCornerShape(24.dp))
                .padding(top = Spacing.block, bottom = Spacing.gutter, start = Spacing.gutter, end = Spacing.gutter),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Posledních 14 dní",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .padding(horizontal = Spacing.small, vertical = Spacing.tiny)
                        .clickable { onDismiss() },
                ) {
                    Text(text = "Zavřít", color = TextSecondary)
                }
            }
            VSpace(10)
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                TrendLine(trend = trend, tone = tone)
                VSpace(Spacing.block)

                // The fourteen bars, oldest first, so the dimmer half always sits on
                // the left of the brighter one.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    days.forEachIndexed { index, day ->
                        val value = metricDayValue(day, kind)
                        val isThisWeek = index >= startOfThisWeek
                        val fraction = if (value == null || maxValue <= 0.0) {
                            0.05f
                        } else {
                            (value / maxValue).toFloat().coerceIn(0.06f, 1f)
                        }
                        val fill = if (isThisWeek) {
                            Gradients.fill(listOf(accent, accent.copy(alpha = 0.55f)), vertical = true)
                        } else {
                            Gradients.fill(
                                listOf(accent.copy(alpha = 0.35f), accent.copy(alpha = 0.12f)),
                                vertical = true,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(DIALOG_BAR_TRACK),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(DIALOG_BAR_TRACK * fraction)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(fill),
                                )
                            }
                            VSpace(Spacing.tiny)
                            Text(
                                text = dialogBarLabel(day.date, today),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isThisWeek) TextPrimary else TextTertiary,
                            )
                        }
                    }
                }

                VSpace(Spacing.section)
                GlassDivider()
                VSpace(Spacing.small)
                ReadOnlyRow(label = "Tento týden", value = formatMetricValue(weekValue, kind))
                ReadOnlyRow(label = "Minulý týden", value = formatMetricValue(previousValue, kind))
                ReadOnlyRow(
                    label = "Změna",
                    value = when {
                        trend.current == null || percent == null -> "—"
                        percent == 0 -> "0 %"
                        percent > 0 -> "+$percent %"
                        else -> "$percent %"
                    },
                )
                ReadOnlyRow(label = "Ø na den", value = formatMetricValue(perDay, kind))
                ReadOnlyRow(
                    label = "Nejvyšší den",
                    value = best?.let { "${dialogRowLabel(it.first.date)} – ${formatMetricValue(it.second, kind)}" } ?: "—",
                )
                ReadOnlyRow(
                    label = "Nejnižší den",
                    value = worst?.let { "${dialogRowLabel(it.first.date)} – ${formatMetricValue(it.second, kind)}" } ?: "—",
                )

                VSpace(Spacing.section)
                GlassDivider()
                VSpace(Spacing.small)
                SectionHeader("Po dnech")
                VSpace(Spacing.tight)

                // Newest first: the days a tap is most likely to want are on top.
                days.reversed().forEach { day ->
                    val value = metricDayValue(day, kind)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                onDismiss()
                                onOpenDay(day.date)
                            }
                            .padding(horizontal = Spacing.tiny, vertical = Spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = dialogRowLabel(day.date),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary,
                            )
                            if (kind == DashboardMetricKind.MOOD) {
                                moodWord(value)?.let { word ->
                                    Text(
                                        text = word,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary,
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (kind == DashboardMetricKind.MOOD && value != null) {
                                "${moodFace(value)} ${formatMetricValue(value, kind)}"
                            } else {
                                formatMetricValue(value, kind)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (value == null) TextTertiary else TextSecondary,
                        )
                    }
                }
            }
        }
    }
}
