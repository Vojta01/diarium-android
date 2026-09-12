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
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale
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
 * What the split deliberately did **not** change:
 *
 * - the window is still **anchored on the last day that actually has data**, not on
 *   today, so a phone that has not synced this morning shows the last full day instead
 *   of an empty chart (the web's `chartData` does the same);
 * - the screen-time bars keep the web's six time buckets, from "<30m" to "6h+", each
 *   with its own colour, and the dashed line still marks the average of the timed days;
 * - both cards print the same three numbers as the web (Průměr denně / Celkem / Nejvíc)
 *   over exactly the same days — [StatsMath.screenTimeSummary] for the time, and
 *   [ScreenTimeBars.unlockSummary], which uses the same denominators, for the count;
 * - the top apps are aggregated over the same window the bars show, and the part of
 *   the screen time no listed app accounts for stays visible as "Ostatní" rather than
 *   silently inflating the app shares.
 *
 * Deliberately native, unchanged: the switch between 7 and 30 days (the web's chart is
 * always 7), and tapping a bar opens that day's detail instead of a hover tooltip. The
 * two charts share one window and one selection, so a day tapped in either one opens
 * the same detail card and dims the same column in both — they read as two views of
 * one period, which is why the range switch lives on the first card only and the
 * second card's caption names the period it is showing.
 *
 * A day with no synced data draws no bar and reads "Žádná data" in the detail — it is
 * never drawn as zero screen time, and never as zero unlocks.
 */
@Composable
fun ScreenTimeCharts(entries: List<StatsDay>, today: String, modifier: Modifier = Modifier) {
    var windowDays by remember { mutableStateOf(SCREEN_TIME_WEEK) }
    var selectedDate by remember { mutableStateOf<String?>(null) }

    val window = StatsMath.screenTimeWindow(days = entries, windowDays = windowDays)
    val summary = StatsMath.screenTimeSummary(window)
    val unlocks = ScreenTimeBars.unlockSummary(window)
    val windowDates = window.map { it.date }.toSet()
    val windowEntries = entries.filter { it.date in windowDates }
    val breakdown = StatsMath.topApps(windowEntries, limit = APP_LIST_LIMIT)

    // One selection for both charts: tapping the same day again clears it.
    val onSelectDate: (String) -> Unit = { date ->
        selectedDate = if (selectedDate == date) null else date
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTimeCard(
            window = window,
            windowDays = windowDays,
            today = today,
            selectedDate = selectedDate,
            summary = summary,
            onSelectRange = { days ->
                windowDays = days
                selectedDate = null
            },
            onSelectDate = onSelectDate,
        )

        UnlockCard(
            window = window,
            windowDays = windowDays,
            today = today,
            selectedDate = selectedDate,
            summary = unlocks,
            onSelectDate = onSelectDate,
        )

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
 * The screen-time chart: seconds on the screen, the web's colours and its three numbers.
 *
 * This card also owns the range switch for both charts — it is the first of the two, and
 * whoever moves the range moves it for the pair.
 */
@Composable
private fun ScreenTimeCard(
    window: List<ScreenTimeDay>,
    windowDays: Int,
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
        SectionHint(windowCaption(window = window, windowDays = windowDays))

        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        if (window.none { ScreenTimeBars.drawsBar(it.seconds) }) {
            VSpace(14)
            SectionHint(EMPTY_SCREEN_TIME)
            return@GlassCard
        }

        VSpace(14)
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
    today: String,
    selectedDate: String?,
    summary: UnlockSummary,
    onSelectDate: (String) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader(
            "🔓 Odemknutí",
            trailing = {
                Text(
                    text = "· $windowDays dní",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            },
        )
        VSpace(6)
        Text(
            text = "Počet odemknutí",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(2)
        SectionHint(unlockCaption(window = window, windowDays = windowDays))

        val average = summary.averageUnlocks
        if (average == null) {
            VSpace(14)
            SectionHint(EMPTY_UNLOCKS)
            return@GlassCard
        }

        VSpace(14)
        Row(modifier = Modifier.fillMaxWidth()) {
            SummaryCell(
                label = "Průměr denně",
                value = unlockAverageLabel(average),
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
                val value = metric.valueOf(day) ?: 0
                // A day the worker never reported draws no column at all; the label row
                // is what tells "the phone said zero" from "nothing was ever synced".
                if (!ScreenTimeBars.drawsBar(value)) return@forEachIndexed
                val barHeight = size.height * ScreenTimeBars.barFraction(value, maxValue) * BAR_HEADROOM
                drawRoundRect(
                    color = BUCKET_COLORS[metric.bucketOf(value).coerceIn(0, BUCKET_COLORS.lastIndex)],
                    topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(3f, 3f),
                    alpha = if (selectedDate == null || selectedDate == day.date) 1f else 0.35f,
                )
            }

            if (average != null && average > 0) {
                val y = size.height -
                    ScreenTimeBars.averageLineFraction(average, maxValue) * size.height * BAR_HEADROOM
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
                        .testTagOrEmpty(metric.testTag)
                        .clickable { onSelectDate(day.date) },
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

/**
 * The unlock chart's caption: the same window, spelled the same way, plus the sentence
 * that explains why this card has no range switch of its own.
 */
private fun unlockCaption(window: List<ScreenTimeDay>, windowDays: Int): String {
    if (window.isEmpty()) return "Za posledních $windowDays dní nemám žádná data o odemknutích."
    val from = shortDayMonth(window.first().date)
    val to = shortDayMonth(window.last().date)
    return "Posledních $windowDays dní ($from – $to), stejné období jako čas na obrazovce. " +
        "Sloupec = jeden den, přerušovaná čára = průměr."
}

/** "63×" — the unlock chart's counts carry their unit like everywhere else in the app. */
private fun unlockCountLabel(count: Int): String = "$count×"

/** The average is a mean over days, so it lands between counts — round it, keep the unit. */
private fun unlockAverageLabel(average: Double): String = "${average.roundToInt()}×"

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
    "Zatím žádná data o screen timu. Časy sbírá worker z Home Assistantu, první data se objeví po nejbližší synchronizaci."

private const val EMPTY_UNLOCKS =
    "Zatím žádná data o odemknutích. Počty odemknutí sbírá aplikace z telefonu, první data se objeví po nejbližší synchronizaci."

private const val NO_DAY_DATA = "Pro tento den nemám žádná data o čase na obrazovce."
