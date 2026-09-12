package cz.digitalnivedomi.diarium.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import cz.digitalnivedomi.diarium.core.data.Goal
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.ui.checkin.components.GlassTextField
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassChip
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/** Goals with progress and a 🔥 streak — the app's take on the web's "Cíle". */
@Composable
fun GoalsScreen(deps: GoalsDeps, modifier: Modifier = Modifier) {
    val repository = deps.goals
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<GoalsUiState>(GoalsUiState.Loading) }
    var reloadToken by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    var editorOpen by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<Goal?>(null) }
    var deleteTarget by remember { mutableStateOf<Goal?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(reloadToken) {
        if (repository == null) {
            state = GoalsUiState.Error(MSG_NO_SESSION)
        } else {
            state = GoalsUiState.Loading
            val items = repository.loadProgress()
            state = if (items == null) GoalsUiState.Error(MSG_LOAD_FAILED) else GoalsUiState.Ready(items)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Denní, týdenní a měsíční cíle s 🔥 streakem",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
                ActionPill(text = "＋ Nový cíl", accent = IndigoLight) {
                    if (repository == null) {
                        message = MSG_NO_SESSION
                    } else {
                        editorTarget = null
                        editorOpen = true
                    }
                }
            }

            message?.let { text ->
                GlassCard(modifier = Modifier.fillMaxWidth(), accent = Danger) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(10.dp))
                    ActionPill(text = "Zavřít", accent = Danger) { message = null }
                }
            }

            when (val current = state) {
                is GoalsUiState.Loading -> LoadingCard()

                is GoalsUiState.Error -> ErrorCard(current.message) { reloadToken++ }

                is GoalsUiState.Ready -> {
                    if (current.items.isEmpty()) {
                        EmptyCard(
                            onCreate = {
                                editorTarget = null
                                editorOpen = true
                            },
                        )
                    } else {
                        SectionHeader(
                            title = "Moje cíle",
                            trailing = { GlassChip(text = "${current.items.size}") },
                        )
                        current.items.forEach { item ->
                            GoalCard(
                                item = item,
                                onEdit = {
                                    editorTarget = item.goal
                                    editorOpen = true
                                },
                                onDelete = { deleteTarget = item.goal },
                            )
                        }
                    }
                }
            }
        }

        // ── dialogs ─────────────────────────────────────────────────────────
        if (editorOpen) {
            GoalEditorDialog(
                initial = editorTarget,
                saving = saving,
                onDismiss = { if (!saving) editorOpen = false },
                onSave = { name, activityKey, targetCount, frequency ->
                    val repo = repository
                    if (repo == null) {
                        message = MSG_NO_SESSION
                    } else {
                        saving = true
                        val editing = editorTarget
                        scope.launch {
                            val ok = if (editing == null) {
                                repo.create(name, activityKey, targetCount, frequency)
                            } else {
                                repo.update(editing.id, name, activityKey, targetCount, frequency)
                            }
                            saving = false
                            if (ok) {
                                editorOpen = false
                                editorTarget = null
                                reloadToken++
                            } else {
                                message = MSG_SAVE_FAILED
                            }
                        }
                    }
                },
            )
        }

        deleteTarget?.let { goal ->
            DeleteGoalDialog(
                goal = goal,
                onDismiss = { deleteTarget = null },
                onConfirm = {
                    val repo = repository
                    if (repo == null) {
                        message = MSG_NO_SESSION
                        deleteTarget = null
                    } else {
                        scope.launch {
                            val ok = repo.delete(goal.id)
                            deleteTarget = null
                            if (ok) reloadToken++ else message = MSG_DELETE_FAILED
                        }
                    }
                },
            )
        }
    }
}

// ── Screen states ───────────────────────────────────────────────────────────

private sealed interface GoalsUiState {
    object Loading : GoalsUiState
    data class Error(val message: String) : GoalsUiState
    data class Ready(val items: List<GoalProgress>) : GoalsUiState
}

@Composable
private fun LoadingCard() {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Text(text = "⏳", fontSize = 26.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Načítám cíle…",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Danger) {
        Text(text = "⚠️", fontSize = 26.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Cíle se nepodařilo načíst",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        ActionPill(text = "Zkusit znovu", accent = IndigoLight, onClick = onRetry)
    }
}

@Composable
private fun EmptyCard(onCreate: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Text(text = "🎯", fontSize = 30.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Zatím žádné cíle",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Vytvoř si první cíl — třeba 3× týdně běhat. Appka pak počítá dny v období a 🔥 streak.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        ActionPill(text = "＋ Přidat cíl", accent = IndigoLight, onClick = onCreate)
    }
}

// ── Goal card ───────────────────────────────────────────────────────────────

@Composable
private fun GoalCard(item: GoalProgress, onEdit: () -> Unit, onDelete: () -> Unit) {
    val met = item.targetMet
    val accent = if (met) SuccessGreen else Indigo

    GlassCard(modifier = Modifier.fillMaxWidth(), accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = emojiFor(item.goal.activityKey), fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.goal.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "aktivita: ${item.goal.activityKey}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            GlassChip(text = item.periodLabel, accent = Indigo)
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${item.count}/${item.goal.targetCount}",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                text = if (met) "cíl splněn ✅" else "zbývá ${item.remaining}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = if (met) SuccessGreen else TextSecondary,
            )
        }

        Spacer(Modifier.height(8.dp))

        GoalProgressBar(fraction = item.fraction, accent = accent)

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "streak 🔥 ${item.streak} dní",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (item.streak > 0) IndigoLight else TextSecondary,
            )
        }

        Spacer(Modifier.height(12.dp))
        GlassDivider()
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionPill(text = "✏️ Upravit", accent = IndigoLight, onClick = onEdit)
            ActionPill(text = "🗑 Smazat", accent = Danger, onClick = onDelete)
        }
    }
}

