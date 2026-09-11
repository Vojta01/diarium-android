package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary

/**
 * Small, dependency-free form primitives shared by every check-in section.
 *
 * They are deliberately NOT Material's `OutlinedTextField` / `Switch`: those
 * render opaque grey surfaces that break the glass language, so the dark
 * translucent surface is built here instead.
 */

/** Test tag only when the caller asks for one (keeps production tags clean). */
fun Modifier.testTagOrEmpty(tag: String?): Modifier = if (tag == null) this else this.testTag(tag)

/** Collapsible section with the indigo dot + caret header from the web form. */
@Composable
fun CheckInSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    initiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Indigo))
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (expanded) "▴" else "▾",
                // Quiet indigo accent so an open section reads as active
                // without adding a second colour family.
                color = if (expanded) IndigoLight else TextTertiary,
                fontSize = 12.sp,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Translucent input matching the glass language, with an inline placeholder. */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minHeight: Int = 44,
    testTag: String? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTagOrEmpty(testTag)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Outline.copy(alpha = 0.75f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = TextTertiary,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
            cursorBrush = SolidColor(Indigo),
        )
    }
}

/** A tappable emoji tile; the accent fill + border is the "selected" glow. */
@Composable
fun EmojiOption(
    emoji: String,
    selected: Boolean,
    accent: Color = Indigo,
    label: String? = null,
    testTag: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .testTagOrEmpty(testTag)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.05f))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.85f) else Outline.copy(alpha = 0.5f),
                shape = shape,
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The mood emoji stays the largest thing on the screen (28/34sp against
        // the chip's 22/24sp); the selected day grows without any animation.
        Text(
            text = emoji,
            fontSize = if (selected) CheckInIconSize.scaleSelected else CheckInIconSize.scale,
        )
        if (label != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) accent else TextSecondary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                // Two lines instead of one so a longer label is never
                // truncated, centred under its icon.
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A row of emoji options, evenly weighted. */
@Composable
fun EmojiChoiceRow(
    choices: List<cz.digitalnivedomi.diarium.ui.checkin.EmojiChoice>,
    selectedValue: Int,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        choices.forEach { choice ->
            EmojiOption(
                emoji = choice.emoji,
                label = choice.label,
                selected = selectedValue == choice.value,
                accent = choice.color,
                testTag = "${testTagPrefix}_${choice.value}",
                modifier = Modifier.weight(1f),
                onClick = { onSelect(choice.value) },
            )
        }
    }
}

/** Pill toggle used for habits and goals (no Material Switch grey). */
@Composable
fun TogglePill(
    checked: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .testTagOrEmpty(testTag)
            .size(width = 44.dp, height = 26.dp)
            .clip(CircleShape)
            .background(if (checked) accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.10f))
            .border(1.dp, if (checked) accent else Outline.copy(alpha = 0.6f), CircleShape)
            .clickable { onClick() }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (checked) 0.95f else 0.5f)),
        )
    }
}

/** Selectable chip for activities / weather / templates. */
@Composable
fun SelectableChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
    icon: String? = null,
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .testTagOrEmpty(testTag)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.06f))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.85f) else Outline.copy(alpha = 0.55f),
                shape = shape,
            )
            .clickable { onClick() }
            // Bigger icons need a taller pill; 46dp also clears the 44dp
            // minimum touch target on phones.
            .heightIn(min = CheckInIconSize.chipMinHeight)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (icon == null || icon.isBlank()) {
            // Callers whose emoji is already part of `text` (hide/restore,
            // screen-time ranges, stats) keep the single-string rendering.
            ChipLabel(text = text, selected = selected)
        } else {
            // Activities and weather own their emoji separately so it can be
            // drawn larger than the label instead of at text size.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = icon,
                    fontSize = if (selected) CheckInIconSize.chipSelected else CheckInIconSize.chip,
                )
                Spacer(Modifier.width(8.dp))
                ChipLabel(text = text, selected = selected)
            }
        }
    }
}

/** Chip label styling shared by the plain and icon-prefixed variants. */
@Composable
private fun ChipLabel(text: String, selected: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        // One step up from bodyMedium (13.5sp) and slightly tightened, so the
        // label still reads as secondary next to the 22/24sp icon.
        fontSize = 14.sp,
        letterSpacing = (-0.1).sp,
        lineHeight = 18.sp,
        color = if (selected) TextPrimary else TextPrimary.copy(alpha = 0.85f),
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
}

/** Small circular action button (◀ ▶ ⚙️ etc.) that keeps the glass look. */
@Composable
fun GlassIconButton(
    text: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    accent: Color = Indigo,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .testTagOrEmpty(testTag)
            .size(40.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.35f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = TextPrimary, fontSize = CheckInIconSize.actionGlyph)
    }
}

/** Activity/habit label with its catalogue icon. */
fun labelWithIcon(icon: String, label: String): String =
    if (icon.isBlank()) label else "$icon $label"

/**
 * Red-tinted glass banner for the last failed action.
 *
 * Lives here rather than in one screen because both the form and the AI
 * reflection section report failures with it — same tone, same accent as every
 * other "this is about X" surface (the theme's [ErrorRed]).
 */
@Composable
fun ErrorBanner(message: String) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ErrorRed.copy(alpha = 0.14f))
            .border(1.dp, ErrorRed.copy(alpha = 0.5f), shape)
            .padding(12.dp),
        style = MaterialTheme.typography.bodySmall,
        color = ErrorRed,
    )
}
