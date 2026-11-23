package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import cz.digitalnivedomi.diarium.core.i18n.AppLanguage
import cz.digitalnivedomi.diarium.core.i18n.UiDates
import cz.digitalnivedomi.diarium.ui.i18n.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.stats.ScreenTimeBars
import cz.digitalnivedomi.diarium.core.stats.ScreenTimeDay
import cz.digitalnivedomi.diarium.core.stats.ScreenTimeSeries
import cz.digitalnivedomi.diarium.core.stats.ScreenTimeSummary
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import cz.digitalnivedomi.diarium.core.stats.TopAppsBreakdown
import cz.digitalnivedomi.diarium.core.stats.UnlockSummary
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.components.ReadOnlyRow
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.checkin.components.SecondaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.SelectableChip
import cz.digitalnivedomi.diarium.ui.checkin.components.formatMinutes
import cz.digitalnivedomi.diarium.ui.checkin.components.testTagOrEmpty
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.theme.Gradients
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 📱 Screen time **and** 🔓 Odemknutí — the web `ScreenTimeChart.tsx` ported to Compose,
 * split into the two charts its bars were carrying at once, plus the top-apps breakdown
 * the web shows underneath them.
 *
 * A single chart used to answer two questions with one column: its height was seconds
 * of screen time and the count printed underneath was unlocks. The two do not move
 * together (a day of navigation in the car has unlocks and almost no screen time), so
 * one of them always looked wrong. They are now two charts over the same window — the
 * screen-time card keeps the range switch and the summary the web prints, and the
 * unlock card has its own bars, bands, average line and summary numbers.
 *
 * A supplied inclusive range is clamped to today. Today remains visible as a partial
 * observation in aggregated timelines; completed-day averages and trends exclude it.
 * Daily bars select a date; long ranges use calendar week/month totals and retain their source-day drill-down.
 *
 * The caller supplies the global inclusive period. A tap reports a selected source date
 * (or null to clear) and never opens check-in. Long periods aggregate into calendar
 * weeks/months, with per-date drill-down retained inside each selected bucket.
 *
 * A day with no synced data draws no bar and reads "Žádná data" in the detail — it is
 * never drawn as zero screen time, and never as zero unlocks.
 */
@Composable
fun ScreenTimeCharts(
    entries: List<StatsDay>, today: String, modifier: Modifier = Modifier,
    /** Inclusive ISO-8601 global period; legacy callers default to the last 7 days. */
    startDate: String = LocalDate.parse(today).minusDays((SCREEN_TIME_WEEK - 1).toLong()).toString(),
    endDate: String = today,
    /** Controlled selection; pass null to clear it. */
    selectedDate: String? = null,
    /** Select or clear a source date; this never opens check-in. */
    onSelectDate: (String?) -> Unit = {},
    /** @deprecated Compatibility only; never invoked by chart selection. Remove at integration. */
    @Suppress("UNUSED_PARAMETER") onOpenDay: ((String) -> Unit)? = null,
) {
    var windowDays by remember { mutableStateOf(SCREEN_TIME_WEEK) }
    var expandedBucketStart by remember { mutableStateOf<String?>(null) }

    val explicitRange = true
    // Timeline bars include today's partial observation; aggregate summaries exclude it.
    val effectiveEnd = visibleScreenTimeEnd(startDate, endDate, today)
    val window = if (effectiveEnd != null && startDate <= effectiveEnd)
        ScreenTimeDateRange.days(entries, startDate, effectiveEnd) else emptyList()
    val effectiveDays = window.size.coerceAtLeast(1)
    val summary = inclusiveScreenTimeSummary(window.filterNot { it.date == today })
    val unlocks = inclusiveUnlockSummary(window.filterNot { it.date == today })
    val windowDates = window.map { it.date }.toSet()
    val windowEntries = entries.filter { it.date in windowDates }
    val selectedEntries = if (selectedDate == null) windowEntries else windowEntries.filter { it.date == selectedDate }
    val selectedDay = selectedDate?.let { date -> window.firstOrNull { it.date == date } }
    val selectedTotalSeconds = if (selectedDate == null) summary.totalSeconds else selectedDay?.seconds ?: 0
    val breakdown = StatsMath.topApps(selectedEntries, limit = APP_LIST_LIMIT)
    val buckets = screenTimeBuckets(window, today = today)
    val aggregated = buckets.any { it.days.size > 1 }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (aggregated) {
            AggregatedDigitalCharts(
                buckets = buckets,
                today = today,
                selectedDate = selectedDate,
                expandedBucketStart = expandedBucketStart,
                onExpandBucket = { expandedBucketStart = if (expandedBucketStart == it) null else it },
                onSelectDate = { date -> onSelectDate(if (selectedDate == date) null else date) },
            )
        } else {
        ScreenTimeCard(
            window = window,
            windowDays = effectiveDays,
            showRangeChips = !explicitRange,
            today = today,
            selectedDate = selectedDate,
            summary = summary,
            onSelectRange = { /* Global period is owned by StatsScreen. */ },
            onSelectDate = { date -> onSelectDate(if (selectedDate == date) null else date) },
        )

        UnlockCard(
            window = window,
            windowDays = effectiveDays,
            showRangeChip = !explicitRange,
            today = today,
            selectedDate = selectedDate,
            summary = unlocks,
            onSelectDate = { date -> onSelectDate(if (selectedDate == date) null else date) },
        )
        }

        selectedDay?.let { selected ->
            SelectedDayCard(
                day = selected,
                entry = entries.firstOrNull { it.date == selected.date },
                onClose = { onSelectDate(null) },
                onOpenDay = null,
            )
        }

        CoverageLabel(window = window)
        TopAppsCard(breakdown = breakdown, windowDays = effectiveDays, totalSeconds = selectedTotalSeconds, selectedDate = selectedDate)
    }
}

