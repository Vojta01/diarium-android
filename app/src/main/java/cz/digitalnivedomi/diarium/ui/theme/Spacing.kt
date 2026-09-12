package cz.digitalnivedomi.diarium.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The one vertical rhythm the top-level screens share.
 *
 * Deliberately tiny — five steps — so spacing reads as a system instead of a
 * series of one-off numbers. The check-in tab already uses the same 16dp gutter
 * and the same 4/6/12/14 ladder inside its cards, and the numbers here are picked
 * so the shell, the dashboard, the history and the stats screens land on exactly
 * that ladder.
 *
 * Usage (all also available as `Dp`, so `VSpace(Spacing.section)` works):
 * - [gutter]       screen edge padding, identical on every tab
 * - [screenTop]    first line of content below the status bar inset
 * - [screenBottom] trailing space under the last card, above the bottom bar
 * - [section]      between two glass cards / major blocks
 * - [block]        between blocks inside one card
 * - [tight]        between a label and the value it names
 * - [cardPadding]  inner padding every [cz.digitalnivedomi.diarium.ui.components.GlassCard] applies
 *
 * Four more steps fill the gaps the screens used to type by hand ([tiny] 4dp,
 * [small] 8dp, [large] 20dp, [huge] 28dp) so new code never has to invent a
 * number that already exists in the ladder. Sizes (radii, strokes, touch targets)
 * live in [Dimens], not here — this object is only "how far apart".
 */
object Spacing {
    val tiny = 4.dp
    val tight = 6.dp
    val small = 8.dp
    val block = 12.dp
    val section = 14.dp
    val cardPadding = 18.dp
    val large = 20.dp
    val huge = 28.dp
    val gutter = 16.dp
    val screenTop = 14.dp
    val screenBottom = 40.dp
}
