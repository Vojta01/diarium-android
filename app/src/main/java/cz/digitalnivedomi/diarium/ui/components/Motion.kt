package cz.digitalnivedomi.diarium.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = progress.value
                alpha = p
                translationY = (1f - p) * 18.dp.toPx()
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
 */
@Composable
fun rememberLightHaptics(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
}
