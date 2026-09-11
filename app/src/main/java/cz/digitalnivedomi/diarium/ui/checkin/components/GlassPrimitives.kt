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
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/**
 * Last few shared bits: hex colour parsing (catalogue rows store `#RRGGBB`),
 * the numeric scale row and the read-only label/value row.
 */

/**
 * One place for every emoji / touch size the check-in form draws with.
 *
 * The owner's complaint was "the icons are too small and not clear at all", so
 * the sizes live here instead of being re-typed per call site: an activity icon
 * inside a chip, a scale emoji and the mood emoji can only grow together, and
 * an item's icon can never drift below [chip] (22sp) — it is the visual anchor
 * of everything it labels.
 */
object CheckInIconSize {
    /** Icon of a chip (activities, weather) in its resting state. */
    val chip = 22.sp

    /** Icon of a selected chip — one step up so the anchor grows on tap. */
    val chipSelected = 24.sp

    /** The largest element in the form: the mood / sleep / stress emoji. */
    val scale = 28.sp

    /** The selected scale emoji — a subtle size pop, no animation. */
    val scaleSelected = 34.sp

    /** Emoji that leads a labelled row (a custom scale, a goal) — same floor. */
    val rowIcon = 22.sp

    /** The icon-only ✕ that clears a scale (has its own 44dp touch target). */
    val clearGlyph = 14.sp

    /** Glyph of a [GlassIconButton] (◀ ▶ …). */
    val actionGlyph = 16.sp

    /** Minimum touch height of a [SelectableChip]. */
    val chipMinHeight = 46.dp

    /** Comfortable touch target for an icon-only affordance. */
    val touchTarget = 44.dp
}

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
