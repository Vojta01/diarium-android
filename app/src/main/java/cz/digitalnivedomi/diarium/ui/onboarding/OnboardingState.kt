package cz.digitalnivedomi.diarium.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.digitalnivedomi.diarium.core.data.Goal
import cz.digitalnivedomi.diarium.core.data.HabitDef

/**
 * First-run wizard state — pure selection/step logic, no Android, no network, no
 * repository, so every decision the wizard makes (which step, what is ticked,
 * what the finish step would write) is unit-tested on the JVM.
 *
 * The screen owns the effects ([cz.digitalnivedomi.diarium.core.data.PickersRepository],
 * [cz.digitalnivedomi.diarium.core.data.GoalsRepository], the profile store); this
 * holder only answers "what did the user pick", and turns that into the exact write
 * plan (`habitsToHide`, `habitsToRestore`, `goalsToCreate`) so the plan is testable
 * without a device.
 */
class OnboardingState(
    habits: List<HabitDef> = emptyList(),
    initialName: String = "",
    initialBirthYear: Int? = null,
) {

    /** 1..[STEP_COUNT]; the dots and the buttons are driven from this. */
    var step: Int by mutableStateOf(1)
        private set

    /** Optional profile fields; they stay on the device, only AI uses them. */
    var name: String by mutableStateOf(initialName)
    var birthYear: String by mutableStateOf(initialBirthYear?.toString().orEmpty())

    /** The DeepSeek key as typed; only ever handed to `AiKeyStore.saveKey`. */
    var aiKey: String by mutableStateOf("")

    /** Step 3 rows. All start checked; unchecked ones are hidden on finish. */
    var habits: List<HabitChoice> by mutableStateOf(habits.map(::choiceOf))
        private set

    /** Step 4 templates — all start UNCHECKED, the user opts in. */
    var templates: List<GoalTemplate> by mutableStateOf(TEMPLATES)
        private set

    /** Custom goals built in the „＋ Vlastní cíl" dialog. */
    var customGoals: List<CustomGoal> by mutableStateOf(emptyList())
        private set

    fun next() {
        if (step < STEP_COUNT) step++
    }

    fun back() {
        if (step > 1) step--
    }

    /**
     * The catalogue list (re)arrived: every catalogue row is replaced, wizard-added
     * customs are kept — a reload must never drop a habit the user just created.
     */
    fun loadHabits(defs: List<HabitDef>) {
        val keys = defs.map { it.key }.toSet()
        val customs = habits.filter { it.custom && it.key !in keys }
        habits = defs.map(::choiceOf) + customs
    }

    /**
     * Flips one habit row. Switching a row off records [HabitChoice.toggledOff], so a
     * row that is switched back on is known to be a *restore* (written back with
     * `updateHabit`) instead of being left as it was.
     */
    fun toggleHabit(key: String) {
        habits = habits.map { choice ->
            if (choice.key != key) {
                choice
            } else {
                choice.copy(
                    checked = !choice.checked,
                    toggledOff = choice.toggledOff || choice.checked,
                )
            }
        }
    }

    /** Appends a custom habit (already created via `addHabit`), ticked. */
    fun addCustomHabit(def: HabitDef) {
        habits = habits + choiceOf(def).copy(custom = true, checked = true)
    }

    /** The „✏️ Upravit" dialog saved a name/icon change; reflect it in the row now. */
    fun updateHabitDefinition(key: String, label: String, icon: String, isNegative: Boolean) {
        habits = habits.map { choice ->
            if (choice.key == key) choice.copy(label = label, icon = icon, isNegative = isNegative) else choice
        }
    }

    /**
     * Rows switched off that were live — hidden on finish (`removeHabit`).
     */
    fun habitsToHide(): List<HabitChoice> = habits.filter { !it.checked && it.wasActive }

    /**
     * Rows ticked that were switched off when the wizard loaded — restored on finish
     * (`updateHabit`, active). A live row that is flipped off and back on needs no
     * write: nothing was persisted mid-wizard, so it never left the live state.
     */
    fun habitsToRestore(): List<HabitChoice> = habits.filter { it.checked && !it.wasActive }

    /**
     * Rows deleted in this run (the account's own habits). The finish step deletes
     * them first, before any hide/restore write — and leaving the wizard early
     * writes nothing, so a staged delete is never half-applied.
     */
    var deletedHabits: List<HabitDef> = emptyList()
        private set

    /** Stages a true delete: the row leaves the wizard now, the database on finish. */
    fun deleteHabit(def: HabitDef) {
        deletedHabits = deletedHabits + def
        habits = habits.filterNot { it.key == def.key }
    }

    /** What the wizard saw when it loaded — only these goals may later be deleted. */
    private var seenGoals: List<Goal> = emptyList()

    /**
     * The account's goals arrived: a re-run must show what is already set up. A
     * template whose activity already has a goal comes back ticked with the live
     * count/frequency; every other goal becomes a removable „existing" custom row,
     * so unchecking it in the wizard deletes it on finish.
     */
    fun loadExistingGoals(goals: List<Goal>) {
        seenGoals = goals
        val byKey = goals.associateBy { it.activityKey }
        templates = templates.map { template ->
            val existing = byKey[template.activityKey]
            if (existing == null) {
                template
            } else {
                template.copy(
                    checked = true,
                    targetCount = existing.targetCount,
                    frequency = existing.frequency,
                )
            }
        }
        val templateKeys = templates.map { it.activityKey }.toSet()
        val known = customGoals.map { it.activityKey }.toSet()
        customGoals = customGoals + goals
            .filter { it.activityKey !in templateKeys && it.activityKey !in known }
            .map { goal ->
                CustomGoal(
                    name = goal.name,
                    activityKey = goal.activityKey,
                    targetCount = goal.targetCount,
                    frequency = goal.frequency,
                    existingId = goal.id,
                )
            }
    }

    fun toggleTemplate(activityKey: String) {
        templates = templates.map { template ->
            if (template.activityKey == activityKey) template.copy(checked = !template.checked) else template
        }
    }

    /**
     * Step-4 editing: the count stepper on a template row. Unknown keys are ignored;
     * the count is floored at 1 so a goal never targets zero days.
     */
    fun updateTemplateCount(activityKey: String, count: Int) {
        templates = templates.map { template ->
            if (template.activityKey == activityKey) template.copy(targetCount = count.coerceAtLeast(1)) else template
        }
    }

    /**
     * Step-4 editing: the frequency chip on a template row. Only „daily", „weekly" and
     * „monthly" are accepted — anything else, or an unknown key, changes nothing.
     */
    fun updateTemplateFrequency(activityKey: String, frequency: String) {
        if (frequency !in KNOWN_FREQUENCIES) return
        templates = templates.map { template ->
            if (template.activityKey == activityKey) template.copy(frequency = frequency) else template
        }
    }

    /** Adds a custom goal, ticked; blank names/keys are refused (the dialog validates too). */
    fun addCustomGoal(name: String, activityKey: String, targetCount: Int, frequency: String) {
        val cleanName = name.trim()
        val cleanKey = activityKey.trim()
        if (cleanName.isBlank() || cleanKey.isBlank()) return
        customGoals = customGoals + CustomGoal(
            name = cleanName,
            activityKey = cleanKey,
            targetCount = targetCount.coerceAtLeast(1),
            frequency = frequency,
        )
    }

    /**
     * Flips one custom row's toggle. Like an unchecked template, a row switched off
     * is dropped on finish: a goal that mirrors a stored row is deleted from the
     * account, a goal created in this run is simply never written.
     */
    fun toggleCustomGoal(index: Int) {
        if (index !in customGoals.indices) return
        customGoals = customGoals.mapIndexed { position, goal ->
            if (position == index) goal.copy(checked = !goal.checked) else goal
        }
    }

    /**
     * Step-4 editing: the count stepper on a custom row. Unknown indexes are
     * ignored; the count is floored at 1 so a goal never targets zero days.
     */
    fun updateCustomGoalCount(index: Int, count: Int) {
        if (index !in customGoals.indices) return
        customGoals = customGoals.mapIndexed { position, goal ->
            if (position == index) goal.copy(targetCount = count.coerceAtLeast(1)) else goal
        }
    }

    /**
     * Step-4 editing: the frequency chip on a custom row. Only „daily", „weekly"
     * and „monthly" are accepted — anything else, or an unknown index, changes
     * nothing.
     */
    fun updateCustomGoalFrequency(index: Int, frequency: String) {
        if (frequency !in KNOWN_FREQUENCIES) return
        if (index !in customGoals.indices) return
        customGoals = customGoals.mapIndexed { position, goal ->
            if (position == index) goal.copy(frequency = frequency) else goal
        }
    }

    /**
     * The „✏️ Upravit" dialog saved: name (required, trimmed), count (floored at
     * 1) and frequency (known values only). A blank name, unknown frequency or
     * unknown index changes nothing. The activity key stays as it was — pointing
     * a stored goal at another activity would silently make it a different goal.
     */
    fun updateCustomGoal(index: Int, name: String, targetCount: Int, frequency: String) {
        if (index !in customGoals.indices) return
        val cleanName = name.trim()
        if (cleanName.isBlank() || frequency !in KNOWN_FREQUENCIES) return
        customGoals = customGoals.mapIndexed { position, goal ->
            if (position == index) {
                goal.copy(
                    name = cleanName,
                    targetCount = targetCount.coerceAtLeast(1),
                    frequency = frequency,
                )
            } else {
                goal
            }
        }
    }

    /** The typed year, or null when blank / outside the plausible range. */
    fun birthYearValue(): Int? = birthYear.trim().toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR }

    /** The year field is optional; anything typed must be a plausible year. */
    val birthYearValid: Boolean get() = birthYear.isBlank() || birthYearValue() != null

    /** Everything the user selected, in the order shown (templates first). */
    fun plannedGoals(): List<PlannedGoal> =
        templates.filter { it.checked }.map {
            PlannedGoal(goalNameFor(it.baseName, it.targetCount, it.frequency), it.activityKey, it.targetCount, it.frequency)
        } + customGoals.filter { it.checked }.map { PlannedGoal(it.name, it.activityKey, it.targetCount, it.frequency) }

    /**
     * [plannedGoals] minus the goals the account already has (same `activityKey`),
     * one goal per activity — selecting a template and a custom goal on the same key
     * must not create two rows.
     */
    fun goalsToCreate(existing: Map<String, Goal>): List<PlannedGoal> =
        plannedGoals()
            .filter { it.activityKey !in existing.keys }
            .distinctBy { it.activityKey }

    /**
     * Checked template rows **and edited custom rows** whose numbers differ from
     * the stored goal: the finish step writes the new name/count/frequency onto
     * the existing row. (The wizard used to drop edits on „existing" custom rows
     * entirely — the finish plan only knew templates.)
     *
     * Only a row that mirrors a stored goal ([CustomGoal.existingId] != null) can
     * be an update; a goal created in this run is handled by [goalsToCreate].
     */
    fun goalsToUpdate(existing: Map<String, Goal>): List<PlannedGoalUpdate> =
        templates.filter { it.checked }.mapNotNull { template ->
            val row = existing[template.activityKey] ?: return@mapNotNull null
            if (row.targetCount == template.targetCount && row.frequency == template.frequency) {
                return@mapNotNull null
            }
            PlannedGoalUpdate(
                id = row.id,
                name = goalNameFor(template.baseName, template.targetCount, template.frequency),
                activityKey = template.activityKey,
                targetCount = template.targetCount,
                frequency = template.frequency,
            )
        } + customGoals.filter { it.checked }.mapNotNull { goal ->
            val row = existing[goal.activityKey] ?: return@mapNotNull null
            if (goal.existingId == null) return@mapNotNull null
            if (row.name == goal.name && row.targetCount == goal.targetCount && row.frequency == goal.frequency) {
                return@mapNotNull null
            }
            PlannedGoalUpdate(
                id = row.id,
                name = goal.name,
                activityKey = goal.activityKey,
                targetCount = goal.targetCount,
                frequency = goal.frequency,
            )
        }

    /**
     * Goals that were shown at load (a template or an „existing" custom row) and are
     * no longer selected — an unchecked template or custom row. Only
     * goals the wizard actually saw can be deleted: a failed load (empty [seenGoals])
     * can therefore never wipe the account's goals.
     */
    fun goalsToDelete(existing: Map<String, Goal>): List<String> {
        val kept = plannedGoals().map { it.activityKey }.toSet()
        return seenGoals
            .filter { it.activityKey !in kept }
            .mapNotNull { seen -> existing[seen.activityKey]?.id }
    }

    companion object {
        const val STEP_COUNT = 4

        /** The year field accepts 1900..2100 (the wizard's own validation). */
        const val MIN_YEAR = 1900
        const val MAX_YEAR = 2100

        /**
         * The six offer templates, each an existing activity key so the goal counts
         * real check-in days the moment it is created.
         */
        val TEMPLATES: List<GoalTemplate> = listOf(
            GoalTemplate(emoji = "🏋️", name = "Cvičit 3× týdně", baseName = "Cvičit", activityKey = "trenink", targetCount = 3, frequency = "weekly"),
            GoalTemplate(emoji = "🚶", name = "Chodit denně", baseName = "Chodit", activityKey = "chuze", targetCount = 1, frequency = "daily"),
            GoalTemplate(emoji = "📖", name = "Číst 4× týdně", baseName = "Číst", activityKey = "cteni", targetCount = 4, frequency = "weekly"),
            GoalTemplate(emoji = "🧘", name = "Meditovat 3× týdně", baseName = "Meditovat", activityKey = "meditovat", targetCount = 3, frequency = "weekly"),
            GoalTemplate(emoji = "🥗", name = "Jíst zdravě 5× týdně", baseName = "Jíst zdravě", activityKey = "jist_zdrave", targetCount = 5, frequency = "weekly"),
            GoalTemplate(emoji = "💧", name = "Pít vodu denně", baseName = "Pít vodu", activityKey = "pit_vody", targetCount = 1, frequency = "daily"),
        )

        /** The only frequencies [updateTemplateFrequency] lets a template take. */
        private val KNOWN_FREQUENCIES = setOf("daily", "weekly", "monthly")

        /**
         * The goal name a template row stands for, derived from its editable parts —
         * `1× daily` reads bare („Chodit denně"), everything else keeps the `N×` shape
         * („Cvičit 3× týdně", „Pít vodu 1× týdně", „Číst 1× měsíčně").
         */
        fun goalNameFor(baseName: String, count: Int, frequency: String): String =
            if (count == 1 && frequency == "daily") {
                "$baseName denně"
            } else {
                val adverb = when (frequency) {
                    "daily" -> "denně"
                    "monthly" -> "měsíčně"
                    else -> "týdně"
                }
                "$baseName $count× $adverb"
            }

        private fun choiceOf(def: HabitDef) = HabitChoice(
            key = def.key,
            label = def.label,
            icon = def.icon,
            category = def.category,
            color = def.color,
            isNegative = def.isNegative,
            source = def.source,
            checked = def.isActive,
            wasActive = def.isActive,
        )
    }
}

