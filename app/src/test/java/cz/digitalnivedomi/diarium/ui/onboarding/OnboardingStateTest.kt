package cz.digitalnivedomi.diarium.ui.onboarding

import cz.digitalnivedomi.diarium.core.data.Goal
import cz.digitalnivedomi.diarium.core.data.HabitDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wizard's decisions, pinned on the JVM: step boundaries, what a habit toggle
 * means on finish (hide vs. restore), the template ticks, the custom-goal de-dupe
 * against the activities the account already has, and the birth-year validation.
 *
 * No Android, no network — [OnboardingState] is the pure holder the screen drives.
 */
class OnboardingStateTest {

    private fun habit(key: String) = HabitDef(key = key, label = key)

    private fun stateWithHabits() = OnboardingState(
        habits = listOf(habit("sport"), habit("cteni")),
    )

    private fun OnboardingState.keys(): List<String> = habits.map { it.key }

    // ── step ─────────────────────────────────────────────────────────────────

    @Test
    fun `next walks 1 to STEP_COUNT and stops on the last step`() {
        val state = OnboardingState()
        assertEquals(1, state.step)

        state.next()
        assertEquals(2, state.step)
        state.next()
        state.next()
        assertEquals(OnboardingState.STEP_COUNT, state.step)

        // The last step is the end — another next must not run past it.
        state.next()
        assertEquals(OnboardingState.STEP_COUNT, state.step)
    }

    @Test
    fun `back stops on the first step`() {
        val state = OnboardingState()

        state.back()
        assertEquals(1, state.step)

        state.next()
        assertEquals(2, state.step)
        state.back()
        assertEquals(1, state.step)
    }

    // ── habits (step 3) ──────────────────────────────────────────────────────

    @Test
    fun `habits start checked, so nothing is hidden or restored before a toggle`() {
        val state = stateWithHabits()

        assertEquals(listOf("sport", "cteni"), state.keys())
        assertTrue(state.habitsToHide().isEmpty())
        assertTrue(state.habitsToRestore().isEmpty())
    }

    @Test
    fun `a habit switched off is hidden on finish, switched back on it writes nothing`() {
        val state = stateWithHabits()

        state.toggleHabit("sport")
        assertEquals(listOf("sport"), state.habitsToHide().map { it.key })
        assertTrue(state.habitsToRestore().isEmpty())

        state.toggleHabit("sport")
        // Off and on again: the habit never left its live state (nothing is written
        // mid-wizard), so the plan is empty — no write, no stale restore.
        assertTrue(state.habitsToHide().isEmpty())
        assertTrue(state.habitsToRestore().isEmpty())

        // And off once more: hidden again.
        state.toggleHabit("sport")
        assertEquals(listOf("sport"), state.habitsToHide().map { it.key })
        assertTrue(state.habitsToRestore().isEmpty())
    }

    @Test
    fun `a hidden habit switched on is restored, left alone it writes nothing`() {
        val state = OnboardingState(
            habits = listOf(
                HabitDef(key = "koureni", label = "Kouření", isActive = true),
                HabitDef(key = "cteni", label = "Čtení", isActive = false),
            ),
        )

        // The prefilled ticks mirror the live state — a re-run shows what is on.
        assertEquals(listOf(true, false), state.habits.map { it.checked })

        // Untouched: nothing in the plan.
        assertTrue(state.habitsToHide().isEmpty())
        assertTrue(state.habitsToRestore().isEmpty())

        state.toggleHabit("cteni")
        assertEquals(listOf("cteni"), state.habitsToRestore().map { it.key })

        state.toggleHabit("koureni")
        assertEquals(listOf("koureni"), state.habitsToHide().map { it.key })
        assertEquals(listOf("cteni"), state.habitsToRestore().map { it.key })
    }

