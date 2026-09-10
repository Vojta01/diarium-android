package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.DailyGoal
import cz.digitalnivedomi.diarium.core.data.Scale
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/**
 * Škály — the user's custom scales from the `scales` table, one numeric row
 * each. Value 0 means "not filled", which the repository skips both in the RPC
 * payload and in the mirrored `scale_entries` rows.
 *
 * A tap on the already-selected value clears that scale (there is no separate
 * "Vymazat" button). The header states which end is best when every scale
 * shares one range; otherwise each row carries its own hint.
 */
@Composable
fun ScalesSection(
    scales: List<Scale>,
    values: Map<String, Int>,
    onChange: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    CheckInSection(title = "Škály", modifier = modifier) {
        if (scales.isEmpty()) {
            Text(
                text = "Zatím žádné škály.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            return@CheckInSection
        }
        val shared = sharedScaleRange(scales)
        if (shared != null) {
            SectionHint(scaleRangeHint(shared.first, shared.second))
            Spacer(Modifier.height(4.dp))
        }
        SectionHint("Klepnutím na zvolenou hodnotu ji vymažeš.")
        Spacer(Modifier.height(10.dp))

        scales.forEach { scale ->
            val current = values[scale.id]
            Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = labelWithIcon(scale.emoji, scale.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = current?.toString() ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                NumberRow(
                    values = scale.minValue..scale.maxValue,
                    selected = current,
                    tagPrefix = "scale_${scale.id}",
                    // Re-tapping the chosen value clears the scale (0 = not
                    // filled, the empty state the API expects).
                    onSelect = { value -> onChange(scale.id, if (value == current) 0 else value) },
                )
                if (shared == null) {
                    Spacer(Modifier.height(6.dp))
                    SectionHint(scaleRangeHint(scale.minValue, scale.maxValue))
                }
            }
        }
    }
}

/** "1 = nejhorší · 5 = nejlepší" — states which end of a scale is the good one. */
internal fun scaleRangeHint(min: Int, max: Int): String =
    "$min = nejhorší · $max = nejlepší"

/**
 * The range every scale shares, or null when they differ (each row then shows
 * its own hint). The user's scales normally all run 1..5.
 */
internal fun sharedScaleRange(scales: List<Scale>): Pair<Int, Int>? {
    val first = scales.firstOrNull() ?: return null
    val sameRange = scales.all { it.minValue == first.minValue && it.maxValue == first.maxValue }
    return if (sameRange) first.minValue to first.maxValue else null
}

/**
 * Denní cíle — DataStore-backed goals (default: 🏋️ Krátké cvičení). Ticking a
 * goal records today's ISO date in `completedDates`, exactly like the web's
 * `diarium_goals` blob.
 */
@Composable
fun GoalsSection(
    goals: List<DailyGoal>,
    date: String,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newGoal by rememberSaveable { mutableStateOf("") }
    val done = goals.count { date in it.completedDates }

    CheckInSection(title = "Cíle $done/${goals.size}", modifier = modifier) {
        if (goals.isEmpty()) {
            Text(
                text = "Zatím žádné cíle.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        goals.forEach { goal ->
            val completed = date in goal.completedDates
            val shape = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Outline.copy(alpha = 0.6f), shape)
                    .clickable { onToggle(goal.id) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${goal.emoji} ${goal.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(text = "🗑", testTag = "goal_remove_${goal.id}") { onRemove(goal.id) }
                Spacer(Modifier.height(0.dp))
                TogglePill(
                    checked = completed,
                    testTag = "goal_${goal.id}",
                ) { onToggle(goal.id) }
            }
        }
        Spacer(Modifier.height(6.dp))
        GlassTextField(
            value = newGoal,
            onValueChange = { newGoal = it },
            placeholder = "Nový cíl",
            testTag = "goal_new",
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton(text = "Přidat cíl", testTag = "goal_add") {
            val name = newGoal.trim()
            if (name.isNotEmpty()) {
                onAdd(name)
                newGoal = ""
            }
        }
    }
}
