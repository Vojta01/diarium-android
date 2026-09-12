package cz.digitalnivedomi.diarium.ui.scales

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.data.Scale
import cz.digitalnivedomi.diarium.core.data.ScaleDraft
import cz.digitalnivedomi.diarium.core.data.ScaleStats
import cz.digitalnivedomi.diarium.core.data.formatAverage
import cz.digitalnivedomi.diarium.ui.checkin.components.parseHex
import cz.digitalnivedomi.diarium.ui.checkin.components.scaleRangeHint
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import kotlinx.coroutines.launch

/**
 * Škály — správa škál (vytvoření / úprava / smazání) a rozložení hodnot za
 * posledních 30 dní.
 *
 * The visual language is the check-in's: dark glass cards
 * (`White 5%` over the dark background, `Outline` hairline border), the indigo
 * brand accent, and each scale's own `color` as its accent dot, bars and mean.
 * Nothing here touches the check-in's [cz.digitalnivedomi.diarium.ui.checkin.components.ScalesSection];
 * this screen only reuses its public helpers (`parseHex`, `scaleRangeHint`).
 *
 * States: loading, error + retry ("no silent empty screen" — a missing session
 * is not the same as a user with no scales), empty, and ready.
 */
@Composable
fun ScalesScreen(deps: ScalesDeps, modifier: Modifier = Modifier) {
    val repository = deps.scales
    val scope = rememberCoroutineScope()

    var reload by remember { mutableIntStateOf(0) }
    var state: ScalesUiState by remember { mutableStateOf(ScalesUiState.Loading) }
    var banner by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<ScaleEditor?>(null) }
    var pendingDelete by remember { mutableStateOf<Scale?>(null) }

    LaunchedEffect(repository, reload) {
        banner = null
        if (repository == null) {
            state = ScalesUiState.Error
            return@LaunchedEffect
        }
        state = ScalesUiState.Loading
        state = try {
            val scales = repository.listScales()
            ScalesUiState.Ready(scales, repository.statsFor(scales))
        } catch (t: Throwable) {
            ScalesUiState.Error
        }
    }

    val runSeed: () -> Unit = {
        val repo = repository
        if (repo == null) {
            banner = "Bez připojení nelze obnovit výchozí škály."
        } else {
            scope.launch {
                banner = null
                val ok = try {
                    repo.seedDefaults()
                } catch (t: Throwable) {
                    false
                }
                if (ok) reload++ else banner = "Obnovení výchozích škál se nepovedlo."
            }
        }
    }

    val submit: (ScaleEditor) -> Unit = { edited ->
        val repo = repository
        val draft = edited.toDraftOrNull()
        when {
            repo == null -> banner = "Bez připojení nelze ukládat."
            draft == null -> banner = "Zkontroluj název a rozsah (min < max)."
            else -> scope.launch {
                banner = null
                val ok = try {
                    if (edited.id == null) repo.createScale(draft)
                    else repo.updateScale(edited.id, draft)
                } catch (t: Throwable) {
                    false
                }
                if (ok) {
                    editor = null
                    reload++
                } else {
                    banner = "Uložení se nepovedlo."
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        ScalesHeader(
            onAdd = { editor = ScaleEditor() },
            onSeed = runSeed,
        )
        banner?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(10.dp))

        when (val current = state) {
            ScalesUiState.Loading -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Indigo)
            }

            ScalesUiState.Error -> ErrorState(
                modifier = Modifier.fillMaxWidth().weight(1f),
                onRetry = { reload++ },
            )

            is ScalesUiState.Ready -> if (current.scales.isEmpty()) {
                EmptyState(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onAdd = { editor = ScaleEditor() },
                    onSeed = runSeed,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(current.scales, key = { it.id }) { scale ->
                        ScaleCard(
                            scale = scale,
                            stats = current.stats[scale.id],
                            onEdit = { editor = ScaleEditor.from(scale) },
                            onDelete = { pendingDelete = scale },
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }

    editor?.let { edited ->
        ScaleEditorDialog(
            value = edited,
            onChange = { editor = it },
            onDismiss = { editor = null },
            onSubmit = { submit(edited) },
        )
    }

    pendingDelete?.let { scale ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Smazat škálu?") },
            text = {
                Text("Škála „${scale.name}“ se smaže. Tuto akci nelze vrátit.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        val repo = repository
                        if (repo == null) {
                            banner = "Bez připojení nelze mazat."
                        } else {
                            scope.launch {
                                banner = null
                                val ok = try {
                                    repo.deleteScale(scale.id)
                                } catch (t: Throwable) {
                                    false
                                }
                                if (ok) reload++ else banner = "Smazání se nepovedlo."
                            }
                        }
                    },
                    modifier = Modifier.testTag("scale_delete_confirm"),
                ) { Text("Smazat") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Zrušit") }
            },
        )
    }
}

// ── state ───────────────────────────────────────────────────────────────────

private sealed interface ScalesUiState {
    object Loading : ScalesUiState
    object Error : ScalesUiState
    data class Ready(val scales: List<Scale>, val stats: Map<String, ScaleStats>) : ScalesUiState
}

// ── header, cards, states ───────────────────────────────────────────────────

@Composable
private fun ScalesHeader(onAdd: () -> Unit, onSeed: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Rozložení hodnot za posledních 30 dní.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        TextButton(onClick = onSeed, modifier = Modifier.testTag("scales_seed")) {
            Text("Výchozí")
        }
        TextButton(onClick = onAdd, modifier = Modifier.testTag("scales_add")) {
            Text("＋ Přidat")
        }
    }
}

@Composable
private fun ScaleCard(
    scale: Scale,
    stats: ScaleStats?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = parseHex(scale.color, Indigo)
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Outline.copy(alpha = 0.45f), shape)
            .padding(14.dp)
            .testTag("scale_card_${scale.id}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(text = scale.emoji.ifBlank { "📊" }, fontSize = 20.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = scale.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                )
                Text(
                    text = scaleRangeHint(scale.minValue, scale.maxValue),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            TextButton(onClick = onEdit, modifier = Modifier.testTag("scale_edit_${scale.id}")) {
                Text("✏️")
            }
            TextButton(onClick = onDelete, modifier = Modifier.testTag("scale_delete_${scale.id}")) {
                Text("🗑")
            }
        }
        Spacer(Modifier.height(10.dp))
        DistributionBars(scale = scale, stats = stats, accent = accent)
    }
}

/**
 * 30-day distribution: one small bar per value of the scale's own range, the
 * count under each bar, and the mean on the right. Bars are scaled against the
 * tallest count, and a value that never occurred still gets its slot (a dimmed
 * stub with a `0`), so the reader sees the whole range at a glance.
 */
@Composable
private fun DistributionBars(scale: Scale, stats: ScaleStats?, accent: Color) {
    val min = scale.minValue
    val max = scale.maxValue
    val counts = stats?.distribution ?: emptyMap()
    val total = stats?.count ?: 0
    val maxCount = (min..max).maxOfOrNull { counts[it] ?: 0 } ?: 0

    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        if (max - min > 20) {
            Text(
                text = "Rozsah $min–$max je příliš široký pro graf.",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                (min..max).forEach { value ->
                    val count = counts[value] ?: 0
                    val fraction = if (maxCount == 0) 0f else count.toFloat() / maxCount
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(44.dp), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                modifier = Modifier
                                    .width(12.dp)
                                    .height((4f + 40f * fraction).dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (count > 0) accent else Outline.copy(alpha = 0.5f)),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "$count",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (count > 0) TextPrimary else TextTertiary,
                            fontWeight = if (count > 0) FontWeight.Medium else FontWeight.Normal,
                        )
                        Text(
                            text = "$value",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextTertiary,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (total == 0) "—" else formatAverage(stats?.average ?: 0.0),
                style = MaterialTheme.typography.titleMedium,
                color = if (total == 0) TextTertiary else accent,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (total == 0) "bez záznamů" else "průměr · $total×",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onAdd: () -> Unit, onSeed: () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Zatím žádné škály",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Vytvoř si vlastní škálu — třeba Energie 1–5 — a uvidíš, jak se hodnoty za posledních 30 dní rozkládají.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onSeed) { Text("Obnovit výchozí") }
                TextButton(onClick = onAdd, modifier = Modifier.testTag("scales_empty_add")) {
                    Text("Přidat škálu")
                }
            }
        }
    }
}

@Composable
private fun ErrorState(modifier: Modifier = Modifier, onRetry: () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Škály se nepodařilo načíst",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Nejsi přihlášený, nebo chybí připojení.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onRetry, modifier = Modifier.testTag("scales_retry")) {
                Text("Zkusit znovu")
            }
        }
    }
}

