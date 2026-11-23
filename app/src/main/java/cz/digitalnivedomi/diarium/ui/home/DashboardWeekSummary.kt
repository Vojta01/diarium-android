package cz.digitalnivedomi.diarium.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.data.DashboardData
import cz.digitalnivedomi.diarium.core.data.DashboardDay
import cz.digitalnivedomi.diarium.core.data.DashboardRepository
import cz.digitalnivedomi.diarium.core.data.PhoneTopApp
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.formatTopAppDuration
import cz.digitalnivedomi.diarium.core.data.isRecordedDay

import cz.digitalnivedomi.diarium.ui.checkin.components.formatMinutes
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.i18n.Text
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.core.i18n.UiDates
import java.time.format.DateTimeFormatter
import cz.digitalnivedomi.diarium.ui.theme.Cyan
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight

import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import java.time.LocalDate
import java.util.Locale

import kotlin.math.roundToInt

/** A real inclusive seven-calendar-day window ending on [today], independent of list shape. */
internal fun dashboardCalendarWeek(today: String): List<String> =
    DashboardRepository.weekWindow(today)

/** Completed-week windows: yesterday..6 days earlier, then the adjacent prior week. */
internal fun dashboardCompletedWeek(today: String, weeksBack: Long = 0): List<String> {
    val end = LocalDate.parse(today).minusDays(1 + weeksBack * DashboardRepository.WEEK_DAYS)
    return (DashboardRepository.WEEK_DAYS - 1 downTo 0).map { back -> end.minusDays(back.toLong()).toString() }
}

/** Monday through today; prior window ends on the matching weekday. */
internal fun dashboardComparisonWindow(today: String, previous: Boolean): List<String> {
    val date = LocalDate.parse(today)
    val offset = (date.dayOfWeek.value - 1).toLong()
    val start = date.minusDays(offset).minusWeeks(if (previous) 1 else 0)
    return (0..offset.toInt()).map { start.plusDays(it.toLong()).toString() }
}

internal fun dashboardWeekDays(data: DashboardData): List<DashboardDay> =
    dashboardCalendarWeek(data.today).map { date ->
        data.week.firstOrNull { it.date == date } ?: DashboardDay(
            date = date,
            mood = 0,
            hasEntry = false,
            screenTimeSeconds = null,
            unlocks = null,
        )
    }

internal data class WeekMetric(
    val value: Double?,
    val coveredDays: Int,
    val previousValue: Double?,
    val previousCoveredDays: Int,
    val difference: Double?,
)

/** Compare equivalent weekday windows only when both have full observed coverage. */
internal fun hasComparableCoverage(current: Int, previous: Int, expected: Int = DashboardRepository.WEEK_DAYS): Boolean =
    current == expected && previous == expected

internal fun coverageComparisonHint(current: Int, previous: Int, expected: Int = DashboardRepository.WEEK_DAYS): String? =
    if (!hasComparableCoverage(current, previous, expected)) {
        "Srovnání skryto · pokrytí $current/$expected vs. $previous/$expected dní"
    } else null

/** Nulls are omitted from each denominator; a reported zero remains a real observation. */
internal fun dashboardWeekMetric(
    today: String,
    currentDays: List<DashboardDay>,
    previousDays: List<DashboardDay>,
    kind: DashboardMetricKind,
): WeekMetric {
    val currentWindow = dashboardComparisonWindow(today, previous = false)
    val previousWindow = dashboardComparisonWindow(today, previous = true)
    val current = windowValues(currentWindow, currentDays, kind)
    val previous = windowValues(previousWindow, previousDays, kind)
    val comparable = hasComparableCoverage(current.size, previous.size, currentWindow.size)
    val currentValue = aggregate(current, kind)
    val previousValue = aggregate(previous, kind)
    return WeekMetric(
        value = currentValue,
        coveredDays = current.size,
        previousValue = previousValue,
        previousCoveredDays = previous.size,
        difference = if (comparable && currentValue != null && previousValue != null) currentValue - previousValue else null,
    )
}

internal fun dashboardObservedAverage(values: List<Double?>): Double? =
    values.filterNotNull().filter { it.isFinite() }.takeIf { it.isNotEmpty() }?.average()

