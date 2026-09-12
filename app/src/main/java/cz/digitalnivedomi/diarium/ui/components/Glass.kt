package cz.digitalnivedomi.diarium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.ui.theme.Dimens
import cz.digitalnivedomi.diarium.ui.theme.Gradients
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Ink
import cz.digitalnivedomi.diarium.ui.theme.InkDeep
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.Violet

/**
 * Shared "glass" building blocks — the surface every screen is built from.
 *
 * A glass surface is a translucent white overlay on the ink background with a
 * gradient fill, a hairline highlight along its top edge and a gradient border
 * that dissolves into the fill. Never use flat grey here — it kills the depth and
 * makes the app look like default Material.
 *
 * Two layers, pick per situation:
 * - [GlassCard]  — padded, `ColumnScope`: 95% of the app (title, rows, divider…).
 * - [GlassSurface] — padded by default, `BoxScope`: charts, overlays, badges,
 *   anything that positions children itself.
 * For a surface that is not a container at all (a sheet, a dialog, an inner pane),
 * use `Modifier.glassSurface(shape)` directly.
 *
 * Every visual richness knob is **off by default except [sheen]** — `elevated`,
 * `glow` and `onClick` are opt-in, so existing call sites keep their exact
 * signature and can be upgraded one at a time.
 */

/** Deep ambient background: vertical ink wash + two soft accent glows (+ optional grid). */
@Composable
fun DiariumBackground(
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
    grid: Boolean = false,
    gridCell: Dp = Dimens.gridCell,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(InkDeep, Ink, InkDeep)))
            .drawBehind {
                // Top-right indigo halo
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width * 1.05f, size.height * -0.05f),
                        radius = size.width * 0.95f,
                    ),
                    radius = size.width * 0.95f,
                    center = Offset(size.width * 1.05f, size.height * -0.05f),
                )
                // Bottom-left violet halo
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Violet.copy(alpha = 0.13f), Color.Transparent),
                        center = Offset(size.width * -0.15f, size.height * 0.92f),
                        radius = size.width * 0.85f,
                    ),
                    radius = size.width * 0.85f,
                    center = Offset(size.width * -0.15f, size.height * 0.92f),
                )
            },
    ) {
        if (grid) GridOverlay(cell = gridCell)
        content()
    }
}

/**
 * The one corner radius every glass surface is cut to. Named so the cards cannot
 * drift apart as new ones are added.
 */
val GlassCornerRadius = Dimens.radiusCard

// -----------------------------------------------------------------------------
// Modifiers — the glass treatment, reusable outside the two container composables
// -----------------------------------------------------------------------------

/**
 * Turns any box into a glass pane: gradient fill, top highlight, glowing accent
 * wash and a gradient border, clipped to [shape].
 *
 * Use it on a `Box`/`Column` the composables do not cover (a bottom sheet, a
 * dialog, an inner pane, a chart frame). Prefer [GlassCard]/[GlassSurface] when a
 * plain container is enough — they apply the standard padding as well.
 *
 * @param elevated lifts the first fill stop so this pane sits above its neighbours.
 * @param sheen    the 1dp highlight along the top edge (default on — it is what
 *                 makes the surface read as glass).
 * @param glow     adds an accent radial wash bleeding from the top-left corner.
 */
fun Modifier.glassSurface(
    shape: Shape,
    accent: Color? = null,
    elevated: Boolean = false,
    sheen: Boolean = true,
    glow: Boolean = false,
    borderWidth: Dp = Dimens.border,
): Modifier {
    var modifier = this
        .clip(shape)
        .background(Brush.linearGradient(Gradients.glassFill(accent, elevated)))

    if (glow) {
        modifier = modifier.accentGlow(
            accent = accent ?: Color.White,
            alpha = if (accent == null) 0.10f else 0.22f,
        )
    }
    if (sheen) {
        modifier = if (accent == null) {
            modifier.topSheen(accent = Color.White, alpha = 0.10f)
        } else {
            modifier.topSheen(accent = accent, alpha = 0.18f)
        }
    }

    return modifier.gradientBorder(
        shape = shape,
        colors = Gradients.glassBorder(accent, strong = glow),
        width = borderWidth,
    )
}

