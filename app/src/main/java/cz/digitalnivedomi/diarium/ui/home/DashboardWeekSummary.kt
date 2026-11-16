package cz.digitalnivedomi.diarium.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.data.DashboardData
import cz.digitalnivedomi.diarium.core.data.DashboardDay
import cz.digitalnivedomi.diarium.core.data.DashboardRepository
import cz.digitalnivedomi.diarium.core.data.PhoneTopApp
import cz.digitalnivedomi.diarium.core.data.formatTopAppDuration
import cz.digitalnivedomi.diarium.core.data.isRecordedDay
import cz.digitalnivedomi.diarium.core.data.rankTopApps
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
import cz.digitalnivedomi.diarium.ui.theme.Outline
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
)

/** Only two complete calendar weeks are comparable; matching partial samples can differ by day. */
internal fun hasComparableCoverage(current: Int, previous: Int): Boolean =
    current == DashboardRepository.WEEK_DAYS && previous == DashboardRepository.WEEK_DAYS

internal fun coverageComparisonHint(current: Int, previous: Int): String? =
    if (!hasComparableCoverage(current, previous)) {
        "Srovnání skryto · pokrytí $current/${DashboardRepository.WEEK_DAYS} vs. $previous/${DashboardRepository.WEEK_DAYS} dní"
    } else null

/** Nulls are omitted from each denominator; a reported zero remains a real observation. */
internal fun dashboardWeekMetric(
    today: String,
    currentDays: List<DashboardDay>,
    previousDays: List<DashboardDay>,
    kind: DashboardMetricKind,
): WeekMetric {
    val current = windowValues(today, currentDays, kind)
    val previous = windowValues(LocalDate.parse(today).minusDays(DashboardRepository.WEEK_DAYS.toLong()).toString(), previousDays, kind)
    return WeekMetric(
        value = aggregate(current, kind),
        coveredDays = current.size,
        previousValue = aggregate(previous, kind),
        previousCoveredDays = previous.size,
    )
}

private fun windowValues(anchor: String, days: List<DashboardDay>, kind: DashboardMetricKind): List<Double> {
    val expected = dashboardCalendarWeek(anchor).toSet()
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
        SectionHint("Posledních ${DashboardRepository.WEEK_DAYS} kalendářních dní · série 🔥 ${data.streak}")
        VSpace(Spacing.small)
        metrics.forEachIndexed { index, (kind, label) ->
            if (index > 0) GlassDivider()
            val metric = dashboardWeekMetric(data.today, data.week, data.previousWeek, kind)
            WeekMetricRow(
                label = label,
                value = metric.value,
                coverage = metric.coveredDays,
                previousValue = metric.previousValue,
                previousCoverage = metric.previousCoveredDays,
                comparisonAllowed = hasComparableCoverage(metric.coveredDays, metric.previousCoveredDays),
                comparisonHint = coverageComparisonHint(metric.coveredDays, metric.previousCoveredDays),
                kind = kind,
                onClick = { onOpenMetric(kind) },
            )
        }
        val moodDays = dashboardCalendarWeek(data.today).count { date ->
            data.week.firstOrNull { it.date == date }?.mood?.let(::isRecordedDay) == true
        }
        // Recorded days are a separate measure from phone-data coverage and are never
        // inferred from a synced shell row.
        GlassDivider()
        WeekMetricRow(
            label = "Zapsané dny",
            value = moodDays.toDouble(),
            coverage = DashboardRepository.WEEK_DAYS,
            previousValue = null,
            previousCoverage = 0,
            kind = DashboardMetricKind.MOOD,
            onClick = { onOpenMetric(DashboardMetricKind.MOOD) },
            valueText = "$moodDays / 7",
            coverageText = null,
            comparison = null,
            comparisonHint = null,
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
    comparisonAllowed: Boolean = hasComparableCoverage(coverage, previousCoverage),
    comparisonHint: String? = coverageComparisonHint(coverage, previousCoverage),
    kind: DashboardMetricKind,
    onClick: () -> Unit,
    valueText: String? = null,
    coverageText: String? = "$coverage/7 dní",
    comparison: String? = previousValue?.takeIf { comparisonAllowed }?.let {
        "Minule ${formatMetric(kind, it)} · $previousCoverage/7 dní"
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

private val weekSummaryDateFormat: DateTimeFormatter get() = UiDates.numericDate()

/** Use the same locale-aware numeric date API as DashboardScreen; malformed dates stay visible. */
private fun weekSummaryShortDate(date: String): String =
    CheckInDates.parse(date)?.format(weekSummaryDateFormat) ?: date

/** One compact usage strip and a dated app snapshot, without repeating weekly totals. */
@Composable
internal fun DigitalSummaryCard(data: DashboardData, modifier: Modifier = Modifier) {
    val days = dashboardWeekDays(data)
    val topApps = data.rankedTopApps ?: data.topApps
    val leadingApp: PhoneTopApp? = topApps?.apps?.let(::rankTopApps)?.firstOrNull()
    GlassCard(modifier = modifier.fillMaxWidth().testTag("dashboard_digital_summary"), accent = Cyan) {
        SectionHeader("📱 Digitální přehled")
        VSpace(4)
        val observed = days.count { it.screenTimeSeconds != null }
        SectionHint("Denní čas u obrazovky · šedá = bez dat, krátký indigo sloupec = 0 min.")
        VSpace(Spacing.small)
        val maxSeconds = days.mapNotNull { it.screenTimeSeconds }.maxOrNull() ?: 0
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            days.forEach { day ->
                val seconds = day.screenTimeSeconds
                val height = when {
                    seconds == null -> 3.dp
                    maxSeconds == 0 -> 4.dp
                    else -> (24f * seconds / maxSeconds).dp.coerceAtLeast(4.dp)
                }
                Box(
                    Modifier.weight(1f).height(28.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .width(12.dp)
                            .height(height)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (seconds == null) Outline.copy(alpha = 0.35f) else IndigoLight),
                    )
                }
            }
        }
        VSpace(Spacing.small)
        GlassDivider()
        VSpace(Spacing.small)
        when {
            topApps == null -> SectionHint("Seznam aplikací ještě není synchronizovaný.")
            leadingApp == null -> SectionHint("Ze dne ${weekSummaryShortDate(topApps.date)} · zatím bez údajů o aplikacích.")
            else -> {
                SectionHint("Nejpoužívanější aplikace · snapshot ze dne ${weekSummaryShortDate(topApps.date)}")
                VSpace(4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(Cyan.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) { Text("1", style = MaterialTheme.typography.labelSmall, color = Cyan) }
                    Spacer(Modifier.width(Spacing.small))
                    Text(leadingApp.app, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                    Text(formatTopAppDuration(leadingApp.minutes), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                }
            }
        }
        if (observed == 0 && topApps == null) {
            VSpace(Spacing.small)
            SectionHint("Digitální údaje se ještě nenasynchronizovaly.")
        }
    }
}
