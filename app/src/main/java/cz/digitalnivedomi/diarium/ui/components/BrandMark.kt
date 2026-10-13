package cz.digitalnivedomi.diarium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Violet

/**
 * Brand mark: an indigo→violet glass square with a "D". Shared by the login screen and
 * the onboarding wizard hero — one definition, so the two heroes can never drift apart.
 */
@Composable
fun BrandMark(size: Dp = 76.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Indigo, Violet)))
            .border(1.dp, Color.White.copy(alpha = 0.20f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "D",
            style = MaterialTheme.typography.displaySmall.copy(
                fontSize = (size.value * 0.47f).sp,
                lineHeight = (size.value * 0.50f).sp,
            ),
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}