/**
 * A 1dp border painted with a colour list (diagonal by default), so the edge is
 * pale at the top-left and dissolves towards the bottom-right instead of snapping
 * to nothing after the first pixel.
 *
 * Use with `Gradients.glassBorder(accent)` for a standard glass edge, or with any
 * ramp from `Gradients` for a brand-coloured one.
 */
fun Modifier.gradientBorder(
    shape: Shape,
    colors: List<Color>,
    width: Dp = Dimens.border,
    vertical: Boolean = false,
): Modifier = border(width = width, brush = Gradients.border(colors, vertical = vertical), shape = shape)

/**
 * The highlight along the top edge of a pane of glass. Apply **after** `clip(shape)`
 * so the corners stay clean.
 *
 * [accent] tints the highlight; white is the neutral sheen, the surface's accent is
 * what makes an accent card look lit from above.
 */
fun Modifier.topSheen(
    accent: Color = Color.White,
    alpha: Float = 0.10f,
    thickness: Dp = Dimens.border,
): Modifier = drawBehind {
    val sheenHeight = thickness.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, accent.copy(alpha = alpha), Color.Transparent),
        ),
        size = Size(size.width, sheenHeight),
    )
}

/**
 * A soft radial wash of [accent] bleeding in from the top-left corner, drawn
 * inside the surface's own bounds (no API-31 blur, no RenderEffect — it is a plain
 * radial gradient, so it is safe on every supported device).
 *
 * Apply after the fill (`clip` → `background` → `accentGlow`) and before the content.
 */
fun Modifier.accentGlow(
    accent: Color = Indigo,
    alpha: Float = 0.20f,
): Modifier = drawBehind {
    val radius = size.maxDimension * 0.95f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = alpha), Color.Transparent),
            center = Offset.Zero,
            radius = radius,
        ),
        radius = radius,
        center = Offset.Zero,
    )
}

// -----------------------------------------------------------------------------
// Containers
// -----------------------------------------------------------------------------

/**
 * Translucent card. [accent] tints the fill, border and top highlight, which is how
 * the app marks "this card is about X" (mood, screen time, streak…) without
 * introducing a second colour system.
 *
 * Opt-in extras — all defaults keep the previous look and signature:
 * - [elevated] — brighter fill; for the one card that matters most on a screen.
 * - [glow]     — accent wash from the top-left + a stronger border.
 * - [onClick]   — turns the card into a pressable surface: it scales slightly under
 *   the finger, fires a light haptic and shows no ripple.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassCornerRadius),
    accent: Color? = null,
    elevated: Boolean = false,
    sheen: Boolean = true,
    glow: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, enabled = onClick != null)
    val feedback = rememberHaptics()
    val onCardClick = onClick

    val pressModifier = if (onCardClick == null) {
        Modifier
    } else {
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) {
                feedback.light()
                onCardClick()
            }
    }

    Column(
        modifier = modifier
            .then(pressModifier)
            .glassSurface(shape = shape, accent = accent, elevated = elevated, sheen = sheen, glow = glow)
            .padding(Spacing.cardPadding),
        content = content,
    )
}

/**
 * Same glass as [GlassCard] but with `BoxScope` content and a configurable inner
 * padding, for surfaces that place their children themselves: charts, image tiles,
 * a badge pinned to a corner, a full-bleed divider.
 *
 * The default [contentPadding] matches [GlassCard]'s, so the two are visually
 * interchangeable; pass `PaddingValues(0.dp)` for edge-to-edge content.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassCornerRadius),
    accent: Color? = null,
    elevated: Boolean = false,
    sheen: Boolean = true,
    glow: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(Spacing.cardPadding),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .glassSurface(shape = shape, accent = accent, elevated = elevated, sheen = sheen, glow = glow)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
        content = content,
    )
}

// -----------------------------------------------------------------------------
// Small pieces
// -----------------------------------------------------------------------------

/** Small tinted chip — labels, counts, states. */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Uppercase section label with an optional trailing label on the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            fontWeight = FontWeight.SemiBold,
        )
        trailing?.invoke(this)
    }
}

