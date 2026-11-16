package cz.digitalnivedomi.diarium.ui.checkin

import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.core.data.normalizeFrequency
import cz.digitalnivedomi.diarium.ui.theme.MotionTokens

/** Visual weight of a newly met goal celebration. */
internal enum class GoalCelebrationStyle {
    COMPACT,
    FULL_SCREEN,
}

internal enum class CheckInWriteKind { SAVE_BUTTON, AI_PREFLIGHT_SAVE, AI_REFLECTION_UPDATE }

/** Only writes that persist a check-in in response to the user's action confirm it. */
internal fun shouldPlayCheckInSuccessHaptic(kind: CheckInWriteKind): Boolean =
    kind != CheckInWriteKind.AI_REFLECTION_UPDATE

/** A progress read belongs to exactly one captured account/day pair. */
internal fun isGoalSnapshotCurrent(
    snapshotDate: String,
    snapshotOwner: String,
    activeDate: String,
    activeOwner: String?,
): Boolean = snapshotDate == activeDate && snapshotOwner.isNotBlank() && snapshotOwner == activeOwner

/** The visible lifetime of one celebration, exit included. */
internal data class CelebrationTiming(
    val totalMillis: Long,
    val revealMillis: Int,
    val exitMillis: Int,
) {
    /** Time the card is fully readable before it starts to leave. */
    val holdMillis: Long get() = totalMillis - revealMillis - exitMillis
}

/**
 * The timing budget is a hard contract: a celebration may never outlive
 * [CelebrationTiming.totalMillis], and a bad token combination must fail here,
 * at composition time, rather than silently running twice as long.
 */
internal fun requireValidCelebrationTiming(timing: CelebrationTiming): CelebrationTiming {
    require(timing.totalMillis > 0) { "A celebration needs a positive lifetime." }
    require(timing.revealMillis > 0) { "A celebration needs a positive reveal." }
    require(timing.exitMillis >= 0) { "A celebration exit cannot be negative." }
    require(timing.revealMillis.toLong() + timing.exitMillis.toLong() <= timing.totalMillis) {
        "Reveal and exit (${timing.revealMillis + timing.exitMillis}ms) overrun the " +
            "${timing.totalMillis}ms celebration budget."
    }
    return timing
}

/**
 * 500ms is enough for a daily/weekly notice: the reveal is inside that number and
 * there is no separate exit. A monthly hero gets 1500ms, and its 120ms exit is
 * part of the 1500 — the celebration is gone at the budget, never after it.
 */
internal fun celebrationTiming(style: GoalCelebrationStyle): CelebrationTiming =
    requireValidCelebrationTiming(
        when (style) {
            GoalCelebrationStyle.COMPACT -> CelebrationTiming(
                totalMillis = 500L,
                revealMillis = MotionTokens.mediumMillis,
                exitMillis = 0,
            )

            GoalCelebrationStyle.FULL_SCREEN -> CelebrationTiming(
                totalMillis = 1_500L,
                revealMillis = MotionTokens.slowMillis,
                exitMillis = MotionTokens.fastMillis,
            )
        },
    )

/**
 * One save's celebration, resolved once: which goal is the hero, which goals are
 * named, and how many were not named.
 *
 * A monthly goal is the hero even when the repository returns it after a daily or
 * weekly goal (`createdAt` order is not a ranking). When more than one goal is
 * celebrated the card names at most two and reports the group; it never prints
 * one goal's count or streak as if it described every goal.
 */
internal data class GoalCelebrationPlan(
    val style: GoalCelebrationStyle,
    val hero: GoalProgress,
    val named: List<GoalProgress>,
    val total: Int,
) {
    val single: Boolean get() = total == 1

    /** Goals beyond the two named ones — `total - min(2, total)`. */
    val remainder: Int get() = total - minOf(2, total)
}

internal fun goalCelebrationPlan(goals: List<GoalProgress>): GoalCelebrationPlan? {
    if (goals.isEmpty()) return null
    val style = goalCelebrationStyle(goals) ?: return null
    val hero = if (style == GoalCelebrationStyle.FULL_SCREEN) {
        goals.firstOrNull { normalizeFrequency(it.goal.frequency) == "monthly" } ?: goals.first()
    } else {
        goals.first()
    }
    val named = buildList {
        add(hero)
        goals.firstOrNull { it.goal.id != hero.goal.id }?.let { other -> add(other) }
    }
    return GoalCelebrationPlan(style = style, hero = hero, named = named, total = goals.size)
}

/** The goals a card names, hero first, never more than two. */
internal fun goalNamesText(plan: GoalCelebrationPlan): String =
    plan.named.joinToString(" · ") { it.goal.name }

/** „dnes“ / „tento týden“ / „tento měsíc“ — the window the goal was met in. */
internal fun goalPeriodPhrase(frequency: String): String = when (normalizeFrequency(frequency)) {
    "daily" -> "dnes"
    "monthly" -> "tento měsíc"
    else -> "tento týden"
}

/**
 * The count/frequency line, only for a single goal. For a group this is null on
 * purpose: `3 / 3 · dnes` under two names would attribute one goal's period to
 * the other.
 */
internal fun goalDetailText(plan: GoalCelebrationPlan): String? {
    if (!plan.single) return null
    val hero = plan.hero
    return "${hero.count} / ${hero.goal.targetCount} · ${goalPeriodPhrase(hero.goal.frequency)}"
}

/** The streak line, only for a single goal (and only when it is a real streak). */
internal fun goalStreakText(plan: GoalCelebrationPlan): String? {
    if (!plan.single) return null
    val streak = plan.hero.streak
    return if (streak > 1) "🔥 $streak dní v řadě" else null
}

/** The group aggregate line, only when the card celebrates more than one goal. */
internal fun goalGroupText(plan: GoalCelebrationPlan): String? =
    if (plan.single) null else goalGroupSummaryText(plan.total)

/** Czech plural for the group aggregate: 2–4 „cíle“, 5+ „cílů“. */
internal fun goalGroupSummaryText(count: Int): String = when {
    count == 1 -> "1 cíl splněno"
    count in 2..4 -> "$count cíle splněno"
    else -> "$count cílů splněno"
}

/** The "+N more" tail for goals the two named entries did not cover. */
internal fun goalRemainderText(plan: GoalCelebrationPlan): String? =
    plan.remainder.takeIf { it > 0 }?.let { "+ $it dalších cílů splněno" }

/**
 * A celebration is keyed by the save request, not by a goal id. Saving the same
 * goal again must start a fresh 500/1500ms run; keying on `goals.first().goal.id`
 * silently kept the old `AtomicBoolean`/`LaunchedEffect` and the second save
 * never celebrated. A blank id is a programming error, so it fails fast.
 */
internal fun celebrationKey(saveRequestId: String, goals: List<GoalProgress>): String {
    require(saveRequestId.isNotBlank()) { "A celebration needs the save request id as its token." }
    require(goals.isNotEmpty()) { "A celebration needs at least one goal." }
    return saveRequestId
}

/**
 * Picks one celebration for a save. A monthly goal is the rarer event, so any
 * monthly goal escalates the whole save to the full-screen treatment. Daily and
 * weekly goals share the compact one. An empty list has nothing to celebrate.
 */
internal fun goalCelebrationStyle(goals: List<GoalProgress>): GoalCelebrationStyle? {
    if (goals.isEmpty()) return null
    val hasMonthly = goals.any { normalizeFrequency(it.goal.frequency) == "monthly" }
    return if (hasMonthly) GoalCelebrationStyle.FULL_SCREEN else GoalCelebrationStyle.COMPACT
}

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
