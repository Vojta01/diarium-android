package cz.digitalnivedomi.diarium.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoDeep
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Ink
import cz.digitalnivedomi.diarium.ui.theme.InkDeep
import cz.digitalnivedomi.diarium.ui.theme.Surface1
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.Violet

/**
 * The chrome the shell draws *around* every screen: the shared backdrop and the
 * bottom navigation bar.
 *
 * It lives next to [TopLevelDestination] on purpose — the bar is the only consumer
 * of the destination list, and both belong to the shell rather than to any one
 * screen. Nothing in here reads screen state, so a screen can be previewed or unit
 * tested on its own exactly as before.
 */

// --- Backdrop ----------------------------------------------------------------

/**
 * The shell's backdrop layer: a very faint 34dp grid plus a soft top vignette,
 * painted over whatever the app root already put underneath ([DiariumBackground],
 * which owns the ink wash and the two brand halos and is shared with the login
 * screen).
 *
 * It is deliberately **not** opaque. Re-painting the wash here would duplicate the
 * app's one background inside the one file that is not supposed to own it, and a
 * second full-screen fill per frame buys nothing. What the shell adds is the layer
 * the app root does not draw: the grid that keeps a large dark panel from reading
 * as flat black, and a vignette so the top of the content dissolves instead of
 * ending on a hard edge.
 *
 * Painted by the shell, so it runs edge to edge behind the status bar and the
 * gesture area — that is what makes the transparent system bars read as part of
 * the app instead of as letterboxing — and so every screen sits on the same
 * surface without any screen file knowing about it.
 */
@Composable
fun DiariumShellBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // Grid at ~2% white: all but invisible, but it stops the wash from
                // reading as flat black on a large panel and gives the halos
                // something to sit on. Drawn with plain lines — no bitmap, no
                // allocation per frame.
                val step = 34.dp.toPx()
                val verticalLine = Color.White.copy(alpha = 0.020f)
                val horizontalLine = Color.White.copy(alpha = 0.013f)
                var x = step
                while (x < size.width) {
                    drawLine(verticalLine, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                    x += step
                }
                var y = step
                while (y < size.height) {
                    drawLine(horizontalLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }

                // Vignette: darkens the top of the shell so the status bar reads as
                // part of the backdrop rather than as a band the app happens to end
                // under. Kept to one gradient rect; no blur, no per-frame work.
                val vignette = size.height * 0.16f
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(InkDeep.copy(alpha = 0.55f), Color.Transparent),
                        startY = 0f,
                        endY = vignette,
                    ),
                    size = androidx.compose.ui.geometry.Size(size.width, vignette),
                )
            },
        content = content,
    )
}

// --- Motion ------------------------------------------------------------------

/**
 * Every timing the shell animates with, in one place.
 *
 * Two families only, so navigating always feels like the same app:
 * - **tab**: between two peer tabs — a dissolve with a hair of scale. No sideways
 *   travel, because "left to right" between five tabs would be a lie.
 * - **push / pop**: into a sub-screen (and back) — the same dissolve plus a small
 *   slide, which is what makes the back arrow's behaviour legible.
 *
 * Both are ~200-240ms: long enough to read as motion, short enough that no one
 * waits for it. Nothing here exceeds the compose default of a simple tween, so
 * there is no layout-size animation and no per-frame measure pass.
 */
object ShellMotion {
    /** Duration of the incoming screen. */
    const val SCREEN_MILLIS = 240

    /** Duration of the outgoing screen — shorter, so the new screen leads. */
    const val SCREEN_EXIT_MILLIS = 180

    /** The push slide is 1/[SLIDE_DIVISOR] of the width: a hint of direction. */
    private const val SLIDE_DIVISOR = 14

    private val enter = tween<Float>(SCREEN_MILLIS, easing = LinearOutSlowInEasing)
    private val exit = tween<Float>(SCREEN_EXIT_MILLIS, easing = FastOutLinearInEasing)
    private val enterOffset = tween<IntOffset>(SCREEN_MILLIS, easing = LinearOutSlowInEasing)
    private val exitOffset = tween<IntOffset>(SCREEN_EXIT_MILLIS, easing = FastOutLinearInEasing)

    /** A peer tab arriving. */
    val tabEnter: EnterTransition = fadeIn(enter) + scaleIn(enter, initialScale = 0.975f)

    /** A peer tab leaving (or being covered by a push). */
    val tabExit: ExitTransition = fadeOut(exit) + scaleOut(exit, targetScale = 0.99f)

    /** A sub-screen arriving from the right. */
    val pushEnter: EnterTransition =
        fadeIn(enter) + slideInHorizontally(enterOffset) { fullWidth -> fullWidth / SLIDE_DIVISOR }

    /** A sub-screen being covered (rare — a sub-screen that pushes another). */
    val pushExit: ExitTransition =
        fadeOut(exit) + slideOutHorizontally(exitOffset) { fullWidth -> -fullWidth / SLIDE_DIVISOR }

    /** A sub-screen leaving on back: it goes back out the way it came in. */
    val popExit: ExitTransition =
        fadeOut(exit) + slideOutHorizontally(exitOffset) { fullWidth -> fullWidth / SLIDE_DIVISOR }
}

// --- Bottom navigation -------------------------------------------------------

/** Height of the tab row itself, above the gesture/navigation inset. */
private val BarRowHeight = 62.dp

/** Size of the sliding capsule, and the icon inside a tab. */
private val IndicatorHeight = 36.dp
private val IconSize = 23.dp

