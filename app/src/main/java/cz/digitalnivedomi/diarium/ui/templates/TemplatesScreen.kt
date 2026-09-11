package cz.digitalnivedomi.diarium.ui.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.TemplateDefaults
import cz.digitalnivedomi.diarium.core.data.TemplateItem
import cz.digitalnivedomi.diarium.core.data.firstLinePreview
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** Glass card on the dark theme; the accent is the app's indigo #6366f1. */
private val Accent = Color(0xFF6366F1)
private val CardFill = Color.White.copy(alpha = 0.05f)
private val CardStroke = Color.White.copy(alpha = 0.08f)

private const val NO_SESSION = "Nejsi přihlášený — šablony teď nejde načíst."
private const val LOAD_FAILED = "Šablony se nepodařilo načíst. Zkontroluj připojení a zkus to znovu."

/** Nothing to edit yet (new template) vs. editing an existing row. */
private data class TemplateEdit(val id: String?, val name: String, val content: String)

/**
 * Šablony poznámek — list, create/edit and delete, matching the web 1:1.
 *
 * The screen owns no session of its own: everything it reads or writes goes
 * through [deps], so `TemplatesDeps.offline()` composes the error state instead
 * of a list it cannot vouch for.
 *
 * A template's content is what the check-in inserts, and inserting always
 * REPLACES the note — see `applyTemplate` in `TemplatesRepository.kt`.
 */
@Composable
fun TemplatesScreen(deps: TemplatesDeps, modifier: Modifier = Modifier) {
    val repo = deps.templates

    var items by remember { mutableStateOf<List<TemplateItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<TemplateEdit?>(null) }
    var pendingDelete by remember { mutableStateOf<TemplateItem?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        if (repo == null) {
            items = emptyList()
            loading = false
            error = NO_SESSION
            return
        }
        loading = true
        error = null
        try {
            items = repo.list()
        } catch (t: Throwable) {
            items = emptyList()
            error = LOAD_FAILED
        }
        loading = false
    }

    LaunchedEffect(repo) { load() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Šablony",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Vložíš je do poznámky v deníčku — vložení celou poznámku nahradí.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(14.dp))

        when {
            loading -> LoadingRow()

            error != null -> ErrorCard(
                message = error!!,
                onRetry = { scope.launch { load() } },
            )

            items.isEmpty() -> EmptyCard(
                onSeed = {
                    scope.launch {
                        busy = true
                        runCatching { repo?.seedDefaults() }
                        busy = false
                        load()
                    }
                },
                busy = busy,
            )

            else -> {
                items.forEach { item ->
                    TemplateCard(
                        item = item,
                        onEdit = { editor = TemplateEdit(item.id, item.name, item.content) },
                        onDelete = { pendingDelete = item },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(text = "＋ Nová šablona", primary = true, enabled = repo != null) {
                editor = TemplateEdit(null, "", "")
            }
            if (items.isNotEmpty()) {
                ActionButton(text = "Vložit výchozí", enabled = repo != null && !busy) {
                    scope.launch {
                        busy = true
                        runCatching { repo?.seedDefaults() }
                        busy = false
                        load()
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    editor?.let { edit ->
        TemplateEditorDialog(
            edit = edit,
            onDismiss = { editor = null },
            onSave = { name, content ->
                val target = edit
                scope.launch {
                    busy = true
                    val ok = runCatching {
                        if (target.id == null) repo?.create(name, content)
                        else repo?.update(target.id, name, content)
                    }.getOrNull() ?: false
                    busy = false
                    if (ok) {
                        editor = null
                        load()
                    } else {
                        error = "Šablonu se nepodařilo uložit. Zkus to znovu."
                    }
                }
            },
            saving = busy,
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        runCatching { repo?.delete(item.id) }
                        busy = false
                        pendingDelete = null
                        load()
                    }
                }) { Text("Smazat", color = Color(0xFFEF4444)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Zrušit") }
            },
            title = { Text("Smazat šablonu?") },
            text = { Text("Opravdu chceš šablonu „${item.name}“ smazat? Nedá se to vrátit.") },
        )
    }
}

// ── pieces ──────────────────────────────────────────────────────────────────

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = Accent)
        Spacer(Modifier.width(8.dp))
        Text("Načítám šablony…", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    GlassCard {
        Text(
            text = "Něco se nepovedlo",
            style = MaterialTheme.typography.titleSmall,
            color = Color(0xFFFCA5A5),
        )
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(Modifier.height(12.dp))
        ActionButton(text = "Zkusit znovu", primary = true, onClick = onRetry)
    }
}

@Composable
private fun EmptyCard(onSeed: () -> Unit, busy: Boolean) {
    GlassCard {
        Text(
            text = "Zatím žádné šablony",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Šablona je hotová kostra poznámky — vložíš ji jedním klepnutím " +
                "do dnešního zápisu a nemusíš pořád psát to samé.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Výchozí: " + TemplateDefaults.DEFAULTS.joinToString(", ") { it.name },
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        ActionButton(
            text = if (busy) "Vkládám…" else "Vložit výchozí šablony",
            primary = true,
            enabled = !busy,
            onClick = onSeed,
        )
    }
}

@Composable
private fun TemplateCard(item: TemplateItem, onEdit: () -> Unit, onDelete: () -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                val created = item.createdAt?.take(10)
                if (created != null) {
                    Text(
                        text = "Vytvořeno $created",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = firstLinePreview(item.content).ifBlank { "(prázdná šablona)" },
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            maxLines = 2,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(text = "Upravit", onClick = onEdit)
            ActionButton(text = "Smazat", onClick = onDelete)
        }
    }
}

@Composable
private fun TemplateEditorDialog(
    edit: TemplateEdit,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    saving: Boolean,
) {
    var name by remember(edit) { mutableStateOf(edit.name) }
    var content by remember(edit) { mutableStateOf(edit.content) }
    val valid = name.isNotBlank() && content.isNotBlank() && !saving

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(name, content) }, enabled = valid) {
                Text(if (edit.id == null) "Vytvořit" else "Uložit", color = if (valid) Accent else TextSecondary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Zrušit", color = TextSecondary) }
        },
        title = { Text(if (edit.id == null) "Nová šablona" else "Upravit šablonu") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Název") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Obsah šablony") },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Tohle se vloží do poznámky místo jejího současného textu.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        },
    )
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardFill)
            .border(1.dp, CardStroke, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun ActionButton(
    text: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (primary) Accent.copy(alpha = if (enabled) 1f else 0.4f)
                else Color.White.copy(alpha = if (enabled) 0.06f else 0.03f),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) Color.White else TextPrimary,
        )
    }
}
