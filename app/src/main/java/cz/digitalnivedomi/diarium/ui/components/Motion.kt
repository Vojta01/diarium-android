package cz.digitalnivedomi.diarium.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import cz.digitalnivedomi.diarium.ui.theme.Dimens
import cz.digitalnivedomi.diarium.ui.theme.MotionTokens
import kotlinx.coroutines.delay

/**
 * Motion and haptics: the entrance animation every list uses, and the shared
 * haptic vocabulary. Nothing here draws a colour or a shape — that is `Glass.kt`.
 */

/**
 * Wraps one card so it fades and slides up when its screen first appears.
 *
 * Cheap by construction: a single [Animatable] per card that runs once, and the
 * alpha/offset are applied in a `graphicsLayer` block — the animation invalidates
 * only that layer, never the layout and never a per-frame recomposition of the
 * list. The [Box] keeps the card's real height from the first frame, so nothing
 * below it jumps around as the cards arrive.
 *
 * [index] is the card's position in the list; the delay comes from [Entrance].
 */
@Composable
fun StaggeredItem(index: Int, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val wait = Entrance.delayMillisFor(index)
        if (wait > 0) delay(wait.toLong())
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = MotionTokens.slowMillis, easing = FastOutSlowInEasing),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = progress.value
                alpha = p
                translationY = (1f - p) * Dimens.enterOffset.toPx()
            },
    ) {
        content()
    }
}

/**
 * A light haptic tick for the primary taps outside check-in (tab changes, card and
 * row taps).
 *
 * Uses the platform's long-press tick through Compose's [LocalHapticFeedback], which
 * needs no `VIBRATE` permission and respects the system's haptics setting. Returned
 * as a lambda so callers can drop it straight into an `onClick`.
 *
 * Prefer [rememberHaptics] in new code: it can also express a *selection*, a
 * *confirmation* and a *rejection*, which a single light tick cannot.
 */
@Composable
fun rememberLightHaptics(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
}

/**
 * The app's haptic vocabulary, backed by the platform constants (not by Compose's
 * [HapticFeedbackType], which only offers "long press" and "text handle").
 *
 * Every method is a no-op on a device that has haptics disabled or has no vibrator:
 * the platform call returns false and nothing else happens, so callers never have
 * to check. No `VIBRATE` permission is needed.
 *
 * Usage:
 * ```
 * val haptics = rememberHaptics()
 * haptics.light()          // a row / chip was tapped
 * haptics.selection()      // a value changed inside a picker
 * haptics.medium()         // a card was opened, a long press landed
 * haptics.success()        // a check-in was saved
 * haptics.error()          // something failed
 * ```
 */
@Stable
class Haptics internal constructor(private val view: View) {

    /** A short, soft tick. The default for taps. */
    fun light() = tick(HapticFeedbackConstants.VIRTUAL_KEY)

    /** A lighter tick used while dragging through values. */
    fun selection() =
        tick(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.VIRTUAL_KEY)

    /** A stronger tick for an action that changed something. */
    fun medium() = tick(HapticFeedbackConstants.LONG_PRESS)

    /** Doubled tick for "it worked". Falls back to [medium] below Android 11. */
    fun success() = tick(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS,
    )

    /** Doubled tick for "it failed". Falls back to [medium] below Android 11. */
    fun error() = tick(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    private fun tick(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

/** Remembers the shared [Haptics] holder. Cheap — call it wherever it is needed. */
@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