/**
 * The screen-time chart: seconds on the screen, the web's colours and its three numbers.
 *
 * This card also owns the range switch for both charts — it is the first of the two, and
 * whoever moves the range moves it for the pair.
 */
@Composable
private fun ScreenTimeCard(
    window: List<ScreenTimeDay>,
    windowDays: Int,
    showRangeChips: Boolean,
    today: String,
    selectedDate: String?,
    summary: ScreenTimeSummary,
    onSelectRange: (Int) -> Unit,
    onSelectDate: (String) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader("📱 Screen time")
        VSpace(6)
        Text(
            text = "Čas na obrazovce",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(2)
        SectionHint(windowCaption(window = window, windowDays = windowDays, today = today))

        VSpace(12)
        if (showRangeChips) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip(
                text = "7 dní",
                selected = windowDays == SCREEN_TIME_WEEK,
                testTag = "screen_time_range_week",
            ) { onSelectRange(SCREEN_TIME_WEEK) }
            SelectableChip(
                text = "30 dní",
                selected = windowDays == SCREEN_TIME_MONTH,
                testTag = "screen_time_range_month",
            ) { onSelectRange(SCREEN_TIME_MONTH) }
        }

        if (window.none { it.seconds != null }) {
            VSpace(14)
            SectionHint(EMPTY_SCREEN_TIME)
            return@GlassCard
        }

        VSpace(14)
        Row(modifier = Modifier.fillMaxWidth()) {
            SummaryCell(
                label = "Průměr denně",
                value = summary.averageSeconds?.let { formatMinutes(it.toInt() / 60) } ?: "—",
                modifier = Modifier.weight(1f),
            )
            SummaryCell(
                label = "Celkem",
                value = formatMinutes(summary.totalSeconds / 60),
                modifier = Modifier.weight(1f),
            )
            SummaryCell(
                label = "Nejvíc",
                value = formatMinutes((summary.maxSeconds ?: 0) / 60),
                modifier = Modifier.weight(1f),
            )
        }

        VSpace(14)
        BarChartTrack(
            window = window,
            windowDays = windowDays,
            today = today,
            selectedDate = selectedDate,
            metric = SCREEN_TIME_METRIC,
            maxValue = summary.maxSeconds ?: 0,
            average = summary.averageSeconds,
            onSelectDate = onSelectDate,
        )

        VSpace(8)
        SectionHint(ScreenTimeBars.SCREEN_TIME_LEGEND)

        VSpace(12)
        GlassDivider()
        VSpace(12)
        BucketLegend(labels = ScreenTimeBars.TIME_BUCKET_LABELS)
    }
}

/**
 * The unlock chart: the day's unlock count, on its own scale.
 *
 * The count is small and flat where screen time is large and spiky, so it gets its own
 * bands and its own height scale — a 40-unlock day must not be drawn at 2 % of the
 * track because some other day held the screen for six hours. It has no range switch of
 * its own: it shows the window the screen-time card above is set to, and says so in its
 * caption.
 */
