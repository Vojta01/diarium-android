package cz.digitalnivedomi.diarium.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing

/**
 * The timing vocabulary of the app, so every animation feels like one hand made it.
 *
 * Rule of thumb for new code:
 * - [fastMillis] — a state flip the user caused (chip selected, switch toggled).
 * - [mediumMillis] — something appearing or resizing inside a screen.
 * - [slowMillis] — a screen-level entrance or an emphasised value change.
 * - [shimmerMillis] / [pulseMillis] — decorative loops (skeletons, status dots).
 *
 * [pressScale] is the shared "the surface is being pressed" amount used by
 * `Pressable` and by a clickable `GlassCard`, so a tap feels identical everywhere.
 */
object MotionTokens {

    /** 120ms — immediate feedback on a direct manipulation. */
    const val fastMillis = 120

    /** 220ms — a control appearing, a small layout change. */
    const val mediumMillis = 220

    /** 320ms — screen and card entrances (matches StaggeredItem). */
    const val slowMillis = 320

    /** 55ms — gap between two staggered cards (mirrors Entrance.STEP_MILLIS). */
    const val staggerStepMillis = 55

    /** 330ms — hard ceiling for a whole stagger (mirrors Entrance.MAX_MILLIS). */
    const val staggerMaxMillis = 330

    /** Period of one shimmer sweep across a skeleton. */
    const val shimmerMillis = 1400

    /** Period of one pulse of a live status dot. */
    const val pulseMillis = 1700

    /** Scale a pressed surface shrinks to. */
    const val pressScale = 0.97f

    /** Default easing: starts quickly, settles softly. */
    val standardEasing: Easing = FastOutSlowInEasing

    /** For numbers and reveals that must not overshoot. */
    val decelerateEasing: Easing = LinearOutSlowInEasing

    /** Expressive easing for hero entrances; emphasised-decelerate curve. */
    val emphasizedEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
