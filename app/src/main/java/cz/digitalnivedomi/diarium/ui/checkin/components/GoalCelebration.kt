package cz.digitalnivedomi.diarium.ui.checkin.components

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.ui.checkin.GoalCelebrationPlan
import cz.digitalnivedomi.diarium.ui.checkin.GoalCelebrationStyle
import cz.digitalnivedomi.diarium.ui.checkin.celebrationTiming
import cz.digitalnivedomi.diarium.ui.checkin.goalCelebrationPlan
import cz.digitalnivedomi.diarium.ui.checkin.goalDetailText
import cz.digitalnivedomi.diarium.ui.checkin.goalGroupText
import cz.digitalnivedomi.diarium.ui.checkin.goalNamesText
import cz.digitalnivedomi.diarium.ui.checkin.goalRemainderText
import cz.digitalnivedomi.diarium.ui.checkin.goalStreakText
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlowDisc
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.i18n.Text
import cz.digitalnivedomi.diarium.ui.i18n.translate
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.LocalReduceMotion
import cz.digitalnivedomi.diarium.ui.theme.MotionTokens
import cz.digitalnivedomi.diarium.ui.theme.OverlayScrim
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.WarningAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The burst is decorative; it never outlives the 1500ms hero budget. */
private const val BURST_MILLIS = 900

/** Reduced motion keeps a crossfade only — never a slide, scale or burst. */
private const val REDUCED_MOTION_FADE_MILLIS = 120

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

/**
 * One celebration per save. Daily and weekly goals get a short, non-blocking
 * notice. A monthly goal gets the full-screen moment. Both styles come from the
 * same pure plan, so the hero, the names and the remainder can never disagree.
 *
 * [celebrationKey] is the save request id. It is the identity of *this* run, not
 * of the goals: saving the same goal again must get a fresh `LaunchedEffect` and
 * a fresh once-only guard instead of inheriting the previous run's finished flag.
 */
@Composable
fun GoalCelebration(
    goals: List<GoalProgress>,
    celebrationKey: String,
    onDone: () -> Unit,
    testTag: String? = "goal_celebration",
) {
    val plan = remember(goals, celebrationKey) { goalCelebrationPlan(goals) } ?: return
    when (plan.style) {
        GoalCelebrationStyle.FULL_SCREEN -> FullScreenGoalCelebration(plan, celebrationKey, onDone, testTag)
        GoalCelebrationStyle.COMPACT ->
            CompactGoalCelebration(plan, celebrationKey, onDone, testTag?.let { "${it}_compact" })
    }
}

