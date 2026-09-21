package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.window.Dialog
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.components.GlassCard

/**
 * "Změnit datum" — moves a stored check-in to another day.
 *
 * The dialog has two steps, and which one is drawn depends only on what the caller
 * knows about the target day:
 *
 * 1. **Picking** ([conflictDate] == null): a Czech date picker. Confirming reports the
 *    picked day through [onPickDate]; the caller then decides whether that day is free
 *    (and moves) or already holds an entry (and comes back with [conflictDate] set).
 * 2. **Overwriting** ([conflictDate] != null): the picked day already has an entry, so
 *    the move would replace it. That is destructive, so it is confirmed in words before
 *    anything is written — the owner's rule for this flow.
 *
 * The dialog writes nothing itself: the screen owns the repositories, and this only
 * reports what the user chose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveDateDialog(
    currentDate: String,
    conflictDate: String?,
    onDismiss: () -> Unit,
    onPickDate: (String) -> Unit,
    onConfirmOverwrite: () -> Unit,
) {
    if (conflictDate != null) {
        OverwriteConfirmDialog(
            currentDate = currentDate,
            conflictDate = conflictDate,
            onDismiss = onDismiss,
            onConfirm = onConfirmOverwrite,
        )
        return
    }

    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = CheckInDates.toEpochMillis(currentDate),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { onPickDate(CheckInDates.fromEpochMillis(it)) }
                },
            ) { Text("Přesunout") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Zrušit") }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

/**
 * The second step: "that day already has an entry, replace it?".
 *
 * Both days are printed in full, so the sentence cannot be misread as moving *to* the
 * day that is already on screen.
 */
@Composable
private fun OverwriteConfirmDialog(
    currentDate: String,
    conflictDate: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = ErrorRed) {
            Text(
                text = "🗓 Přesunout zápis?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Den ${CheckInDates.display(conflictDate)} ($conflictDate) už má vyplněný zápis. " +
                    "Přesunout sem zápis z ${CheckInDates.display(currentDate)} ($currentDate) " +
                    "a původní obsah cílového dne přepsat?",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SecondaryButton(text = "Přesunout a přepsat", testTag = "move_overwrite") { onConfirm() }
                Spacer(Modifier.weight(1f))
                SecondaryButton(text = "Zrušit", testTag = "move_cancel") { onDismiss() }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Původní zápis cílového dne se nedá vrátit.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}
