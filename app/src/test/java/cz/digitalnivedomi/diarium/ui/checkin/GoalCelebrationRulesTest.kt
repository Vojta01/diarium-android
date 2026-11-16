package cz.digitalnivedomi.diarium.ui.checkin

import cz.digitalnivedomi.diarium.core.data.Goal
import cz.digitalnivedomi.diarium.core.data.GoalPeriodResult
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        streak: Int = 0,
        createdAt: String = "",
        name: String = id,
    ): GoalProgress = GoalProgress(
        goal = goal(id = id, target = target, frequency = frequency).copy(createdAt = createdAt, name = name),
        count = count,
        streak = streak,
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

    // ── celebration style ───────────────────────────────────────────────────
    // Daily and weekly goals earn the compact inline celebration; a monthly goal
    // is rare enough to justify the full-screen one. There is no third tier, and
    // the classifier never depends on how many goals flipped.

    @Test
    fun `no newly met goals has no celebration`() {
        assertEquals(null, goalCelebrationStyle(emptyList()))
    }

    @Test
    fun `a daily goal is compact`() {
        assertEquals(
            GoalCelebrationStyle.COMPACT,
            goalCelebrationStyle(listOf(progress(count = 3, frequency = "daily"))),
        )
    }

    @Test
    fun `a weekly goal is compact`() {
        assertEquals(
            GoalCelebrationStyle.COMPACT,
            goalCelebrationStyle(listOf(progress(count = 3, frequency = "weekly"))),
        )
    }

    @Test
    fun `a monthly goal is full screen`() {
        assertEquals(
            GoalCelebrationStyle.FULL_SCREEN,
            goalCelebrationStyle(listOf(progress(count = 3, frequency = "monthly"))),
        )
    }

    @Test
    fun `czech aliases normalize like their english frequency`() {
        assertEquals(
            GoalCelebrationStyle.COMPACT,
            goalCelebrationStyle(listOf(progress(count = 3, frequency = "denně"))),
        )
        assertEquals(
            GoalCelebrationStyle.COMPACT,
            goalCelebrationStyle(listOf(progress(count = 3, frequency = "týdně"))),
        )
        assertEquals(
            GoalCelebrationStyle.FULL_SCREEN,
            goalCelebrationStyle(listOf(progress(count = 3, frequency = "měsíčně"))),
        )
    }

    @Test
    fun `an unknown frequency falls back to compact`() {
        // normalizeFrequency maps anything unrecognized to weekly, so the
        // celebration must inherit that instead of inventing a third style.
        assertEquals(
            GoalCelebrationStyle.COMPACT,
            goalCelebrationStyle(listOf(progress(count = 3, frequency = "quarterly"))),
        )
        assertEquals(
            GoalCelebrationStyle.COMPACT,
            goalCelebrationStyle(listOf(progress(count = 3, frequency = ""))),
        )
    }

    @Test
    fun `monthly among weekly goals escalates to full screen`() {
        val newlyMet = listOf(
            progress(count = 3, id = "week", frequency = "weekly"),
            progress(count = 3, id = "month", frequency = "monthly"),
        )
        assertEquals(GoalCelebrationStyle.FULL_SCREEN, goalCelebrationStyle(newlyMet))
    }

    // ── plan: hero, remainder, single vs group ──────────────────────────────
    // A save can meet several goals at once. The plan picks the hero (a monthly
    // goal always wins, whatever the repository's createdAt order), caps the
    // named goals at two and never renders one goal's count or streak as if it
    // described the whole group.

    @Test
    fun `a monthly goal is the hero regardless of repository order`() {
        val plan = goalCelebrationPlan(
            listOf(
                progress(count = 3, id = "week", frequency = "weekly", createdAt = "2026-11-01"),
                progress(count = 3, id = "month", frequency = "monthly", createdAt = "2020-01-01"),
            ),
        )!!
        assertEquals(GoalCelebrationStyle.FULL_SCREEN, plan.style)
        assertEquals("month", plan.hero.goal.id)
        assertEquals(listOf("month", "week"), plan.named.map { it.goal.id })
    }

    @Test
    fun `a single goal is its own hero and has no group text`() {
        val plan = goalCelebrationPlan(listOf(progress(count = 3)))!!
        assertTrue(plan.single)
        assertEquals("g1", plan.hero.goal.id)
        assertEquals(0, plan.remainder)
        assertEquals("3 / 3 · dnes", goalDetailText(plan))
        assertEquals(null, goalGroupText(plan))
        assertEquals(null, goalRemainderText(plan))
    }

    @Test
    fun `remainder is total minus the two named goals`() {
        fun plan(size: Int) = goalCelebrationPlan(
            List(size) { index ->
                progress(count = 3, id = "g$index", frequency = if (index == 0) "daily" else "weekly")
            },
        )!!
        assertEquals(0, plan(1).remainder)
        assertEquals(0, plan(2).remainder)
        assertEquals(1, plan(3).remainder)
        assertEquals(3, plan(5).remainder)
        assertEquals(2, plan(5).named.size)
    }

    @Test
    fun `a group never borrows one goal's count or streak`() {
        val plan = goalCelebrationPlan(
            listOf(
                progress(count = 5, id = "a", frequency = "daily", streak = 9),
                progress(count = 3, id = "b", frequency = "weekly", streak = 2),
            ),
        )!!
        assertEquals(null, goalDetailText(plan))
        assertEquals(null, goalStreakText(plan))
        assertEquals("2 cíle splněno", goalGroupText(plan))
        assertEquals("a · b", goalNamesText(plan))
    }

    @Test
    fun `a single goal keeps its own period phrase and streak`() {
        val plan = goalCelebrationPlan(
            listOf(progress(count = 4, target = 4, frequency = "monthly", streak = 7)),
        )!!
        assertEquals("4 / 4 · tento měsíc", goalDetailText(plan))
        assertEquals("🔥 7 dní v řadě", goalStreakText(plan))
        assertEquals(null, goalRemainderText(plan))
    }

    @Test
    fun `no goals has no plan`() {
        assertEquals(null, goalCelebrationPlan(emptyList()))
    }

    // ── timing budget ───────────────────────────────────────────────────────
    // The visible lifetime of a celebration is a hard budget, exit included.

    @Test
    fun `compact lives 500ms including the reveal`() {
        val timing = celebrationTiming(GoalCelebrationStyle.COMPACT)
        assertEquals(500L, timing.totalMillis)
        assertEquals(0, timing.exitMillis)
        assertTrue(timing.revealMillis.toLong() <= timing.totalMillis)
        assertTrue(timing.holdMillis >= 0)
    }

    @Test
    fun `full lives 1500ms including the exit`() {
        val timing = celebrationTiming(GoalCelebrationStyle.FULL_SCREEN)
        assertEquals(1_500L, timing.totalMillis)
        assertTrue(timing.exitMillis > 0)
        assertTrue(timing.holdMillis >= 0)
        assertEquals(
            timing.totalMillis,
            timing.revealMillis.toLong() + timing.exitMillis.toLong() + timing.holdMillis,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a timing that overruns its budget fails fast`() {
        requireValidCelebrationTiming(CelebrationTiming(totalMillis = 100, revealMillis = 90, exitMillis = 20))
    }

    @Test
    fun `a celebration token follows the save request, not the goal id`() {
        val sameGoal = listOf(progress(count = 3))
        assertEquals("req-1", celebrationKey("req-1", sameGoal))
        assertTrue(celebrationKey("req-1", sameGoal) != celebrationKey("req-2", sameGoal))
    }

    @Test
    fun `goal snapshot is current only for the captured date and owner`() {
        assertTrue(isGoalSnapshotCurrent("2026-11-15", "owner-a", "2026-11-15", "owner-a"))
        assertFalse(isGoalSnapshotCurrent("2026-11-14", "owner-a", "2026-11-15", "owner-a"))
        assertFalse(isGoalSnapshotCurrent("2026-11-15", "owner-a", "2026-11-15", "owner-b"))
        assertFalse(isGoalSnapshotCurrent("2026-11-15", "owner-a", "2026-11-15", null))
    }

    @Test
    fun `unknown before or after progress never celebrates`() {
        val met = progress(count = 3)
        assertTrue(newlyMetGoals(null, listOf(met)).isEmpty())
        assertTrue(newlyMetGoals(listOf(progress(count = 2)), null).isEmpty())
        assertTrue(newlyMetGoals(emptyList(), listOf(met)).isEmpty())
    }

    @Test
    fun `one check-in save haptic is not repeated by AI reflection persistence`() {
        val manualSaveThenReflection = listOf(
            CheckInWriteKind.SAVE_BUTTON,
            CheckInWriteKind.AI_REFLECTION_UPDATE,
        )
        val preflightSaveThenReflection = listOf(
            CheckInWriteKind.AI_PREFLIGHT_SAVE,
            CheckInWriteKind.AI_REFLECTION_UPDATE,
        )
        assertEquals(1, manualSaveThenReflection.count(::shouldPlayCheckInSuccessHaptic))
        assertEquals(1, preflightSaveThenReflection.count(::shouldPlayCheckInSuccessHaptic))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a blank celebration token fails fast`() {
        celebrationKey(" ", listOf(progress(count = 3)))
    }

    @Test
    fun `style is derived from the transition result`() {
        val before = listOf(
            progress(count = 2, id = "daily", frequency = "daily"),
            progress(count = 2, id = "monthly", frequency = "monthly"),
        )
        val after = listOf(
            progress(count = 3, id = "daily", frequency = "daily"),
            progress(count = 3, id = "monthly", frequency = "monthly"),
        )
        assertEquals(
            GoalCelebrationStyle.FULL_SCREEN,
            goalCelebrationStyle(newlyMetGoals(before, after)),
        )
        assertTrue(newlyMetGoals(after, after).isEmpty())
        assertEquals(null, goalCelebrationStyle(newlyMetGoals(after, after)))
    }
}
