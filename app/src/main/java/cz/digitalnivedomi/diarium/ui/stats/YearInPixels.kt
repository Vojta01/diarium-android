package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.StatsRepository
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import cz.digitalnivedomi.diarium.core.stats.YearMonthGrid
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.MOOD_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.checkin.components.testTagOrEmpty
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import cz.digitalnivedomi.diarium.ui.theme.moodColor
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
fun YearInPixels(days: List<StatsDay>, year: Int, today: String, modifier: Modifier = Modifier) {
    val months = remember(days, year) { StatsMath.yearGrid(year, days) }
    val stats = remember(days, year) { StatsMath.yearStats(year = year, days = days) }
    var selected by remember(year) { mutableStateOf<String?>(null) }

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
                selected = selected,
                onSelect = { date -> selected = if (selected == date) null else date },
            )
        }

        LegendCard()
    }
}

/** One month: its name, how much of it is tracked, and its weeks of squares. */
@Composable
private fun MonthCard(
    month: YearMonthGrid,
    today: String,
    selected: String?,
    onSelect: (String) -> Unit,
) {
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
                        val isSelected = cell.date == selected
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .testTagOrEmpty("year_pixel_${cell.date}")
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (cell.mood > 0) {
                                        moodColor(cell.mood).copy(alpha = if (isSelected) 1f else 0.85f)
                                    } else {
                                        Color.White.copy(alpha = 0.04f)
                                    },
                                )
                                .then(
                                    if (isToday) {
                                        Modifier.border(1.5.dp, Indigo, RoundedCornerShape(3.dp))
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { onSelect(cell.date) },
                        )
                    }
                }
            }
        }

        selected?.let { date ->
            if (belongsTo(month, date)) {
                val cell = month.weeks.flatten().firstOrNull { it.date == date }
                if (cell != null) {
                    VSpace(10)
                    GlassDivider()
                    VSpace(10)
                    Text(
                        text = cellLabel(date, cell.mood),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (cell.mood > 0) TextPrimary else TextSecondary,
                    )
                }
            }
        }
    }
}

/** "10. 9. 2026 — 🙂 Dobře", or the untracked / unanswered wording. */
private fun cellLabel(date: String, mood: Int): String {
    val parsed = CheckInDates.parse(date)
    val formatted = parsed?.format(dayMonthYear) ?: date
    if (mood <= 0) return "$formatted — bez odpovědi"
    val choice = MOOD_CHOICES.firstOrNull { it.value == mood }
    val emoji = choice?.emoji ?: "⚪"
    val label = choice?.label ?: "Neznámá"
    return "$formatted — $emoji $label"
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

private val dayMonthYear = DateTimeFormatter.ofPattern("d. M. yyyy", Locale("cs", "CZ"))

private fun belongsTo(month: YearMonthGrid, date: String): Boolean =
    month.weeks.flatten().any { it.date == date }

/** The web prints `toFixed(1)`, so the year average keeps a dot rather than a Czech comma. */
private fun formatMood(value: Double): String = String.format(Locale.US, "%.1f", value)
