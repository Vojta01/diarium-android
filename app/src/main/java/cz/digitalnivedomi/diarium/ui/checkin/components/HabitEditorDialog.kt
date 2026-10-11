package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/**
 * "Upravit návyk" / "Nový návyk" — the icon and the label of one habit.
 *
 * Until now a habit's icon was whatever the catalogue row shipped with and there was no
 * way to change it; the same emoji picker the goals use now edits it here. The label is
 * editable in the same window because that is the other half of "this row is not quite
 * what I meant", and both are written together by
 * [cz.digitalnivedomi.diarium.core.data.PickersRepository.updateHabit].
 *
 * With no [habit] the window is the create step instead — "Nový návyk", the icon well
 * already holding the `✅` the repository would default to anyway — and save hands the
 * values to the screen's [cz.digitalnivedomi.diarium.core.data.PickersRepository.addHabit].
 * An existing habit additionally offers "✕ Odebrat návyk", which swaps this window into
 * an inline confirm (no second dialog) and reports through [onRemove]; the wording there
 * says what survives, because removal is a hide, not a delete.
 *
 * The `key` is deliberately *not* editable: it is the join key the day's entries store
 * their ticks under, so renaming it would orphan them.
 *
 * Nothing is written here — [onSave] and [onRemove] hand the values back to the screen,
 * which owns the repository and reports failures on its own banner.
 */
@Composable
fun HabitEditorDialog(
    habit: HabitDef? = null,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (label: String, icon: String, isNegative: Boolean) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    // Seeded per habit, so opening the editor on another row never shows the previous
    // row's values; in create mode the icon starts at the ✅ the repository defaults to.
    var label by rememberSaveable(habit?.key) { mutableStateOf(habit?.label.orEmpty()) }
    var icon by rememberSaveable(habit?.key) { mutableStateOf(habit?.icon ?: "✅") }
    var isNegative by rememberSaveable(habit?.key) { mutableStateOf(habit?.isNegative ?: false) }
    var pickingIcon by rememberSaveable { mutableStateOf(false) }
    // "✕ Odebrat návyk" was tapped: the window itself turns into the confirmation.
    var confirmingRemove by rememberSaveable(habit?.key) { mutableStateOf(false) }

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
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            accent = if (confirmingRemove) ErrorRed else Indigo,
        ) {
            if (confirmingRemove) {
                // Removal is destructive but never loses data — the past days keep
                // their ticks — so it is confirmed in words before anything is
                // written, in this same window (see MoveDateDialog's overwrite step).
                Text(
                    text = "Odebrat návyk?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                SectionHint("Historie zůstane.")
                Spacer(Modifier.height(14.dp))
                DestructiveButton(
                    text = "Odebrat",
                    enabled = !saving,
                    testTag = "habit_remove_confirm",
                ) { onRemove?.invoke() }
                Spacer(Modifier.height(8.dp))
                SecondaryButton(text = "Zpět", testTag = "habit_remove_back") {
                    confirmingRemove = false
                }
                return@GlassCard
            }

            Text(
                text = if (habit == null) "Nový návyk" else "✏️ Upravit návyk",
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
                text = when {
                    saving -> "Ukládám…"
                    habit == null -> "Přidat"
                    else -> "Uložit návyk"
                },
                enabled = clean.isNotEmpty() && !saving,
                testTag = "habit_save",
            ) { onSave(clean, icon, isNegative) }
            Spacer(Modifier.height(8.dp))
            SecondaryButton(text = "Zrušit", testTag = "habit_cancel") { onDismiss() }
            if (habit != null && onRemove != null) {
                Spacer(Modifier.height(8.dp))
                DestructiveButton(text = "✕ Odebrat návyk", testTag = "habit_remove") {
                    confirmingRemove = true
                }
            }
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

/**
 * The destructive twin of [SecondaryButton]: the same full-width glass shape in the
 * theme's error colour instead of the neutral fill. Used for "✕ Odebrat návyk" and its
 * confirm step, which are the only actions here that can make something disappear.
 */
@Composable
private fun DestructiveButton(
    text: String,
    testTag: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val color = MaterialTheme.colorScheme.error
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTagOrEmpty(testTag)
            .clip(shape)
            .background(color.copy(alpha = if (enabled) 0.14f else 0.06f))
            .border(1.dp, color.copy(alpha = if (enabled) 0.45f else 0.2f), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else TextSecondary,
        )
    }
}