// ── editor ──────────────────────────────────────────────────────────────────

/**
 * The dialog's draft: the numeric fields stay strings while the user types, so a
 * half-typed "1" never has to be represented as a number. [id] null = creating.
 * [toDraftOrNull] is the single validation point, so the saved payload can never
 * disagree with what the preview showed.
 */
private data class ScaleEditor(
    val id: String? = null,
    val name: String = "",
    val emoji: String = "📊",
    val minValue: String = "1",
    val maxValue: String = "5",
    val color: String = "#6366F1",
    val sortOrder: String = "0",
) {

    fun toDraftOrNull(): ScaleDraft? {
        val cleanName = name.trim()
        val min = minValue.trim().toIntOrNull() ?: return null
        val max = maxValue.trim().toIntOrNull() ?: return null
        if (cleanName.isBlank() || min < 0 || max <= min) return null
        return ScaleDraft(
            name = cleanName,
            emoji = emoji.trim().ifBlank { "📊" },
            minValue = min,
            maxValue = max,
            color = color.trim().ifBlank { "#6366F1" },
            sortOrder = sortOrder.trim().toIntOrNull() ?: 0,
        )
    }

    companion object {
        fun from(scale: Scale): ScaleEditor = ScaleEditor(
            id = scale.id,
            name = scale.name,
            emoji = scale.emoji,
            minValue = scale.minValue.toString(),
            maxValue = scale.maxValue.toString(),
            color = scale.color,
            sortOrder = scale.sortOrder.toString(),
        )
    }
}

