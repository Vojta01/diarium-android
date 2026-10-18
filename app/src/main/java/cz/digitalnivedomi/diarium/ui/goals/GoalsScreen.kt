package cz.digitalnivedomi.diarium.ui.goals

import cz.digitalnivedomi.diarium.ui.i18n.tr
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animateFloatAsState
import cz.digitalnivedomi.diarium.ui.i18n.Text
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
import cz.digitalnivedomi.diarium.core.data.ActivityDef
import cz.digitalnivedomi.diarium.core.data.Goal
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.core.data.HabitDef
import cz.digitalnivedomi.diarium.ui.checkin.components.GlassTextField
import cz.digitalnivedomi.diarium.ui.checkin.components.SelectableChip
import cz.digitalnivedomi.diarium.ui.checkin.components.labelWithIcon
import cz.digitalnivedomi.diarium.ui.components.FrequencyDropdown
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassChip
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import androidx.compose.ui.graphics.Brush
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/** Goals with progress and a 🔥 streak — the app's take on the web's "Cíle". */
@Composable
fun GoalsScreen(deps: GoalsDeps, modifier: Modifier = Modifier) {
    val repository = deps.goals
    val pickers = deps.pickers
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<GoalsUiState>(GoalsUiState.Loading) }
    var reloadToken by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    var editorOpen by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<Goal?>(null) }
    var deleteTarget by remember { mutableStateOf<Goal?>(null) }
    var saving by remember { mutableStateOf(false) }
    var activities by remember { mutableStateOf<List<ActivityDef>>(emptyList()) }
    var habits by remember { mutableStateOf<List<HabitDef>>(emptyList()) }

    LaunchedEffect(reloadToken) {
        if (repository == null) {
            state = GoalsUiState.Error(MSG_NO_SESSION)
        } else {
            state = GoalsUiState.Loading
            val items = repository.loadProgress()
            state = if (items == null) GoalsUiState.Error(MSG_LOAD_FAILED) else GoalsUiState.Ready(items)
        }
    }

    // Labels and icons for the cards and the pickers (activities + habits). Read
    // only with a session; a failed read comes back as the repository's static
    // fallback list, and every lookup then falls back to the stored key instead
    // of hiding the goal.
    LaunchedEffect(reloadToken) {
        if (repository != null) {
            activities = pickers.activities()
            habits = pickers.habits()
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
                                activities = activities,
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
                activities = activities,
                habits = habits,
                canCreateActivity = repository != null,
                saving = saving,
                onDismiss = { if (!saving) editorOpen = false },
                onCreateActivity = { label, onCreated ->
                    scope.launch {
                        val added = pickers.addActivity(label)
                        if (!added) {
                            onCreated(null)
                        } else {
                            // Re-read so the created activity's real key comes back
                            // from the repository instead of being guessed here.
                            val fresh = pickers.activities()
                            activities = fresh
                            val clean = label.trim()
                            onCreated(fresh.firstOrNull { it.label.equals(clean, ignoreCase = true) })
                        }
                    }
                },
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
private fun GoalCard(
    item: GoalProgress,
    activities: List<ActivityDef>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val met = item.targetMet
    val accent = if (met) SuccessGreen else Indigo
    // A missing/renamed activity falls back to the stored key instead of vanishing.
    val def = activities.firstOrNull { it.key.equals(item.goal.activityKey, ignoreCase = true) }
    val icon = def?.icon?.takeIf { it.isNotBlank() } ?: emojiFor(item.goal.activityKey)
    val label = def?.label ?: item.goal.activityKey

    GlassCard(modifier = Modifier.fillMaxWidth(), accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 22.sp)
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
                    text = "aktivita: $label",
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
    val target = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = target, label = "goalProgress")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f)),
    ) {
        if (animated > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(Brush.horizontalGradient(listOf(accent, IndigoLight))),
            )
        }
    }
}

// ── Editor / delete dialogs ─────────────────────────────────────────────────

