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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cz.digitalnivedomi.diarium.core.data.HabitDef
import cz.digitalnivedomi.diarium.ui.components.EmojiPickerDialog
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/**
 * "Upravit návyk" — the icon and the label of one habit.
 *
 * Until now a habit's icon was whatever the catalogue row shipped with and there was no
 * way to change it; the same emoji picker the goals use now edits it here. The label is
 * editable in the same window because that is the other half of "this row is not quite
 * what I meant", and both are written together by
 * [cz.digitalnivedomi.diarium.core.data.PickersRepository.updateHabit].
 *
 * The `key` is deliberately *not* editable: it is the join key the day's entries store
 * their ticks under, so renaming it would orphan them.
 *
 * Nothing is written here — [onSave] hands the three edited values back to the screen,
 * which owns the repository and reports failures on its own banner.
 */
@Composable
fun HabitEditorDialog(
    habit: HabitDef,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (label: String, icon: String, isNegative: Boolean) -> Unit,
) {
    // Seeded per habit, so opening the editor on another row never shows the previous
    // row's values.
    var label by rememberSaveable(habit.key) { mutableStateOf(habit.label) }
    var icon by rememberSaveable(habit.key) { mutableStateOf(habit.icon) }
    var isNegative by rememberSaveable(habit.key) { mutableStateOf(habit.isNegative) }
    var pickingIcon by rememberSaveable { mutableStateOf(false) }

    if (pickingIcon) {
        EmojiPickerDialog(
            title = "Ikona návyku",
            selected = icon,
            onDismiss = { pickingIcon = false },
            onPick = { picked ->
                icon = picked
                pickingIcon = false
            },
        )
        return
    }

    val clean = label.trim()
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
            Text(
                text = "✏️ Upravit návyk",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            SectionHint("Ikona i název se ukládají k účtu, takže je uvidíš i na webu.")
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButtonBox(
                    emoji = icon,
                    testTag = "habit_icon",
                    onClick = { pickingIcon = true },
                )
                Spacer(Modifier.width(10.dp))
                GlassTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = "Název návyku",
                    modifier = Modifier.weight(1f),
                    testTag = "habit_label",
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Nežádoucí návyk",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                    Text(
                        text = "Zaškrtnutí pak znamená „dnes jsem to udělal\".",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
                TogglePill(
                    checked = isNegative,
                    accent = MaterialTheme.colorScheme.error,
                    testTag = "habit_negative",
                ) { isNegative = !isNegative }
            }

            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = if (saving) "Ukládám…" else "Uložit návyk",
                enabled = clean.isNotEmpty() && !saving,
                testTag = "habit_save",
            ) { onSave(clean, icon, isNegative) }
            Spacer(Modifier.height(8.dp))
            SecondaryButton(text = "Zrušit", testTag = "habit_cancel") { onDismiss() }
        }
    }
}

/**
 * The tappable icon well the picker opens from: a 44 dp glass square showing the current
 * emoji (or a target when a habit has none), so the icon is visible before it is changed.
 */
@Composable
private fun IconButtonBox(
    emoji: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Outline.copy(alpha = 0.6f), shape)
            .clickable { onClick() }
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji.ifBlank { "🎯" }, fontSize = 22.sp)
    }
}