@Composable
private fun FullScreenGoalCelebration(
    plan: GoalCelebrationPlan,
    celebrationKey: String,
    onDone: () -> Unit,
    testTag: String?,
) {
    val timing = remember(plan.style) { celebrationTiming(plan.style) }
    val reduceMotion = LocalReduceMotion.current
    val revealMillis = if (reduceMotion) {
        minOf(REDUCED_MOTION_FADE_MILLIS, timing.revealMillis)
    } else {
        timing.revealMillis
    }
    val exitMillis = if (reduceMotion) {
        minOf(REDUCED_MOTION_FADE_MILLIS, timing.exitMillis)
    } else {
        timing.exitMillis
    }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val finished = remember(celebrationKey) { AtomicBoolean(false) }
    val reveal = remember(celebrationKey) { Animatable(0f) }
    val exit = remember(celebrationKey) { Animatable(1f) }
    val burst = remember(celebrationKey) { Animatable(0f) }
    val sparks = remember { buildSparks() }
    val dismissLabel = translate("Zavřít oslavu")

    suspend fun finish() {
        if (!finished.compareAndSet(false, true)) return
        if (exitMillis > 0) {
            exit.animateTo(0f, tween(exitMillis, easing = MotionTokens.standardEasing))
        } else {
            exit.snapTo(0f)
        }
        onDone()
    }

    fun dismissManually() {
        haptics.light()
        scope.launch { finish() }
    }

    LaunchedEffect(celebrationKey) {
        exit.snapTo(1f)
        if (reduceMotion) {
            // A static, readable card: no burst, no scale, only a short crossfade.
            burst.snapTo(0f)
            launch { reveal.animateTo(1f, tween(revealMillis, easing = MotionTokens.standardEasing)) }
        } else {
            launch { reveal.animateTo(1f, tween(revealMillis, easing = MotionTokens.emphasizedEasing)) }
            launch { burst.animateTo(1f, tween(BURST_MILLIS, easing = LinearOutSlowInEasing)) }
        }
        // The reveal runs concurrently and the exit starts at `total - exit`, so
        // the card is gone at 1500ms — never 1500 + exit.
        delay((timing.totalMillis - exitMillis).coerceAtLeast(0))
        finish()
    }

    Dialog(
        onDismissRequest = { dismissManually() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = true,
        ),
    ) {
        ClearDialogDim()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = dismissLabel,
                    onClick = { dismissManually() },
                )
                .testTagOrEmpty(testTag)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            // The scrim fades with the card: removing it after the card left was
            // the dark "flash" at the end of the hero.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(OverlayScrim)
                    .graphicsLayer { alpha = exit.value },
            )

            Canvas(Modifier.fillMaxSize()) {
                if (reduceMotion) return@Canvas
                val progress = burst.value
                if (progress <= 0f || progress >= 1f) return@Canvas
                val center = Offset(size.width / 2f, size.height / 2f)
                val fade = 1f - progress
                sparks.forEach { spark ->
                    val reach = size.minDimension * 0.46f
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

            GlassCard(
                modifier = Modifier
                    .padding(horizontal = 28.dp)
                    .graphicsLayer {
                        alpha = reveal.value * exit.value
                        if (!reduceMotion) {
                            val scale = 0.92f + 0.08f * reveal.value
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                accent = IndigoLight,
                elevated = true,
                glow = true,
                opaque = true,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GlowDisc(accent = IndigoLight, size = 64.dp) {
                        Text(
                            text = "🎯",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "🎯 Cíl splněn!",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = IndigoLight,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = goalNamesText(plan),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    goalDetailText(plan)?.let { detail ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                    goalStreakText(plan)?.let { streak ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = streak,
                            style = MaterialTheme.typography.labelMedium,
                            color = WarningAmber,
                        )
                    }
                    goalGroupText(plan)?.let { group ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = group,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                        )
                    }
                    goalRemainderText(plan)?.let { remainder ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = remainder,
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactGoalCelebration(
    plan: GoalCelebrationPlan,
    celebrationKey: String,
    onDone: () -> Unit,
    testTag: String?,
) {
    val timing = remember(plan.style) { celebrationTiming(plan.style) }
    val reduceMotion = LocalReduceMotion.current
    val revealMillis = if (reduceMotion) {
        minOf(REDUCED_MOTION_FADE_MILLIS, timing.revealMillis)
    } else {
        timing.revealMillis
    }
    val finished = remember(celebrationKey) { AtomicBoolean(false) }
    val reveal = remember(celebrationKey) { Animatable(0f) }

    LaunchedEffect(celebrationKey) {
        if (reduceMotion) {
            reveal.animateTo(1f, tween(revealMillis, easing = MotionTokens.standardEasing))
        } else {
            reveal.animateTo(1f, tween(revealMillis, easing = MotionTokens.emphasizedEasing))
        }
        // 500ms total, reveal included; there is no separate exit to add.
        delay((timing.totalMillis - revealMillis).coerceAtLeast(0))
        if (finished.compareAndSet(false, true)) onDone()
    }

    // A non-focusable Popup does not create a modal dialog window, consume outside
    // pointer input, or enter TalkBack's focus traversal. The popup itself has no
    // click handler: the underlying controls remain usable during the 500ms notice.
    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = reveal.value
                        if (!reduceMotion) {
                            translationY = (1f - reveal.value) * -16f
                            val scale = 0.97f + 0.03f * reveal.value
                            scaleX = scale
                            scaleY = scale
                        }
                    }
                    .testTagOrEmpty(testTag)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                accent = IndigoLight,
                elevated = true,
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    GlowDisc(accent = IndigoLight, size = 44.dp) {
                        Text(
                            text = "🎯",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "🎯 Cíl splněn!",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = IndigoLight,
                        )
                        Text(
                            text = goalNamesText(plan),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                        )
                        // Single goal: its own count/frequency and streak. Multiple
                        // goals: only the group aggregate, never one goal's period
                        // presented as the whole result.
                        goalDetailText(plan)?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                            )
                        }
                        goalStreakText(plan)?.let { streak ->
                            Text(
                                text = streak,
                                style = MaterialTheme.typography.labelSmall,
                                color = WarningAmber,
                            )
                        }
                        goalGroupText(plan)?.let { group ->
                            Text(
                                text = group,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                            )
                        }
                        goalRemainderText(plan)?.let { remainder ->
                            Text(
                                text = remainder,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A celebration has its own scrim/gradient or is a deliberate borderless notice;
 * the Dialog window's default dim would sit on top of that and turn a non-modal
 * top card into a dark modal. Best-effort by design: if a future Compose version
 * stops exposing the provider through the view tree, the card still works, only
 * with the platform's default dim.
 */
@Composable
private fun ClearDialogDim() {
    val view = LocalView.current
    SideEffect {
        val provider = generateSequence(view) { it.parent as? View }
            .filterIsInstance<DialogWindowProvider>()
            .firstOrNull()
        provider?.window?.setDimAmount(0f)
    }
}
