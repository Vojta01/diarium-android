package cz.digitalnivedomi.diarium.ui.onboarding

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
    fun `a habit switched off is hidden on finish, switched back on it is a restore`() {
        val state = stateWithHabits()

        state.toggleHabit("sport")
        assertEquals(listOf("sport"), state.habitsToHide().map { it.key })
        assertTrue(state.habitsToRestore().isEmpty())

        state.toggleHabit("sport")
        // Off and on again: not hidden, but written back as active (a restore).
        assertTrue(state.habitsToHide().isEmpty())
        assertEquals(listOf("sport"), state.habitsToRestore().map { it.key })

        // And off once more: hidden again — the toggledOff flag does not rescue it.
        state.toggleHabit("sport")
        assertEquals(listOf("sport"), state.habitsToHide().map { it.key })
        assertTrue(state.habitsToRestore().isEmpty())
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

        val fresh = state.goalsToCreate(emptySet())
        // Templates first, and the duplicate trenink keeps the template's shape.
        assertEquals(listOf("trenink", "spanek"), fresh.map { it.activityKey })
        assertEquals("Cvičit 3× týdně", fresh[0].name)
        assertEquals("Spaní 8h", fresh[1].name)

        assertEquals(listOf("trenink"), state.goalsToCreate(setOf("spanek")).map { it.activityKey })
        assertTrue(state.goalsToCreate(setOf("trenink", "spanek")).isEmpty())
    }

    @Test
    fun `removeCustomGoal ignores out-of-range indexes`() {
        val state = OnboardingState()
        state.addCustomGoal("Běh", "beh", 3, "weekly")
        state.addCustomGoal("Jóga", "joga", 2, "weekly")

        state.removeCustomGoal(5)
        assertEquals(2, state.customGoals.size)

        state.removeCustomGoal(0)
        assertEquals(listOf("joga"), state.customGoals.map { it.activityKey })
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
}