private fun windowValues(expectedDates: List<String>, days: List<DashboardDay>, kind: DashboardMetricKind): List<Double> {
    val expected = expectedDates.toSet()
    return days.asSequence()
        .filter { it.date in expected }
        .mapNotNull { day ->
            when (kind) {
                DashboardMetricKind.MOOD -> day.mood.takeIf(::isRecordedDay)?.toDouble()
                DashboardMetricKind.SCREEN_TIME -> day.screenTimeSeconds?.toDouble()?.div(60.0)
                DashboardMetricKind.UNLOCKS -> day.unlocks?.toDouble()
            }
        }
        .toList()
}

private fun aggregate(values: List<Double>, kind: DashboardMetricKind): Double? = when {
    values.isEmpty() -> null
    kind == DashboardMetricKind.MOOD -> values.average()
    else -> values.sum()
}

/** Aggregate only reported rows from Monday through today. */
internal fun aggregateDashboardTopApps(today: String, entries: Map<String, DiaryEntry>): List<PhoneTopApp> {
    val dates = dashboardComparisonWindow(today, previous = false).toSet()
    return entries.asSequence()
        .filter { (date, _) -> date in dates }
        .flatMap { (_, entry) -> entry.phoneTopApps.asSequence() }
        .filter { it.app.isNotBlank() && it.minutes.isFinite() && it.minutes >= 0.0 }
        .groupBy { it.app.trim().lowercase(Locale.ROOT) }
        .map { (key, rows) -> PhoneTopApp(rows.first().app.trim(), rows.sumOf { it.minutes }) to key }
        .sortedWith(compareByDescending<Pair<PhoneTopApp, String>> { it.first.minutes }.thenBy { it.second })
        .take(5)
        .map { it.first }
}

internal fun dashboardAppCoverage(today: String, entries: Map<String, DiaryEntry>): Int =
    dashboardComparisonWindow(today, previous = false).count { date ->
        entries[date]?.phoneTopApps?.any { it.app.isNotBlank() && it.minutes.isFinite() && it.minutes >= 0.0 } == true
    }

