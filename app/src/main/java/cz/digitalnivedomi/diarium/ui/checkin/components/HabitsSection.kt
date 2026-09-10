package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.HabitDef
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary

/**
 * Návyky — the user's habits from the `habits` table as a 2-column toggle grid.
 *
 * `is_negative` habits are tinted red and get a hint: they are the ones the user
 * is trying to avoid, so a filled pill means "I did it", same as the web.
 */
@Composable
fun HabitsSection(
    habits: List<HabitDef>,
    values: Map<String, Boolean>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val negativeColor = Color(0xFFEF4444)
    CheckInSection("Návyky", modifier = modifier) {
        if (habits.isEmpty()) {
            Text(
                text = "Zatím žádné návyky.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            return@CheckInSection
        }
        habits.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEach { habit ->
                    val accent = if (habit.isNegative) negativeColor else parseHex(habit.color, Indigo)
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(shape)
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Outline.copy(alpha = 0.6f), shape)
                            .clickable { onToggle(habit.key) }
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = labelWithIcon(habit.icon, habit.label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                maxLines = 2,
                            )
                            if (habit.isNegative) {
                                Text(
                                    text = "nežádoucí",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextTertiary,
                                )
                            }
                        }
                        TogglePill(
                            checked = values[habit.key] == true,
                            accent = accent,
                            testTag = "habit_${habit.key}",
                        ) { onToggle(habit.key) }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
