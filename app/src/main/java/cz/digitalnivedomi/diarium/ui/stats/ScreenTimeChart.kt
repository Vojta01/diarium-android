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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.stats.ScreenTimeDay
import cz.digitalnivedomi.diarium.core.stats.ScreenTimeSeries
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import cz.digitalnivedomi.diarium.core.stats.TopAppsBreakdown
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
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 📱 Screen time — the web `ScreenTimeChart.tsx` ported to Compose, plus the top-apps
 * breakdown the web shows underneath its bars.
 *
 * Ported behaviour, kept deliberately:
 *
 * - the window is **anchored on the last day that actually has screen time**, not on
 *   today, so a phone that has not synced this morning still shows the last full day
 *   instead of an empty chart (the web's `chartData` does the same);
 * - the bars keep the web's six time buckets, from "<30m" to "6h+", each with its own
 *   colour, and the dashed line marks the average of the timed days;
 * - the top apps are aggregated over the same window the bars show, and the part of
 *   the screen time no listed app accounts for stays visible as "Ostatní" rather than
 *   silently inflating the app shares.
 *
 * Deliberately native: the switch between 7 and 30 days (the web's chart is always 7),
 * and tapping a bar opens that day's detail instead of the web's hover tooltip.
 *
 * A day with no synced data draws no bar and reads "Žádná data" in the detail — it is
 * never drawn as zero screen time.
 */
@Composable
fun ScreenTimeChart(entries: List<StatsDay>, today: String, modifier: Modifier = Modifier) {
    var windowDays by remember { mutableStateOf(SCREEN_TIME_WEEK) }
    var selectedDate by remember { mutableStateOf<String?>(null) }

    val window = StatsMath.screenTimeWindow(days = entries, windowDays = windowDays)
    val summary = StatsMath.screenTimeSummary(window)
    val windowDates = window.map { it.date }.toSet()
    val windowEntries = entries.filter { it.date in windowDates }
    val breakdown = StatsMath.topApps(windowEntries, limit = APP_LIST_LIMIT)
    val timed = window.filter { (it.seconds ?: 0) > 0 }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
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
            SectionHint(windowCaption(window = window, windowDays = windowDays))

            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectableChip(
                    text = "7 dní",
                    selected = windowDays == SCREEN_TIME_WEEK,
                    testTag = "screen_time_range_week",
                ) { windowDays = SCREEN_TIME_WEEK; selectedDate = null }
                SelectableChip(
                    text = "30 dní",
                    selected = windowDays == SCREEN_TIME_MONTH,
                    testTag = "screen_time_range_month",
                ) { windowDays = SCREEN_TIME_MONTH; selectedDate = null }
            }

            VSpace(14)
            if (timed.isEmpty()) {
                SectionHint(EMPTY_SCREEN_TIME)
                return@GlassCard
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryCell(
                    label = "Průměr denně",
                    value = formatMinutes((summary.averageSeconds ?: 0.0).toInt() / 60),
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
            val maxSeconds = (summary.maxSeconds ?: 0).coerceAtLeast(1)
            Box(modifier = Modifier.fillMaxWidth().height(SCREEN_TIME_TRACK)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val count = window.size
                    if (count == 0) return@Canvas
                    val slot = size.width / count

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
                        val seconds = day.seconds ?: 0
                        if (seconds <= 0) return@forEachIndexed
                        val fraction = (seconds.toFloat() / maxSeconds).coerceIn(0.02f, 1f)
                        val barHeight = size.height * fraction * 0.94f
                        drawRoundRect(
                            color = barColor(seconds),
                            topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - barHeight),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(3f, 3f),
                            alpha = if (selectedDate == null || selectedDate == day.date) 1f else 0.35f,
                        )
                    }

                    val average = summary.averageSeconds
                    if (average != null && average > 0) {
                        val y = size.height - (average.toFloat() / maxSeconds).coerceIn(0f, 1f) * size.height * 0.94f
                        drawLine(
                            color = Color.White.copy(alpha = 0.45f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
                        )
                    }
                }

                Row(modifier = Modifier.matchParentSize()) {
                    window.forEach { day ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTagOrEmpty("screen_time_bar")
                                .clickable {
                                    selectedDate = if (selectedDate == day.date) null else day.date
                                },
                        )
                    }
                }
            }

            VSpace(6)
            // The value (and the unlock count) sits under every labelled bar; the
            // weekday row drops a name for exactly the bars that carry no label, so
            // the two rows read as one column instead of drifting apart in the
            // 30-day window.
            val selectedIndex = window.indexOfFirst { it.date == selectedDate }.takeIf { it >= 0 }
            val labelled = ScreenTimeSeries.labelIndices(
                count = window.size,
                windowDays = windowDays,
                selectedIndex = selectedIndex,
            )
            BarValueRow(window = window, labelled = labelled, selectedDate = selectedDate)

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

            VSpace(8)
            SectionHint(ScreenTimeSeries.LEGEND)

            VSpace(12)
            GlassDivider()
            VSpace(12)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BUCKET_LABELS.forEachIndexed { index, label ->
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(BUCKET_COLORS[index]),
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

        val selected = window.firstOrNull { it.date == selectedDate }
        if (selected != null) {
            SelectedDayCard(
                day = selected,
                entry = entries.firstOrNull { it.date == selected.date },
                onClose = { selectedDate = null },
            )
        }

        TopAppsCard(breakdown = breakdown, windowDays = windowDays, totalSeconds = summary.totalSeconds)
    }
}

