package cz.digitalnivedomi.diarium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/** The three goal periods as canonical value → Czech adverb; the set GoalsRepository stores. */
val FREQUENCY_OPTIONS: List<Pair<String, String>> = listOf(
    "daily" to "denně",
    "weekly" to "týdně",
    "monthly" to "měsíčně",
)

/** Czech adverb for a stored frequency value; an unknown value reads as weekly. */
fun frequencyAdverb(value: String): String =
    FREQUENCY_OPTIONS.firstOrNull { it.first == value }?.second ?: "týdně"

/**
 * Compact dropdown for a goal's period — the value set both the onboarding wizard's goal
 * rows and the Cíle editor offer, so the two surfaces can never show different phrasing.
 * The anchor is a small glass pill; the menu is a plain Material menu (readable above the
 * glass cards). Picking an option reports the canonical value (`daily`/`weekly`/`monthly`).
 */
@Composable
fun FrequencyDropdown(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Outline.copy(alpha = 0.6f), shape)
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = frequencyAdverb(value),
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
            )
            Spacer(Modifier.width(6.dp))
            Text(text = "▾", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FREQUENCY_OPTIONS.forEach { (raw, adverb) ->
                DropdownMenuItem(
                    text = { Text(adverb, color = TextPrimary) },
                    onClick = {
                        onValueChange(raw)
                        open = false
                    },
                    modifier = Modifier.then(
                        if (testTag != null) Modifier.testTag("${testTag}_$raw") else Modifier,
                    ),
                )
            }
        }
    }
}
