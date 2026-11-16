package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import cz.digitalnivedomi.diarium.core.i18n.UiDates
import cz.digitalnivedomi.diarium.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.StatsRepository
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import cz.digitalnivedomi.diarium.core.stats.YearMonthGrid
import cz.digitalnivedomi.diarium.ui.i18n.translate
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.MOOD_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.moodChoiceText
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.checkin.components.testTagOrEmpty
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import cz.digitalnivedomi.diarium.ui.theme.moodColor
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 🗓️ Rok v pixelech — the web `YearInPixels.tsx` grid, ported to Compose.
 *
 * One card per month, one square per day, Monday-first columns ("Po" … "Ne"), and the
 * square's colour is the day's mood — the same palette the check-in form and the
 * dashboard use, so a mood looks the same wherever the app draws it. A day without an
 * answer stays a barely visible grey square: never a colour, never a zero.
 *
 * The layout comes from [StatsMath.yearGrid], so the month padding, the week rows and
 * the month names are all unit tested away from the UI.
 *
 * **One honest limit, stated on the card.** One statistics read covers the last
 * [StatsRepository.LOAD_DAYS] days, so the grid can only colour days inside that
 * window; an older day of the selected year stays empty even though the diary still
 * holds it. The web's `.limit(1000)` can paint a whole past year, the native read
 * cannot — so the card says so instead of pretending the day was never tracked.
 */
@Composable
fun YearInPixels(
    days: List<StatsDay>,
    year: Int,
    today: String,
    modifier: Modifier = Modifier,
    /** Receives every tapped ISO date, including unanswered dates absent from [days]. */
    onOpenDay: (String) -> Unit = {},
    /** Receives the calendar month for a long-pressed pixel. */
    onOpenHistoryMonth: (YearMonth) -> Unit = {},
) {
    val months = remember(days, year) { StatsMath.yearGrid(year, days) }
    val stats = remember(days, year) { StatsMath.yearStats(year = year, days = days) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
            SectionHeader("🗓️ Rok v pixelech")
            VSpace(6)
            Text(
                text = "Rok $year",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            VSpace(2)
            SectionHint("Každý čtvereček je jeden den. Barva odpovídá náladě, šedý čtvereček znamená den bez odpovědi.")
            SectionHint("Klepnutím otevřeš den; dlouhým stiskem přejdeš do historie daného měsíce.")
            VSpace(10)

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Průměrná nálada",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                    VSpace(2)
                    Text(
                        text = stats?.averageMood?.let { "${formatMood(it)} / 5" } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sledovaných dní",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                    VSpace(2)
                    Text(
                        text = (stats?.trackedDays ?: 0).toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )
                }
            }

            VSpace(10)
            SectionHint(
                "Zobrazuju posledních ${StatsRepository.LOAD_DAYS} dní, takže starší dny roku " +
                    "zůstávají prázdné, i když je máš v deníku zapsané.",
            )
        }

        months.forEach { month ->
            MonthCard(
                month = month,
                today = today,
                onOpenDay = onOpenDay,
                onOpenHistoryMonth = onOpenHistoryMonth,
            )
        }

        LegendCard()
    }
}

/** One month: its name, how much of it is tracked, and its weeks of squares. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MonthCard(
    month: YearMonthGrid,
    today: String,
    onOpenDay: (String) -> Unit,
    onOpenHistoryMonth: (YearMonth) -> Unit,
) {
    val haptics = rememberHaptics()
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = month.name,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${month.trackedDays}/${month.daysInMonth} sledovaných dní",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
        }

        VSpace(10)
        Row(modifier = Modifier.fillMaxWidth()) {
            StatsMath.WEEKDAY_LABELS.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        VSpace(6)
        month.weeks.forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    if (cell.day == 0) {
                        // Padding before the 1st (or after the last) — an empty slot,
                        // exactly the web's zero-mood placeholder cell.
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp))
                    } else {
                        val isToday = cell.date == today
                        val isFuture = CheckInDates.parse(cell.date)?.isAfter(CheckInDates.parse(today)) == true
                        val cellShape = RoundedCornerShape(4.dp)
                        // No per-cell animation state here on purpose: a year is ~370
                        // cells, so a layer per cell would cost more than it shows.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .testTagOrEmpty("year_pixel_${cell.date}")
                                .clip(cellShape)
                                .background(
                                    if (cell.mood > 0) {
                                        moodColor(cell.mood).copy(alpha = 0.85f)
                                    } else {
                                        Color.White.copy(alpha = 0.04f)
                                    },
                                )
                                .then(
                                    if (isToday) Modifier.border(1.5.dp, Indigo, cellShape) else Modifier,
                                )
                                .semantics { contentDescription = cellLabel(cell.date, cell.mood) }
                                .combinedClickable(
                                    enabled = !isFuture,
                                    onClick = {
                                        haptics.selection()
                                        onOpenDay(cell.date)
                                    },
                                    onLongClick = {
                                        haptics.selection()
                                        onOpenHistoryMonth(YearMonth.from(CheckInDates.parse(cell.date)!!))
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * "10. 9. 2026 — 🙂 Dobře", or the untracked / unanswered wording.
 *
 * The date is already formatted in the UI language and both the mood label and the
 * unanswered phrase are translated before they are joined to it, so an English card
 * never shows "10 Sep 2026 — 🙂 Dobře" or "10 Sep 2026 — bez odpovědi".
 */
internal fun cellLabel(date: String, mood: Int): String {
    val parsed = CheckInDates.parse(date)
    val formatted = parsed?.format(dayMonthYear) ?: date
    if (mood <= 0) return translate("$formatted — bez odpovědi")
    return translate("$formatted — ${moodChoiceText(mood)}")
}

/** Emoji + label for the legend, in the web's 5 → 1 order. */
@Composable
private fun LegendCard() {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Legenda")
        VSpace(10)
        Row(modifier = Modifier.fillMaxWidth()) {
            MOOD_CHOICES.forEach { choice ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(moodColor(choice.value)),
                    )
                    VSpace(6)
                    Text(text = choice.emoji, style = MaterialTheme.typography.labelMedium)
                    VSpace(2)
                    Text(
                        text = choice.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        VSpace(14)
        GlassDivider()
        VSpace(12)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.5.dp, Indigo, RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Dnes",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        VSpace(8)
        SectionHint("Bez barvy = den bez odpovědi (nálada 0). Takové dny se do průměrů nepočítají.")
    }
}

private val dayMonthYear: DateTimeFormatter
    get() = UiDates.numericDate()

/** The web prints `toFixed(1)`, so the year average keeps a dot rather than a Czech comma. */
private fun formatMood(value: Double): String = String.format(Locale.US, "%.1f", value)
