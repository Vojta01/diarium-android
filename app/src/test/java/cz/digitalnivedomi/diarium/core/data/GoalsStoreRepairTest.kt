package cz.digitalnivedomi.diarium.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The icon repair [GoalsStore.repairLostIcons] performs on the next read.
 *
 * Goals live on-device only, so this is the one place the app can put back what the
 * old hardcoded 🎯 never gave: a goal whose name matches a built-in one gets that
 * built-in's icon back. The tests below are the two halves of the rule — it must fix
 * the loss, and it must not touch an icon the owner actually chose.
 */
class GoalsStoreRepairTest {

    private fun goal(name: String, emoji: String) =
        DailyGoal(id = "goal-1", emoji = emoji, name = name)

    @Test
    fun `the goal that lost its icon gets the built-in one back`() {
        val repaired = GoalsStore.repairLostIcons(
            listOf(goal("Krátké cvičení", GoalsStore.PLACEHOLDER_EMOJI)),
        )

        assertEquals(listOf("🏋️"), repaired.map { it.emoji })
    }

    @Test
    fun `the name match ignores case, spacing and diacritics`() {
        // The owner retypes the name by hand, so it arrives however he types it.
        val repaired = GoalsStore.repairLostIcons(
            listOf(
                goal("  krátké cvičení ", GoalsStore.PLACEHOLDER_EMOJI),
                goal("KRATKE CVIČENÍ", GoalsStore.PLACEHOLDER_EMOJI),
            ),
        )

        assertEquals(listOf("🏋️", "🏋️"), repaired.map { it.emoji })
    }

    @Test
    fun `an icon the owner picked is never replaced`() {
        // 🏃 is a choice, not the placeholder — the repair has no business here.
        val repaired = GoalsStore.repairLostIcons(listOf(goal("Krátké cvičení", "🏃")))

        assertEquals(listOf("🏃"), repaired.map { it.emoji })
    }

    @Test
    fun `a goal with no built-in twin keeps its icon`() {
        val repaired = GoalsStore.repairLostIcons(
            listOf(goal("Pít vodu", GoalsStore.PLACEHOLDER_EMOJI)),
        )

        assertEquals(listOf(GoalsStore.PLACEHOLDER_EMOJI), repaired.map { it.emoji })
    }

    @Test
    fun `the repair keeps ids, names and ticked days`() {
        val ticks = listOf("2026-09-08", "2026-09-09")
        val repaired = GoalsStore.repairLostIcons(
            listOf(
                DailyGoal(
                    id = "goal-1",
                    emoji = GoalsStore.PLACEHOLDER_EMOJI,
                    name = "Krátké cvičení",
                    completedDates = ticks,
                ),
            ),
        ).single()

        assertEquals("goal-1", repaired.id)
        assertEquals("Krátké cvičení", repaired.name)
        assertEquals(ticks, repaired.completedDates)
    }

    @Test
    fun `an already correct icon survives the repair untouched`() {
        val goals = listOf(goal("Krátké cvičení", "🏋️"))

        assertEquals(goals, GoalsStore.repairLostIcons(goals))
    }
}
