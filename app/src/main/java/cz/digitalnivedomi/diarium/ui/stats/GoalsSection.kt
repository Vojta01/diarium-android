package cz.digitalnivedomi.diarium.ui.stats

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import cz.digitalnivedomi.diarium.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.data.GoalPeriodResult
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.core.data.normalizeFrequency
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.StaggeredItem
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * 🎯 Cíle — one card per active goal, its recent periods as a row of squares and the
 * success summary under them. Tapping a square opens [GoalPeriodDialog] with that
 * period's window and the days it logged.
 *
 * The section carries its own read ([cz.digitalnivedomi.diarium.core.data.GoalsRepository],
 * loaded next to the statistics read) and its own failure domain: the caller hides the
 * whole block when there are no goals to show, so a failed or empty goals read never
 * renders as an empty shell and never takes the mood numbers down with it.
 *
 * A square is one period of [GoalProgress.periods], oldest first, the current period
 * last and outlined: filled [SuccessGreen] = the target was met, a grey outline = the
 * period has data but missed, a very faint outline = no data at all ("bez dat"), so a
 * gap in the log is never drawn as a missed period. A monthly goal labels each square
 * with its month ("Kvě"…), because six bare squares are hard to read.
 */
@Composable
internal fun GoalsSection(goals: List<GoalProgress>) {
    var selected by remember { mutableStateOf<PeriodSelection?>(null) }

    StaggeredItem(GOALS_STAGGER) { SectionHeader("🎯 Cíle") }
    goals.forEachIndexed { cardIndex, progress ->
        StaggeredItem(GOALS_STAGGER) {
            GoalProgressCard(
                progress = progress,
                testTagPrefix = "goal_period_square_$cardIndex",
                onPeriodClick = { periodIndex -> selected = PeriodSelection(progress, periodIndex) },
            )
        }
    }

    val selection = selected
    val period = selection?.progress?.periods?.getOrNull(selection.index)
    if (selection != null && period != null) {
        GoalPeriodDialog(
            goal = selection.progress.goal,
            period = period,
            isCurrent = selection.index == selection.progress.periods.lastIndex,
            onDismiss = { selected = null },
        )
    }
}

/** The square whose detail window is open: which card, which index in its history. */
private data class PeriodSelection(val progress: GoalProgress, val index: Int)

/** The stagger the tail of the screen shares — see the ladder in `StatsContent`. */
private const val GOALS_STAGGER = 6

/** One period square: 16 dp, rounded 4 dp, 4 dp apart. */
private val SQUARE_SIZE = 16.dp
private val SQUARE_SHAPE = RoundedCornerShape(4.dp)
private val SQUARE_SPACING = 4.dp

