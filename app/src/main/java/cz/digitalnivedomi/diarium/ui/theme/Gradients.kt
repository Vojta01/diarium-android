package cz.digitalnivedomi.diarium.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The one place every gradient in the app is defined.
 *
 * Screens never hand-roll a `Brush.linearGradient(listOf(...))` any more: they ask
 * here, so a card, a chip and a hero banner automatically share the same ramp, the
 * same alphas and the same light direction (top-left bright -> bottom-right deep,
 * which is what sells a translucent pane of glass).
 *
 * Usage:
 * ```
 * Modifier.background(Gradients.fill(Gradients.brand, vertical = true))
 * Modifier.border(1.dp, Gradients.border(Gradients.glassBorder(accent)), shape)
 * ```
 * The glass ramps are the important part: [glassFill] / [glassBorder] are what
 * `GlassCard` and `Modifier.glassSurface` paint with, so changing the surface look
 * of the whole app happens here and nowhere else.
 */
object Gradients {

    // --- Brand ramps ---------------------------------------------------------

    /** Indigo light -> indigo. The default accent ramp (buttons, bars, glows). */
    val brand = listOf(IndigoLight, Indigo)

    /** Indigo light -> deep indigo. For large fills that must stay dark. */
    val brandDeep = listOf(IndigoLight, IndigoDeep)

    /** Indigo -> violet. The "premium" ramp, for hero surfaces only. */
    val violetRamp = listOf(IndigoLight, Violet)

    /** Cyan -> indigo -> violet. Used sparingly: hero highlights, streak flames. */
    val aurora = listOf(Cyan, IndigoLight, Violet)

    // --- Glass surface ramps -------------------------------------------------

    /**
     * Fill of a glass surface: bright at the top-left, fading into the card.
     *
     * With [accent] the whole fill is tinted by it, which is how the app marks
     * "this card is about X" without a second colour system. [elevated] lifts the
     * first stop so a focused/primary card sits visibly above its neighbours.
     */
    fun glassFill(accent: Color? = null, elevated: Boolean = false): List<Color> = when {
        accent == null && !elevated -> listOf(
            Color.White.copy(alpha = 0.095f),
            Color.White.copy(alpha = 0.045f),
            Color.White.copy(alpha = 0.028f),
        )
        accent == null -> listOf(
            Color.White.copy(alpha = 0.135f),
            Color.White.copy(alpha = 0.065f),
            Color.White.copy(alpha = 0.035f),
        )
        !elevated -> listOf(
            accent.copy(alpha = 0.26f),
            accent.copy(alpha = 0.11f),
            accent.copy(alpha = 0.05f),
        )
        else -> listOf(
            accent.copy(alpha = 0.36f),
            accent.copy(alpha = 0.16f),
            accent.copy(alpha = 0.07f),
        )
    }

    /**
     * Border of a glass surface. Three stops for the same reason the fill has
     * three: the bright edge has to dissolve gradually instead of snapping to
     * nothing after the first pixel. [strong] is the glowing variant.
     */
    fun glassBorder(accent: Color? = null, strong: Boolean = false): List<Color> = when {
        accent == null && !strong -> listOf(
            Color.White.copy(alpha = 0.13f),
            Color.White.copy(alpha = 0.05f),
            Color.White.copy(alpha = 0.015f),
        )
        accent == null -> listOf(
            Color.White.copy(alpha = 0.22f),
            Color.White.copy(alpha = 0.09f),
            Color.White.copy(alpha = 0.03f),
        )
        !strong -> listOf(
            accent.copy(alpha = 0.52f),
            accent.copy(alpha = 0.18f),
            accent.copy(alpha = 0.06f),
        )
        else -> listOf(
            accent.copy(alpha = 0.70f),
            accent.copy(alpha = 0.28f),
            accent.copy(alpha = 0.10f),
        )
    }

    // --- Brushes -------------------------------------------------------------

    /**
     * A [Brush] for a colour list. Default is the diagonal top-left -> bottom-right
     * direction the whole system uses; [vertical] switches to top -> bottom for
     * bars and backdrops.
     */
    fun fill(colors: List<Color>, vertical: Boolean = false): Brush = if (vertical) {
        Brush.verticalGradient(colors)
    } else {
        Brush.linearGradient(colors)
    }

    /** Same as [fill] but named for border use, so call sites read clearly. */
    fun border(colors: List<Color>, vertical: Boolean = false): Brush = fill(colors, vertical)
}
