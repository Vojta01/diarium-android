package cz.digitalnivedomi.diarium.ui.components

import cz.digitalnivedomi.diarium.ui.theme.MotionTokens

/**
 * Timing for the staggered card entrance used by the dashboard, history and stats
 * screens.
 *
 * Pure arithmetic on purpose: the ladder can be unit-tested on the JVM without a
 * device, and the composables ([StaggeredItem]) only consume it.
 *
 * The delay is capped so a long list never turns into a slow-motion reveal — the
 * last card starts at most [MAX_MILLIS] after the first, however many there are.
 */
object Entrance {
    /** Gap between two consecutive cards. Same tempo as every other animation. */
    const val STEP_MILLIS = MotionTokens.staggerStepMillis

    /** Hard ceiling for the whole stagger, so long lists stay snappy. */
    const val MAX_MILLIS = MotionTokens.staggerMaxMillis

    /** Milliseconds [index] waits before it starts fading in. Index 0 starts at once. */
    fun delayMillisFor(index: Int): Int {
        if (index <= 0) return 0
        return (index * STEP_MILLIS).coerceAtMost(MAX_MILLIS)
    }
}
