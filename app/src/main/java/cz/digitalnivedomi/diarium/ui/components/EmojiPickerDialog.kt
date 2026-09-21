package cz.digitalnivedomi.diarium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cz.digitalnivedomi.diarium.ui.checkin.components.GlassTextField
import cz.digitalnivedomi.diarium.ui.checkin.components.testTagOrEmpty
import cz.digitalnivedomi.diarium.ui.theme.Dimens
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary

/**
 * Modal picker of a single emoji — the "icon" of a goal, a habit or a custom scale.
 *
 * Every editor that labels an item with an emoji needs the same two ways in: a short
 * curated catalogue to tap through, and a free-text field for anything the catalogue
 * does not cover (a personal glyph, a flag, a food that got missed). The dialog owns
 * neither storage nor navigation: the caller opens it from its own editor with the
 * item's current icon in [selected] and writes the result back through [onPick], so
 * the same window serves the goals screen, the habit editor and the scale editor.
 *
 * It never closes itself and never calls [onPick] with a blank value — a caller that
 * wants to stay open after a tap (live preview of a goal's new icon) can.
 *
 * @param title heading of the window, worded by the caller ("Ikona cíle", "Ikona návyku"…).
 * @param selected emoji the item already uses: drawn with an indigo ring in the
 *   catalogue and prefilled into the free-text field; null when there is no icon yet.
 * @param onDismiss called for "Zrušit", a tap outside and the back gesture.
 * @param onPick called with a tapped catalogue emoji or with the trimmed free-text
 *   value — only when that value is not blank.
 */
@Composable
fun EmojiPickerDialog(
    title: String,
    selected: String? = null,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    // Re-seeded whenever the caller hands over a different icon, so reopening the
    // picker on another item never shows the previous item's emoji in the field.
    var custom by remember(selected) { mutableStateOf(selected.orEmpty()) }

    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTagOrEmpty("emoji_picker"),
            accent = Indigo,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(Modifier.height(Spacing.block))

            // ── Free text: paste or type any emoji ──────────────────────────
            PickerLabel("Vlastní emoji")
            Spacer(Modifier.height(Spacing.tight))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassTextField(
                    value = custom,
                    onValueChange = { custom = it },
                    placeholder = "Vlož nebo napiš emoji",
                    modifier = Modifier.weight(1f),
                    testTag = "emoji_picker_custom",
                )
                Spacer(Modifier.width(Spacing.small))
                PickerButton(text = "Použít", accent = IndigoLight) {
                    val value = custom.trim()
                    if (value.isNotEmpty()) onPick(value)
                }
            }
            Spacer(Modifier.height(Spacing.tight))
            Text(
                text = "Můžeš vložit i složené emoji (např. 🏋️‍♂️).",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )

            Spacer(Modifier.height(Spacing.block))
            GlassDivider()
            Spacer(Modifier.height(Spacing.block))

            // ── Catalogue: grouped, scrollable, tap to pick ─────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = EMOJI_CATALOGUE_MAX_HEIGHT.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                EMOJI_CATALOGUE.forEach { (heading, emojis) ->
                    EmojiCategory(
                        heading = heading,
                        emojis = emojis,
                        selected = selected,
                        onPick = onPick,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.block))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                PickerButton(text = "Zrušit", accent = TextSecondary, onClick = onDismiss)
            }
        }
    }
}

// ── Catalogue ───────────────────────────────────────────────────────────────

/** One Czech group heading with the emoji the everyday tracker actually needs. */
@Composable
private fun EmojiCategory(
    heading: String,
    emojis: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
) {
    PickerLabel(heading)
    Spacer(Modifier.height(Spacing.small))
    emojis.chunked(EMOJI_PER_ROW).forEach { cells ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            cells.forEach { emoji ->
                EmojiCell(
                    emoji = emoji,
                    selected = emoji == selected,
                    modifier = Modifier.weight(1f),
                    onClick = { onPick(emoji) },
                )
            }
            // Invisible cells keep a short last row aligned with the grid above it.
            repeat(EMOJI_PER_ROW - cells.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.height(Spacing.tight))
}

/** A tap target around one emoji; [selected] draws the indigo ring. */
@Composable
private fun EmojiCell(
    emoji: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.radiusSm)
    Box(
        modifier = modifier
            .height(Dimens.touchTarget)
            .clip(shape)
            .background(if (selected) Indigo.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.05f))
            .then(
                if (selected) {
                    // The ring, not the fill, is what makes the current choice obvious.
                    Modifier.gradientBorder(shape, listOf(Indigo, IndigoLight), width = Dimens.borderStrong)
                } else {
                    Modifier.border(Dimens.border, Outline.copy(alpha = 0.5f), shape)
                },
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = 20.sp)
    }
}

// ── Small shared pieces ─────────────────────────────────────────────────────

/** Small glass pill used as an action; [accent] tints fill, border and label. */
@Composable
private fun PickerButton(
    text: String,
    accent: Color = Indigo,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.18f))
            .border(Dimens.border, accent.copy(alpha = 0.45f), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
    }
}

/** Quiet caption above a group of emoji. */
@Composable
private fun PickerLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = TextSecondary,
    )
}

// ── Constants ───────────────────────────────────────────────────────────────

private const val EMOJI_PER_ROW = 6

/** Height of the scrolling catalogue — tall enough for a group and a half. */
private const val EMOJI_CATALOGUE_MAX_HEIGHT = 300

/**
 * Curated for a mood / habit tracker, not for chat: every group leads with the
 * glyphs a diary entry uses most, and the sport group starts with the gym so the
 * most common activity is the first tap in the whole window.
 */
private val EMOJI_CATALOGUE: List<Pair<String, List<String>>> = listOf(
    "Sport" to listOf(
        "🏋️", "🏃", "🚴", "⚽", "🏊", "🧘", "🥊", "🏔️",
        "⛷️", "🏀", "🎾", "🏸", "🛹", "🏆", "💪", "🥾",
    ),
    "Zdraví" to listOf(
        "💧", "😴", "💊", "🩺", "🦷", "🧴", "🧼", "🚭",
        "🌡️", "🩸", "🫀", "🧠", "💤", "☀️", "🥶", "🤒",
    ),
    "Jídlo a pití" to listOf(
        "🍎", "🥗", "🍲", "🥣", "🍞", "🥚", "🥑", "🍌",
        "🍓", "🥦", "🍗", "🐟", "☕", "🍵", "🍰", "🍫",
    ),
    "Práce a učení" to listOf(
        "💻", "📚", "📝", "🎓", "🧑‍💻", "📖", "✏️", "📊",
        "🖊️", "🎯", "🗂️", "📅", "🔬", "📐", "💼", "🖥️",
    ),
    "Relax a lidé" to listOf(
        "🎵", "🎧", "🎮", "🎬", "📺", "🎨", "🧶", "🎸",
        "👨‍👩‍👧", "👫", "❤️", "🤗", "🎉", "😂", "🛋️", "📷",
    ),
    "Ostatní" to listOf(
        "🚶", "🚗", "✈️", "🏠", "🛒", "🧹", "🧺", "🐶",
        "🐱", "🌳", "🌸", "☔", "🌙", "🙏", "✨", "🔑",
    ),
)
