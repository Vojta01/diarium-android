package cz.digitalnivedomi.diarium.ui.checkin

import cz.digitalnivedomi.diarium.core.data.Goal
import cz.digitalnivedomi.diarium.core.data.GoalPeriodResult
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The celebration trigger is a transition, not a state. These cases are the ones
 * that broke the naive `targetMet` check: a goal met yesterday, a save after
 * midnight, a goal that never had progress, and a goal whose window moved.
 */
class GoalCelebrationRulesTest {

    private fun goal(id: String = "g1", target: Int = 3, frequency: String = "daily") = Goal(
        id = id,
        activityKey = "chuze",
        name = "Chůze",
        targetCount = target,
        frequency = frequency,
        isActive = true,
    )

    private fun progress(
        count: Int,
        target: Int = 3,
        id: String = "g1",
        frequency: String = "daily",
        window: Pair<String, String>? = "2026-10-30" to "2026-10-30",
    ): GoalProgress = GoalProgress(
        goal = goal(id = id, target = target, frequency = frequency),
        count = count,
        streak = 0,
        periods = listOfNotNull(
            window?.let { (start, end) ->
                GoalPeriodResult(
                    start = start,
                    end = end,
                    dates = List(count) { "2026-10-30" },
                    met = count >= target,
                )
            },
        ),
    )

    @Test
    fun `a goal that crossed its target is celebrated`() {
        val newlyMet = newlyMetGoals(listOf(progress(count = 2)), listOf(progress(count = 3)))
        assertEquals(1, newlyMet.size)
        assertEquals("g1", newlyMet.first().goal.id)
    }

    @Test
    fun `a goal that was already met is silent`() {
        assertTrue(newlyMetGoals(listOf(progress(count = 3)), listOf(progress(count = 3))).isEmpty())
    }

    @Test
    fun `a goal that is still short is silent`() {
        assertTrue(newlyMetGoals(listOf(progress(count = 1)), listOf(progress(count = 2))).isEmpty())
    }

    @Test
    fun `unknown progress on either side is silent`() {
        assertTrue(newlyMetGoals(null, listOf(progress(count = 3))).isEmpty())
        assertTrue(newlyMetGoals(listOf(progress(count = 2)), null).isEmpty())
        assertTrue(newlyMetGoals(emptyList(), listOf(progress(count = 3))).isEmpty())
    }

    @Test
    fun `a goal whose period window moved is not evaluated`() {
        val before = listOf(progress(count = 2, window = "2026-10-29" to "2026-10-29"))
        val after = listOf(progress(count = 3, window = "2026-10-30" to "2026-10-30"))
        assertTrue(newlyMetGoals(before, after).isEmpty())
    }

    @Test
    fun `only the goals that flipped are returned`() {
        val before = listOf(
            progress(count = 2, id = "a"),
            progress(count = 3, id = "b"),
            progress(count = 0, id = "c", target = 1, frequency = "weekly"),
        )
        val after = listOf(
            progress(count = 3, id = "a"),
            progress(count = 4, id = "b"),
            progress(count = 1, id = "c", target = 1, frequency = "weekly"),
        )
        val newlyMet = newlyMetGoals(before, after)
        assertEquals(listOf("a", "c"), newlyMet.map { it.goal.id })
    }

    @Test
    fun `a new goal with no snapshot is not celebrated`() {
        val before = listOf(progress(count = 0, id = "a"))
        val after = listOf(progress(count = 0, id = "a"), progress(count = 3, id = "fresh"))
        assertTrue(newlyMetGoals(before, after).isEmpty())
    }
}
