package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.i18n.Text
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.MotionTokens
import cz.digitalnivedomi.diarium.ui.theme.OverlayScrim
import cz.digitalnivedomi.diarium.ui.theme.Surface2
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The celebration a saved check-in earns when it pushed a goal over its target.
 *
 * Variant A from the backlog: a full-screen overlay for 2.2 s, tap skips. It owns
 * no state beyond its own animation — the caller keeps the queue and the AI
 * reflection request keeps running behind it, so dismissing the overlay reveals a
 * reflection that is already written.
 */
private const val CELEBRATION_MILLIS = 2_200L

/** One particle of the burst: a fixed angle, so a recomposition cannot reshuffle it. */
private data class Spark(val angle: Float, val speed: Float, val radius: Float, val color: Color)

private fun buildSparks(): List<Spark> {
    val palette = listOf(IndigoLight, WarningAmber, Color(0xFF22D3EE), Color(0xFF34D399), Color(0xFF8B5CF6))
    val random = Random(0x5EED)
    return List(30) { index ->
        Spark(
            angle = (index / 30f) * 6.2831855f + random.nextFloat() * 0.18f,
            speed = 0.55f + random.nextFloat() * 0.45f,
            radius = 2.5f + random.nextFloat() * 3.5f,
            color = palette[index % palette.size],
        )
    }
}

/** „dnes“ / „tento týden“ / „tento měsíc“ — the window the goal was met in. */
private fun periodPhrase(frequency: String): String = when (frequency.lowercase()) {
    "daily" -> "dnes"
    "monthly" -> "tento měsíc"
    else -> "tento týden"
}

@Composable
fun GoalCelebration(
    goal: GoalProgress,
    extra: Int = 0,
    onDone: () -> Unit,
) {
    val haptics = rememberHaptics()
    val reveal = remember(goal.goal.id) { Animatable(0f) }
    val burst = remember(goal.goal.id) { Animatable(0f) }
    val sparks = remember { buildSparks() }

    LaunchedEffect(goal.goal.id) {
        haptics.success()
        launch { reveal.animateTo(1f, tween(MotionTokens.slowMillis)) }
        launch { burst.animateTo(1f, tween(1_100, easing = LinearOutSlowInEasing)) }
        delay(CELEBRATION_MILLIS)
        onDone()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OverlayScrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDone,
            )
            .testTagOrEmpty("goal_celebration"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val progress = burst.value
            if (progress <= 0f || progress >= 1f) return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)
            val fade = 1f - progress
            sparks.forEach { spark ->
                val reach = size.minDimension * 0.46f
                // A small downward pull makes the burst read as gravity, not a starburst.
                val x = center.x + cos(spark.angle) * reach * spark.speed * progress
                val y = center.y + sin(spark.angle) * reach * spark.speed * progress +
                    size.minDimension * 0.10f * progress * progress
                drawCircle(
                    color = spark.color.copy(alpha = fade),
                    radius = spark.radius,
                    center = Offset(x, y),
                )
            }
        }

        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .graphicsLayer {
                    alpha = reveal.value
                    val scale = 0.88f + 0.12f * reveal.value
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(24.dp))
                .background(Surface2)
                .padding(horizontal = 26.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "🎯 Cíl splněn!",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = IndigoLight,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = goal.goal.name,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${goal.count} / ${goal.goal.targetCount} · ${periodPhrase(goal.goal.frequency)}",
                fontSize = 15.sp,
                color = TextSecondary,
            )
            if (goal.streak > 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "🔥 ${goal.streak} dní v řadě",
                    fontSize = 13.sp,
                    color = WarningAmber,
                )
            }
            if (extra > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "+ $extra dalších cílů splněno",
                    fontSize = 13.sp,
                    color = TextSecondary,
                )
            }
        }
    }
}
