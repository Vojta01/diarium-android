package cz.digitalnivedomi.diarium.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Diarium brand palette.
 *
 * Dark-only by design: the app never renders a light surface. Indigo is the
 * brand accent, and every "glass" surface is a translucent white overlay on the
 * deep ink background rather than an opaque grey — that is what makes cards read
 * as glass instead of as flat Material default.
 */

// --- Brand accents -----------------------------------------------------------
val Indigo = Color(0xFF6366F1)
val IndigoLight = Color(0xFF818CF8)
val IndigoDeep = Color(0xFF4338CA)
val Violet = Color(0xFF8B5CF6)
val Cyan = Color(0xFF22D3EE)

// --- Backgrounds & surfaces (layered, darkest first) --------------------------
/** App background — the deepest layer, behind every gradient. */
val Ink = Color(0xFF0A0A0F)

/** Gradient anchor used in the top-left of the ambient background wash. */
val InkDeep = Color(0xFF07070B)

val Surface1 = Color(0xFF12121A)
val Surface2 = Color(0xFF1A1A26)
val Surface3 = Color(0xFF232333)
val Outline = Color(0xFF2E2E42)

// --- Text ---------------------------------------------------------------------
val TextPrimary = Color(0xFFEDEDF5)
val TextSecondary = Color(0xFF9C9CB4)
val TextTertiary = Color(0xFF6E6E85)

// --- Semantic -----------------------------------------------------------------
val ErrorRed = Color(0xFFF87171)
val SuccessGreen = Color(0xFF34D399)
val WarningAmber = Color(0xFFFBBF24)

/**
 * The "something needs attention" colour.
 *
 * Same value as [ErrorRed] on purpose — a warning and an error should not be two
 * different reds. It exists as a name so screens that mean *warning* say so
 * instead of each keeping its own private copy (three screens used to).
 */
val WarnColor = ErrorRed

/** Scrim over content behind a dialog, sheet or blocking spinner (ink at ~80%). */
val OverlayScrim = Color(0xCC07070B)

// --- Mood scale (1..5) --------------------------------------------------------
// One palette for check-in, dashboard, calendar and stats, so a mood always
// looks the same wherever it appears.
val MoodAwful = Color(0xFFEF4444)
val MoodBad = Color(0xFFF97316)
val MoodNeutral = Color(0xFFFBBF24)
val MoodGood = Color(0xFF34D399)
val MoodGreat = Color(0xFF22D3EE)

/** Colour for a mood value on the 1..5 scale; null/0 falls back to neutral grey. */
fun moodColor(mood: Int?): Color = when (mood) {
    1 -> MoodAwful
    2 -> MoodBad
    3 -> MoodNeutral
    4 -> MoodGood
    5 -> MoodGreat
    else -> TextTertiary
}
