package cz.digitalnivedomi.diarium.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.theme.Dimens
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.MotionTokens
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Small animated building blocks: the press state, the loading skeleton, the
 * counting number and the live status dot.
 *
 * Everything here follows the same two rules as [StaggeredItem]:
 * 1. animation state is applied in a `graphicsLayer` (or a draw block), so a frame
 *    never recomposes a screen — it only repaints one layer;
 * 2. the timings come from [MotionTokens], so the app moves with one tempo.
 */

/**
 * Press scale of a tappable surface, driven by an [interactionSource].
 *
 * Returns `1f` (and collects nothing) when [enabled] is false, so a non-interactive
 * card costs nothing. Used by [Pressable] and by `GlassCard(onClick = …)`.
 */
@Composable
fun rememberPressScale(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    scaleDown: Float = MotionTokens.pressScale,
): Float {
    if (!enabled) return 1f
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    return scale
}

/**
 * A tappable area with the app's press feel: the content shrinks slightly under the
 * finger, a light haptic fires, and there is **no ripple** — a material ripple over
 * translucent glass reads as grey noise instead of depth.
 *
 * Use it for anything that should feel like a physical pane: cards, tiles, hero
 * buttons, chart cells. For a plain list row keep `Modifier.clickable`.
 *
 * Usage:
 * ```
 * Pressable(onClick = { onOpen(id) }) {
 *     GlassSurface(Modifier.fillMaxWidth()) { … }
 * }
 * ```
 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Dimens.radiusMd),
    enabled: Boolean = true,
    haptics: Boolean = true,
    scaleDown: Float = MotionTokens.pressScale,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, enabled = enabled, scaleDown = scaleDown)
    val feedback = rememberHaptics()

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
            ) {
                if (haptics) feedback.light()
                onClick()
            },
        content = content,
    )
}

/**
 * Skeleton placeholder: a soft surface with a highlight sweeping across it.
 *
 * Draw this while a card's real content is loading (inside the card, at the exact
 * size the content will occupy) instead of a spinner in the middle of an empty box.
 * The sweep is a single draw-phase invalidation, so a list of skeletons is cheap.
 *
 * Usage: `ShimmerBox(Modifier.fillMaxWidth().height(Dimens.skeletonLine))`
 */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Dimens.radiusSm),
    base: Color = Color.White.copy(alpha = 0.05f),
    highlight: Color = Color.White.copy(alpha = 0.12f),
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MotionTokens.shimmerMillis, easing = LinearEasing),
        ),
        label = "shimmerSweep",
    )

    Box(
        modifier = modifier
            .clip(shape)
            .drawWithCache {
                val width = size.width
                val height = size.height
                val travel = width * 2f
                val start = -width + travel * progress
                val brush = Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(start, 0f),
                    end = Offset(start + width, height),
                )
                onDrawBehind { drawRect(brush = brush, size = Size(width, height)) }
            },
    )
}

/**
 * A number that counts up to [value] instead of snapping to it.
 *
 * Only the drawn text changes per frame — no layout, no recomposition of the parent
 * — so it is safe to put a row of these on the dashboard. Rounding is delegated to
 * `String.format(Locale.US, …)` so a value renders identically to the web app.
 *
 * Usage: `AnimatedCounter(value = data.streak, style = MaterialTheme.typography.displaySmall)`
 */
@Composable
fun AnimatedCounter(
    value: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = TextPrimary,
    decimals: Int = 0,
    prefix: String = "",
    suffix: String = "",
    durationMillis: Int = MotionTokens.slowMillis,
) {
    val animated by animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = tween(durationMillis = durationMillis, easing = MotionTokens.decelerateEasing),
        label = "animatedCounter",
    )
    Text(
        text = prefix + formatDecimal(animated.toDouble(), decimals) + suffix,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = 1,
    )
}

/** Integer overload of [AnimatedCounter] for the common count/streak case. */
@Composable
fun AnimatedCounter(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = TextPrimary,
    prefix: String = "",
    suffix: String = "",
    durationMillis: Int = MotionTokens.slowMillis,
) {
    AnimatedCounter(
        value = value.toDouble(),
        modifier = modifier,
        style = style,
        color = color,
        decimals = 0,
        prefix = prefix,
        suffix = suffix,
        durationMillis = durationMillis,
    )
}

/**
 * A small glowing dot that optionally pulses — "synced", "live", "recording".
 *
 * The dot itself is always drawn; the pulse is a soft ring that expands and fades
 * behind it. Keep [pulsing] false for a static state indicator: an always-moving
 * element on a calm screen is noise, not polish.
 *
 * Usage: `Row { StatusDot(accent = SuccessGreen); VSpace(Spacing.tight); Text("Uloženo") }`
 */
@Composable
fun StatusDot(
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
    size: Dp = 8.dp,
    pulsing: Boolean = true,
) {
    // Captured before the draw block so the DrawScope's own `size` cannot shadow it.
    val dotSize = size
    // The animation State is created only while pulsing and is read *inside* the draw
    // block below, so a pulsing dot invalidates a paint — never a recomposition.
    val pulse: State<Float>? = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "statusDot")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = MotionTokens.pulseMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusDotPulse",
        )
    } else {
        null
    }

    Box(
        modifier = modifier
            .size(dotSize * 2.4f)
            .drawBehind {
                val dotPx = dotSize.toPx()
                val radius = dotPx / 2f
                val p = pulse?.value ?: 0f
                if (pulsing) {
                    drawCircle(
                        color = accent.copy(alpha = 0.26f * (1f - p)),
                        radius = radius + radius * 1.8f * p,
                        center = center,
                    )
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent, accent.copy(alpha = 0.72f)),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            },
    )
}

/** `12` or `3.5` — the web app's number formatting, so both surfaces agree. */
private fun formatDecimal(value: Double, decimals: Int): String =
    if (decimals <= 0) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.${decimals}f", value)
    }
