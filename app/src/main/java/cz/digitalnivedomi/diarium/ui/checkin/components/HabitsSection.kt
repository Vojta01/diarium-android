package cz.digitalnivedomi.diarium.ui.checkin.components

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
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary

/** Lighter stop for the checked-pill gradient: a lit pill must ramp inside its own
 *  hue (green -> light green), not bleed into indigo. */
private val PositiveColorLight = Color(0xFF6EE7B7)

/**
 * How one habit row should read.
 *
 * A tick always means fulfilled (owner decision, 2026-10-13): on a positive habit
 * it means the user did it, on a negative — "avoid" — habit (e.g. Alkohol) it
 * means the user *didn't* do it. Either way a ticked row turns green (the theme's
 * semantic success colour) with a `✓ Splněno` glyph — ticking must never be a
 * colour-only signal and is never red. An unticked habit is neutral whatever its
 * kind.
 */
internal enum class HabitAccent { NEUTRAL, POSITIVE }

/** The single decision a habit row's fill, border, accent and glyph hang off.
 *  The kind no longer splits the outcome; the flag stays so call sites read the
 *  decision explicitly. */
@Suppress("UNUSED_PARAMETER")
internal fun habitAccentState(isNegative: Boolean, checked: Boolean): HabitAccent =
    if (checked) HabitAccent.POSITIVE else HabitAccent.NEUTRAL

/**
 * Návyky — the user's habits from the `habits` table as a 2-column toggle grid.
 *
 * A tick always means fulfilled (owner decision, 2026-10-13), `is_negative`
 * (avoid) habits included — there a ticked pill means the user *didn't* do it
 * that day. So every checked habit gets the same green treatment with „✓ Splněno",
 * never red; only an unticked avoid habit carries the quiet „nežádoucí" type hint,
 * which marks the kind without judging the day.
 *
 * A full-width "➕ Přidat návyk" row sits under the grid — shown even when the
 * list is empty — and the pencil on each pill opens the editor, mirroring the
 * web's per-habit ✏️ / "✕ Odebrat" actions.
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
    /**
     * Opens the editor in create mode (the "➕ Přidat návyk" row). Left null by
     * callers that only read habits, so the button only appears where adding is
     * actually wired.
     */
    onAddHabit: (() -> Unit)? = null,
) {
    CheckInSection("Návyky", modifier = modifier) {
        if (habits.isEmpty()) {
            Text(
                text = "Zatím žádné návyky.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        } else {
            // Short, no clutter: says what a tick means before the grid, and that
            // the pencil opens the editor.
            SectionHint("Zaškrtnutí = splněno. U nežádoucích návyků je splněno, když jsi je dnes nedělal. ✏️ otevře úpravu návyku.")
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
                            // Neutral rows ignore the accent (the pill is off); the
                            // habit's own colour is only a fallback for odd data.
                            HabitAccent.NEUTRAL -> parseHex(habit.color, Indigo)
                        }
                        // The second gradient stop, in the accent's own hue, so a
                        // lit pill never ramps into indigo.
                        val accentEnd = when (state) {
                            HabitAccent.POSITIVE -> PositiveColorLight
                            HabitAccent.NEUTRAL -> IndigoLight
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
                                    // Display-only capitalisation; the stored label
                                    // keeps exactly what the user typed.
                                    text = labelWithIcon(
                                        habit.icon,
                                        habit.label.replaceFirstChar { it.uppercase() },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                    maxLines = 2,
                                )
                                // A ticked habit says so in words as well as colour —
                                // positive and avoid habits alike, where the tick means
                                // "I didn't". An unticked avoid habit keeps the quiet
                                // type hint.
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
                                accentEnd = accentEnd,
                                testTag = "habit_${habit.key}",
                            ) { onToggle(habit.key) }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (onAddHabit != null) {
            Spacer(Modifier.height(10.dp))
            // Full-width like the web's "add" row, in the section's quiet indigo
            // glass — adding is a side action, not the screen's save.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Indigo.copy(alpha = 0.16f))
                    .border(1.dp, Indigo.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                    .clickable { onAddHabit() }
                    .padding(vertical = 10.dp)
                    .testTagOrEmpty("habit_add"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "➕ Přidat návyk",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                )
            }
        }
    }
}
