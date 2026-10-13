package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.GoalProgress
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
 * success summary under them.
 *
 * The section carries its own read ([cz.digitalnivedomi.diarium.core.data.GoalsRepository],
 * loaded next to the statistics read) and its own failure domain: the caller hides the
 * whole block when there are no goals to show, so a failed or empty goals read never
 * renders as an empty shell and never takes the mood numbers down with it.
 *
 * A square is one period of [GoalProgress.history], oldest first, the current period
 * last and outlined: filled [SuccessGreen] = the target was met, a grey outline = the
 * period has data but missed, a very faint outline = no data at all ("bez dat"), so a
 * gap in the log is never drawn as a missed period.
 */
@Composable
internal fun GoalsSection(goals: List<GoalProgress>) {
    StaggeredItem(GOALS_STAGGER) { SectionHeader("🎯 Cíle") }
    goals.forEach { progress ->
        StaggeredItem(GOALS_STAGGER) { GoalProgressCard(progress) }
    }
}

/** The stagger the tail of the screen shares — see the ladder in `StatsContent`. */
private const val GOALS_STAGGER = 6

/** One period square: 16 dp, rounded 4 dp, 4 dp apart. */
private val SQUARE_SIZE = 16.dp
private val SQUARE_SHAPE = RoundedCornerShape(4.dp)
private val SQUARE_SPACING = 4.dp

@Composable
private fun GoalProgressCard(progress: GoalProgress) {
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

        if (progress.history.isNotEmpty()) {
            VSpace(Spacing.block)
            Row(horizontalArrangement = Arrangement.spacedBy(SQUARE_SPACING)) {
                progress.history.forEachIndexed { index, met ->
                    GoalPeriodSquare(met = met, current = index == progress.history.lastIndex)
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
 * One period of the history: [met] true paints it [SuccessGreen], false leaves a grey
 * outline, null the faintest one ("bez dat"). The current period ([current]) is ringed
 * with the brand accent — the same marker the year grid puts on today.
 */
@Composable
private fun GoalPeriodSquare(met: Boolean?, current: Boolean) {
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
            .then(if (current) Modifier.border(1.dp, Indigo, SQUARE_SHAPE) else Modifier),
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

/**
 * Activity-key → emoji for the card's title row.
 *
 * The statistics screen has no activity picker of its own and the goal carries no icon,
 * so the small table the goals feature keeps is mirrored here rather than a pickers
 * repository being wired in for one glyph. An unknown key gets the goals target — the
 * same fallback `ui/goals` uses.
 */
private fun goalEmoji(activityKey: String): String = when (activityKey.trim().lowercase()) {
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