/** Thin 1dp divider that fades out at both ends. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Outline, Color.Transparent),
                ),
            ),
    )
}

/**
 * Decorative rounded square used behind icons.
 *
 * @param gradient fills the badge with a diagonal gradient of [accent] instead of
 *                 a flat tint — use it for the leading badge of the one card that
 *                 should draw the eye.
 */
@Composable
fun IconBadge(
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
    size: Int = 40,
    gradient: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape((size / 3).dp)
    val surface = modifier
        .size(size.dp)
        .clip(shape)

    Box(
        modifier = if (gradient) {
            surface
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.10f))))
                .gradientBorder(shape = shape, colors = listOf(accent.copy(alpha = 0.48f), accent.copy(alpha = 0.12f)))
                .topSheen(accent = Color.White, alpha = 0.14f)
        } else {
            surface
                .background(accent.copy(alpha = 0.18f))
                .border(Dimens.border, accent.copy(alpha = 0.30f), shape)
        },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * A glowing disc behind an emoji or glyph — the app's "here is the subject" anchor
 * (empty states, mood of the day, an unlocked badge).
 *
 * [accent] tints both the radial fill and the hairline ring.
 */
@Composable
fun GlowDisc(
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
    size: Dp = Dimens.glowDisc,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.05f)),
                ),
            )
            .border(Dimens.border, accent.copy(alpha = 0.32f), CircleShape),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/**
 * The vertical accent bar that marks a screen or section title.
 *
 * [colors] is the ramp; the default is the brand indigo. Pass a custom ramp
 * (e.g. `listOf(IndigoLight, accent)`) to tie a section to its accent colour.
 */
@Composable
fun AccentBar(
    modifier: Modifier = Modifier,
    colors: List<Color> = Gradients.brand,
    width: Dp = Dimens.accentBarWidth,
    height: Dp = Dimens.accentBarHeight,
    radius: Dp = 2.dp,
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(Brush.verticalGradient(colors)),
    )
}

/** Zero-height spacer used inside glass cards. */
@Composable
fun VSpace(dp: Int) {
    Spacer(Modifier.height(dp.dp))
}

/** Same as [VSpace], for call sites that use the shared [Spacing] scale. */
@Composable
fun VSpace(dp: Dp) {
    Spacer(Modifier.height(dp))
}

/**
 * The one screen header treatment: a large title with a short supporting line,
 * anchored by an indigo accent bar and a soft indigo glow bleeding in from the
 * top-left so the header belongs to the brand instead of floating as bare text on
 * the ink. Dashboard, history, stats and settings all use this.
 *
 * [accent] tints the glow and the lower stop of the bar — leave it alone unless the
 * screen itself has a colour (an error screen, a themed sub-flow).
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // Radial indigo wash, clipped to the header box — cheap, drawn once
                // per layout, and it keeps the title from sitting on flat black.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = size.width * 0.85f,
                    ),
                    radius = size.width * 0.85f,
                    center = Offset(0f, 0f),
                )
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentBar(colors = listOf(IndigoLight, accent))
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
        }
        Spacer(Modifier.height(Spacing.tight))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(start = 14.dp),
        )
    }
}

/**
 * The context line under a sub-screen's own chrome title. Sub-screens draw their title
 * (and back arrow) in `SubScreenChrome`, so a second big title here would print the
 * same words twice — this shows only the accent-barred context line.
 */
@Composable
fun ScreenSubtitle(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentBar(colors = listOf(IndigoLight, accent))
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
    }
}

/**
 * Friendly, on-brand empty state: a glowing disc with an emoji, a short title, one
 * explanatory line and an optional action. Used instead of bare grey text on the
 * dashboard, history and stats screens.
 *
 * [accent] tints the disc — pass `SuccessGreen`/`WarnColor` when the empty state is
 * really a state (nothing left to do / something failed).
 */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlowDisc(accent = accent, size = Dimens.glowDisc) {
            Text(text = emoji, fontSize = 28.sp)
        }
        VSpace(Spacing.block)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )
        VSpace(Spacing.tight)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            VSpace(Spacing.section)
            action()
        }
    }
}

/** The one spinner style, so every loading state carries the indigo accent. */
@Composable
fun BrandSpinner(
    modifier: Modifier = Modifier,
    size: Int = 20,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size.dp),
        strokeWidth = 2.dp,
        color = IndigoLight,
    )
}
