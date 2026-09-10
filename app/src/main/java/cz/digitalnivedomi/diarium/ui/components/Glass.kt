package cz.digitalnivedomi.diarium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Shared "glass" building blocks.
 *
 * A glass surface is a translucent white overlay with a light top-to-bottom
 * gradient border, sitting on the ink background. Never use flat grey here —
 * it kills the depth and makes the app look like default Material.
 */

/** Deep ambient background: vertical ink wash + two soft accent glows. */
@Composable
fun DiariumBackground(
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
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
        content = content,
    )
}

/**
 * The one corner radius every glass surface is cut to. Named so the cards cannot
 * drift apart as new ones are added.
 */
val GlassCornerRadius = 20.dp

/**
 * Translucent card. [accent] tints the fill and border, which is how the app
 * marks "this card is about X" (mood, screen time, streak…) without introducing
 * a second colour system.
 *
 * The fill is a three-stop gradient (brighter at the top-left, deeper into the
 * card) so the surface reads as a raised pane of glass rather than a faint wash;
 * the border uses the same three stops so its bright edge dissolves gradually
 * instead of snapping to nothing after the first pixel.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(GlassCornerRadius),
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fillColors = if (accent == null) {
        listOf(
            Color.White.copy(alpha = 0.095f),
            Color.White.copy(alpha = 0.045f),
            Color.White.copy(alpha = 0.028f),
        )
    } else {
        listOf(
            accent.copy(alpha = 0.26f),
            accent.copy(alpha = 0.11f),
            accent.copy(alpha = 0.05f),
        )
    }
    val borderColors = if (accent == null) {
        listOf(
            Color.White.copy(alpha = 0.13f),
            Color.White.copy(alpha = 0.05f),
            Color.White.copy(alpha = 0.015f),
        )
    } else {
        listOf(
            accent.copy(alpha = 0.52f),
            accent.copy(alpha = 0.18f),
            accent.copy(alpha = 0.06f),
        )
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(fillColors))
            .border(width = 1.dp, brush = Brush.linearGradient(borderColors), shape = shape)
            .padding(Spacing.cardPadding),
        content = content,
    )
}

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

/** Decorative rounded square used behind icons. */
@Composable
fun IconBadge(
    modifier: Modifier = Modifier,
    accent: Color = Indigo,
    size: Int = 40,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape((size / 3).dp)),
        contentAlignment = Alignment.Center,
        content = content,
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
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // Radial indigo wash, clipped to the header box — cheap, drawn once
                // per layout, and it keeps the title from sitting on flat black.
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Indigo.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = size.width * 0.85f,
                    ),
                    radius = size.width * 0.85f,
                    center = Offset(0f, 0f),
                )
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(listOf(IndigoLight, Indigo))),
            )
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
 * Friendly, on-brand empty state: a glowing indigo disc with an emoji, a short
 * title, one explanatory line and an optional action. Used instead of bare grey
 * text on the dashboard, history and stats screens.
 */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Indigo.copy(alpha = 0.32f), Indigo.copy(alpha = 0.05f)),
                    ),
                )
                .border(1.dp, Indigo.copy(alpha = 0.32f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
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
