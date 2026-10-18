package cz.digitalnivedomi.diarium.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import cz.digitalnivedomi.diarium.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.components.ReadOnlyRow
import cz.digitalnivedomi.diarium.ui.checkin.components.testTagOrEmpty
import cz.digitalnivedomi.diarium.ui.theme.Gradients
import cz.digitalnivedomi.diarium.ui.theme.IndigoDeep
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.MotionTokens
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import cz.digitalnivedomi.diarium.ui.theme.moodColor
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One column of a mood trend chart: a day in the short windows, a month in the year window.
 *
 * Flattening both into one shape is what lets the year reuse the daily chart's drawing,
 * tap handling and detail rows instead of a second chart that would drift from it.
 */
data class MoodTrendSlot(
    /** Identity for the grow animation and the reveal — a date, or `m3` for March. */
    val key: String,
    /** The caption under the column ("Led"); daily edge captions derive from [key]. */
    val label: String,
    /** What the tap detail calls this column ("Leden 2026", "13. 9."). */
    val title: String,
    /** The value the bar is drawn from; 0 keeps the near-flat grey stub of a blank slot. */
    val mood: Int,
    /** True when the slot holds a real answer — blank slots stay off the moving average. */
    val written: Boolean,
    /** The mood sentence of the tap detail ("🙂 Dobře", "Bez odpovědi"). */
    val moodText: String,
    /** Extra label → value rows of the tap detail. */
    val detailRows: List<Pair<String, String>>,
)

/** How the columns are captioned under the track. */
enum class MoodTrendCaption {
    /** One caption per slot, in a single row — the year's twelve months. */
    PerSlot,

    /** First and last slot only, pushed to the edges — a run of days. */
    EdgeDates,
}

/** Height of the chart track: the tallest bar fills it. */
private val CHART_TRACK = 120.dp

/** Czech numeric date, used for the daily edge captions. */
private val shortDate = DateTimeFormatter.ofPattern("d. M. yyyy", Locale("cs", "CZ"))

/** "5. 9. 2026", or the raw value if it is not a date. */
private fun shortDateOf(date: String): String = CheckInDates.parse(date)?.format(shortDate) ?: date

/** The web prints `toFixed(1)`, so averages keep a dot rather than a Czech comma. */
private fun formatAverageMood(value: Double): String = String.format(Locale.US, "%.1f", value)

/**
 * Vertical brand-lit gradient for one column of a chart: the column's own colour at the
 * top, sinking towards the deep indigo at the baseline (same light direction the glass
 * surfaces use). Public because the statistics screen's distribution card reuses it.
 */
fun chartBarBrush(base: Color): Brush =
    Gradients.fill(listOf(base, lerp(base, IndigoDeep, 0.62f)), vertical = true)

/**
 * 📈 The mood trend chart's shared body: one bar per slot plus the window's moving
 * average drawn as an indigo line over the bars, with a tap detail under them.
 *
 * The caller owns what a slot *is* (a day, a month) and the chrome around the chart (the
 * card, its header and hint); this composable owns the drawing, the tap handling and the
 * detail rows, so both the statistics screen and the dashboard render the same chart.
 *
 * @param slots the columns, left to right. A slot that was never answered stays in as a
 *   near-flat grey stub so it still holds its place on the time axis.
 * @param average the moving average, one (nullable) value per slot; nulls break the line.
 * @param averageLabel legend chip text, e.g. "7denní klouzavý průměr".
 * @param averageDetailLabel label of the average row inside the tap detail.
 * @param caption how the slots are captioned ([MoodTrendCaption.PerSlot] for months,
 *   [MoodTrendCaption.EdgeDates] for a run of days).
 * @param summary optional line under the captions (the year's one-line takeaway).
 * @param onOpenDay when non-null, the tap detail appends an "Otevřít celý den ›" action
 *   calling it with the selected slot's [MoodTrendSlot.key].
 */
