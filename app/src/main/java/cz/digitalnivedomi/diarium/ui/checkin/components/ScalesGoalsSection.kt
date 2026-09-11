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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.DailyGoal
import cz.digitalnivedomi.diarium.core.data.Scale
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import kotlin.math.roundToInt

/**
 * Škály — the user's custom scales from the `scales` table, each rendered as a
 * discrete Material 3 slider over its own `minValue..maxValue` range. Value 0
 * (and a missing key) means "not filled", which the repository skips both in the
 * RPC payload and in the mirrored `scale_entries` rows.
 *
 * There is no text "Vymazat" button: a set scale shows a small icon-only clear
 * affordance, and an unset one parks the thumb at its minimum over a dimmed
 * track with a "Klepnutím nastav" hint. The header states which end is best when
 * every scale shares one range; otherwise each row carries its own hint.
 */
@Composable
fun ScalesSection(
    scales: List<Scale>,
    values: Map<String, Int>,
    onChange: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    CheckInSection(title = "Škály", modifier = modifier) {
        if (scales.isEmpty()) {
            Text(
                text = "Zatím žádné škály.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            return@CheckInSection
        }
        val shared = sharedScaleRange(scales)
        if (shared != null) {
            SectionHint(scaleRangeHint(shared.first, shared.second))
            Spacer(Modifier.height(4.dp))
        }
        SectionHint("Posuň slider na hodnotu; ✕ ji vymaže.")
        Spacer(Modifier.height(10.dp))

        scales.forEach { scale ->
            ScaleSliderRow(
                scale = scale,
                current = values[scale.id],
                showRangeHint = shared == null,
                // Value 0 is the API's empty state (CheckInState.setScale drops
                // the key), so clearing is a normal change to 0.
                onValueChange = { value -> onChange(scale.id, value) },
                onClear = { onChange(scale.id, 0) },
            )
        }
    }
}

/**
 * One graphical scale: emoji + name + `3 / 5` over a discrete slider whose range
 * is the scale's own `minValue..maxValue`. Dragging reports whole values only;
 * [showRangeHint] adds the per-scale "which end is best" line when the scales do
 * not all share one range.
 */
@Composable
private fun ScaleSliderRow(
    scale: Scale,
    current: Int?,
    showRangeHint: Boolean,
    onValueChange: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val min = scale.minValue
    val max = scale.maxValue
    val accent = parseHex(scale.color, Indigo)
    val set = current != null

    // Local float position keeps dragging smooth; it re-syncs whenever the form
    // hands back a new value (a drag landing on a step, or a clear to 0).
    var position by remember(scale.id) { mutableFloatStateOf(sliderPosition(current, min)) }
    LaunchedEffect(current) { position = sliderPosition(current, min) }

    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (scale.emoji.isBlank()) "📊" else scale.emoji,
                fontSize = CheckInIconSize.rowIcon,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = scale.name,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = scaleSliderValue(current, max),
                style = MaterialTheme.typography.bodyMedium,
                color = if (set) accent else TextTertiary,
                fontWeight = if (set) FontWeight.Medium else FontWeight.Normal,
            )
            if (set) {
                Spacer(Modifier.width(8.dp))
                ScaleClearButton(
                    testTag = "scale_${scale.id}_clear",
                    scaleName = scale.name,
                    onClear = onClear,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = position,
            onValueChange = { raw ->
                val snapped = snapScaleValue(raw, min, max)
                position = snapped.toFloat()
                if (snapped != current) onValueChange(snapped)
            },
            valueRange = min.toFloat()..max.toFloat(),
            // steps = max - min - 1: with the two endpoints that gives exactly
            // one slot per whole number, so no fractional value is reachable.
            steps = sliderSteps(min, max),
            modifier = Modifier.fillMaxWidth().testTagOrEmpty("scale_${scale.id}"),
            colors = SliderDefaults.colors(
                thumbColor = if (set) accent else TextTertiary,
                activeTrackColor = if (set) accent else Outline,
                inactiveTrackColor = Outline.copy(alpha = if (set) 0.6f else 0.35f),
            ),
        )
        if (!set) {
            Spacer(Modifier.height(2.dp))
            SectionHint("Klepnutím nastav")
        }
        if (showRangeHint) {
            Spacer(Modifier.height(2.dp))
            SectionHint(scaleRangeHint(min, max))
        }
    }
}

/**
 * Small icon-only clear affordance, shown only while the scale has a value. The
 * content description is what a screen reader announces (there is no text label
 * on screen, and no text "Vymazat" button anywhere in the section), and it names
 * the scale so a reader knows which value it clears.
 */
