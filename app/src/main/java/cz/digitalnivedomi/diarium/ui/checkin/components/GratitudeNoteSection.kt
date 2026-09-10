package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.NoteTemplate
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/**
 * Vděčnost — exactly three slots, matching `gratitude[3]` in the RPC payload.
 * Blank slots are filtered out on save (see [EntriesRepository.buildPayload]).
 */
@Composable
fun GratitudeSection(
    gratitude: List<String>,
    onChange: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CheckInSection(title = "Za co jsi vděčný?", modifier = modifier) {
        repeat(3) { index ->
            GlassTextField(
                value = gratitude.getOrElse(index) { "" },
                onValueChange = { onChange(index, it) },
                placeholder = "${index + 1}. věc...",
                testTag = "gratitude_$index",
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Poznámka + templates.
 *
 * Picking a template REPLACES the note text — the web's TemplatePicker does the
 * same, and appending would silently concatenate two different days' thoughts.
 */
@Composable
fun NoteSection(
    note: String,
    templates: List<NoteTemplate>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    CheckInSection(
        title = "Rychlá poznámka",
        modifier = modifier,
        trailing = {
            SecondaryButton(text = "📄 Šablony", testTag = "note_templates") { pickerOpen = true }
        },
    ) {
        GlassTextField(
            value = note,
            onValueChange = onChange,
            placeholder = "Co ti dnes běželo hlavou...",
            minHeight = 90,
            testTag = "note",
        )
    }

    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("Zavřít") }
            },
            title = { Text("Šablony") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    if (templates.isEmpty()) {
                        Text("Zatím žádné šablony.", color = TextSecondary)
                    }
                    templates.forEach { template ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .clickable {
                                    onChange(template.content)
                                    pickerOpen = false
                                }
                                .padding(10.dp),
                        ) {
                            Text(template.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            Text(
                                text = template.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                maxLines = 2,
                            )
                        }
                    }
                }
            },
        )
    }
}
