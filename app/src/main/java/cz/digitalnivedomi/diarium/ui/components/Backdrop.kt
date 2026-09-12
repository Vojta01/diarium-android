package cz.digitalnivedomi.diarium.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.theme.Dimens
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.InkDeep

/**
 * Backdrops: the ambient wash a screen sits on, and the two fades used when
 * content scrolls under a bar.
 *
 * The point of all of it is the same — a large dark surface must never be flat
 * black. A subtle grid + a soft accent wash gives the eye something to read depth
 * against, which is what makes the glass cards above it look like glass.
 *
 * Usage:
 * ```
 * ScreenBackdrop(accent = Indigo) {          // wash + grid, once per screen
 *     Column(Modifier.verticalScroll(state).edgeFade(top = Dimens.scrimTop, bottom = Dimens.scrimBottom)) { … }
 * }
 * ```
 */

/**
 * Draws a faint 1dp grid over whatever this is applied to.
 *
 * Keep [alpha] very low (0.03–0.05): the grid must be almost subliminal. Never put
 * it on top of text — apply it to the background layer, not to the content.
 */
fun Modifier.subtleGrid(
    cell: Dp = Dimens.gridCell,
    color: Color = Color.White,
    alpha: Float = 0.035f,
): Modifier = drawBehind {
    val step = cell.toPx()
    if (step <= 0f) return@drawBehind
    val hairline = Dimens.border.toPx()
    val stroke = color.copy(alpha = alpha)

    var x = 0f
    while (x <= size.width) {
        drawLine(
            color = stroke,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = hairline,
        )
        x += step
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(
            color = stroke,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = hairline,
        )
        y += step
    }
}

/**
 * Fades the top and/or bottom edge of the content into [color].
 *
 * Apply this to the **scrolling container** (the `Column`/`LazyColumn`), not to the
 * individual items: it draws over the content and dissolves it under the top and
 * bottom bars, so a long list never ends in a hard cut at the screen edge.
 * A non-zero value on both ends turns the column into a "peeking" list.
 */
fun Modifier.edgeFade(
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    color: Color = InkDeep,
): Modifier = drawWithContent {
    drawContent()

    if (top > 0.dp) {
        val topPx = top.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(color, Color.Transparent),
                startY = 0f,
                endY = topPx,
            ),
            size = Size(size.width, topPx),
        )
    }

    if (bottom > 0.dp) {
        val bottomPx = bottom.toPx()
        val startY = size.height - bottomPx
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, color),
                startY = startY,
                endY = size.height,
            ),
            topLeft = Offset(0f, startY),
            size = Size(size.width, bottomPx),
        )
    }
}

/**
 * The grid layer on its own, for screens that already draw their own wash (or sit
 * inside [DiariumBackground] from the shell) but still want the depth cue.
 *
 * Fills the space it is given and never reacts to touches.
 */
@Composable
fun GridOverlay(
    modifier: Modifier = Modifier,
    cell: Dp = Dimens.gridCell,
    color: Color = Color.White,
    alpha: Float = 0.035f,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .subtleGrid(cell = cell, color = color, alpha = alpha),
    )
}

/**
 * A complete screen backdrop for a screen that is **not** inside the app shell
 * (sub-screens, dialogs, the export/report flows): the ambient indigo/violet wash
 * plus the subtle grid.
 *
 * Screens that live in the shell (`DiariumApp`) must NOT call this — the shell
 * already paints [DiariumBackground]; use [GridOverlay] there if the grid is wanted.
 */
@Composable
fun ScreenBackdrop(
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
    grid: Boolean = true,
    gridAlpha: Float = 0.035f,
    content: @Composable BoxScope.() -> Unit,
) {
    DiariumBackground(modifier = modifier, accent = accent) {
        if (grid) GridOverlay(alpha = gridAlpha)
        content()
    }
}
