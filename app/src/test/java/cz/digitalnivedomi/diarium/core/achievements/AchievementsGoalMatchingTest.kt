package cz.digitalnivedomi.diarium.core.achievements

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `complete_goal`'s matching rules, after the 2026-10 fix: a goal key must match
 * a stored activity label in any case/diacritics, and a goal may live only in
 * the habits map („studena_sprcha"), which `activities` alone cannot see.
 */
class AchievementsGoalMatchingTest {

    private fun entry(
        date: String,
        activities: List<String> = emptyList(),
        habits: List<String> = emptyList(),
    ) = AchievementEntry(date = date, mood = 4, activities = activities, habitKeys = habits)

    @Test
    fun `a goal key matches a stored label with diacritics and case`() {
        val goal = AchievementGoal(activityKey = "trenink", targetCount = 1, frequency = "weekly")

        assertTrue(goalEverCompleted(goal, listOf(entry("2026-10-05", activities = listOf("Trénink")))))
        assertTrue(goalEverCompleted(goal, listOf(entry("2026-10-05", activities = listOf("TRÉNINK")))))
        assertTrue(goalEverCompleted(goal, listOf(entry("2026-10-05", activities = listOf("trenink")))))
    }

    @Test
    fun `a goal matches a fulfilled habit, not only an activity`() {
        val goal = AchievementGoal(activityKey = "studena_sprcha", targetCount = 1, frequency = "weekly")

        // The real case: the goal keys off the habit, which never appears in
        // `activities` — the raw `contains` could never unlock the badge.
        assertTrue(goalEverCompleted(goal, listOf(entry("2026-10-05", habits = listOf("studena_sprcha")))))
        assertFalse(goalEverCompleted(goal, listOf(entry("2026-10-05", activities = listOf("sprcha")))))
    }

    @Test
    fun `a weekly target needs its count inside one window`() {
        val goal = AchievementGoal(activityKey = "chuze", targetCount = 3, frequency = "weekly")

        assertTrue(
            goalEverCompleted(
                goal,
                listOf(
                    entry("2026-10-05", activities = listOf("Chůze")),
                    entry("2026-10-06", activities = listOf("chůze")),
                    entry("2026-10-07", activities = listOf("CHUZE")),
                ),
            ),
        )
        assertFalse(
            goalEverCompleted(
                goal,
                listOf(
                    entry("2026-09-01", activities = listOf("chůze")),
                    entry("2026-09-10", activities = listOf("chůze")),
                    entry("2026-09-20", activities = listOf("chůze")),
                ),
            ),
        )
    }

    @Test
    fun `the same day never counts twice, even for both lists`() {
        val goal = AchievementGoal(activityKey = "trenink", targetCount = 2, frequency = "weekly")

        // One day with the key twice (activities + habit) is still one day: the
        // window counts distinct dates.
        assertFalse(
            goalEverCompleted(
                goal,
                listOf(
                    entry("2026-10-05", activities = listOf("Trénink", "trenink"), habits = listOf("trenink")),
                ),
            ),
        )
    }
}
