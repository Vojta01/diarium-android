package cz.digitalnivedomi.diarium.ui.theme

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether this device asks for reduced motion.
 *
 * A `CompositionLocal` instead of a per-screen check: the setting belongs to the
 * whole app, so a screen opts in by reading it and no screen has to know how the
 * platform spells the flag. Default `false` keeps previews and plain JVM hosts
 * animated, exactly like a normal device.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

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

/**
 * Reads reduced-motion settings initially and whenever the activity resumes.
 * No content observer is needed for settings changed outside the app, and the
 * lifecycle effect is removed automatically with the composition.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduceMotion by remember(context) {
        mutableStateOf(readReduceMotion(context))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reduceMotion = readReduceMotion(context)
    }
    return reduceMotion
}

private fun readReduceMotion(context: Context): Boolean =
    runCatching {
        // minSdk is 26, the same API that introduced areAnimatorsEnabled, so the
        // boolean is always callable; the duration scale below remains the
        // fallback for ROMs that only update that value.
        shouldReduceMotion(ValueAnimator.areAnimatorsEnabled(), animatorDurationScale(context))
    }.getOrDefault(false)

private fun animatorDurationScale(context: Context): Float = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
}.getOrDefault(1f)

@Composable
fun DiariumTheme(content: @Composable () -> Unit) {
    val reduceMotion = rememberReduceMotion()
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
        CompositionLocalProvider(
            LocalContentColor provides DiariumDarkColors.onBackground,
            LocalReduceMotion provides reduceMotion,
        ) {
            content()
        }
    }
}