    @Test
    fun `a deleted habit leaves the wizard and is staged for the database`() {
        val state = stateWithHabits()
        val sport = state.habits.first { it.key == "sport" }
        state.deleteHabit(sport.toHabitDef())

        assertTrue(state.habits.none { it.key == "sport" })
        assertEquals(listOf("sport"), state.deletedHabits.map { it.key })
        // A staged delete must never also ride along as a hide or a restore.
        assertTrue(state.habitsToHide().none { it.key == "sport" })
        assertTrue(state.habitsToRestore().none { it.key == "sport" })
    }

    @Test
    fun `toggling an unknown habit key leaves every row as it was`() {
        val state = stateWithHabits()

        state.toggleHabit("neexistuje")

        assertEquals(listOf("sport", "cteni"), state.keys())
        assertTrue(state.habitsToHide().isEmpty())
    }

    // ── templates (step 4) ───────────────────────────────────────────────────

    @Test
    fun `templates start unchecked and toggle on and off`() {
        val state = OnboardingState()
        val key = OnboardingState.TEMPLATES.first().activityKey

        assertTrue(state.templates.none { it.checked })

        state.toggleTemplate(key)
        assertEquals(listOf(key), state.templates.filter { it.checked }.map { it.activityKey })

        state.toggleTemplate(key)
        assertTrue(state.templates.none { it.checked })
    }

    @Test
    fun `toggling an unknown template key changes nothing`() {
        val state = OnboardingState()

        state.toggleTemplate("neexistuje")

        assertTrue(state.templates.none { it.checked })
    }

    private fun OnboardingState.template(key: String): GoalTemplate =
        templates.first { it.activityKey == key }

    @Test
    fun `updateTemplateCount floors the count at one and ignores unknown keys`() {
        val state = OnboardingState()

        state.updateTemplateCount("trenink", 0)
        assertEquals(1, state.template("trenink").targetCount)

        state.updateTemplateCount("trenink", 5)
        assertEquals(5, state.template("trenink").targetCount)

        // Unknown key: no row moves.
        val before = state.templates.map { it.activityKey to it.targetCount }
        state.updateTemplateCount("neexistuje", 7)
        assertEquals(before, state.templates.map { it.activityKey to it.targetCount })
    }

    @Test
    fun `updateTemplateFrequency takes the three known values and refuses anything else`() {
        val state = OnboardingState()

        state.updateTemplateFrequency("trenink", "daily")
        assertEquals("daily", state.template("trenink").frequency)

        state.updateTemplateFrequency("trenink", "monthly")
        assertEquals("monthly", state.template("trenink").frequency)

        state.updateTemplateFrequency("trenink", "weekly")
        assertEquals("weekly", state.template("trenink").frequency)

        // Junk keeps the last accepted value.
        state.updateTemplateFrequency("trenink", "obcas")
        state.updateTemplateFrequency("trenink", "")
        assertEquals("weekly", state.template("trenink").frequency)

        // Unknown key: no row moves, even with a valid frequency.
        val before = state.templates.map { it.activityKey to it.frequency }
        state.updateTemplateFrequency("neexistuje", "daily")
        assertEquals(before, state.templates.map { it.activityKey to it.frequency })
    }

    @Test
    fun `goalNameFor keeps the daily single bare and builds N× names otherwise`() {
        assertEquals("Chodit denně", OnboardingState.goalNameFor("Chodit", 1, "daily"))
        assertEquals("Cvičit 3× týdně", OnboardingState.goalNameFor("Cvičit", 3, "weekly"))
        // Only 1× daily drops the N× tail — 1× weekly keeps it.
        assertEquals("Pít vodu 1× týdně", OnboardingState.goalNameFor("Pít vodu", 1, "weekly"))
        assertEquals("Číst 1× měsíčně", OnboardingState.goalNameFor("Číst", 1, "monthly"))
        assertEquals("Číst 4× měsíčně", OnboardingState.goalNameFor("Číst", 4, "monthly"))
    }

