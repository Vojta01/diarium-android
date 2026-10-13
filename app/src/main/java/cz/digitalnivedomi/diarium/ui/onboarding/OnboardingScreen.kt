package cz.digitalnivedomi.diarium.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import cz.digitalnivedomi.diarium.core.data.ActivityDef
import cz.digitalnivedomi.diarium.core.data.HabitDef
import cz.digitalnivedomi.diarium.ui.checkin.components.GlassIconButton
import cz.digitalnivedomi.diarium.ui.checkin.components.GlassTextField
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.checkin.components.SelectableChip
import cz.digitalnivedomi.diarium.ui.checkin.components.TogglePill
import cz.digitalnivedomi.diarium.ui.components.BrandMark
import cz.digitalnivedomi.diarium.ui.components.FrequencyDropdown
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlowDisc
import cz.digitalnivedomi.diarium.ui.components.ScreenBackdrop
import cz.digitalnivedomi.diarium.ui.components.StaggeredItem
import cz.digitalnivedomi.diarium.ui.components.accentGlow
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Gradients
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.Violet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Czech label for a stored frequency value. */
private fun frequencyLabel(frequency: String): String = when (frequency) {
    "daily" -> "denně"
    "monthly" -> "měsíčně"
    else -> "týdně"
}

/**
 * The first-run wizard: 4 steps (Profil → AI → Návyky → Cíle) over the app's
 * backdrop/glass language, driven entirely by [OnboardingState] for what the user
 * picked.
 *
 * This screen owns the effects the state deliberately does not: the habit and
 * activity catalogue reads, the custom habit/activity/goal dialogs, and the
 * finish step, which writes the profile, the optional AI key, the habit
 * hide/restore plan and the goal plan through the repositories — every repo call
 * on [Dispatchers.IO], then [onFinished] once the writes have run.
 *
 * Nothing here is fatal: the finish step runs inside `runCatching`, so a flaky
 * network can delay a write but can never trap the user in the wizard.
 */
