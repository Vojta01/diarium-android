package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/**
 * 🎯 Cíle — the check-in's goal section, read-only.
 *
 * The rows are [GoalProgress] computed from the diary by `GoalsRepository` —
 * the same source the Cíle screen, the dashboard and the stats read: the
 * period's count, the target and the progress bar. Nothing here is tickable;
 * the check-in only *shows* goals and the Cíle screen manages them (owner
 * decision), so this section has no toggles, no add row and no editor.
 *
 * A null [progress] means the read failed (or there is no session) and the
 * section renders nothing, so a flaky network can never read as "no goals";
 * an empty list is a real answer and gets the pointer to the Cíle screen.
 */
@Composable
fun CheckInGoalsSection(
    progress: List<GoalProgress>?,
    modifier: Modifier = Modifier,
) {
    // "Unknown" hides the section; only a list the repository really returned
    // may say there are no goals.
    val goals = progress ?: return

    val met = goals.count { it.targetMet }
    CheckInSection(
        title = if (goals.isEmpty()) "🎯 Cíle" else "🎯 Cíle · $met/${goals.size} splněno",
        modifier = modifier,
    ) {
        if (goals.isEmpty()) {
            SectionHint("Zatím žádné cíle — nastav si je v záložce Cíle.")
            return@CheckInSection
        }
        goals.forEach { goal -> GoalProgressRow(goal) }
    }
}

/**
 * The fallback emoji on a goal row: a goal carries an activity key, not an icon,
 * so the target emoji stands in — the same rule as the dashboard's goal card.
 */
private const val GOAL_EMOJI = "🎯"

/**
 * One goal row: emoji, name, `count/target · period`, the period's progress bar
 * and ✓ once the target is met.
 *
 * The container mirrors the old check-in goal row (glass surface, 14 dp corners,
 * 10/10 padding); the bar and the met colour are copied from the dashboard's
 * goal card, so a goal looks the same wherever it appears.
 */
@Composable
private fun GoalProgressRow(progress: GoalProgress) {
    val goal = progress.goal
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Same rule as an activity: the icon anchors the row and is never
            // drawn at body-text size.
            Text(text = GOAL_EMOJI, fontSize = CheckInIconSize.rowIcon)
            Spacer(Modifier.width(8.dp))
            Text(
                text = goal.name,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${progress.count}/${goal.targetCount} · ${progress.periodLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            if (progress.targetMet) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelLarge,
                    color = SuccessGreen,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // Thin bar in the dashboard card's exact shape: green once the period's
        // target is met, indigo while it is still running.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.07f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.fraction)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(SolidColor(if (progress.targetMet) SuccessGreen else Indigo)),
            )
        }
    }
}
