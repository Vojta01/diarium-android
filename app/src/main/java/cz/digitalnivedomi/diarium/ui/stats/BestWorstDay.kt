package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.stats.MoodPoint
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.MOOD_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.components.formatMinutes
import cz.digitalnivedomi.diarium.ui.checkin.components.testTagOrEmpty
import cz.digitalnivedomi.diarium.ui.components.DayDetailDialog
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.StaggeredItem
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import cz.digitalnivedomi.diarium.ui.theme.WarningAmber
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ▲ Nejlepší den / ▼ Nejhorší den — the window's two mood extremes as one premium
 * pair of glass cards, each of them tappable through to the whole day.
 *
 * The two points come straight from [StatsMath.bestDay] / [StatsMath.worstDay] over
 * the same window the rest of the screen feeds its charts (`data.forRange(range)`),
 * so this pair can never disagree with the bars above it. The surrounding numbers are
 * the day's own row ([StatsDay]) — screen time, unlocks and how many activities — and
 * the full record behind the tap is [StatsData.entryByDate]'s [DiaryEntry], which the
 * screen already holds: opening a day costs no request and works offline.
 *
 * Nothing is drawn when the window has no answered mood: there is nothing to compare,
 * and an empty pair of cards would read as "no data" instead of "not answered yet".
 */

/**
 * Stagger ladder of this block: header, then the two cards. The indices stay below
 * [cz.digitalnivedomi.diarium.ui.components.Entrance]'s cap so the three steps are
 * actually visible — the block is the last of the mood ladder, so 4/5/6 lands right
 * after the charts above it.
 */
private const val HEADER_INDEX = 4
private const val BEST_INDEX = 5
private const val WORST_INDEX = 6

@Composable
internal fun BestWorstDayBlock(
    days: List<StatsDay>,
    entryByDate: Map<String, DiaryEntry> = emptyMap(),
) {
    val best = StatsMath.bestDay(days) ?: return
    val worst = StatsMath.worstDay(days) ?: best
    val byDate = remember(days) { days.associateBy { it.date } }
    var detailDate by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        StaggeredItem(HEADER_INDEX) { SectionHeader("Nejlepší a nejhorší den") }
        VSpace(Spacing.block)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.block),
        ) {
            // The stagger lives inside the pair, so the two extremes arrive one after
            // the other instead of both at the block's own delay. [StaggeredItem] fills
            // the width it is given, hence the weighted box around each one.
            Box(modifier = Modifier.weight(1f)) {
                StaggeredItem(BEST_INDEX) {
                    BestWorstDayCard(
                        title = "Nejlepší den",
                        arrow = "▲",
                        point = best,
                        day = byDate[best.date],
                        accent = SuccessGreen,
                        testTag = "stats_best_day_card",
                        onOpen = { detailDate = best.date },
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                StaggeredItem(WORST_INDEX) {
                    BestWorstDayCard(
                        title = "Nejhorší den",
                        arrow = "▼",
                        point = worst,
                        day = byDate[worst.date],
                        accent = WarningAmber,
                        testTag = "stats_worst_day_card",
                        onOpen = { detailDate = worst.date },
                    )
                }
            }
        }
    }

    detailDate?.let { date ->
        DayDetailDialog(
            date = date,
            entry = entryByDate[date],
            onDismiss = { detailDate = null },
            // The statistics tab reaches the day itself, not the check-in form — the
            // app shell owns that navigation, so the window only closes.
            onOpenCheckIn = {},
        )
    }
}

/**
 * One extreme: the day in Czech, its mood and the three facts that make it tangible.
 * Pressable — [GlassCard]'s own press scale and light haptic, then the day's detail.
 */
@Composable
private fun BestWorstDayCard(
    title: String,
    arrow: String,
    point: MoodPoint,
    day: StatsDay?,
    accent: Color,
    testTag: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(
        modifier = modifier.testTagOrEmpty(testTag),
        accent = accent,
        elevated = true,
        glow = true,
        onClick = onOpen,
    ) {
        SectionHeader("$arrow $title")
        VSpace(Spacing.small)
        Text(
            text = bwMoodEmoji(point.mood),
            fontSize = 26.sp,
        )
        VSpace(Spacing.tight)
        Text(
            text = bwMoodLabel(point.mood),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(Spacing.tiny)
        Text(
            text = "${point.mood} / 5",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
        )
        VSpace(Spacing.tight)
        Text(
            text = bwDate(point.date),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        val weekday = bwWeekday(point.date)
        if (weekday.isNotEmpty()) {
            VSpace(2)
            Text(
                text = weekday,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
        }
        VSpace(Spacing.small)
        Text(
            text = dayFacts(day),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
    }
}

/** Czech numeric date, "5. 9. 2026"; the raw value when it is not a date. */
private val bwDatePattern: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d. M. yyyy", Locale("cs", "CZ"))

private fun bwDate(date: String): String =
    CheckInDates.parse(date)?.format(bwDatePattern) ?: date

/** Czech weekday name, lowercase — "pátek", empty when the value is not a date. */
private fun bwWeekday(date: String): String =
    when (CheckInDates.parse(date)?.dayOfWeek) {
        DayOfWeek.MONDAY -> "pondělí"
        DayOfWeek.TUESDAY -> "úterý"
        DayOfWeek.WEDNESDAY -> "středa"
        DayOfWeek.THURSDAY -> "čtvrtek"
        DayOfWeek.FRIDAY -> "pátek"
        DayOfWeek.SATURDAY -> "sobota"
        DayOfWeek.SUNDAY -> "neděle"
        null -> ""
    }

/** Czech plural for an activity count: 1 aktivita, 2–4 aktivity, 5+ aktivit. */
private fun activityWord(count: Int): String = when {
    count == 1 -> "aktivita"
    count in 2..4 -> "aktivity"
    else -> "aktivit"
}

/** The screen's own mood mapping, kept identical so the emoji never drift apart. */
private fun bwMoodEmoji(mood: Int): String =
    MOOD_CHOICES.firstOrNull { it.value == mood }?.emoji ?: "⚪"

private fun bwMoodLabel(mood: Int): String =
    MOOD_CHOICES.firstOrNull { it.value == mood }?.label ?: "Bez odpovědi"

/**
 * The day's context line: screen time (formatMinutes), unlocks and how many
 * activities were logged. A day the sync worker never reported says so with the
 * screen's own dash rather than inventing a zero — `null` is not `0`.
 */
private fun dayFacts(day: StatsDay?): String {
    val screenTime = day?.screenTimeSeconds?.let { formatMinutes(it / 60) } ?: "—"
    val unlocks = day?.unlocks?.let { "$it odemknutí" } ?: "— odemknutí"
    val activities = day?.activities?.size ?: 0
    return "$screenTime · $unlocks · $activities ${activityWord(activities)}"
}
