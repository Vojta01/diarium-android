package cz.digitalnivedomi.diarium.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Fixed *sizes* every screen draws with: corner radii, hairline strokes, control
 * heights, icon/badge sizes and the decorative sizes of the backdrops.
 *
 * Deliberately split from [Spacing], which owns the *rhythm* between elements
 * (16dp gutter, 14dp between cards, …). `Spacing` answers "how far apart", `Dimens`
 * answers "how big". New screens take both from here instead of typing literals,
 * so a value can only ever change in one place.
 *
 * Usage: `RoundedCornerShape(Dimens.radiusMd)`, `.size(Dimens.touchTarget)`,
 * `Modifier.border(Dimens.border, …)`. It is a plain object, so it is safe to read
 * from non-composable code too.
 */
object Dimens {

    // --- Corner radii --------------------------------------------------------

    /** Chips, pills, skeletons, small swatches. */
    val radiusXs = 8.dp

    /** Rows, inputs, inner panes inside a card. */
    val radiusSm = 12.dp

    /** Buttons, square icon badges, modals. */
    val radiusMd = 16.dp

    /** The default glass card (same value as GlassCornerRadius in ui.components). */
    val radiusCard = 20.dp

    /** Hero surfaces, sheets, chart containers. */
    val radiusLg = 24.dp

    /** Screen-sized containers and bottom sheets. */
    val radiusXl = 28.dp

    // --- Strokes -------------------------------------------------------------

    /** The one hairline: card borders, chips, dividers. */
    val border = 1.dp

    /** Emphasis border for the focused or glowing surface. */
    val borderStrong = 1.5.dp

    // --- Controls ------------------------------------------------------------

    /** Minimum height of a tappable control. Comfortably above the platform floor. */
    val controlHeight = 44.dp

    /** Height of a primary action button. */
    val controlHeightLarge = 52.dp

    /** Square touch target for an icon-only affordance. */
    val touchTarget = 44.dp

    // --- Icons & badges ------------------------------------------------------

    /** Smallest glyph, e.g. a clear "✕" inside a chip. */
    val iconXs = 14.dp

    /** Glyph inside a chip. */
    val iconSm = 16.dp

    /** Default Material icon size. */
    val iconMd = 20.dp

    /** Leading icon of a card title / navigation glyph. */
    val iconLg = 24.dp

    /** Small coloured disc behind a status icon. */
    val badgeSm = 32.dp

    /** Default size of `IconBadge`. */
    val badge = 40.dp

    /** Leading avatar or hero badge. */
    val badgeLg = 48.dp

    // --- Decoration ----------------------------------------------------------

    /** Cell size of the subtle grid backdrop. */
    val gridCell = 28.dp

    /** Disc behind an empty-state emoji. */
    val glowDisc = 64.dp

    /** Height of a shimmering skeleton line. */
    val skeletonLine = 12.dp

    /** Vertical travel of an entrance animation, so every screen moves alike. */
    val enterOffset = 18.dp

    /** Fade drawn under the top bar while content scrolls beneath it. */
    val scrimTop = 28.dp

    /** Fade drawn above the bottom bar while content scrolls beneath it. */
    val scrimBottom = 40.dp

    /** Height of a section accent bar (see `AccentBar`). */
    val accentBarHeight = 22.dp

    /** Width of a section accent bar. */
    val accentBarWidth = 4.dp
}