@Composable
private fun GoalProgressCard(
    progress: GoalProgress,
    testTagPrefix: String,
    onPeriodClick: (Int) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = goalEmoji(progress.goal.activityKey), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(Spacing.small))
            Text(
                text = progress.goal.name,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Spacing.small))
            // The current period in numbers, so the highlighted last square has a value.
            Text(
                text = "${progress.count}/${progress.goal.targetCount} ${progress.periodLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }

        if (progress.periods.isNotEmpty()) {
            VSpace(Spacing.block)
            Row(horizontalArrangement = Arrangement.spacedBy(SQUARE_SPACING)) {
                val monthly = normalizeFrequency(progress.goal.frequency) == "monthly"
                progress.periods.forEachIndexed { index, period ->
                    PeriodSquareColumn(
                        period = period,
                        current = index == progress.periods.lastIndex,
                        monthLabel = if (monthly) monthShortLabel(period.start) else null,
                        onClick = { onPeriodClick(index) },
                        testTag = "${testTagPrefix}_$index",
                    )
                }
            }
        }

        val summary = goalSummaryText(
            metPeriods = progress.metPeriods,
            knownPeriods = progress.knownPeriods,
            streak = progress.streak,
        )
        if (summary != null) {
            VSpace(Spacing.small)
            Text(text = summary, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

/**
 * One square, optionally with its month label under it. The label column keeps the
 * square's 16 dp width, so the squares keep their rhythm; a goal without labels needs
 * no wrapper column at all.
 */
@Composable
private fun PeriodSquareColumn(
    period: GoalPeriodResult,
    current: Boolean,
    monthLabel: String?,
    onClick: () -> Unit,
    testTag: String,
) {
    if (monthLabel == null) {
        GoalPeriodSquare(period = period, current = current, onClick = onClick, testTag = testTag)
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(SQUARE_SIZE),
    ) {
        GoalPeriodSquare(period = period, current = current, onClick = onClick, testTag = testTag)
        Spacer(Modifier.height(3.dp))
        Text(
            text = monthLabel,
            color = TextSecondary,
            fontSize = 8.sp,
            lineHeight = 9.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * One period of the history: [GoalPeriodResult.met] true paints it [SuccessGreen],
 * false leaves a grey outline, null the faintest one ("bez dat"). The current period
 * ([current]) is ringed with the brand accent — the same marker the year grid puts on
 * today — and every square is tappable: the tap opens the period's detail window.
 */
@Composable
private fun GoalPeriodSquare(
    period: GoalPeriodResult,
    current: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val met = period.met
    Box(
        modifier = Modifier
            .size(SQUARE_SIZE)
            .clip(SQUARE_SHAPE)
            .background(if (met == true) SuccessGreen else Color.Transparent)
            .then(
                when (met) {
                    false -> Modifier.border(1.dp, Outline, SQUARE_SHAPE)
                    null -> Modifier.border(1.dp, Outline.copy(alpha = 0.35f), SQUARE_SHAPE)
                    true -> Modifier
                },
            )
            .then(if (current) Modifier.border(1.dp, Indigo, SQUARE_SHAPE) else Modifier)
            .clickable(onClick = onClick)
            .testTag(testTag),
    )
}

/**
 * „3/8 období ✓ (38 %) · série 🔥5" for the caption under the squares, or null when
 * there is nothing to report at all.
 *
 * The percentage is left out while nothing is known ([knownPeriods] == 0) instead of
 * being printed as 0/0 or a division by zero; the streak appears only while one is
 * alive, and says nothing about a run of zero.
 */
internal fun goalSummaryText(metPeriods: Int, knownPeriods: Int, streak: Int): String? {
    val parts = buildList {
        if (knownPeriods > 0) {
            val percent = (metPeriods * 100f / knownPeriods).roundToInt()
            add("$metPeriods/$knownPeriods období ✓ ($percent %)")
        }
        if (streak > 0) add("série 🔥$streak")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** The three-letter Czech month for a period's start ("Kvě" for any May date). */
internal fun monthShortLabel(isoDate: String): String {
    val month = isoDate.drop(5).take(2).toIntOrNull() ?: return ""
    return MONTHS_SHORT.getOrElse(month - 1) { "" }
}

private val MONTHS_SHORT = listOf(
    "Led", "Úno", "Bře", "Dub", "Kvě", "Čvn",
    "Čvc", "Srp", "Zář", "Říj", "Lis", "Pro",
)

/**
 * Activity-key → emoji for the card's title row.
 *
 * The statistics screen has no activity picker of its own and the goal carries no icon,
 * so the small table the goals feature keeps is mirrored here rather than a pickers
 * repository being wired in for one glyph. An unknown key gets the goals target — the
 * same fallback `ui/goals` uses. Shared with [GoalPeriodDialog]'s title row.
 */
internal fun goalEmoji(activityKey: String): String = when (activityKey.trim().lowercase()) {
    "beh", "behani", "běh", "běhání", "run", "running" -> "🏃"
    "spanek", "spánek", "sleep" -> "😴"
    "cteni", "čtení", "reading", "cten" -> "📚"
    "cviceni", "cvičení", "gym", "workout" -> "💪"
    "meditace", "meditation" -> "🧘"
    "voda", "water" -> "💧"
    "prochazka", "procházka", "walk" -> "🚶"
    "posilovna" -> "🏋️"
    "hacking", "programovani", "programování", "coding" -> "💻"
    else -> "🎯"
}