@Composable
internal fun DashboardWeekSummary(
    data: DashboardData,
    onOpenMetric: (DashboardMetricKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = listOf(
        DashboardMetricKind.MOOD to "Nálada",
        DashboardMetricKind.SCREEN_TIME to "Čas u obrazovky",
        DashboardMetricKind.UNLOCKS to "Odemknutí",
    )
    GlassCard(
        modifier = modifier.fillMaxWidth().testTag("dashboard_week_summary"),
        accent = Indigo,
    ) {
        SectionHeader("Týden v číslech")
        VSpace(4)
        SectionHint("Tento týden ${dashboardComparisonPeriodLabel(data.today, false)} · minulý týden ${dashboardComparisonPeriodLabel(data.today, true)} · série 🔥 ${data.streak}")
        VSpace(Spacing.small)
        metrics.forEachIndexed { index, (kind, label) ->
            if (index > 0) GlassDivider()
            val metric = dashboardWeekMetric(data.today, data.month, data.month, kind)
            WeekMetricRow(
                label = label,
                value = metric.value,
                coverage = metric.coveredDays,
                coverageDenominator = dashboardComparisonWindow(data.today, false).size,
                previousValue = metric.previousValue,
                previousCoverage = metric.previousCoveredDays,
                comparisonAllowed = hasComparableCoverage(metric.coveredDays, metric.previousCoveredDays, dashboardComparisonWindow(data.today, false).size),
                comparisonHint = coverageComparisonHint(metric.coveredDays, metric.previousCoveredDays, dashboardComparisonWindow(data.today, false).size),
                difference = metric.difference,
                kind = kind,
                onClick = { onOpenMetric(kind) },
            )
        }
        val currentDates = dashboardComparisonWindow(data.today, previous = false)
        val moodDays = currentDates.count { date ->
            data.month.firstOrNull { it.date == date }?.mood?.let(::isRecordedDay) == true
        }
        // Recorded days are a separate measure from phone-data coverage and are never
        // inferred from a synced shell row.
        GlassDivider()
        WeekMetricRow(
            label = "Zapsané dny",
            value = moodDays.toDouble(),
            coverage = moodDays,
            previousValue = null,
            previousCoverage = 0,
            kind = DashboardMetricKind.MOOD,
            onClick = { onOpenMetric(DashboardMetricKind.MOOD) },
            valueText = "$moodDays / ${currentDates.size}",
            coverageText = null,
            comparison = null,
            comparisonHint = null,
            difference = null,
            tag = "dashboard_recorded_days",
        )
    }
}

@Composable
private fun WeekMetricRow(
    label: String,
    value: Double?,
    coverage: Int,
    previousValue: Double?,
    previousCoverage: Int,
    coverageDenominator: Int = DashboardRepository.WEEK_DAYS,
    comparisonAllowed: Boolean = hasComparableCoverage(coverage, previousCoverage),
    comparisonHint: String? = coverageComparisonHint(coverage, previousCoverage),
    difference: Double? = null,
    kind: DashboardMetricKind,
    onClick: () -> Unit,
    valueText: String? = null,
    coverageText: String? = "$coverage/$coverageDenominator dní",
    comparison: String? = previousValue?.takeIf { comparisonAllowed }?.let {
        val delta = difference ?: ((value ?: return@let null) - it)
        val direction = when {
            delta > 0 -> "↑"
            delta < 0 -> "↓"
            else -> "="
        }
        "Minule ${formatMetric(kind, it)} · pokrytí $previousCoverage/$coverageDenominator · rozdíl $direction ${formatDifference(kind, kotlin.math.abs(delta))}"
    },
    tag: String = "dashboard_metric_${kind.name.lowercase(Locale.ROOT)}",
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp)
            .testTag(tag),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
            Text(
                text = valueText ?: if (value == null) "—" else formatMetric(kind, value),
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(Modifier.width(Spacing.small))
            Text("›", style = MaterialTheme.typography.titleMedium, color = IndigoLight)
        }
        Row {
            if (coverageText != null) {
                Text(coverageText, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            if (comparison != null) {
                Text("  ·  $comparison", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            } else if (comparisonHint != null) {
                Text("  ·  $comparisonHint", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            } else if (previousCoverage == 0) {
                Text("  ·  Bez minulých dat", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            }
        }
    }
}

private fun formatMetric(kind: DashboardMetricKind, value: Double): String = when (kind) {
    DashboardMetricKind.MOOD -> String.format(Locale.US, "%.1f / 5", value)
    DashboardMetricKind.SCREEN_TIME -> formatMinutes(value.roundToInt())
    DashboardMetricKind.UNLOCKS -> value.roundToInt().toString()
}

private fun formatDifference(kind: DashboardMetricKind, value: Double): String = when (kind) {
    DashboardMetricKind.MOOD -> String.format(Locale.US, "%.1f bodu", value)
    DashboardMetricKind.SCREEN_TIME -> formatMinutes(value.roundToInt())
    DashboardMetricKind.UNLOCKS -> "${value.roundToInt()} odemknutí"
}

private val weekSummaryDateFormat: DateTimeFormatter get() = UiDates.numericDate()

/** Use the same locale-aware numeric date API as DashboardScreen; malformed dates stay visible. */
private fun weekSummaryShortDate(date: String): String =
    CheckInDates.parse(date)?.format(weekSummaryDateFormat) ?: date

private fun dashboardPeriodLabel(today: String, weeksBack: Long): String {
    val dates = dashboardCompletedWeek(today, weeksBack)
    return "${weekSummaryShortDate(dates.first())}–${weekSummaryShortDate(dates.last())}"
}

private fun dashboardComparisonPeriodLabel(today: String, previous: Boolean): String {
    val dates = dashboardComparisonWindow(today, previous)
    return "${weekSummaryShortDate(dates.first())}–${weekSummaryShortDate(dates.last())}"
}

/** One compact usage strip and a dated app snapshot, without repeating weekly totals. */
@Composable
internal fun DigitalSummaryCard(
    data: DashboardData,
    modifier: Modifier = Modifier,
    onOpenMetric: (DashboardMetricKind) -> Unit = {},
) {
    val dates = dashboardComparisonWindow(data.today, previous = false)
    val days = dates.map { date -> data.month.firstOrNull { it.date == date } }
    val apps = aggregateDashboardTopApps(data.today, data.entryByDate)
    val appCoverage = dashboardAppCoverage(data.today, data.entryByDate)
    val observed = days.count { it?.screenTimeSeconds != null }
    val maxSeconds = days.mapNotNull { it?.screenTimeSeconds?.toFloat() }.maxOrNull() ?: 0f
    val range = "${weekSummaryShortDate(dates.first())}–${weekSummaryShortDate(dates.last())}"
    GlassCard(modifier = modifier.fillMaxWidth().testTag("dashboard_digital_summary"), accent = Cyan) {
        SectionHeader("📱 Digitální přehled")
        VSpace(4)
        SectionHint("Čas na obrazovce · $range · pokrytí $observed/${dates.size} dní")
        VSpace(Spacing.small)
        if (observed == 0) {
            SectionHint("Čas na obrazovce zatím není synchronizovaný.")
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .testTag("dashboard_digital_chart")
                    .clickable { onOpenMetric(DashboardMetricKind.SCREEN_TIME) },
            ) {
                val count = days.size
                val slot = size.width / count
                val baseline = size.height - 12.dp.toPx()
                val chartHeight = size.height - 24.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.16f), androidx.compose.ui.geometry.Offset(0f, baseline), androidx.compose.ui.geometry.Offset(size.width, baseline), strokeWidth = 1.dp.toPx())
                val points = days.mapIndexed { index, day ->
                    val value = day?.screenTimeSeconds ?: return@mapIndexed null
                    val x = slot * (index + 0.5f)
                    val y = if (maxSeconds <= 0f) baseline else baseline - chartHeight * value / maxSeconds
                    val barWidth = minOf(20.dp.toPx(), slot * 0.48f)
                    if (value > 0) {
                        drawRoundRect(IndigoLight.copy(alpha = 0.34f), androidx.compose.ui.geometry.Offset(x - barWidth / 2f, y), androidx.compose.ui.geometry.Size(barWidth, baseline - y), cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                    }
                    drawCircle(IndigoLight, radius = 4.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
                    androidx.compose.ui.geometry.Offset(x, y)
                }
                // Keep raw daily bars and show a distinct average over observed days.
                dashboardObservedAverage(days.map { it?.screenTimeSeconds?.toDouble() })?.let { average ->
                    val y = if (maxSeconds <= 0f) baseline else baseline - chartHeight * average.toFloat() / maxSeconds
                    drawLine(Cyan.copy(alpha = 0.75f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                }
                points.forEachIndexed { index, point ->
                    if (point != null) {
                        drawCircle(Cyan, radius = 2.dp.toPx(), center = point)
                        val minutes = days[index]?.screenTimeSeconds?.div(60) ?: return@forEachIndexed
                        drawContext.canvas.nativeCanvas.drawText(
                            "${minutes}m",
                            point.x,
                            (point.y - 8.dp.toPx()).coerceAtLeast(10.dp.toPx()),
                            android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = 9.sp.toPx()
                                textAlign = android.graphics.Paint.Align.CENTER
                            },
                        )
                    }
                    val label = dates[index].takeLast(2)
                    drawContext.canvas.nativeCanvas.drawText(label, slot * (index + 0.5f), size.height - 2.dp.toPx(), android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 10.sp.toPx()
                        textAlign = android.graphics.Paint.Align.CENTER
                    })
                }
            }
            Text("Sloupce ukazují denní čas v minutách; čárkovaná čára je průměr zaznamenaných dní. Chybějící dny jsou mezery a 0 min je skutečná nula.", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        VSpace(Spacing.small)
        GlassDivider()
        VSpace(Spacing.small)
        SectionHint("Top 5 aplikací · $range · uložené řádky v $appCoverage/7 dnech")
        VSpace(4)
        if (apps.isEmpty()) {
            SectionHint("V tomto období nejsou uložené žádné řádky aplikací.")
        } else {
            apps.forEachIndexed { index, app ->
                if (index > 0) VSpace(4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}.", style = MaterialTheme.typography.labelMedium, color = Cyan, modifier = Modifier.width(26.dp))
                    Text(app.app, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                    Text(formatTopAppDuration(app.minutes), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
            }
            VSpace(4)
            SectionHint("Součet uložených řádků; prázdný seznam nelze odlišit od chybějící synchronizace. Denní zdroj obsahuje nejvýše 15 aplikací, takže nejde o úplný žebříček všech aplikací.")
        }
    }
}
