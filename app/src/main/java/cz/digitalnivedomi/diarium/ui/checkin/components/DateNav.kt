package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary

/**
 * Day header: ◀ / displayed date / ▶ plus a Czech date picker.
 *
 * Forward navigation past tomorrow is disabled — a check-in is always for a real
 * day that already (partly) happened.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateNav(
    date: String,
    onDateChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val canGoForward = date < CheckInDates.today()

    GlassCard(modifier = modifier, accent = Indigo) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            GlassIconButton("◀", testTag = "date_prev") { onDateChange(CheckInDates.shift(date, -1)) }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = CheckInDates.display(date),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Text(text = date, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable { pickerOpen = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("📅 Vybrat datum", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
            GlassIconButton(
                text = "▶",
                testTag = "date_next",
                accent = if (canGoForward) Indigo else TextTertiary,
            ) {
                if (canGoForward) onDateChange(CheckInDates.shift(date, 1))
            }
        }
    }

    if (pickerOpen) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = CheckInDates.toEpochMillis(date),
        )
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onDateChange(CheckInDates.fromEpochMillis(it)) }
                        pickerOpen = false
                    },
                ) { Text("Vybrat") }
            },
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("Zrušit") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** Full-width primary action (save) in the brand indigo. */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTagOrEmpty(testTag)
            .clip(shape)
            .background(
                if (enabled) Indigo.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.06f),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) Color.White else TextTertiary,
        )
    }
}

/** Compact secondary action (add/section utilities). */
@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .testTagOrEmpty(testTag)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
    }
}

/** Two short lines used under section titles. */
@Composable
fun SectionHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
    )
}
