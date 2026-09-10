package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/**
 * Last few shared bits: hex colour parsing (catalogue rows store `#RRGGBB`),
 * the numeric scale row and the read-only label/value row.
 */

/** `#6366F1` (or `#FF6366F1`) -> [Color]; falls back to [fallback] when junk. */
fun parseHex(hex: String, fallback: Color = Indigo): Color {
    val clean = hex.trim().removePrefix("#")
    return try {
        val value = clean.toLong(16)
        when (clean.length) {
            6 -> Color(0xFF000000L or value)
            8 -> Color(value)
            else -> fallback
        }
    } catch (_: NumberFormatException) {
        fallback
    }
}

/** Evenly weighted row of numeric buttons used by the scales. */
@Composable
fun NumberRow(
    values: IntRange,
    selected: Int?,
    tagPrefix: String,
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth()) {
        values.forEach { value ->
            val active = selected == value
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Indigo.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.05f))
                    .border(1.dp, if (active) Indigo else Outline, RoundedCornerShape(12.dp))
                    .clickable { onSelect(value) }
                    .testTag("${tagPrefix}_$value"),
                contentAlignment = Alignment.Center,
            ) {
                Text(value.toString(), color = if (active) TextPrimary else TextSecondary)
            }
        }
    }
}

/** Read-only label/value row (screen time, weather summary…). */
@Composable
fun ReadOnlyRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}
