package cz.digitalnivedomi.diarium.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.components.rememberLightHaptics
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/** The chart's two windows: a week, or the whole month the dashboard loads. */
internal enum class ChartRange { WEEK, MONTH }

/** The chips' exact labels. */
private val ChartRange.label: String
    get() = when (this) {
        ChartRange.WEEK -> "7 dní"
        ChartRange.MONTH -> "30 dní"
    }

/**
 * The compact 7/30-day chips — the statistics screen's switch, reduced to two taps.
 * Shared by the dashboard's mood chart and both usage charts, so all three windows
 * move with the same control.
 */
@Composable
internal fun RangeToggle(
    range: ChartRange,
    onSelect: (ChartRange) -> Unit,
) {
    val haptics = rememberLightHaptics()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChartRange.entries.forEach { option ->
            val selected = option == range
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) {
                            Indigo.copy(alpha = 0.30f)
                        } else {
                            Color.White.copy(alpha = 0.05f)
                        },
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) {
                            IndigoLight.copy(alpha = 0.55f)
                        } else {
                            Outline.copy(alpha = 0.40f)
                        },
                        shape = RoundedCornerShape(50),
                    )
                    .clickable {
                        if (!selected) {
                            // A window change is a value change: the softer tick the
                            // statistics switch uses, not a full tap.
                            haptics()
                            onSelect(option)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) TextPrimary else TextSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