@Composable
private fun ScaleEditorDialog(
    value: ScaleEditor,
    onChange: (ScaleEditor) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    val draft = value.toDraftOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (value.id == null) "Nová škála" else "Upravit škálu") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = value.name,
                    onValueChange = { onChange(value.copy(name = it)) },
                    label = { Text("Název") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("scale_field_name"),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = value.emoji,
                        onValueChange = { onChange(value.copy(emoji = it)) },
                        label = { Text("Emoji") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("scale_field_emoji"),
                    )
                    OutlinedTextField(
                        value = value.color,
                        onValueChange = { onChange(value.copy(color = it)) },
                        label = { Text("Barva") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("scale_field_color"),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = value.minValue,
                        onValueChange = { onChange(value.copy(minValue = it.filter(Char::isDigit))) },
                        label = { Text("Min") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("scale_field_min"),
                    )
                    OutlinedTextField(
                        value = value.maxValue,
                        onValueChange = { onChange(value.copy(maxValue = it.filter(Char::isDigit))) },
                        label = { Text("Max") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("scale_field_max"),
                    )
                    OutlinedTextField(
                        value = value.sortOrder,
                        onValueChange = { onChange(value.copy(sortOrder = it.filter(Char::isDigit))) },
                        label = { Text("Pořadí") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("scale_field_sort"),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(parseHex(value.color, Indigo)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (draft != null) {
                            scaleRangeHint(draft.minValue, draft.maxValue)
                        } else {
                            "Zadej název a rozsah (min < max)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (draft != null) TextSecondary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = draft != null,
                modifier = Modifier.testTag("scale_save"),
            ) { Text("Uložit") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Zrušit") }
        },
    )
}