/**
 * The bottom navigation bar.
 *
 * Same five destinations and the same click behaviour as before ([TopLevelDestination],
 * handed back through [onSelect] exactly as the shell's `switchTab` expects), but the
 * selection is now a real, moving object: one indigo capsule springs between the tabs
 * (see [SlidingIndicator]) while the icon and label under it light up continuously,
 * so the bar reads as a physical control rather than five independent toggles.
 *
 * Insets are handled here, not by the caller: the wash is painted across the whole
 * node *before* the navigation-bar inset is applied, so the gradient (and therefore
 * the dark backdrop behind it) continues into the gesture area, while the tabs
 * themselves stay above it.
 */
@Composable
fun DiariumBottomBar(
    currentRoute: String?,
    onSelect: (TopLevelDestination) -> Unit,
) {
    val tabs = TopLevelDestination.entries
    // -1 on a sub-screen (cíle, škály, export…): no tab is the current one there, and
    // the capsule fades out instead of pointing at Přehled, which is exactly what the
    // Material bar used to do.
    val selectedIndex = tabs.indexOfFirst { it.route == currentRoute }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Fades in from the content above so the bar is the bottom of one
            // surface, not a slab with a hard edge.
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Surface1.copy(alpha = 0.74f),
                        Surface1.copy(alpha = 0.97f),
                    ),
                ),
            )
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        IndigoHairline()
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(BarRowHeight)) {
            // One slot per tab; the capsule is positioned from this width, so it
            // always lands dead-centre on a tab whatever the screen size.
            val tabWidth = maxWidth / tabs.size

            SlidingIndicator(
                targetIndex = selectedIndex,
                visible = selectedIndex >= 0,
                tabWidth = tabWidth,
            )

            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, destination ->
                    BottomBarTab(
                        destination = destination,
                        selected = index == selectedIndex,
                        onClick = { onSelect(destination) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** The 1dp divider that used to separate the bar: still indigo, still fading out. */
@Composable
private fun IndigoHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        Indigo.copy(alpha = 0.45f),
                        Violet.copy(alpha = 0.35f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

/**
 * The sliding selection capsule.
 *
 * It owns its own [Animatable] and is the only thing that recomposes while it
 * travels: the tab items animate their own emphasis independently, so a tab change
 * never recomposes the whole bar per frame. The animatable starts at the current
 * tab, so the very first frame is already in place — there is no slide-in from the
 * left edge on app start.
 *
 * The bloom behind it is drawn *outside* the capsule's clip, so the glow can spill
 * over the neighbouring slots without the capsule inheriting a soft edge.
 */
@Composable
private fun SlidingIndicator(
    targetIndex: Int,
    visible: Boolean,
    tabWidth: Dp,
) {
    val position = remember { Animatable(targetIndex.coerceAtLeast(0).toFloat()) }
    LaunchedEffect(targetIndex) {
        if (targetIndex < 0) return@LaunchedEffect
        position.animateTo(
            targetValue = targetIndex.toFloat(),
            // A little bounce, so the capsule settles like something physical.
            animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        )
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(160),
        label = "bottomBarIndicatorAlpha",
    )

    val pillWidth = minOf(tabWidth - 14.dp, 58.dp).coerceAtLeast(24.dp)
    // Centred in the row by arithmetic rather than by `align`: this composable is not
    // a BoxScope extension, so it stays independent of where the bar places it.
    val pillTop = (BarRowHeight - IndicatorHeight) / 2

    Box(
        modifier = Modifier
            .offset(
                x = tabWidth * position.value + (tabWidth - pillWidth) / 2,
                y = pillTop,
            )
            .width(pillWidth)
            .height(IndicatorHeight)
            .graphicsLayer { this.alpha = alpha }
            .drawBehind {
                val radius = size.width * 1.15f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Indigo.copy(alpha = 0.22f), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
            .clip(RoundedCornerShape(percent = 50))
            .background(
                Brush.verticalGradient(
                    listOf(Indigo.copy(alpha = 0.40f), IndigoDeep.copy(alpha = 0.24f)),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(IndigoLight.copy(alpha = 0.55f), Indigo.copy(alpha = 0.14f)),
                ),
                shape = RoundedCornerShape(percent = 50),
            ),
    )
}

/**
 * One tab: the icon over the label, both emphasised continuously by [selected].
 *
 * Icon and label share a single animated float, so the two never disagree: the icon
 * grows a tenth and goes grey → white while the label goes grey → indigo and gains
 * weight. Both are animated with a non-bouncy spring (the capsule carries the
 * bounce) whose value is clamped when used as a colour fraction — a bouncy spring
 * can overshoot past 1, and a colour fraction is not allowed to.
 *
 * The node is a `selectable` with `Role.Tab`, so the accessibility tree reports the
 * same thing a Material bar would.
 */
@Composable
private fun BottomBarTab(
    destination: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val emphasis by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "bottomBarTabEmphasis",
    )
    val tint = emphasis.coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = {
                    // A light tick on every tab change; the platform tick needs no
                    // VIBRATE permission and respects the system haptics setting.
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
            contentDescription = destination.label,
            tint = lerp(TextSecondary, Color.White, tint),
            modifier = Modifier
                .size(IconSize)
                .graphicsLayer {
                    val scale = 1f + 0.10f * tint
                    scaleX = scale
                    scaleY = scale
                },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = destination.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = lerp(TextSecondary, IndigoLight, tint),
            maxLines = 1,
        )
    }
}