/**
 * The label under each bar: the day's compact screen time, and — only on days the
 * phone reported unlocks for — the unlock count on a second line.
 *
 * A label is centred on its own slot and allowed to spill past it
 * (`wrapContentWidth(unbounded = true)`): in the 30-day window a slot is ~11dp on a
 * phone, far narrower than "5h58", so measuring the text against the slot would clip
 * it to an ellipsis under every bar. Only labelled bars get text and labels are
 * [ScreenTimeSeries.THIN_EVERY] slots apart there, so the spill always lands in an
 * empty slot instead of on a neighbour's number.
 */
@Composable
private fun BarValueRow(
    window: List<ScreenTimeDay>,
    labelled: Set<Int>,
    selectedDate: String?,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        window.forEachIndexed { index, day ->
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.TopCenter,
            ) {
                if (index in labelled) {
                    Column(
                        modifier = Modifier.wrapContentWidth(
                            align = Alignment.CenterHorizontally,
                            unbounded = true,
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = ScreenTimeSeries.secondsLabel(day.seconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (day.date == selectedDate) IndigoLight else TextSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                        ScreenTimeSeries.unlockLabel(day.unlocks)?.let { unlock ->
                            // A hair smaller than the value so the two lines stay
                            // distinguishable at phone width.
                            Text(
                                text = unlock,
                                fontSize = 9.sp,
                                color = TextTertiary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
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
private fun SelectedDayCard(day: ScreenTimeDay, entry: StatsDay?, onClose: () -> Unit) {
    val seconds = day.seconds ?: 0
    val apps = entry?.topApps?.sortedByDescending { it.minutes }.orEmpty()
    val appsSeconds = apps.sumOf { it.minutes * 60 }
    val other = (seconds - appsSeconds).coerceAtLeast(0)

    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader("📅 Detail dne", trailing = { Text(shortDateLabel(day.date), style = MaterialTheme.typography.labelSmall, color = TextTertiary) })
        VSpace(10)
        if (seconds <= 0) {
            SectionHint(NO_DAY_DATA)
        } else {
            ReadOnlyRow(label = "Čas na obrazovce", value = formatMinutes(seconds / 60))
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
                    seconds = app.minutes * 60,
                    share = if (seconds > 0) (app.minutes * 60).toDouble() / seconds else 0.0,
                    dotColor = accentFor(app.app),
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
        SecondaryButton(text = "Zavřít", testTag = "screen_time_close", onClick = onClose)
    }
}

/** The window's apps, summed over the same days the bars show. */
@Composable
private fun TopAppsCard(
    breakdown: TopAppsBreakdown?,
    windowDays: Int,
    totalSeconds: Int,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("🏆 Nejčastější aplikace")
        VSpace(4)
        SectionHint("Za posledních $windowDays dní, podle času na obrazovce.")

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
private fun AppRow(name: String, seconds: Int, share: Double, dotColor: Color) {
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
            modifier = Modifier.width(38.dp),
        )
    }
}

/** "posledních 7 dní (4. 9. – 10. 9.)", or the empty-state hint. */
private fun windowCaption(window: List<ScreenTimeDay>, windowDays: Int): String {
    if (window.isEmpty()) return "Za posledních $windowDays dní nemám žádná data o času na obrazovce."
    val from = shortDayMonth(window.first().date)
    val to = shortDayMonth(window.last().date)
    return "Posledních $windowDays dní ($from – $to). Sloupec = jeden den, přerušovaná čára = průměr."
}

private fun shortDayMonth(date: String): String = CheckInDates.parse(date)?.format(dayMonth) ?: date

private val dayMonth = DateTimeFormatter.ofPattern("d. M.", Locale("cs", "CZ"))
private val fullDate = DateTimeFormatter.ofPattern("d. M. yyyy", Locale("cs", "CZ"))

/** "5. 9. 2026" for the day-detail card header. */
private fun shortDateLabel(date: String): String = CheckInDates.parse(date)?.format(fullDate) ?: date

/** Same labels the bars' weekday captions use (Monday first). */
private fun weekdayLabel(date: String): String = when (CheckInDates.parse(date)?.dayOfWeek) {
    DayOfWeek.MONDAY -> "Po"
    DayOfWeek.TUESDAY -> "Út"
    DayOfWeek.WEDNESDAY -> "St"
    DayOfWeek.THURSDAY -> "Čt"
    DayOfWeek.FRIDAY -> "Pá"
    DayOfWeek.SATURDAY -> "So"
    DayOfWeek.SUNDAY -> "Ne"
    null -> ""
}

/** The web's `getBarColorTotals` buckets, with its exact hex values. */
private fun barColor(seconds: Int): Color = when {
    seconds < 30 * 60 -> Color(0xFF22C55E)
    seconds < 60 * 60 -> Color(0xFF84CC16)
    seconds < 2 * 60 * 60 -> Color(0xFFEAB308)
    seconds < 4 * 60 * 60 -> Color(0xFFF97316)
    seconds < 6 * 60 * 60 -> Color(0xFFEF4444)
    else -> Color(0xFFDC2626)
}

/** Colour for an app name — stable across the list and the day detail. */
private fun accentFor(app: String): Color {
    val index = (app.hashCode().toLong() and 0x7FFFFFFFL).toInt() % APP_COLORS.size
    return APP_COLORS[index]
}

/** Web `getBarColorTotals` legend, in bucket order. */
private val BUCKET_LABELS = listOf("<30m", "30m–1h", "1–2h", "2–4h", "4–6h", "6h+")

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

private const val EMPTY_SCREEN_TIME =
    "Zatím žádná data o screen timu. Časy sbírá worker z Home Assistantu, první data se objeví po nejbližší synchronizaci."

private const val NO_DAY_DATA = "Pro tento den nemám žádná data o čase na obrazovce."