/** One habit row in step 3: the habit plus the wizard's own tick state. */
data class HabitChoice(
    val key: String,
    val label: String,
    val icon: String,
    val category: String,
    val color: String,
    val isNegative: Boolean,
    val source: String,
    val checked: Boolean = true,
    /**
     * True once the user switched this row off at some point during the wizard:
     * checked + [toggledOff] means "switched off and back on", which the finish step
     * writes back as active (`updateHabit`).
     */
    val toggledOff: Boolean = false,
    /**
     * The live state when the wizard loaded (`def.isActive`). Unchecked + wasActive →
     * hide on finish; checked + !wasActive → restore on finish.
     */
    val wasActive: Boolean = true,
    /** True for habits created in this wizard run; a catalogue reload keeps them. */
    val custom: Boolean = false,
) {
    /** The original habit, as `removeHabit` / `updateHabit` want it. */
    fun toHabitDef(): HabitDef = HabitDef(
        key = key,
        label = label,
        icon = icon,
        category = category,
        color = color,
        isNegative = isNegative,
        source = source,
        isActive = true,
    )
}

/** One of the six goal templates. */
data class GoalTemplate(
    val emoji: String,
    val name: String,
    /** The name without the count/frequency tail; [OnboardingState.goalNameFor] rebuilds the goal name from it. */
    val baseName: String,
    val activityKey: String,
    val targetCount: Int,
    val frequency: String,
    val checked: Boolean = false,
)

/** A goal built in the „＋ Vlastní cíl" dialog (or an existing one on a re-run). */
data class CustomGoal(
    val name: String,
    val activityKey: String,
    val targetCount: Int,
    val frequency: String,
    /** Set when the row mirrors a goal that already exists in the account. */
    val existingId: String? = null,
    /**
     * The row's toggle, mirroring a template's `checked`: switched off means the
     * goal is dropped on finish — deleted when it mirrors a stored row, never
     * created when it is new.
     */
    val checked: Boolean = true,
)

/** One goal the finish step should create, in `GoalsRepository.create` shape. */
data class PlannedGoal(
    val name: String,
    val activityKey: String,
    val targetCount: Int,
    val frequency: String,
)

/** One existing goal whose numbers changed in the wizard; written by `update`. */
data class PlannedGoalUpdate(
    val id: String,
    val name: String,
    val activityKey: String,
    val targetCount: Int,
    val frequency: String,
)
