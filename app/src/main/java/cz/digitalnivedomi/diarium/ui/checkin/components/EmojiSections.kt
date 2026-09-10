package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cz.digitalnivedomi.diarium.ui.checkin.MOOD_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.SLEEP_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.STRESS_CHOICES

/**
 * The three emoji scales. Each is a thin wrapper over [EmojiChoiceRow] so the
 * screen reads like the web form and each scale stays individually testable.
 */

/** Nálada — 5 emoji; selecting one also stores its emoji (`mood_emoji`). */
@Composable
fun MoodSection(
    selected: Int,
    onSelect: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CheckInSection(title = "Nálada", modifier = modifier) {
        EmojiChoiceRow(
            choices = MOOD_CHOICES,
            selectedValue = selected,
            testTagPrefix = "mood",
            onSelect = { value ->
                val emoji = MOOD_CHOICES.firstOrNull { it.value == value }?.emoji.orEmpty()
                onSelect(value, emoji)
            },
        )
    }
}

/** Kvalita spánku — 3 emoji. */
@Composable
fun SleepSection(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    CheckInSection(title = "Kvalita spánku", modifier = modifier) {
        EmojiChoiceRow(
            choices = SLEEP_CHOICES,
            selectedValue = selected,
            testTagPrefix = "sleep",
            onSelect = onSelect,
        )
    }
}

/** Stres — 5 emoji. */
@Composable
fun StressSection(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    CheckInSection(title = "Stres", modifier = modifier) {
        EmojiChoiceRow(
            choices = STRESS_CHOICES,
            selectedValue = selected,
            testTagPrefix = "stress",
            onSelect = onSelect,
        )
    }
}
