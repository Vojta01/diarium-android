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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
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
            Text(if (expanded) "▴" else "▾", color = TextTertiary, fontSize = 12.sp)
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
            .background(if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.75f) else Outline.copy(alpha = 0.5f),
                shape = shape,
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = emoji, fontSize = if (selected) 30.sp else 26.sp)
        if (label != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) accent else TextTertiary,
                maxLines = 1,
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
    testTag: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .testTagOrEmpty(testTag)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.05f))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.7f) else Outline.copy(alpha = 0.55f),
                shape = shape,
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) TextPrimary else TextPrimary.copy(alpha = 0.8f),
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
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
            .size(38.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.35f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = TextPrimary, fontSize = 14.sp)
    }
}

/** Activity/habit label with its catalogue icon. */
fun labelWithIcon(icon: String, label: String): String =
    if (icon.isBlank()) label else "$icon $label"