    @Test
    fun `every template's default name is goalNameFor of its own parts`() {
        for (template in OnboardingState.TEMPLATES) {
            assertEquals(
                template.name,
                OnboardingState.goalNameFor(template.baseName, template.targetCount, template.frequency),
            )
        }
    }

    @Test
    fun `an edited template creates its goal under the derived name`() {
        val state = OnboardingState()
        state.toggleTemplate("trenink")

        state.updateTemplateCount("trenink", 5)
        state.updateTemplateFrequency("trenink", "monthly")

        val fresh = state.goalsToCreate(emptyMap())
        assertEquals(listOf("trenink"), fresh.map { it.activityKey })
        assertEquals("Cvičit 5× měsíčně", fresh[0].name)
        assertEquals(5, fresh[0].targetCount)
        assertEquals("monthly", fresh[0].frequency)
    }

    // ── custom goals + goalsToCreate ─────────────────────────────────────────

    @Test
    fun `addCustomGoal trims, refuses blanks and floors the target at one`() {
        val state = OnboardingState()

        state.addCustomGoal("  Běh  ", "  beh  ", 0, "weekly")
        assertEquals(1, state.customGoals.size)
        assertEquals("Běh", state.customGoals[0].name)
        assertEquals("beh", state.customGoals[0].activityKey)
        assertEquals(1, state.customGoals[0].targetCount)

        state.addCustomGoal(" ", "joga", 3, "daily")
        state.addCustomGoal("Jóga", "   ", 3, "daily")
        assertEquals(1, state.customGoals.size)
    }

    @Test
    fun `goalsToCreate drops existing keys and keeps one goal per activity`() {
        val state = OnboardingState()
        state.toggleTemplate("trenink")
        // Same activity as the ticked template — must not create a second row.
        state.addCustomGoal("Ranní běh", "trenink", 5, "weekly")
        state.addCustomGoal("Spaní 8h", "spanek", 7, "weekly")

        val fresh = state.goalsToCreate(emptyMap())
        // Templates first, and the duplicate trenink keeps the template's shape.
        assertEquals(listOf("trenink", "spanek"), fresh.map { it.activityKey })
        assertEquals("Cvičit 3× týdně", fresh[0].name)
        assertEquals("Spaní 8h", fresh[1].name)

        val spanek = mapOf("spanek" to goal("g9", "spanek", "Spaní 8h", 7, "weekly"))
        assertEquals(listOf("trenink"), state.goalsToCreate(spanek).map { it.activityKey })
        val both = mapOf(
            "trenink" to goal("g8", "trenink", "Cvičit 3× týdně", 3, "weekly"),
            "spanek" to goal("g9", "spanek", "Spaní 8h", 7, "weekly"),
        )
        assertTrue(state.goalsToCreate(both).isEmpty())
    }

    @Test
    fun `toggleCustomGoal flips a row and ignores out-of-range indexes`() {
        val state = OnboardingState()
        state.addCustomGoal("Běh", "beh", 3, "weekly")

        state.toggleCustomGoal(5) // ignored
        assertTrue(state.customGoals[0].checked)

        state.toggleCustomGoal(0)
        assertFalse(state.customGoals[0].checked)

        state.toggleCustomGoal(0)
        assertTrue(state.customGoals[0].checked)
    }

    // ── birth year ───────────────────────────────────────────────────────────

    @Test
    fun `birthYearValue trims, parses and rejects implausible years`() {
        val state = OnboardingState()

        state.birthYear = "1995"
        assertEquals(1995, state.birthYearValue())

        state.birthYear = " 1995 "
        assertEquals(1995, state.birthYearValue())

        state.birthYear = "garbage"
        assertNull(state.birthYearValue())

        state.birthYear = ""
        assertNull(state.birthYearValue())

        state.birthYear = "1800"
        assertNull(state.birthYearValue())

        state.birthYear = "2101"
        assertNull(state.birthYearValue())

        // The range ends are accepted.
        state.birthYear = "1900"
        assertEquals(1900, state.birthYearValue())
        state.birthYear = "2100"
        assertEquals(2100, state.birthYearValue())
    }

