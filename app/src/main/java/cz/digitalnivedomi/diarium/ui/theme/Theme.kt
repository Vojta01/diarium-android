package cz.digitalnivedomi.diarium.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

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
    ) {
        // A `Text` that names no colour falls back to `LocalContentColor`, and
        // Material 3's own fallback for it is black — right for a light scheme,
        // invisible on Diarium's ink. Every card title that left `color` unset
        // vanished (the "Nastavení headings are black" report), so the theme hands
        // out the dark scheme's foreground as the default and no screen has to
        // remember. Screens that want a different colour still override locally.
        CompositionLocalProvider(LocalContentColor provides DiariumDarkColors.onBackground) {
            content()
        }
    }
}
