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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import cz.digitalnivedomi.diarium.ui.i18n.Text
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
import androidx.compose.ui.graphics.Brush
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.accentGlow
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import java.time.LocalDate

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
    /** The day "forward" is measured against; a parameter so a screenshot can pin it. */
    today: LocalDate = LocalDate.now(),
    /**
     * False while a save or photo upload is in flight: the action works from a
     * snapshot of the day it started on, so the day must not change underneath it.
     */
    enabled: Boolean = true,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val canGoForward = enabled && date < today.toString()

    GlassCard(modifier = modifier, accent = Indigo) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            GlassIconButton(
                text = "◀",
                testTag = "date_prev",
                accent = if (enabled) Indigo else TextTertiary,
            ) {
                if (enabled) onDateChange(CheckInDates.shift(date, -1))
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = CheckInDates.display(date, today),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Text(text = date, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable(enabled = enabled) { pickerOpen = true }
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
                        if (enabled) {
                            pickerState.selectedDateMillis?.let { onDateChange(CheckInDates.fromEpochMillis(it)) }
                        }
                        pickerOpen = false
                    },
                    enabled = enabled,
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

/**
 * Full-width primary action (save) in the brand indigo.
 *
 * [leading] exists for the one action that has to show it is running — the AI
 * reflection button. It is drawn before the label, coloured by the caller.
 */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val haptics = rememberHaptics()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTagOrEmpty(testTag)
            .clip(shape)
            .then(
                if (enabled) {
                    Modifier.background(Brush.horizontalGradient(listOf(Indigo, IndigoLight)))
                } else {
                    Modifier.background(Color.White.copy(alpha = 0.06f))
                },
            )
            .then(if (enabled) Modifier.accentGlow(Indigo, alpha = 0.30f) else Modifier)
            .clickable(enabled = enabled) {
                haptics.light()
                onClick()
            }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Color.White else TextTertiary,
            )
        }
    }
}

/** Compact secondary action (add/section utilities). */
@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .testTagOrEmpty(testTag)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) TextPrimary else TextTertiary,
        )
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
