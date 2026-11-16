package cz.digitalnivedomi.diarium.ui.theme

/**
 * The one reduced-motion decision, kept pure so it can be unit-tested without an
 * emulator.
 *
 * Android exposes two signals, and OEM builds do not always keep them in sync:
 * [android.animation.ValueAnimator.areAnimatorsEnabled] is the modern answer
 * (API 26+, exactly Diarium's minSdk), while `animator_duration_scale` is the
 * dev-options value that older ROMs and accessibility toggles actually write.
 * Either one being off means "no motion".
 */
internal fun shouldReduceMotion(animatorsEnabled: Boolean?, durationScale: Float): Boolean =
    animatorsEnabled == false || durationScale <= 0f