@Composable
fun OnboardingScreen(
    deps: OnboardingDeps,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember { OnboardingState() }
    val scope = rememberCoroutineScope()

    /** Step 2's choice; "Přeskočit (bez AI)" sets it false, the key path true. */
    var aiEnabled by remember { mutableStateOf(true) }
    var finishing by remember { mutableStateOf(false) }
    var habitsLoaded by remember { mutableStateOf(false) }

    // Custom-habit dialog.
    var showHabitDialog by remember { mutableStateOf(false) }
    var habitName by remember { mutableStateOf("") }
    var savingHabit by remember { mutableStateOf(false) }

    // Custom-goal dialog.
    var showGoalDialog by remember { mutableStateOf(false) }
    var goalName by remember { mutableStateOf("") }
    var goalTarget by remember { mutableStateOf("3") }
    var goalFrequency by remember { mutableStateOf("weekly") }
    var goalActivityKey by remember { mutableStateOf<String?>(null) }
    var activities by remember { mutableStateOf<List<ActivityDef>>(emptyList()) }
    var goalHabits by remember { mutableStateOf<List<HabitDef>>(emptyList()) }
    var newActivityName by remember { mutableStateOf("") }
    var showingNewActivity by remember { mutableStateOf(false) }
    var addingActivity by remember { mutableStateOf(false) }

    // Profile prefill (a restart of the guide must not wipe what is stored) and the
    // habit catalogue, both off the main thread.
    LaunchedEffect(deps) {
        val profile = withContext(Dispatchers.IO) { deps.profile.load() }
        if (profile.name.isNotBlank()) state.name = profile.name
        profile.birthYear?.let { state.birthYear = it.toString() }
        aiEnabled = profile.aiEnabled
        // Owner decision: the wizard only ever offers the catalogue's standard habits
        // (`source == "default"`) — personal/custom habits are never offered as toggles
        // here. Habits created in this wizard run are appended by `addCustomHabit`, so
        // they stay visible.
        state.loadHabits(
            withContext(Dispatchers.IO) { deps.pickers.habits() }
                .filter { it.source == "default" },
        )
        habitsLoaded = true
    }

    // The goal dialog's activity and habit pickers read the catalogue each time it opens.
    LaunchedEffect(showGoalDialog) {
        if (showGoalDialog) {
            activities = withContext(Dispatchers.IO) { deps.pickers.activities() }
            goalHabits = withContext(Dispatchers.IO) { deps.pickers.habits() }
        }
    }

    /** Step 2 primary: persist the key now (finish writes it again if it is still there). */
    fun continueWithKey() {
        aiEnabled = true
        val key = state.aiKey
        if (key.isNotBlank()) {
            scope.launch { withContext(Dispatchers.IO) { deps.aiKeys.saveKey(key) } }
        }
        state.next()
    }

    /**
     * „Dokončit": the whole write plan of the wizard, in the order the state's docs
     * promise — profile, AI flag + key, habits (hide / restore), goals — then
     * [onFinished]. Guarded so a double tap cannot create goals twice.
     */
    fun finish() {
        if (finishing) return
        finishing = true
        val name = state.name
        val birthYear = state.birthYearValue()
        val key = state.aiKey
        val enableAi = aiEnabled
        val habitsToHide = state.habitsToHide().map { it.toHabitDef() }
        val habitsToRestore = state.habitsToRestore()
        scope.launch {
            runCatching {
                val existingGoalKeys = withContext(Dispatchers.IO) {
                    deps.profile.saveName(name)
                    deps.profile.saveBirthYear(birthYear)
                    deps.profile.setAiEnabled(enableAi)
                    deps.profile.setOnboardingDone(true)
                    if (key.isNotBlank()) deps.aiKeys.saveKey(key)
                    habitsToHide.forEach { deps.pickers.removeHabit(it) }
                    habitsToRestore.forEach { choice ->
                        deps.pickers.updateHabit(choice.key, choice.label, choice.icon, choice.isNegative)
                    }
                    deps.goals.list()?.map { it.activityKey }?.toSet() ?: emptySet()
                }
                val toCreate = state.goalsToCreate(existingGoalKeys)
                withContext(Dispatchers.IO) {
                    toCreate.forEach { goal ->
                        deps.goals.create(goal.name, goal.activityKey, goal.targetCount, goal.frequency)
                    }
                }
            }
            finishing = false
            onFinished()
        }
    }

    // The backdrop tint follows the step (staying inside the brand ramp), so the
    // guide reads as one continuous surface while moving through it.
    val stepAccent by animateColorAsState(
        targetValue = when (state.step) {
            2 -> Violet
            3 -> IndigoLight
            else -> Indigo
        },
        label = "stepAccent",
    )

    ScreenBackdrop(modifier = modifier.fillMaxSize(), accent = stepAccent) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 40.dp, bottom = 28.dp),
        ) {
            // Hero: the brand mark over a gradient wordmark, the step counter
            // underneath. The back arrow (steps 2–4) rides its own row above, so the
            // mark itself never shifts between steps.
            StaggeredItem(index = 0) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.step > 1) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            GlassIconButton(text = "←", testTag = "onboarding_back") { state.back() }
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                    GlowDisc(accent = Indigo, size = 88.dp) {
                        BrandMark(size = 64.dp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Vítej v Diarium",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            brush = Brush.linearGradient(Gradients.brand),
                        ),
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Krok ${state.step} ze ${OnboardingState.STEP_COUNT}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // Progress pills: past + current lit; width and colour animate, so a step
            // change reads as motion, not as a redraw.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(OnboardingState.STEP_COUNT) { index ->
                    val done = index + 1 <= state.step
                    val current = index + 1 == state.step
                    val pillWidth by animateDpAsState(
                        targetValue = if (current) 22.dp else 8.dp,
                        label = "pillWidth",
                    )
                    val pillColor by animateColorAsState(
                        targetValue = if (done) Indigo else Color.White.copy(alpha = 0.16f),
                        label = "pillColor",
                    )
                    if (index > 0) Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .width(pillWidth)
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(pillColor),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            // The step body: slides in from the side the flow is moving toward, so
            // forward and back are distinguishable at a glance.
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    val forward = targetState > initialState
                    val enter = slideInHorizontally { if (forward) it / 5 else -it / 5 } + fadeIn()
                    val exit = slideOutHorizontally { if (forward) -it / 5 else it / 5 } + fadeOut()
                    enter togetherWith exit
                },
                label = "onboardingStep",
            ) { step ->
                when (step) {
                // ── Step 1: Profil ──────────────────────────────────────────────
                1 -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "👤 Profil",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    SectionHint("Pojďme si tě nastavit. Jméno a rok narození používá AI pro osobnější reflexe — zůstávají jen v tomto telefonu.")
                    Spacer(Modifier.height(14.dp))
                    GlassTextField(
                        value = state.name,
                        onValueChange = { state.name = it },
                        placeholder = "Jméno",
                        testTag = "onboarding_name",
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassTextField(
                        value = state.birthYear,
                        onValueChange = { raw -> state.birthYear = raw.filter { it.isDigit() }.take(4) },
                        placeholder = "Rok narození",
                        testTag = "onboarding_birth_year",
                    )
                    if (!state.birthYearValid) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Zadej rok mezi 1900 a 2100.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorRed,
                            modifier = Modifier.testTag("onboarding_birth_year_error"),
                        )
                    }
                }

                // ── Step 2: AI (BYOK) ───────────────────────────────────────────
                2 -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "🤖 AI",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    SectionHint("AI umí reflexi po check-inu a týdenní i měsíční přehledy.")
                    Spacer(Modifier.height(4.dp))
                    SectionHint("Používá vlastní klíč z platform.deepseek.com — zdarma a bezpečně v telefonu.")
                    Spacer(Modifier.height(14.dp))
                    GlassTextField(
                        value = state.aiKey,
                        onValueChange = { state.aiKey = it },
                        placeholder = "API klíč (sk-…)",
                        testTag = "onboarding_ai_key",
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { deps.openUrl("https://platform.deepseek.com/api_keys") },
                        modifier = Modifier.testTag("onboarding_deepseek_link"),
                    ) {
                        Text("🔑 Získat klíč na platform.deepseek.com")
                    }
                }

                // ── Step 3: Návyky ──────────────────────────────────────────────
                3 -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "✅ Návyky",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    SectionHint("Nech si zapnuté to, co chceš sledovat. Vypnuté návyky schováme.")
                    Spacer(Modifier.height(14.dp))
                    if (!habitsLoaded) {
                        Text(
                            text = "Načítám návyky…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    } else {
                        state.habits.forEachIndexed { index, choice ->
                            if (index > 0) Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(text = choice.icon.ifBlank { "•" }, fontSize = 18.sp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = choice.label,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                )
                                TogglePill(
                                    checked = choice.checked,
                                    testTag = "onboarding_habit_${choice.key}",
                                ) { state.toggleHabit(choice.key) }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            habitName = ""
                            showHabitDialog = true
                        },
                        modifier = Modifier.testTag("onboarding_add_habit"),
                    ) {
                        Text("＋ Vlastní návyk")
                    }
                }

                // ── Step 4: Cíle ────────────────────────────────────────────────
                else -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "🎯 Cíle",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    SectionHint("Vyber si cíle, které chceš sledovat — můžeš je kdykoli upravit.")
                    Spacer(Modifier.height(14.dp))
                    state.templates.forEachIndexed { index, template ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        val activityKey = template.activityKey
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = template.emoji, fontSize = 18.sp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = template.baseName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                            )
                            GlassTextField(
                                value = template.targetCount.toString(),
                                onValueChange = { raw ->
                                    // Empty or non-numeric input is ignored: the field
                                    // then snaps back to the last valid count.
                                    raw.filter { it.isDigit() }.take(2).toIntOrNull()?.let { count ->
                                        state.updateTemplateCount(activityKey, count)
                                    }
                                },
                                placeholder = "3",
                                modifier = Modifier.width(56.dp),
                                testTag = "onboarding_template_count_$activityKey",
                            )
                            Spacer(Modifier.width(6.dp))
                            FrequencyDropdown(
                                value = template.frequency,
                                onValueChange = { state.updateTemplateFrequency(activityKey, it) },
                                testTag = "onboarding_template_frequency_$activityKey",
                            )
                            Spacer(Modifier.width(6.dp))
                            TogglePill(
                                checked = template.checked,
                                testTag = "onboarding_template_$activityKey",
                            ) { state.toggleTemplate(activityKey) }
                        }
                    }
                    if (state.customGoals.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        state.customGoals.forEachIndexed { index, goal ->
                            if (index > 0) Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = goal.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary,
                                )
                                Text(
                                    text = "${goal.targetCount}× ${frequencyLabel(goal.frequency)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "✕",
                                    modifier = Modifier
                                        .clickable { state.removeCustomGoal(index) }
                                        .padding(6.dp),
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            goalName = ""
                            goalTarget = "3"
                            goalFrequency = "weekly"
                            goalActivityKey = null
                            showingNewActivity = false
                            newActivityName = ""
                            showGoalDialog = true
                        },
                        modifier = Modifier.testTag("onboarding_add_goal"),
                    ) {
                        Text("＋ Vlastní cíl")
                    }
                }
            }
            }

            Spacer(Modifier.height(22.dp))

            // ── Bottom actions ──────────────────────────────────────────────────
            when (state.step) {
                1 -> WizardPrimaryButton(
                    text = "Pokračovat",
                    enabled = state.birthYearValid,
                    testTag = "onboarding_continue",
                ) { state.next() }

                2 -> {
                    WizardPrimaryButton(
                        text = "Uložit klíč a pokračovat",
                        testTag = "onboarding_save_key",
                    ) { continueWithKey() }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            aiEnabled = false
                            state.next()
                        },
                        modifier = Modifier.fillMaxWidth().testTag("onboarding_skip_ai"),
                    ) {
                        Text("Přeskočit (bez AI)")
                    }
                }

                3 -> WizardPrimaryButton(
                    text = "Pokračovat",
                    testTag = "onboarding_continue_habits",
                ) { state.next() }

                else -> WizardPrimaryButton(
                    text = if (finishing) "⏳ Ukládám…" else "Dokončit",
                    enabled = !finishing,
                    testTag = "onboarding_finish",
                ) { finish() }
            }
        }
    }

    // ── „＋ Vlastní návyk" dialog ────────────────────────────────────────────────
    if (showHabitDialog) {
        Dialog(onDismissRequest = { if (!savingHabit) showHabitDialog = false }) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = Indigo,
                // Opaque: the window opens over the wizard and must not let it shine through.
                opaque = true,
            ) {
                Text(
                    text = "Nový návyk",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                SectionHint("Návyk se uloží k účtu, takže ho uvidíš i na webu.")
                Spacer(Modifier.height(14.dp))
                GlassTextField(
                    value = habitName,
                    onValueChange = { habitName = it },
                    placeholder = "Název návyku",
                    testTag = "onboarding_custom_habit_name",
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val name = habitName
                        savingHabit = true
                        scope.launch {
                            val def = withContext(Dispatchers.IO) { deps.pickers.addHabit(name, "✅") }
                            if (def != null) state.addCustomHabit(def)
                            savingHabit = false
                            showHabitDialog = false
                        }
                    },
                    enabled = habitName.isNotBlank() && !savingHabit,
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    modifier = Modifier.fillMaxWidth().testTag("onboarding_custom_habit_save"),
                ) {
                    Text(if (savingHabit) "Ukládám…" else "Přidat")
                }
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showHabitDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Zrušit")
                }
            }
        }
    }

    // ── „＋ Vlastní cíl" dialog ─────────────────────────────────────────────────
    if (showGoalDialog) {
        Dialog(onDismissRequest = { showGoalDialog = false }) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                accent = Indigo,
                opaque = true,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "Vlastní cíl",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    SectionHint("Název, aktivita, období a kolikrát ji chceš stihnout.")
                    Spacer(Modifier.height(14.dp))
                    GlassTextField(
                        value = goalName,
                        onValueChange = { goalName = it },
                        placeholder = "Název cíle",
                        testTag = "onboarding_goal_name",
                    )
                    Spacer(Modifier.height(12.dp))
                    SectionHint("Aktivita")
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (activities.isEmpty()) {
                            Text(
                                text = "Načítám aktivity…",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        } else {
                            activities.forEach { activity ->
                                SelectableChip(
                                    text = activity.label,
                                    icon = activity.icon.takeIf { it.isNotBlank() },
                                    selected = activity.key == goalActivityKey,
                                    modifier = Modifier.fillMaxWidth(),
                                    testTag = "onboarding_goal_activity_${activity.key}",
                                    onClick = { goalActivityKey = activity.key },
                                )
                            }
                        }
                        if (goalHabits.isNotEmpty()) {
                            Text(
                                text = "Návyky",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary,
                            )
                            goalHabits.forEach { habit ->
                                SelectableChip(
                                    text = habit.label,
                                    icon = habit.icon.takeIf { it.isNotBlank() },
                                    selected = habit.key == goalActivityKey,
                                    modifier = Modifier.fillMaxWidth(),
                                    testTag = "onboarding_goal_habit_${habit.key}",
                                    onClick = { goalActivityKey = habit.key },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (!showingNewActivity) {
                        TextButton(
                            onClick = { showingNewActivity = true },
                            modifier = Modifier.testTag("onboarding_new_activity"),
                        ) {
                            Text("＋ Nová aktivita")
                        }
                    } else {
                        GlassTextField(
                            value = newActivityName,
                            onValueChange = { newActivityName = it },
                            placeholder = "Název nové aktivity",
                            testTag = "onboarding_new_activity_name",
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    val label = newActivityName
                                    addingActivity = true
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) { deps.pickers.addActivity(label) }
                                        if (ok) {
                                            // Re-read so the new row arrives with its repository
                                            // key (slug) and can be selected right away.
                                            val fresh = withContext(Dispatchers.IO) { deps.pickers.activities() }
                                            activities = fresh
                                            goalActivityKey = fresh
                                                .firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }
                                                ?.key ?: goalActivityKey
                                            newActivityName = ""
                                            showingNewActivity = false
                                        }
                                        addingActivity = false
                                    }
                                },
                                enabled = newActivityName.isNotBlank() && !addingActivity,
                                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                                modifier = Modifier.testTag("onboarding_new_activity_save"),
                            ) {
                                Text(if (addingActivity) "Přidávám…" else "Přidat")
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { showingNewActivity = false }) {
                                Text("Zpět")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SectionHint("Jak často")
                    Spacer(Modifier.height(6.dp))
                    FrequencyDropdown(
                        value = goalFrequency,
                        onValueChange = { goalFrequency = it },
                        testTag = "onboarding_goal_frequency",
                    )
                    Spacer(Modifier.height(12.dp))
                    SectionHint("Kolikrát za období")
                    Spacer(Modifier.height(6.dp))
                    GlassTextField(
                        value = goalTarget,
                        onValueChange = { raw -> goalTarget = raw.filter { it.isDigit() }.take(2) },
                        placeholder = "3",
                        testTag = "onboarding_goal_target",
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            val target = goalTarget.toIntOrNull()?.coerceAtLeast(1) ?: 3
                            val activityKey = goalActivityKey
                            if (activityKey != null) {
                                state.addCustomGoal(goalName, activityKey, target, goalFrequency)
                            }
                            goalName = ""
                            goalTarget = "3"
                            goalFrequency = "weekly"
                            goalActivityKey = null
                            showingNewActivity = false
                            newActivityName = ""
                            showGoalDialog = false
                        },
                        enabled = goalName.isNotBlank() && goalActivityKey != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        modifier = Modifier.fillMaxWidth().testTag("onboarding_goal_save"),
                    ) {
                        Text("Přidat cíl")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { showGoalDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Zrušit")
                    }
                }
            }
        }
    }
}

/**
 * Primary wizard CTA: the brand gradient with a soft glow, sized like the login
 * screen's sign-in button so both heroes feel like one product. Disabled falls
 * back to a quiet glass fill; the whole button is one clickable box, so pressing
 * it never shifts the layout.
 */
@Composable
private fun WizardPrimaryButton(
    text: String,
    testTag: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(
                if (enabled) Brush.linearGradient(Gradients.brand)
                else SolidColor(Color.White.copy(alpha = 0.08f)),
            )
            .then(if (enabled) Modifier.accentGlow(Indigo, alpha = 0.35f) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
        )
    }
}
