package cz.digitalnivedomi.diarium.core.stats

/**
 * Evidence about whether a habit existed and was enabled on a date. HabitDef has no
 * persisted creation date, so callers must not infer historical eligibility from a
 * current definition alone.
 */
enum class HabitEligibility {
    ELIGIBLE,
    NOT_YET_ELIGIBLE,
    DISABLED,
    UNKNOWN,
}

enum class HabitOutcome {
    MET,
    UNMET,
    UNKNOWN,
}

/**
 * Qualifies a habit for one date without changing its stored answer.
 *
 * A false/default-off answer is UNMET only when there is a recorded check-in AND
 * eligibility for that date is proven. An absent check-in, an ineligible/disabled
 * date, or unknown creation/enablement history stays UNKNOWN. A missing per-habit
 * answer (`checked == null`) is equivalent to unchecked only after both proofs.
 */
fun habitOutcome(
    checkInRecorded: Boolean,
    eligibility: HabitEligibility,
    checked: Boolean?,
): HabitOutcome = when {
    !checkInRecorded -> HabitOutcome.UNKNOWN
    eligibility != HabitEligibility.ELIGIBLE -> HabitOutcome.UNKNOWN
    checked == true -> HabitOutcome.MET
    else -> HabitOutcome.UNMET
}

/** Check-in UI's sleep wording, keyed by the stored value (0 is unanswered). */
fun sleepAnswerLabel(value: Int): String = when (value) {
    3 -> "Skvělý"
    2 -> "Normální"
    1 -> "Špatný"
    0 -> "Bez odpovědi"
    else -> "Neznámá hodnota"
}

/** Check-in UI's stress wording, keyed by the stored value (0 is unanswered). */
fun stressAnswerLabel(value: Int): String = when (value) {
    1 -> "Nízký"
    2 -> "Mírný"
    3 -> "Střední"
    4 -> "Vysoký"
    5 -> "Extrémní"
    0 -> "Bez odpovědi"
    else -> "Neznámá hodnota"
}

/**
 * Labels a user-defined numeric scale using only its persisted bounds. These scales
 * have no per-step text semantics in the check-in UI: the numeric endpoints are
 * labelled only as minimum and maximum, with intermediate values kept numeric.
 * Missing/zero values are explicitly unanswered, never displayed as the minimum.
 */
fun numericScaleAnswerLabel(value: Int?, minValue: Int, maxValue: Int): String {
    if (value == null || value == 0) return "Bez odpovědi"
    if (maxValue <= minValue || value !in minValue..maxValue) return "Neznámá hodnota"
    return when (value) {
        minValue -> "$value (minimum)"
        maxValue -> "$value (maximum)"
        else -> "$value / $maxValue"
    }
}