/** Thin hand-rolled bar so the track never overflows and fills exactly. */
@Composable
private fun GoalProgressBar(fraction: Float, accent: Color) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f)),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(shape)
                    .background(accent),
            )
        }
    }
}

// ── Editor / delete dialogs ─────────────────────────────────────────────────

@Composable
private fun GoalEditorDialog(
    initial: Goal?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, activityKey: String, targetCount: Int, frequency: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var activityKey by remember { mutableStateOf(initial?.activityKey.orEmpty()) }
    var targetText by remember { mutableStateOf((initial?.targetCount ?: 3).toString()) }
    var frequency by remember { mutableStateOf(initial?.frequency ?: "weekly") }

    val targetCount = targetText.trim().toIntOrNull() ?: 0
    val valid = name.isNotBlank() && activityKey.isNotBlank() && targetCount > 0

    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
            Text(
                text = if (initial == null) "🎯 Nový cíl" else "✏️ Upravit cíl",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel("Název")
            Spacer(Modifier.height(6.dp))
            GlassTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Např. Běhání",
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("Aktivita (klíč)")
            Spacer(Modifier.height(6.dp))
            GlassTextField(
                value = activityKey,
                onValueChange = { activityKey = it },
                placeholder = "Např. beh",
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Musí odpovídat klíči aktivity v deníku.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("Cíl (počet dní)")
            Spacer(Modifier.height(6.dp))
            GlassTextField(
                value = targetText,
                onValueChange = { targetText = it.filter { c -> c.isDigit() } },
                placeholder = "3",
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("Období")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FREQUENCIES.forEach { (value, label) ->
                    ChoiceChip(
                        text = label,
                        selected = frequency == value,
                        onClick = { frequency = value },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(
                    text = if (saving) "Ukládám…" else "Uložit",
                    accent = if (valid && !saving) IndigoLight else Color.White.copy(alpha = 0.3f),
                ) {
                    if (valid && !saving) onSave(name, activityKey, targetCount, frequency)
                }
                ActionPill(text = "Zrušit", accent = TextSecondary, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun DeleteGoalDialog(goal: Goal, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Danger) {
            Text(
                text = "🗑 Smazat cíl?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = goal.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Cíl se smaže i s jeho postupem. Tahle akce je nevratná.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(text = "Smazat", accent = Danger, onClick = onConfirm)
                ActionPill(text = "Zrušit", accent = TextSecondary, onClick = onDismiss)
            }
        }
    }
}

// ── Small shared pieces ─────────────────────────────────────────────────────

/** Small glass pill used as an action; [accent] tints fill, border and label. */
@Composable
private fun ActionPill(
    text: String,
    accent: Color = Indigo,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.45f), CircleShape)
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

/** Selectable pill for the frequency row. */
@Composable
private fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val accent = if (selected) Indigo else Color.White.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) Indigo.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, accent.copy(alpha = if (selected) 0.75f else 0.25f), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) TextPrimary else TextSecondary,
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = TextSecondary,
    )
}

// ── Constants ───────────────────────────────────────────────────────────────

private val Danger = Color(0xFFF87171)

private val FREQUENCIES = listOf(
    "daily" to "den",
    "weekly" to "týden",
    "monthly" to "měsíc",
)

private const val MSG_NO_SESSION = "Chybí přihlášení — cíle se nepodařilo načíst."
private const val MSG_LOAD_FAILED = "Zkontroluj připojení a zkus to znovu."
private const val MSG_SAVE_FAILED = "Cíl se nepodařilo uložit. Zkus to znovu."
private const val MSG_DELETE_FAILED = "Cíl se nepodařilo smazat. Zkus to znovu."

/** Tiny activity-key → emoji map; unknown keys get the goals target. */
private fun emojiFor(activityKey: String): String = when (activityKey.trim().lowercase()) {
    "beh", "behani", "běh", "běhání", "run", "running" -> "🏃"
    "spanek", "spánek", "sleep" -> "😴"
    "cteni", "čtení", "reading", "cten" -> "📚"
    "cviceni", "cvičení", "gym", "workout" -> "💪"
    "meditace", "meditation" -> "🧘"
    "voda", "water" -> "💧"
    "prochazka", "procházka", "walk" -> "🚶"
    "posilovna" -> "🏋️"
    "hacking", "programovani", "programování", "coding" -> "💻"
    else -> "🎯"
}
