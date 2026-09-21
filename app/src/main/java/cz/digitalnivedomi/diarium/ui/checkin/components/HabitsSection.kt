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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.data.HabitDef
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary

/**
 * How one habit row should read.
 *
 * Ticking a *positive* habit is a win, so the row turns green (the theme's
 * semantic success colour) with a `✓ Splněno` glyph — ticking must never be a
 * colour-only signal. A *negative* habit (one the user is trying to avoid, e.g.
 * Alkohol) is the opposite: ticking it means "I did the bad thing", so it keeps
 * the long-standing red accent. Whatever the kind, an unticked habit is neutral.
 */
internal enum class HabitAccent { NEUTRAL, POSITIVE, NEGATIVE }

/** The single decision a habit row's fill, border, accent and glyph hang off. */
internal fun habitAccentState(isNegative: Boolean, checked: Boolean): HabitAccent = when {
    !checked -> HabitAccent.NEUTRAL
    isNegative -> HabitAccent.NEGATIVE
    else -> HabitAccent.POSITIVE
}

/**
 * Návyky — the user's habits from the `habits` table as a 2-column toggle grid.
 *
 * `is_negative` habits are tinted red and get a hint: they are the ones the user
 * is trying to avoid, so a filled pill means "I did it", same as the web. A
 * ticked positive habit is a fulfilled one, so it gets the green treatment.
 */
@Composable
fun HabitsSection(
    habits: List<HabitDef>,
    values: Map<String, Boolean>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Opens the editor for one habit's icon/label. Left null by callers that only read
     * habits, so the pencil only appears where editing is actually wired.
     */
    onEdit: ((HabitDef) -> Unit)? = null,
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
        // Short, no clutter: says what a tick means before the grid.
        SectionHint("Zaškrtnutím potvrdíš splnění.")
        Spacer(Modifier.height(8.dp))
        habits.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEach { habit ->
                    val checked = values[habit.key] == true
                    val state = habitAccentState(habit.isNegative, checked)
                    val accent = when (state) {
                        HabitAccent.POSITIVE -> SuccessGreen
                        HabitAccent.NEGATIVE -> negativeColor
                        // Neutral rows ignore the accent (the pill is off); the
                        // habit's own colour is only a fallback for odd data.
                        HabitAccent.NEUTRAL -> parseHex(habit.color, Indigo)
                    }
                    val fulfilled = state == HabitAccent.POSITIVE
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(shape)
                            .background(
                                if (fulfilled) SuccessGreen.copy(alpha = 0.16f)
                                else Color.White.copy(alpha = 0.05f),
                            )
                            .border(
                                width = 1.dp,
                                color = if (fulfilled) SuccessGreen.copy(alpha = 0.7f)
                                else Outline.copy(alpha = 0.6f),
                                shape = shape,
                            )
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
                            // A ticked positive habit says so in words as well as
                            // colour; an `is_negative` habit keeps its
                            // long-standing "nežádoucí" hint either way.
                            when {
                                state == HabitAccent.POSITIVE -> Text(
                                    text = "✓ Splněno",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SuccessGreen,
                                )
                                habit.isNegative -> Text(
                                    text = "nežádoucí",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextTertiary,
                                )
                                else -> Unit
                            }
                        }
                        if (onEdit != null) {
                            // A separate tap target inside the pill's own clickable row:
                            // the inner one consumes the tap, so editing never ticks the
                            // habit by accident.
                            Text(
                                text = "✏️",
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onEdit(habit) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                    .testTagOrEmpty("habit_edit_${habit.key}"),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        TogglePill(
                            checked = checked,
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
