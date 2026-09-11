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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.NoteTemplate
import cz.digitalnivedomi.diarium.core.data.applyTemplate
import cz.digitalnivedomi.diarium.core.data.needsConfirm
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
 * Poznámka + šablony.
 *
 * The "Vložit šablonu" action opens a picker of templates. Picking one REPLACES
 * the note text — the web's TemplatePicker does the same, and appending would
 * silently concatenate two different days' thoughts. When the note already
 * holds non-whitespace text, the user is asked first; an empty (or
 * whitespace-only) note is filled straight away.
 */
@Composable
fun NoteSection(
    note: String,
    templates: List<NoteTemplate>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    // The picked template, held only while the user decides whether an existing
    // note may be overwritten. Not saveable: an in-flight confirmation is
    // allowed to disappear across a rotation rather than act on stale text.
    var pendingTemplate by remember { mutableStateOf<NoteTemplate?>(null) }

    CheckInSection(
        title = "Rychlá poznámka",
        modifier = modifier,
        trailing = {
            SecondaryButton(text = "📄 Vložit šablonu", testTag = "note_insert_template") {
                pickerOpen = true
            }
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
            title = { Text("Vložit šablonu") },
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
                                .testTag("note_template_${template.id}")
                                .clickable {
                                    pickerOpen = false
                                    if (needsConfirm(note)) {
                                        pendingTemplate = template
                                    } else {
                                        onChange(applyTemplate(note, template.content))
                                    }
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

    // Confirmation before an existing note is thrown away. The note value is
    // read at confirm time, matching the text the user is looking at.
    pendingTemplate?.let { template ->
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            confirmButton = {
                TextButton(onClick = {
                    onChange(applyTemplate(note, template.content))
                    pendingTemplate = null
                }) { Text("Nahradit") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTemplate = null }) { Text("Zrušit") }
            },
            title = { Text("Nahradit poznámku?") },
            text = { Text("Poznámka není prázdná — nahradit ji šablonou?") },
        )
    }
}