@Composable
private fun UnlockCard(
    window: List<ScreenTimeDay>,
    windowDays: Int,
    showRangeChip: Boolean,
    today: String,
    selectedDate: String?,
    summary: UnlockSummary,
    onSelectDate: (String) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader(
            "🔓 Odemknutí",
            trailing = if (showRangeChip) {
                {
                    Text(
                        text = "· $windowDays dní",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
            } else null,
        )
        VSpace(6)
        Text(
            text = "Počet odemknutí",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(2)
        SectionHint(unlockCaption(window = window, windowDays = windowDays, today = today))

        val average = summary.averageUnlocks
        if (window.none { it.unlocks != null }) {
            VSpace(14)
            SectionHint(EMPTY_UNLOCKS)
            return@GlassCard
        }

        VSpace(14)
        Row(modifier = Modifier.fillMaxWidth()) {
            SummaryCell(
                label = "Průměr denně",
                value = average?.let(::unlockAverageLabel) ?: "—",
                modifier = Modifier.weight(1f),
            )
            SummaryCell(
                label = "Celkem",
                value = unlockCountLabel(summary.totalUnlocks),
                modifier = Modifier.weight(1f),
            )
            SummaryCell(
                label = "Nejvíc",
                value = unlockCountLabel(summary.maxUnlocks ?: 0),
                modifier = Modifier.weight(1f),
            )
        }

        VSpace(14)
        BarChartTrack(
            window = window,
            windowDays = windowDays,
            today = today,
            selectedDate = selectedDate,
            metric = UNLOCK_METRIC,
            maxValue = summary.maxUnlocks ?: 0,
            average = average,
            onSelectDate = onSelectDate,
        )

        VSpace(8)
        SectionHint(ScreenTimeBars.UNLOCK_LEGEND)

        VSpace(12)
        GlassDivider()
        VSpace(12)
        BucketLegend(labels = ScreenTimeBars.UNLOCK_BUCKET_LABELS)
    }
}

/**
 * One metric's chart: the gridlines, the bars, the dashed average and the two label rows
 * under them, which is everything the two cards draw identically.
 *
 * Both charts share the slot maths ([ScreenTimeSeries.labelIndices] puts a label on the
 * same days in both) but not their scale: each is given its own [maxValue] and
 * [average], and each reads its numbers and picks its colours through its [metric].
 */
@Composable
private fun BarChartTrack(
    window: List<ScreenTimeDay>,
    windowDays: Int,
    today: String,
    selectedDate: String?,
    metric: BarMetric,
    maxValue: Int,
    average: Double?,
    onSelectDate: (String) -> Unit,
) {
    val selectedIndex = window.indexOfFirst { it.date == selectedDate }.takeIf { it >= 0 }
    val labelled = ScreenTimeSeries.labelIndices(
        count = window.size,
        windowDays = windowDays,
        selectedIndex = selectedIndex,
    )

    val haptics = rememberHaptics()
    // Bars travel to their height whenever the window or the metric changes; the
    // value is identical, only the drawn height is animated (0 -> 1 of the full bar).
    val grow = remember { Animatable(0f) }
    LaunchedEffect(window, metric) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(durationMillis = 520, easing = FastOutSlowInEasing))
    }

    Box(modifier = Modifier.fillMaxWidth().height(SCREEN_TIME_TRACK)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val count = window.size
            if (count == 0) return@Canvas
            val slot = size.width / count
            val grown = grow.value

            listOf(0.25f, 0.5f, 0.75f).forEach { fraction ->
                val y = size.height * fraction
                drawLine(
                    color = Color.White.copy(alpha = 0.05f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
            }

            val barWidth = (slot * 0.6f).coerceAtLeast(2f)
            window.forEachIndexed { index, day ->
                val value = metric.valueOf(day) ?: 0
                // A day the worker never reported draws no column at all; the label row
                // is what tells "the phone said zero" from "nothing was ever synced".
                if (metric.valueOf(day) == null) return@forEachIndexed
                val barHeight = size.height * ScreenTimeBars.barFraction(value, maxValue) * BAR_HEADROOM * grown
                if (barHeight <= 0f && value != 0) return@forEachIndexed
                // Same bucket colour as before (the mapping stays web-identical), just
                // painted as a top-bright ramp with a pill cap so a column reads as a
                // lit object instead of a flat stripe.
                val bucket = BUCKET_COLORS[metric.bucketOf(value).coerceIn(0, BUCKET_COLORS.lastIndex)]
                drawRoundRect(
                    brush = Gradients.fill(listOf(bucket, bucket.copy(alpha = 0.42f)), vertical = true),
                    topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - barHeight),
                    size = Size(barWidth, barHeight.coerceAtLeast(2f)),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                    alpha = if (selectedDate == null || selectedDate == day.date) 1f else 0.35f,
                )
            }

            if (average != null && average > 0) {
                val y = size.height -
                    ScreenTimeBars.averageLineFraction(average, maxValue) * size.height * BAR_HEADROOM
                drawLine(
                    color = IndigoLight.copy(alpha = 0.55f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
                )
            }
        }

        Row(modifier = Modifier.matchParentSize()) {
            window.forEach { day ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTagOrEmpty(metric.testTag)
                        .semantics {
                            val metricName = if (metric == SCREEN_TIME_METRIC) {
                                if (AppLanguage.isEnglish()) "Screen time" else "Čas na obrazovce"
                            } else {
                                if (AppLanguage.isEnglish()) "Unlocks" else "Odemknutí"
                            }
                            contentDescription = "$metricName, ${shortDateLabel(day.date)}, ${metric.labelOf(metric.valueOf(day))}"
                            selected = selectedDate == day.date
                            role = Role.Button
                        }
                        .clickable {
                            if (selectedDate != day.date) haptics.selection()
                            onSelectDate(day.date)
                        },
                )
            }
        }
    }

    VSpace(6)
    BarValueRow(
        window = window,
        labelled = labelled,
        selectedDate = selectedDate,
        metric = metric,
    )

    VSpace(2)
    Row(modifier = Modifier.fillMaxWidth()) {
        window.forEachIndexed { index, day ->
            Text(
                text = if (index in labelled) weekdayLabel(day.date) else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (day.date == today) IndigoLight else TextTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The label under each bar: this chart's own number, on one line.
 *
 * A label is centred on its own slot and allowed to spill past it
 * (`wrapContentWidth(unbounded = true)`): in the 30-day window a slot is ~11dp on a
 * phone, far narrower than "5h58", so measuring the text against the slot would clip
 * it to an ellipsis under every bar. Only labelled bars get text and labels are
 * [ScreenTimeSeries.THIN_EVERY] slots apart there, so the spill always lands in an
 * empty slot instead of on a neighbour's number.
 *
 * The fused chart printed the unlock count as a second, smaller line under the time;
 * now each chart prints only its own number, which is what let the two rows (and the
 * two scales) come apart in the first place.
 */
@Composable
private fun BarValueRow(
    window: List<ScreenTimeDay>,
    labelled: Set<Int>,
    selectedDate: String?,
    metric: BarMetric,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        window.forEachIndexed { index, day ->
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (index in labelled) {
                    Text(
                        text = metric.labelOf(metric.valueOf(day)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (day.date == selectedDate) IndigoLight else TextSecondary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.wrapContentWidth(
                            align = Alignment.CenterHorizontally,
                            unbounded = true,
                        ),
                    )
                }
            }
        }
    }
}

/** How one chart reads its number off a day and draws it — see [SCREEN_TIME_METRIC]. */
private class BarMetric(
    /** The day's value for this chart, or null when the worker never reported it. */
    val valueOf: (ScreenTimeDay) -> Int?,
    /** Which of [ScreenTimeBars.BUCKETS] bands the value falls into (its bar colour). */
    val bucketOf: (Int) -> Int,
    /** What the bar prints underneath. */
    val labelOf: (Int?) -> String,
    /** The tag this chart's bar hit-areas carry. */
    val testTag: String,
)

/** Screen time: seconds, the web's six time buckets, the web's compact duration label. */
private val SCREEN_TIME_METRIC = BarMetric(
    valueOf = { it.seconds },
    bucketOf = ScreenTimeBars::timeBucket,
    labelOf = ScreenTimeSeries::secondsLabel,
    testTag = SCREEN_TIME_BAR_TAG,
)

/** Unlocks: the raw count, its own bands, the count itself as the label. */
private val UNLOCK_METRIC = BarMetric(
    valueOf = { it.unlocks },
    bucketOf = ScreenTimeBars::unlockBucket,
    labelOf = ScreenTimeBars::unlockCountLabel,
    testTag = UNLOCK_BAR_TAG,
)

/** The six colour dots and their band captions under a chart. */
@Composable
private fun BucketLegend(labels: List<String>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        labels.forEachIndexed { index, label ->
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(BUCKET_COLORS[index % BUCKET_COLORS.size]),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** The three numbers the web prints above the bars. */
@Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        VSpace(2)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The tapped day: its totals and, when the worker captured them, its own apps. */
@Composable
private fun SelectedDayCard(day: ScreenTimeDay, entry: StatsDay?, onClose: () -> Unit, onOpenDay: (() -> Unit)?) {
    val seconds = day.seconds ?: 0
    val apps = entry?.topApps?.sortedByDescending { it.minutes }.orEmpty()
    // Round per app: the model keeps fractional minutes so a 30–59 s app still
    // reads as its true share instead of being truncated to zero.
    val appsSeconds = apps.sumOf { Math.round(it.minutes * 60).toInt() }
    val other = (seconds - appsSeconds).coerceAtLeast(0)

    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader("📅 Detail dne", trailing = { Text(shortDateLabel(day.date), style = MaterialTheme.typography.labelSmall, color = TextTertiary) })
        VSpace(10)
        if (day.seconds == null && day.unlocks == null) {
            SectionHint(NO_DAY_DATA)
        } else {
            ReadOnlyRow(label = "Čas na obrazovce", value = day.seconds?.let { formatMinutes(it / 60) } ?: "—")
            VSpace(6)
            ReadOnlyRow(
                label = "Odemknutí",
                value = day.unlocks?.let { "$it×" } ?: "—",
            )
        }

        if (apps.isNotEmpty()) {
            VSpace(12)
            GlassDivider()
            VSpace(12)
            SectionHint("Nejpoužívanější aplikace dne")
            VSpace(8)
            apps.forEach { app ->
                AppRow(
                    name = app.app,
                    seconds = Math.round(app.minutes * 60).toInt(),
                    share = if (seconds > 0) (app.minutes * 60) / seconds else 0.0,
                    dotColor = accentFor(app.app),
                    percentageTestTag = "selected_day_app_share_${app.app}",
                )
                VSpace(8)
            }
            if (other > 0) {
                AppRow(
                    name = "Ostatní",
                    seconds = other,
                    share = if (seconds > 0) other.toDouble() / seconds else 0.0,
                    dotColor = TextTertiary,
                )
            }
        }

        VSpace(14)
        if (onOpenDay != null) {
            SecondaryButton(text = "Otevřít detail dne", testTag = "screen_time_open_day", onClick = onOpenDay)
            VSpace(8)
        }
        SecondaryButton(text = "Zavřít", testTag = "screen_time_close", onClick = onClose)
    }
}

/** The window's apps, summed over the same days the bars show. */
@Composable
private fun TopAppsCard(
    breakdown: TopAppsBreakdown?,
    windowDays: Int,
    totalSeconds: Int,
    selectedDate: String? = null,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("🏆 Nejčastější aplikace")
        VSpace(4)
        SectionHint(if (selectedDate == null) "Za posledních $windowDays dní, podle času na obrazovce." else "Aplikace dne ${shortDateLabel(selectedDate)} · klepni na vybraný den znovu pro celé období.")

        VSpace(12)
        if (breakdown == null || breakdown.apps.isEmpty()) {
            SectionHint("Pro toto období nejsou k dispozici žádná data o aplikacích.")
            return@GlassCard
        }

        breakdown.apps.forEachIndexed { index, app ->
            AppRow(
                name = app.name,
                seconds = app.seconds,
                share = app.share,
                dotColor = APP_COLORS[index % APP_COLORS.size],
            )
            if (index != breakdown.apps.lastIndex) VSpace(10)
        }

        if (breakdown.otherSeconds > 0) {
            VSpace(12)
            GlassDivider()
            VSpace(12)
            AppRow(
                name = "Ostatní",
                seconds = breakdown.otherSeconds,
                share = if (breakdown.totalSeconds > 0) {
                    breakdown.otherSeconds.toDouble() / breakdown.totalSeconds
                } else {
                    0.0
                },
                dotColor = TextTertiary,
            )
            VSpace(6)
            SectionHint("Čas, který nezabrala žádná z vypsaných aplikací (systém, jiné appky).")
        }
    }
}

@Composable
private fun AppRow(
    name: String,
    seconds: Int,
    share: Double,
    dotColor: Color,
    percentageTestTag: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatMinutes(seconds / 60),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "${(share * 100).toInt()} %",
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(38.dp).testTagOrEmpty(percentageTestTag),
        )
    }
}

/** Clamp the inclusive chart end to today; current-day usage remains visible in the timeline. */
internal fun visibleScreenTimeEnd(startDate: String, endDate: String, today: String): String? {
    val start = runCatching { LocalDate.parse(startDate) }.getOrNull() ?: return null
    val end = runCatching { LocalDate.parse(endDate) }.getOrNull() ?: return null
    val current = runCatching { LocalDate.parse(today) }.getOrNull() ?: return null
    if (start > end) return null
    // Current-day usage is a valid partial observation; missing values stay null.
    return minOf(end, current).toString()
}

/** Completed-day cutoff for comparison summaries; chart bounds intentionally use visibleScreenTimeEnd. */
internal fun completedScreenTimeEnd(startDate: String, endDate: String, today: String): String? =
    visibleScreenTimeEnd(startDate, endDate, today)?.let { minOf(it, LocalDate.parse(today).minusDays(1).toString()) }
        ?.takeIf { it >= startDate }

/** A readable bucket whose [days] retain exact source dates for subsequent drill-down. */
internal data class ScreenTimeChartBucket(
    val startDate: String,
    val endDate: String,
    val label: String,
    val days: List<ScreenTimeDay>,
    val seconds: Int?,
    val unlocks: Int?,
) {
    val screenTimeCoverage: Int get() = days.count { it.seconds != null }
    val unlockCoverage: Int get() = days.count { it.unlocks != null }
}

/** Weekly buckets through 90 days, calendar-month buckets beyond; bounds stay inclusive. */
internal fun screenTimeBuckets(days: List<ScreenTimeDay>, today: String? = null): List<ScreenTimeChartBucket> {
    if (days.isEmpty()) return emptyList()
    val ordered = days.sortedBy { it.date }
    val start = runCatching { LocalDate.parse(ordered.first().date) }.getOrNull() ?: return emptyList()
    val end = runCatching { LocalDate.parse(ordered.last().date) }.getOrNull() ?: return emptyList()
    val span = ChronoUnit.DAYS.between(start, end).toInt() + 1
    // Short periods are genuinely daily: never collapse 7 or 30 dates into one bar.
    // For 31–90 days, completed dates use calendar weeks and a present today is its
    // own bucket, so its partial value is visible without blending into a complete week.
    val todayEntry = ordered.lastOrNull()?.takeIf { it.date == today }
    val completed = if (todayEntry == null) ordered else ordered.dropLast(1)
    val groups = if (span <= 30) ordered.map { listOf(it) } else if (span <= 90) {
        val weekly = completed.groupBy { date ->
            val d = LocalDate.parse(date.date)
            d.minusDays((d.dayOfWeek.value - 1).toLong()).toString()
        }.toSortedMap().values.toList()
        weekly + listOfNotNull(todayEntry?.let { listOf(it) })
    } else {
        ordered.groupBy { YearMonth.from(LocalDate.parse(it.date)).toString() }
            .toSortedMap().values.toList()
    }
    return groups.map { group ->
        val first = LocalDate.parse(group.first().date)
        val last = LocalDate.parse(group.last().date)
        val monthBucket = span > 90
        ScreenTimeChartBucket(
            startDate = group.first().date,
            endDate = group.last().date,
            label = if (monthBucket) DateTimeFormatter.ofPattern("LLLL yyyy", if (AppLanguage.isEnglish()) java.util.Locale.ENGLISH else java.util.Locale("cs", "CZ")).format(YearMonth.from(first))
                else if (first == last) shortDateLabel(first.toString())
                else "${shortDateLabel(first.toString())} – ${shortDateLabel(last.toString())}",
            days = group,
            seconds = group.mapNotNull { it.seconds }.takeIf { it.isNotEmpty() }?.sum(),
            unlocks = group.mapNotNull { it.unlocks }.takeIf { it.isNotEmpty() }?.sum(),
        )
    }
}

@Composable
private fun AggregatedDigitalCharts(
    buckets: List<ScreenTimeChartBucket>,
    today: String,
    selectedDate: String?,
    expandedBucketStart: String?,
    onExpandBucket: (String) -> Unit,
    onSelectDate: (String) -> Unit,
) {
    @Composable fun metricCard(
        title: String,
        value: (ScreenTimeChartBucket) -> Int?,
        dayValue: (ScreenTimeDay) -> Int?,
        coverage: (ScreenTimeChartBucket) -> Int,
        format: (Int?) -> String,
        tag: String,
    ) {
        val max = buckets.mapNotNull(value).maxOrNull()?.coerceAtLeast(1) ?: 1
        GlassCard(Modifier.fillMaxWidth(), accent = Indigo) {
            SectionHeader(title)
            VSpace(4)
            SectionHint("Každý sloupec shrnuje přesně uvedené dny; chybějící synchronizace není nula. Klepni na období a vyber konkrétní den.")
            VSpace(12)
            TimelineTrendChart(
                values = buckets.map { bucket -> bucketObservedTotal(bucket.days, dayValue) },
                averageValues = buckets.map { bucket -> bucketCompletedTotal(bucket.days, today, dayValue) },
                labels = buckets.map { it.label },
                testTag = "${tag}_timeline",
            )
            buckets.forEach { bucket ->
                val current = value(bucket)
                Row(Modifier.fillMaxWidth().testTagOrEmpty("${tag}_${bucket.startDate}").clickable { onExpandBucket(bucket.startDate) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(bucket.label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(format(current), style = MaterialTheme.typography.labelMedium, color = if (current == null) TextTertiary else TextSecondary)
                }
                VSpace(4)
                Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(Color.White.copy(alpha = .06f))) {
                    if (current != null && current > 0) Box(Modifier.fillMaxWidth((current.toFloat() / max).coerceIn(.02f, 1f)).fillMaxHeight().clip(CircleShape).background(IndigoLight))
                }
                SectionHint("${coverage(bucket)}/${bucket.days.size} dní · ${shortDateLabel(bucket.startDate)} – ${shortDateLabel(bucket.endDate)}" +
                    if (bucket.days.any { it.date == today }) " · dnešek je neúplný" else "")
                if (expandedBucketStart == bucket.startDate) {
                    bucket.days.forEach { day ->
                        val selected = selectedDate == day.date
                        Text(
                            text = "${shortDateLabel(day.date)} · ${format(dayValue(day))}",
                            modifier = Modifier.fillMaxWidth().testTagOrEmpty("${tag}_day_${day.date}")
                                .clickable { onSelectDate(day.date) }.padding(vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) IndigoLight else TextSecondary,
                        )
                    }
                }
                VSpace(10)
            }
        }
    }
    metricCard("📱 Čas na obrazovce", { it.seconds }, { it.seconds }, { it.screenTimeCoverage }, { it?.let { seconds -> formatMinutes(seconds / 60) } ?: ScreenTimeSeries.NO_DATA }, "screen_time_bucket")
    metricCard("🔓 Odemknutí", { it.unlocks }, { it.unlocks }, { it.unlockCoverage }, { it?.let(::unlockCountLabel) ?: ScreenTimeSeries.NO_DATA }, "screen_time_unlock_bucket")
}

/** Long ranges show chronological observed bucket bars and their labeled mean only. */
@Composable
private fun TimelineTrendChart(values: List<Int?>, averageValues: List<Int?>, labels: List<String>, testTag: String) {
    val average = averageBucketTotal(averageValues)
    val scaleValues = values.filterNotNull().map(Int::toDouble) + averageValues.filterNotNull().map(Int::toDouble)
    val maxValue = scaleValues.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Column {
        Canvas(Modifier.fillMaxWidth().height(132.dp).testTagOrEmpty(testTag)
            .semantics { contentDescription = "Chronological digital statistics chart with ${values.count { it != null }} measured periods and the average of reported periods excluding today" }) {
            val left = 8f; val right = size.width - 8f; val top = 8f; val bottom = size.height - 8f
            val dx = if (values.size <= 1) 0f else (right - left) / (values.size - 1)
            val barWidth = (size.width / (values.size * 1.8f)).coerceAtLeast(2f)
            values.forEachIndexed { index, value ->
                if (value != null) {
                    val x = left + index * dx
                    val y = bottom - (value / maxValue).toFloat() * (bottom - top)
                    if (value == 0) {
                        drawCircle(IndigoLight.copy(alpha = .9f), radius = 3f, center = Offset(x, bottom - 1.5f))
                    } else {
                        drawRoundRect(IndigoLight.copy(alpha = .75f), Offset(x - barWidth / 2, y), Size(barWidth, bottom - y), CornerRadius(barWidth / 2))
                    }
                }
            }
            if (average != null) {
                val y = bottom - (average / maxValue).toFloat().coerceIn(0f, 1f) * (bottom - top)
                drawLine(IndigoLight.copy(alpha = .85f), Offset(left, y), Offset(right, y), strokeWidth = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(labels.firstOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall, color = TextTertiary, maxLines = 1)
            Text(labels.lastOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall, color = TextTertiary, maxLines = 1)
        }
        SectionHint("Sloupec = skutečný součet za období; modrá přerušková čára = průměr pozorovaných součtů období (dnešek vynechán). Chybějící období není nula.")
    }
}

internal fun bucketObservedTotal(
    days: List<ScreenTimeDay>, value: (ScreenTimeDay) -> Int?,
): Int? = days.mapNotNull(value).takeIf { it.isNotEmpty() }?.sum()

/** Completed-day totals are used for the reference average; today's partial value is not comparable. */
internal fun bucketCompletedTotal(
    days: List<ScreenTimeDay>, today: String, value: (ScreenTimeDay) -> Int?,
): Int? = days.filterNot { it.date == today }.mapNotNull(value).takeIf { it.isNotEmpty() }?.sum()

internal fun averageBucketTotal(totals: List<Int?>): Double? =
    totals.filterNotNull().takeIf { it.isNotEmpty() }?.map { it.toDouble() }?.average()


private fun inclusiveScreenTimeSummary(window: List<ScreenTimeDay>): ScreenTimeSummary {
    val reported = window.filter { it.seconds != null }
    val max = reported.maxOfOrNull { it.seconds!! }
    return ScreenTimeSummary(reported.map { it.seconds!!.toDouble() }.averageOrNull(), reported.sumOf { it.seconds!! }, max,
        reported.firstOrNull { it.seconds == max }?.date)
}

private fun inclusiveUnlockSummary(window: List<ScreenTimeDay>): UnlockSummary {
    val reported = window.filter { it.unlocks != null }
    val max = reported.maxOfOrNull { it.unlocks!! }
    return UnlockSummary(reported.map { it.unlocks!!.toDouble() }.averageOrNull(), reported.sumOf { it.unlocks!! }, max,
        reported.firstOrNull { it.unlocks == max }?.date)
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

@Composable
private fun CoverageLabel(window: List<ScreenTimeDay>) {
    if (window.isEmpty()) return
    val covered = window.count { it.seconds != null || it.unlocks != null }
    SectionHint("Sync coverage: $covered/${window.size} days (${shortDateLabel(window.first().date)} – ${shortDateLabel(window.last().date)}). — means not synced; 0 is a recorded zero.")
}

/** "posledních 7 dní (4. 9. – 10. 9.)", or the empty-state hint. */
internal fun windowCaption(window: List<ScreenTimeDay>, windowDays: Int, today: String): String {
    if (window.isEmpty()) return "Za posledních $windowDays dní nemám žádná data o času na obrazovce."
    val from = shortDateLabel(window.first().date)
    val to = shortDateLabel(window.last().date)
    val partial = if (window.last().date == today) " Dnešek je neúplné období." else ""
    return "$from – $to ($windowDays dní). Sloupec = jeden den, přerušovaná čára = průměr.$partial"
}

/**
 * The unlock chart's caption: the same window, spelled the same way, plus the sentence
 * that explains why this card has no range switch of its own.
 */
internal fun unlockCaption(window: List<ScreenTimeDay>, windowDays: Int, today: String): String {
    if (window.isEmpty()) return "Za posledních $windowDays dní nemám žádná data o odemknutích."
    val from = shortDateLabel(window.first().date)
    val to = shortDateLabel(window.last().date)
    return "$from – $to ($windowDays dní), stejné období jako čas na obrazovce. " +
        "Sloupec = jeden den, přerušovaná čára = průměr." + if (window.last().date == today) " Dnešek je neúplné období." else ""
}

/** "63×" — the unlock chart's counts carry their unit like everywhere else in the app. */
private fun unlockCountLabel(count: Int): String = "$count×"

/** The average is a mean over days, so it lands between counts — round it, keep the unit. */
private fun unlockAverageLabel(average: Double): String = "${average.roundToInt()}×"

private fun shortDayMonth(date: String): String = CheckInDates.parse(date)?.format(dayMonth) ?: date

private val dayMonth: DateTimeFormatter
    get() = UiDates.dayMonth()

private val fullDate: DateTimeFormatter
    get() = UiDates.numericDate()

/** "5. 9. 2026" for the day-detail card header. */
private fun shortDateLabel(date: String): String = CheckInDates.parse(date)?.format(fullDate) ?: date

/** Same labels the bars' weekday captions use (Monday first), in the UI language. */
private fun weekdayLabel(date: String): String {
    val dayOfWeek = CheckInDates.parse(date)?.dayOfWeek ?: return ""
    return StatsMath.weekdayShortName(dayOfWeek)
}

/** Colour for an app name — stable across the list and the day detail. */
private fun accentFor(app: String): Color {
    val index = (app.hashCode().toLong() and 0x7FFFFFFFL).toInt() % APP_COLORS.size
    return APP_COLORS[index]
}

/**
 * The web's `getBarColorTotals` palette, in bucket order — indexed by
 * [ScreenTimeBars.timeBucket] for the screen-time chart and by
 * [ScreenTimeBars.unlockBucket] for the unlock chart. Both charts draw the same six
 * colours, so "dark red" means "a heavy day" in either one.
 */
private val BUCKET_COLORS = listOf(
    Color(0xFF22C55E),
    Color(0xFF84CC16),
    Color(0xFFEAB308),
    Color(0xFFF97316),
    Color(0xFFEF4444),
    Color(0xFFDC2626),
)

/**
 * The app palette. The web keeps its own `APP_COLORS` list and hands out colours by
 * rank; this keeps the rank-order idea with the palette the rest of the app draws in.
 */
private val APP_COLORS = listOf(
    Color(0xFF6366F1),
    Color(0xFF22D3EE),
    Color(0xFF22C55E),
    Color(0xFFF59E0B),
    Color(0xFFEC4899),
    Color(0xFFA855F7),
    Color(0xFF3B82F6),
    Color(0xFFEF4444),
)

/** How many apps the breakdown lists before the rest becomes "Ostatní". */
private const val APP_LIST_LIMIT = 6

private const val SCREEN_TIME_WEEK = 7
private const val SCREEN_TIME_MONTH = 30

private val SCREEN_TIME_TRACK = 150.dp

/** The tallest bar leaves a sliver free so the top of the track stays readable. */
private const val BAR_HEADROOM = 0.94f

private const val SCREEN_TIME_BAR_TAG = "screen_time_bar"
private const val UNLOCK_BAR_TAG = "screen_time_unlock_bar"

private const val EMPTY_SCREEN_TIME =
    "Zatím žádná data o čase na obrazovce. Časy čte aplikace přímo z telefonu " +
        "(UsageStats, stejně jako Digitální rovnováha); první data se objeví po nejbližší synchronizaci."

private const val EMPTY_UNLOCKS =
    "Zatím žádná data o odemknutích. Počty odemknutí sbírá aplikace z telefonu, první data se objeví po nejbližší synchronizaci."

private const val NO_DAY_DATA = "Pro tento den nemám žádná data o čase na obrazovce."