@Composable
fun MoodTrendChart(
    slots: List<MoodTrendSlot>,
    average: List<Double?>,
    averageLabel: String,
    averageDetailLabel: String,
    caption: MoodTrendCaption,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onOpenDay: ((String) -> Unit)? = null,
) {
    var selected by remember(slots.size, slots.firstOrNull()?.key) { mutableStateOf<Int?>(null) }
    val haptics = rememberLightHaptics()

    // One animated fraction per slot, keyed by date (or month number): switching the
    // window re-grows each column into its new value instead of snapping to it.
    val barFractions: List<Float> = slots.map { slot ->
        key(slot.key) {
            val target = if (slot.written) {
                (slot.mood / 5f) * 0.92f
            } else {
                0.02f
            }
            val animated by animateFloatAsState(
                targetValue = target,
                animationSpec = tween(
                    durationMillis = MotionTokens.mediumMillis,
                    easing = MotionTokens.standardEasing,
                ),
                label = "moodTrendBar",
            )
            animated
        }
    }

    // A one-shot reveal so changing the period replays the chart entrance.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(slots.size, slots.firstOrNull()?.key) {
        reveal.snapTo(0f)
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = MotionTokens.slowMillis,
                easing = MotionTokens.standardEasing,
            ),
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(CHART_TRACK)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val count = slots.size
                if (count == 0) return@Canvas
                val slot = size.width / count

                // Hairline grid, then one solid baseline — instead of heavy rules.
                listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
                    val y = size.height * fraction
                    drawLine(
                        color = Color.White.copy(alpha = 0.04f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f,
                    )
                }
                drawLine(
                    color = Outline.copy(alpha = 0.55f),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1f,
                )

                // A year's twelve bars can be chunky without touching each other; the
                // daily bars keep the hairline gap that makes a run of days readable.
                val barWidth = (slot * if (count <= 12) 0.5f else 0.6f).coerceAtLeast(2f)
                val barRadius = (barWidth / 2f).coerceAtMost(12f)
                slots.forEachIndexed { index, column ->
                    val heightFraction = barFractions.getOrElse(index) { 0f } * reveal.value
                    val barHeight = size.height * heightFraction
                    if (barHeight <= 0f) return@forEachIndexed
                    drawRoundRect(
                        brush = chartBarBrush(moodColor(column.mood)),
                        topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barRadius, barRadius),
                        alpha = if (selected == null || selected == index) 1f else 0.35f,
                    )
                }

                val line = Path()
                var started = false
                average.forEachIndexed { index, value ->
                    if (value == null) return@forEachIndexed
                    val x = index * slot + slot / 2f
                    val y = size.height - (value.toFloat() / 5f) * size.height * 0.92f * reveal.value
                    if (started) line.lineTo(x, y) else { line.moveTo(x, y); started = true }
                }
                if (started) {
                    drawPath(
                        path = line,
                        brush = Gradients.fill(Gradients.brand),
                        style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                average.forEachIndexed { index, value ->
                    if (value == null) return@forEachIndexed
                    drawCircle(
                        color = IndigoLight,
                        radius = 3f,
                        center = Offset(
                            index * slot + slot / 2f,
                            size.height - (value.toFloat() / 5f) * size.height * 0.92f * reveal.value,
                        ),
                    )
                }
            }

            Row(modifier = Modifier.matchParentSize()) {
                slots.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTagOrEmpty("stats_trend_bar")
                            .clickable {
                                haptics()
                                selected = if (selected == index) null else index
                            },
                    )
                }
            }
        }

        VSpace(6)
        when (caption) {
            MoodTrendCaption.PerSlot -> if (slots.isNotEmpty()) {
                // Every slot is captioned — that is the whole point of the year view: the
                // reader can name a column without tapping it. Months the read holds
                // nothing for stay dim, so the row still lines up with the calendar
                // instead of skipping them (the web's grid does the same).
                Row(modifier = Modifier.fillMaxWidth()) {
                    slots.forEach { column ->
                        Text(
                            text = column.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = if (column.written) TextSecondary else TextTertiary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            MoodTrendCaption.EdgeDates -> if (slots.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(shortDateOf(slots.first().key), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text(shortDateOf(slots.last().key), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
        }

        // The optional takeaway in one line, right under the columns: with twelve bars
        // the shape alone does not say which month was the good one.
        if (summary != null) {
            VSpace(8)
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }

        VSpace(10)
        // Legend as a glass chip instead of a bare dot + line.
        GlassChip(text = averageLabel, accent = IndigoLight)

        selected?.let { index ->
            slots.getOrNull(index)?.let { column ->
                VSpace(10)
                GlassDivider()
                VSpace(10)
                ReadOnlyRow(label = column.title, value = column.moodText)
                column.detailRows.forEach { (label, value) ->
                    VSpace(6)
                    ReadOnlyRow(label = label, value = value)
                }
                average.getOrNull(index)?.let { value ->
                    VSpace(6)
                    ReadOnlyRow(
                        label = averageDetailLabel,
                        value = "${formatAverageMood(value)} / 5",
                    )
                }
                if (onOpenDay != null) {
                    VSpace(8)
                    Text(
                        text = "Otevřít celý den ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = IndigoLight,
                        modifier = Modifier
                            .clickable {
                                haptics()
                                onOpenDay(column.key)
                            }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}
