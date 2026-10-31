package cz.digitalnivedomi.diarium.ui.checkin

import cz.digitalnivedomi.diarium.core.data.GoalProgress

/**
 * Which goals the just-saved check-in pushed over their target.
 *
 * The rule is a *transition*, never a state: a goal that was already met before
 * the save is silent, and so is an empty or unknown progress list. Without that,
 * every later save of the same day would celebrate the same goal again.
 *
 * The two progress snapshots are paired by `goal.id` **and** the current period
 * window. When the window moved between the snapshot and the reload (the save
 * landed right after midnight or at a week boundary), the counts measure
 * different periods and the comparison is meaningless — such a goal is skipped.
 */
internal fun newlyMetGoals(
    before: List<GoalProgress>?,
    after: List<GoalProgress>?,
): List<GoalProgress> {
    if (before.isNullOrEmpty() || after.isNullOrEmpty()) return emptyList()
    val previous = before.associateBy { it.goal.id }
    return after.filter { current ->
        if (!current.targetMet) return@filter false
        val old = previous[current.goal.id] ?: return@filter false
        !old.targetMet && samePeriod(old, current)
    }
}

/** True when both snapshots describe the same period window for the goal. */
private fun samePeriod(before: GoalProgress, after: GoalProgress): Boolean {
    val oldWindow = before.periods.lastOrNull()
    val newWindow = after.periods.lastOrNull()
    return when {
        oldWindow != null && newWindow != null ->
            oldWindow.start == newWindow.start && oldWindow.end == newWindow.end
        // Progress built without history (a test or a future caller): the frequency
        // is the only thing left to compare, and it is stable for a given goal.
        else -> before.goal.frequency == after.goal.frequency
    }
}
