package cz.digitalnivedomi.diarium.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Diarium is dark-only. There is deliberately no light scheme: the design is
 * built on translucent surfaces over deep ink, and every colour below assumes
 * that. Anything that needs a "light-ish" surface uses a translucent white
 * overlay instead of a light grey.
 */
private val DiariumDarkColors = darkColorScheme(
    primary = Indigo,
    onPrimary = TextPrimary,
    primaryContainer = IndigoDeep,
    onPrimaryContainer = TextPrimary,
    secondary = Violet,
    onSecondary = TextPrimary,
    tertiary = Cyan,
    onTertiary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface3,
    outline = Outline,
    outlineVariant = Outline,
    error = ErrorRed,
    onError = TextPrimary,
)

@Composable
fun DiariumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DiariumDarkColors,
        typography = DiariumTypography,
        shapes = DiariumShapes,
        content = content,
    )
}