    @Test
    fun `birthYearValid treats blank as optional but flags a bad year`() {
        val state = OnboardingState()

        assertTrue(state.birthYearValid)

        state.birthYear = "1800"
        assertFalse(state.birthYearValid)

        state.birthYear = "1995"
        assertTrue(state.birthYearValid)
    }

    @Test
    fun `a stored name and birth year seed the fields`() {
        val state = OnboardingState(initialName = "Vojta", initialBirthYear = 1995)

        assertEquals("Vojta", state.name)
        assertEquals("1995", state.birthYear)
        assertEquals(1995, state.birthYearValue())
    }

    // ── existing goals (a re-run) ────────────────────────────────────────────

    private fun goal(
        id: String,
        activityKey: String,
        name: String,
        targetCount: Int,
        frequency: String,
    ) = Goal(
        id = id,
        activityKey = activityKey,
        name = name,
        targetCount = targetCount,
        frequency = frequency,
        isActive = true,
    )

    @Test
    fun `existing goals prefill the templates and become removable customs`() {
        val state = OnboardingState()
        state.loadExistingGoals(
            listOf(
                goal("g1", "trenink", "Cvičit 10× měsíčně", 10, "monthly"),
                goal("g2", "studena_sprcha", "Studená sprcha", 4, "weekly"),
            ),
        )

        val trenink = state.template("trenink")
        assertTrue("an existing goal comes back ticked", trenink.checked)
        assertEquals(10, trenink.targetCount)
        assertEquals("monthly", trenink.frequency)
        assertEquals(listOf("studena_sprcha"), state.customGoals.map { it.activityKey })

        // Nothing changed -> the plan is empty; finish writes nothing.
        val existing = mapOf(
            "trenink" to goal("g1", "trenink", "Cvičit 10× měsíčně", 10, "monthly"),
            "studena_sprcha" to goal("g2", "studena_sprcha", "Studená sprcha", 4, "weekly"),
        )
        assertTrue(state.goalsToCreate(existing).isEmpty())
        assertTrue(state.goalsToUpdate(existing).isEmpty())
        assertTrue(state.goalsToDelete(existing).isEmpty())
    }

    @Test
    fun `an unchecked template deletes its existing goal`() {
        val state = OnboardingState()
        state.loadExistingGoals(listOf(goal("g1", "trenink", "Cvičit 10× měsíčně", 10, "monthly")))
        state.toggleTemplate("trenink") // prefilled as checked -> off

        val existing = mapOf("trenink" to goal("g1", "trenink", "Cvičit 10× měsíčně", 10, "monthly"))
        assertEquals(listOf("g1"), state.goalsToDelete(existing))
        assertTrue(state.goalsToCreate(existing).isEmpty())
    }

    @Test
    fun `changed numbers update the existing goal instead of creating a second one`() {
        val state = OnboardingState()
        state.loadExistingGoals(listOf(goal("g1", "trenink", "Cvičit 3× týdně", 3, "weekly")))
        state.updateTemplateCount("trenink", 5)
        state.updateTemplateFrequency("trenink", "monthly")

        val existing = mapOf("trenink" to goal("g1", "trenink", "Cvičit 3× týdně", 3, "weekly"))
        assertTrue(state.goalsToCreate(existing).isEmpty())
        val update = state.goalsToUpdate(existing).single()
        assertEquals("g1", update.id)
        assertEquals(5, update.targetCount)
        assertEquals("monthly", update.frequency)
        assertEquals("Cvičit 5× měsíčně", update.name)
    }

    @Test
    fun `a goal the wizard never saw can never be deleted`() {
        val state = OnboardingState() // load never happened
        val existing = mapOf("trenink" to goal("g1", "trenink", "Cvičit", 3, "weekly"))

        assertTrue("an unloaded wizard must not delete anything", state.goalsToDelete(existing).isEmpty())
    }

