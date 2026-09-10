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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Ink
import cz.digitalnivedomi.diarium.ui.theme.InkDeep
import cz.digitalnivedomi.diarium.ui.theme.Outline
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
 * Translucent card. [accent] tints the fill and border, which is how the app
 * marks "this card is about X" (mood, screen time, streak…) without introducing
 * a second colour system.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val fillColors = if (accent == null) {
        listOf(Color.White.copy(alpha = 0.065f), Color.White.copy(alpha = 0.022f))
    } else {
        listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0.06f))
    }
    val borderColors = if (accent == null) {
        listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.03f))
    } else {
        listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.10f))
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(fillColors))
            .border(width = 1.dp, brush = Brush.linearGradient(borderColors), shape = shape)
            .padding(18.dp),
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