@Composable
private fun ScaleClearButton(testTag: String, scaleName: String, onClear: () -> Unit) {
    Box(
        modifier = Modifier
            .testTagOrEmpty(testTag)
            // 44dp square: the glyph is small, the target is not.
            .size(CheckInIconSize.touchTarget)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable { onClear() }
            .semantics {
                role = Role.Button
                contentDescription = if (scaleName.isBlank()) {
                    "Vymazat hodnotu"
                } else {
                    "Vymazat hodnotu: $scaleName"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "✕", color = TextTertiary, fontSize = CheckInIconSize.clearGlyph)
    }
}

/**
 * "1 = nejhorší · 5 = nejlepší" — states which end of a scale is the good one.
 *
 * The bounds come from the user's `scales` rows. Neither the Android [Scale]
 * model nor the web schema (`min_value`, `max_value`, `unit`, `color`,
 * `sort_order`, `is_active`) carries a "reversed" flag, and the web's
 * `ScaleSlider` never inverts its reading, so every real scale is min = worst
 * and the [higherIsBetter] default is always correct. The parameter exists so a
 * scale that ever needs the opposite reading can say so without a data change.
 */
internal fun scaleRangeHint(min: Int, max: Int, higherIsBetter: Boolean = true): String =
    if (higherIsBetter) "$min = nejhorší · $max = nejlepší"
    else "$min = nejlepší · $max = nejhorší"

/**
 * The range every scale shares, or null when they differ (each row then shows
 * its own hint). The user's scales normally all run 1..5.
 */
internal fun sharedScaleRange(scales: List<Scale>): Pair<Int, Int>? {
    val first = scales.firstOrNull() ?: return null
    val sameRange = scales.all { it.minValue == first.minValue && it.maxValue == first.maxValue }
    return if (sameRange) first.minValue to first.maxValue else null
}

/**
 * Discrete steps between the two ends, so a slider of `min..max` reaches only
 * whole numbers (`max - min - 1`). Never negative for a degenerate range.
 */
internal fun sliderSteps(min: Int, max: Int): Int = (max - min - 1).coerceAtLeast(0)

/** The slider position for a value; an unset scale parks the thumb at its minimum. */
internal fun sliderPosition(value: Int?, min: Int): Float = (value ?: min).toFloat()

/** Clamps a raw slider position to the nearest whole value inside `min..max`. */
internal fun snapScaleValue(position: Float, min: Int, max: Int): Int =
    if (max <= min) min else position.roundToInt().coerceIn(min, max)

/** `3 / 5` for a set scale, or the friendly dash while it has no value. */
internal fun scaleSliderValue(value: Int?, max: Int): String =
    if (value == null) "—" else "$value / $max"

/**
 * Denní cíle — DataStore-backed goals (default: 🏋️ Krátké cvičení). Ticking a
 * goal records today's ISO date in `completedDates`, exactly like the web's
 * `diarium_goals` blob.
 */
@Composable
fun GoalsSection(
    goals: List<DailyGoal>,
    date: String,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newGoal by rememberSaveable { mutableStateOf("") }
    val done = goals.count { date in it.completedDates }

    CheckInSection(title = "Cíle $done/${goals.size}", modifier = modifier) {
        if (goals.isEmpty()) {
            Text(
                text = "Zatím žádné cíle.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        goals.forEach { goal ->
            val completed = date in goal.completedDates
            val shape = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Outline.copy(alpha = 0.6f), shape)
                    .clickable { onToggle(goal.id) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (goal.emoji.isNotBlank()) {
                    // Same rule as an activity: the icon anchors the row and is
                    // never drawn at body-text size.
                    Text(text = goal.emoji, fontSize = CheckInIconSize.rowIcon)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = goal.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(text = "🗑", testTag = "goal_remove_${goal.id}") { onRemove(goal.id) }
                Spacer(Modifier.height(0.dp))
                TogglePill(
                    checked = completed,
                    testTag = "goal_${goal.id}",
                ) { onToggle(goal.id) }
            }
        }
        Spacer(Modifier.height(6.dp))
        GlassTextField(
            value = newGoal,
            onValueChange = { newGoal = it },
            placeholder = "Nový cíl",
            testTag = "goal_new",
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton(text = "Přidat cíl", testTag = "goal_add") {
            val name = newGoal.trim()
            if (name.isNotEmpty()) {
                onAdd(name)
                newGoal = ""
            }
        }
    }
}