    @Test
    fun `an unchecked custom goal is deleted on finish`() {
        val state = OnboardingState()
        state.loadExistingGoals(listOf(goal("g2", "studena_sprcha", "Studená sprcha", 4, "weekly")))
        state.toggleCustomGoal(0) // prefilled as checked -> off

        val existing = mapOf("studena_sprcha" to goal("g2", "studena_sprcha", "Studená sprcha", 4, "weekly"))
        assertEquals(listOf("g2"), state.goalsToDelete(existing))
        assertTrue(state.goalsToCreate(existing).isEmpty())
        assertTrue(state.goalsToUpdate(existing).isEmpty())
    }

    @Test
    fun `a custom goal switched off is not created on finish`() {
        val state = OnboardingState()
        state.addCustomGoal("Běhat", "beh", 3, "weekly")
        assertTrue("a ticked custom goal is planned", state.goalsToCreate(emptyMap()).isNotEmpty())

        state.toggleCustomGoal(0)
        assertTrue("a custom goal switched off must not be created", state.goalsToCreate(emptyMap()).isEmpty())
    }

    // ── item 22: the custom goal is editable like the templates ───────────────

    @Test
    fun `a custom goal's count and frequency are editable inline`() {
        val state = OnboardingState()
        state.addCustomGoal("Běhat", "beh", 3, "weekly")

        state.updateCustomGoalCount(0, 0)            // floored at 1
        state.updateCustomGoalFrequency(0, "daily")
        state.updateCustomGoalFrequency(0, "nikdy")  // unknown -> ignored
        state.updateCustomGoalCount(9, 7)            // unknown index -> ignored

        val goal = state.customGoals.single()
        assertEquals(1, goal.targetCount)
        assertEquals("daily", goal.frequency)
    }

    @Test
    fun `a custom goal's name is editable via the dialog`() {
        val state = OnboardingState()
        state.addCustomGoal("Běhat", "beh", 3, "weekly")

        state.updateCustomGoal(0, "  Běhat venku ", 5, "monthly")

        val goal = state.customGoals.single()
        assertEquals("Běhat venku", goal.name)
        assertEquals(5, goal.targetCount)
        assertEquals("monthly", goal.frequency)
        assertEquals("beh", goal.activityKey) // retargeting is not a thing

        state.updateCustomGoal(0, "   ", 5, "monthly") // blank name -> no change
        assertEquals("Běhat venku", state.customGoals.single().name)
        state.updateCustomGoal(9, "X", 1, "daily")     // unknown index -> no change
        assertEquals("Běhat venku", state.customGoals.single().name)
    }

    @Test
    fun `an edited existing custom goal updates its stored row on finish`() {
        val state = OnboardingState()
        state.loadExistingGoals(listOf(goal("g2", "studena_sprcha", "Studená sprcha", 4, "weekly")))
        state.updateCustomGoalCount(0, 6)
        state.updateCustomGoal(0, "Studená sprcha 6×", 6, "weekly")

        val existing = mapOf("studena_sprcha" to goal("g2", "studena_sprcha", "Studená sprcha", 4, "weekly"))
        assertTrue("a change never creates a second goal", state.goalsToCreate(existing).isEmpty())

        val update = state.goalsToUpdate(existing).single()
        assertEquals("g2", update.id)
        assertEquals("Studená sprcha 6×", update.name)
        assertEquals(6, update.targetCount)
        assertEquals("weekly", update.frequency)
    }

    @Test
    fun `a goal created in this run is never an update, only a create`() {
        val state = OnboardingState()
        state.addCustomGoal("Běhat", "beh", 3, "weekly")

        // Even if a row with the same key exists (a race), a fresh wizard row is
        // not a mirror of it — updates only ever come from loaded existing rows.
        val existing = mapOf("beh" to goal("g9", "beh", "Běhat", 3, "weekly"))
        assertTrue(state.goalsToUpdate(existing).isEmpty())
    }
}