@Composable
private fun GoalEditorDialog(
    initial: Goal?,
    activities: List<ActivityDef>,
    habits: List<HabitDef>,
    canCreateActivity: Boolean,
    saving: Boolean,
    onDismiss: () -> Unit,
    onCreateActivity: (label: String, onCreated: (ActivityDef?) -> Unit) -> Unit,
    onSave: (name: String, activityKey: String, targetCount: Int, frequency: String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    // Only a brand-new goal auto-fills its name; a manual edit wins from then on.
    var nameTouched by remember { mutableStateOf(initial != null) }
    var activityKey by remember { mutableStateOf(initial?.activityKey.orEmpty()) }
    var targetText by remember { mutableStateOf((initial?.targetCount ?: 3).toString()) }
    var frequency by remember { mutableStateOf(initial?.frequency ?: "weekly") }

    var pickerOpen by remember { mutableStateOf(false) }
    var newActivityOpen by remember { mutableStateOf(false) }
    var creatingActivity by remember { mutableStateOf(false) }
    var activityError by remember { mutableStateOf<String?>(null) }

    val selected = activities.firstOrNull { it.key.equals(activityKey, ignoreCase = true) }

    /** A picked or freshly created activity sets the key; a fresh goal takes its name. */
    fun applyPicked(def: ActivityDef) {
        activityKey = def.key
        if (initial == null && (name.isBlank() || !nameTouched)) name = def.label
    }

    val targetCount = targetText.trim().toIntOrNull() ?: 0
    val valid = name.isNotBlank() && activityKey.isNotBlank() && targetCount > 0

    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo, opaque = true) {
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
                onValueChange = {
                    nameTouched = true
                    name = it
                },
                placeholder = tr("Např. Běhání"),
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("Aktivita")
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        activityKey.isBlank() -> "Nevybráno"
                        selected != null -> labelWithIcon(selected.icon, selected.label)
                        else -> activityKey
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (activityKey.isBlank()) TextSecondary else TextPrimary,
                )
                Spacer(Modifier.width(8.dp))
                ActionPill(text = "Vybrat", accent = IndigoLight) { pickerOpen = true }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Cíl se plní, když si zvolenou aktivitu zapíšeš v deníku.",
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
            FrequencyDropdown(
                value = frequency,
                onValueChange = { frequency = it },
                testTag = "goal_editor_frequency",
            )

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

    if (pickerOpen) {
        ActivityPickerDialog(
            activities = activities,
            habits = habits,
            pickedKey = activityKey,
            canCreate = canCreateActivity && !saving,
            onCreateNew = {
                pickerOpen = false
                activityError = null
                newActivityOpen = true
            },
            onPick = { def ->
                applyPicked(def)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }

    if (newActivityOpen) {
        NewActivityDialog(
            saving = creatingActivity,
            error = activityError,
            onDismiss = {
                if (!creatingActivity) {
                    newActivityOpen = false
                    activityError = null
                }
            },
            onCreate = { label ->
                activityError = null
                creatingActivity = true
                onCreateActivity(label) { created ->
                    creatingActivity = false
                    if (created == null) {
                        activityError = MSG_ACTIVITY_FAILED
                    } else {
                        newActivityOpen = false
                        applyPicked(created)
                    }
                }
            },
        )
    }
}

/** Picker list with a fixed ceiling so it never outgrows the screen; scrolls instead. */
@Composable
private fun ActivityPickerDialog(
    activities: List<ActivityDef>,
    habits: List<HabitDef>,
    pickedKey: String,
    canCreate: Boolean,
    onCreateNew: () -> Unit,
    onPick: (ActivityDef) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo, opaque = true) {
            Text(
                text = "Vybrat aktivitu",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (canCreate) {
                    PickerRow(
                        text = "＋ Nová aktivita",
                        accent = SuccessGreen,
                        onClick = onCreateNew,
                    )
                }
                if (activities.isEmpty()) {
                    Text(
                        text = "Žádné aktivity k výběru.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                } else {
                    activities.forEach { def ->
                        PickerRow(
                            text = labelWithIcon(def.icon, def.label),
                            accent = Indigo,
                            onClick = { onPick(def) },
                        )
                    }
                }

                // Habits double as goals: picking one stores its key as the goal's
                // activity, and a fresh goal auto-fills its name from the label.
                if (habits.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    FieldLabel("Návyky")
                    habits.forEach { habit ->
                        SelectableChip(
                            text = habit.label,
                            selected = pickedKey.equals(habit.key, ignoreCase = true),
                            icon = habit.icon,
                            testTag = "goal_habit_${habit.key}",
                            onClick = {
                                onPick(
                                    ActivityDef(
                                        key = habit.key,
                                        label = habit.label,
                                        icon = habit.icon,
                                        category = habit.category,
                                    ),
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            ActionPill(text = "Zrušit", accent = TextSecondary, onClick = onDismiss)
        }
    }
}

/** Full-width tappable row inside the activity picker. */
@Composable
private fun PickerRow(text: String, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.30f), shape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        )
    }
}

/** Creates a custom activity; the created row comes back with its real key. */
@Composable
private fun NewActivityDialog(
    saving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (label: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    val valid = label.isNotBlank() && !saving

    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo, opaque = true) {
            Text(
                text = "＋ Nová aktivita",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )

            Spacer(Modifier.height(14.dp))
            FieldLabel("Název aktivity")
            Spacer(Modifier.height(6.dp))
            GlassTextField(
                value = label,
                onValueChange = { label = it },
                placeholder = tr("Např. Jóga"),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Přidá se do deníku a rovnou se vybere pro tenhle cíl.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )

            error?.let { text ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = Danger,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionPill(
                    text = if (saving) "Přidávám…" else "Přidat",
                    accent = if (valid) IndigoLight else Color.White.copy(alpha = 0.3f),
                ) {
                    if (valid) onCreate(label)
                }
                ActionPill(text = "Zrušit", accent = TextSecondary, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun DeleteGoalDialog(goal: Goal, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Danger, opaque = true) {
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

private const val MSG_NO_SESSION = "Chybí přihlášení — cíle se nepodařilo načíst."
private const val MSG_LOAD_FAILED = "Zkontroluj připojení a zkus to znovu."
private const val MSG_SAVE_FAILED = "Cíl se nepodařilo uložit. Zkus to znovu."
private const val MSG_DELETE_FAILED = "Cíl se nepodařilo smazat. Zkus to znovu."
private const val MSG_ACTIVITY_FAILED = "Aktivitu se nepodařilo přidat. Zkus to znovu."

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
