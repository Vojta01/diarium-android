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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.digitalnivedomi.diarium.core.data.StatsData
import cz.digitalnivedomi.diarium.core.data.StatsRepository
import cz.digitalnivedomi.diarium.core.stats.ActivityStat
import cz.digitalnivedomi.diarium.core.stats.MoodPoint
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.MOOD_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.components.ErrorBanner
import cz.digitalnivedomi.diarium.ui.checkin.components.PrimaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.ReadOnlyRow
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.checkin.components.SelectableChip
import cz.digitalnivedomi.diarium.ui.checkin.components.testTagOrEmpty
import cz.digitalnivedomi.diarium.ui.components.BrandSpinner
import cz.digitalnivedomi.diarium.ui.components.EmptyState
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.ScreenHeader
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.StaggeredItem
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.components.rememberLightHaptics
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import cz.digitalnivedomi.diarium.ui.theme.moodColor
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Statistics — "Statistiky", the app's numbers tab (M4).
 *
 * One read ([StatsRepository], 400 days ending today) feeds three views that the web
 * splits across `AdvancedStats`, `ScreenTimeChart` and `YearInPixels`: the mood
 * statistics, the two screen-time charts (time and unlocks) with their top apps, and
 * the year in pixels. The
 * range chips switch between 7 days, 30 days and the current year without a second
 * request — every window is a slice of what is already loaded.
 *
 * Three rules, the same the dashboard follows:
 *
 * 1. **Loading is visible** ("Načítám statistiky…") instead of drawing zeroes.
 * 2. **A failure is visible and retryable** — the repository's own Czech sentence plus
 *    "Zkusit znovu". A dead request must never render as "you have no data yet".
 * 3. **No session still renders**: [StatsDeps.offline] lands in that same retry state.
 *
 * All the arithmetic lives in [StatsMath] so it is unit tested without a UI; this file
 * only turns those numbers into glass cards.
 */
@Composable
fun StatsScreen(deps: StatsDeps = remember { StatsDeps.offline() }) {
    val holder = remember { StatsStateHolder() }
    val state = holder.state
    val data = state.data
    val repository = deps.stats
    var reloadTrigger by remember { mutableStateOf(0) }

    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (firstResume) firstResume = false else reloadTrigger++
        onPauseOrDispose { }
    }

    LaunchedEffect(repository, reloadTrigger) {
        holder.markLoading()
        val result = repository?.load()
        if (result == null) {
            holder.showError(ERROR_LOAD)
        } else {
            result
                .onSuccess { loaded -> holder.show(loaded) }
                .onFailure { error -> holder.showError(error.message ?: ERROR_LOAD) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter),
    ) {
        VSpace(Spacing.screenTop)
        ScreenHeader(
            title = "Statistiky",
            subtitle = "Jak se ti daří v číslech",
        )
        VSpace(Spacing.section)

        when {
            state.loading -> LoadingCard()
            state.errorMessage != null -> ErrorCard(state.errorMessage.orEmpty()) { reloadTrigger++ }
            data != null -> StatsContent(
                data = data,
                range = state.range,
                onSelectRange = holder::selectRange,
            )
        }

        VSpace(Spacing.screenBottom)
    }
}

/** The retry copy used when nothing could be read at all (and when there is no session). */
private const val ERROR_LOAD = "Statistiky se nepodařilo načíst."

/** Height of the chart tracks: the tallest bar fills it. */
private val TRACK = 120.dp

/** Czech numeric date, used for day captions. */
private val shortDate = DateTimeFormatter.ofPattern("d. M. yyyy", Locale("cs", "CZ"))

/** "5. 9. 2026", or the raw value if it is not a date. */
private fun shortDateOf(date: String): String = CheckInDates.parse(date)?.format(shortDate) ?: date

/** Czech weekday abbreviation, Monday first — the same labels the web prints. */
private fun weekdayLabel(date: String): String =
    when (CheckInDates.parse(date)?.dayOfWeek) {
        DayOfWeek.MONDAY -> "Po"
        DayOfWeek.TUESDAY -> "Út"
        DayOfWeek.WEDNESDAY -> "St"
        DayOfWeek.THURSDAY -> "Čt"
        DayOfWeek.FRIDAY -> "Pá"
        DayOfWeek.SATURDAY -> "So"
        DayOfWeek.SUNDAY -> "Ne"
        null -> ""
    }

/** Czech plural for a day count: 1 den, 2–4 dny, 5+ dní. */
private fun dayWord(count: Int): String = when {
    count == 1 -> "den"
    count in 2..4 -> "dny"
    else -> "dní"
}

private fun moodEmojiOf(mood: Int): String =
    MOOD_CHOICES.firstOrNull { it.value == mood }?.emoji ?: "⚪"

private fun moodLabelOf(mood: Int): String =
    MOOD_CHOICES.firstOrNull { it.value == mood }?.label ?: "Bez odpovědi"

/** The web prints `toFixed(1)`, so averages keep a dot rather than a Czech comma. */
private fun formatAverageMood(value: Double): String = String.format(Locale.US, "%.1f", value)

/** Visible loading: never a zeroed statistics screen while the read is in flight. */
@Composable
private fun LoadingCard() {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandSpinner()
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Načítám statistiky…",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                VSpace(2)
                SectionHint(
                    "Chvilku strpení, stahuju posledních ${StatsRepository.LOAD_DAYS} dní.",
                )
            }
        }
    }
}

/** Failed load: the repository's own Czech sentence, plus one way out. */
@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = ErrorRed) {
        SectionHeader("Načtení statistik")
        VSpace(10)
        ErrorBanner(message)
        VSpace(14)
        PrimaryButton(text = "Zkusit znovu", testTag = "stats_retry") { onRetry() }
    }
}

/** 7 dní / 30 dní / Tento rok — the web's three controls. */
@Composable
private fun RangeSelector(range: StatsRange, onSelect: (StatsRange) -> Unit) {
    val haptics = rememberLightHaptics()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatsRange.entries.forEach { option ->
            SelectableChip(
                text = option.label,
                selected = option == range,
                testTag = option.testTag,
            ) {
                haptics()
                onSelect(option)
            }
        }
    }
}

@Composable
private fun StatsContent(
    data: StatsData,
    range: StatsRange,
    onSelectRange: (StatsRange) -> Unit,
) {
    val days = data.forRange(range)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.section),
    ) {
        StaggeredItem(0) { RangeSelector(range = range, onSelect = onSelectRange) }

        // Cards land one after another as the window changes, the same ladder the
        // dashboard and history use.
        if (days.isEmpty()) {
            StaggeredItem(1) { EmptyWindowCard(range.label) }
        } else {
            StaggeredItem(1) { MoodDistributionCard(days = days, rangeLabel = range.label) }
            StaggeredItem(2) { MoodTrendCard(days = days) }
            StaggeredItem(3) { WeekdayCard(days = days) }
            StaggeredItem(4) { ActivityCard(days = days) }
            StaggeredItem(5) { BestWorstCard(days = days) }
        }

        // The screen-time card owns the 7/30-day switch (the web's chart is always
        // 7 days) and the unlock chart under it shows the same window, so both are fed
        // the whole read rather than the mood window.
        StaggeredItem(6) { ScreenTimeCharts(entries = data.days, today = data.today) }

        // The year grid is the "Tento rok" tab's second half, exactly like the web
        // shows `YearInPixels` next to the year's mood numbers.
        if (range.year) {
            StaggeredItem(7) {
                YearInPixels(days = data.yearDays(), year = data.year, today = data.today)
            }
        }
    }
}

@Composable
private fun EmptyWindowCard(rangeLabel: String) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("📊 Nálada")
        VSpace(Spacing.block)
        EmptyState(
            emoji = "📊",
            title = "Za posledních $rangeLabel žádný zápis",
            message = "Zapiš dnešní den na kartě „Dnes“ a čísla se objeví tady.",
        )
    }
}

/**
 * 📊 Rozložení nálad — one bar per mood value 1..5 over the window.
 *
 * Ported from `AdvancedStats`' distribution block: five bars in the web's order
 * (1 → 5), the bar's height the mood's share of the answered days, tapping a bar the
 * web's selection detail ("N dní · P %").
 */
@Composable
private fun MoodDistributionCard(days: List<StatsDay>, rangeLabel: String) {
    val counts = StatsMath.moodDistribution(days)
    val answered = StatsMath.answeredDays(days)
    var selected by remember(days.size) { mutableStateOf<Int?>(null) }
    val haptics = rememberLightHaptics()

    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader("📊 Rozložení nálad", trailing = { Text("· $rangeLabel", style = MaterialTheme.typography.labelSmall, color = TextTertiary) })
        VSpace(4)
        SectionHint("Kolik dní v období jsi měl kterou náladu. Klepni na sloupec pro detail.")
        VSpace(14)

        Row(
            modifier = Modifier.fillMaxWidth().height(TRACK),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatsMath.MOOD_VALUES.forEach { mood ->
                val count = counts[mood] ?: 0
                val share = if (answered > 0) count.toFloat() / answered else 0f
                val dim = selected != null && selected != mood
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTagOrEmpty("stats_mood_bar_$mood")
                        .clickable {
                            haptics()
                            selected = if (selected == mood) null else mood
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (dim) TextTertiary else TextPrimary,
                    )
                    VSpace(4)
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight((0.04f + share * 0.9f).coerceIn(0.04f, 0.94f))
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(moodColor(mood).copy(alpha = if (dim) 0.25f else 0.95f)),
                        )
                    }
                    VSpace(6)
                    Text(text = moodEmojiOf(mood), fontSize = 18.sp)
                }
            }
        }

        VSpace(12)
        GlassDivider()
        VSpace(10)
        val average = StatsMath.averageMood(days)
        if (average == null) {
            SectionHint("V tomto období nemáš zapsanou žádnou náladu.")
        } else {
            ReadOnlyRow(label = "Průměrná nálada", value = "${formatAverageMood(average)} / 5")
            VSpace(6)
            ReadOnlyRow(label = "Zapsaných dní", value = "$answered ${dayWord(answered)}")
            selected?.let { mood ->
                VSpace(6)
                val count = counts[mood] ?: 0
                val percent = if (answered > 0) (count * 100.0 / answered).roundToInt() else 0
                ReadOnlyRow(
                    label = "${moodEmojiOf(mood)} ${moodLabelOf(mood)}",
                    value = "$count ${dayWord(count)} · $percent %",
                )
            }
        }
    }
}

/**
 * 📈 Vývoj nálady — one bar per day plus the web's 7-day moving average ("7denní
 * klouzavý průměr") drawn as an indigo line over the bars.
 *
 * Unanswered days stay in as a near-flat grey stub so the day still exists on the
 * time axis, but they are excluded from the line — see [StatsMath.movingAverage].
 */
@Composable
private fun MoodTrendCard(days: List<StatsDay>) {
    val points = StatsMath.moodTrend(days)
    val average = StatsMath.movingAverage(days)
    var selected by remember(points.size) { mutableStateOf<Int?>(null) }
    val haptics = rememberLightHaptics()

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            "📈 Vývoj nálady",
            trailing = {
                Text(
                    text = "· ${points.size} ${dayWord(points.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            },
        )
        VSpace(4)
        SectionHint("Sloupce = denní nálada, indigo čára = 7denní klouzavý průměr. Klepni na den pro detail.")
        VSpace(14)

        Box(modifier = Modifier.fillMaxWidth().height(TRACK)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val count = points.size
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
                points.forEachIndexed { index, point ->
                    val heightFraction = if (point.mood in StatsMath.MOOD_MIN..StatsMath.MOOD_MAX) {
                        (point.mood / 5f) * 0.92f
                    } else {
                        0.02f
                    }
                    val barHeight = size.height * heightFraction
                    drawRoundRect(
                        color = moodColor(point.mood),
                        topLeft = Offset(index * slot + (slot - barWidth) / 2f, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(3f, 3f),
                        alpha = if (selected == null || selected == index) 1f else 0.35f,
                    )
                }

                val line = Path()
                var started = false
                average.forEachIndexed { index, value ->
                    if (value == null) return@forEachIndexed
                    val x = index * slot + slot / 2f
                    val y = size.height - (value.toFloat() / 5f) * size.height * 0.92f
                    if (started) line.lineTo(x, y) else { line.moveTo(x, y); started = true }
                }
                if (started) {
                    drawPath(
                        path = line,
                        color = IndigoLight,
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
                            size.height - (value.toFloat() / 5f) * size.height * 0.92f,
                        ),
                    )
                }
            }

            Row(modifier = Modifier.matchParentSize()) {
                points.indices.forEach { index ->
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(shortDateOf(points.first().date), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            Text(shortDateOf(points.last().date), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }

        VSpace(10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(IndigoLight),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "7denní klouzavý průměr",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }

        selected?.let { index ->
            val point = points.getOrNull(index)
            if (point != null) {
                VSpace(10)
                GlassDivider()
                VSpace(10)
                ReadOnlyRow(
                    label = shortDateOf(point.date),
                    value = if (point.mood in StatsMath.MOOD_MIN..StatsMath.MOOD_MAX) {
                        "${moodEmojiOf(point.mood)} ${moodLabelOf(point.mood)}"
                    } else {
                        "Bez odpovědi"
                    },
                )
                average.getOrNull(index)?.let { value ->
                    VSpace(6)
                    ReadOnlyRow(
                        label = "7denní průměr",
                        value = "${formatAverageMood(value)} / 5",
                    )
                }
            }
        }
    }
}

/** 📅 Ø nálada podle dne v týdnu — the native breakdown of the same window. */
@Composable
private fun WeekdayCard(days: List<StatsDay>) {
    val averages = StatsMath.weekdayAverages(days)

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("📅 Ø nálada podle dne v týdnu")
        VSpace(4)
        SectionHint("Průměr zapsaných nálad v období, rozdělený podle dne v týdnu.")
        VSpace(14)

        if (averages.isEmpty()) {
            SectionHint("V tomto období nemáš zapsanou žádnou náladu.")
            return@GlassCard
        }

        DayOfWeek.entries.forEachIndexed { index, weekday ->
            val value = averages[weekday]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = StatsMath.WEEKDAY_LABELS[index],
                    style = MaterialTheme.typography.labelMedium,
                    color = if (value == null) TextTertiary else TextSecondary,
                    modifier = Modifier.width(26.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.06f)),
                ) {
                    if (value != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((value / 5.0).toFloat().coerceIn(0.02f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(moodColor(value.roundToInt())),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (value == null) "—" else formatAverageMood(value),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (value == null) TextTertiary else TextPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(32.dp),
                )
            }
            if (index != DayOfWeek.entries.lastIndex) VSpace(10)
        }
    }
}

/**
 * 🎯 Aktivity vs nálada — the web's activity list: the mean mood of the days that
 * mentioned each activity, how often, and (on tap) the best and worst of those days.
 *
 * Mood 0 days never enter the averages, which is why a tap can report a correlation
 * of `null` — the screen prints "—" instead of inventing a number.
 */
@Composable
private fun ActivityCard(days: List<StatsDay>) {
    val stats = StatsMath.activityStats(days)

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("🎯 Aktivity vs nálada")
        VSpace(4)
        SectionHint("Průměrná nálada dnů, kdy jsi aktivitu zmínil (jen zodpovězené dny). Klepni na řádek pro detail.")
        VSpace(14)

        if (stats.isEmpty()) {
            SectionHint("V tomto období nemáš u zápisů žádné aktivity.")
            return@GlassCard
        }

        stats.forEachIndexed { index, stat ->
            ActivityRow(stat = stat)
            if (index != stats.lastIndex) VSpace(12)
        }
    }
}

@Composable
private fun ActivityRow(stat: ActivityStat) {
    var expanded by remember(stat.name) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTagOrEmpty("stats_activity_row")
            .clickable { expanded = !expanded },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stat.name,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .width(70.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((stat.averageMood / 5.0).toFloat().coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(moodColor(stat.averageMood.roundToInt())),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = formatAverageMood(stat.averageMood),
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(28.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "×${stat.count}",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                modifier = Modifier.width(30.dp),
            )
        }

        if (expanded) {
            VSpace(8)
            ReadOnlyRow(label = "Ø nálada", value = "${formatAverageMood(stat.averageMood)} / 5")
            VSpace(6)
            ReadOnlyRow(label = "Zmíněno", value = "${stat.count} ${dayWord(stat.count)}")
            VSpace(6)
            ReadOnlyRow(
                label = "Korelace s náladou",
                value = stat.correlation?.let { "r = ${formatAverageMood(it)}" } ?: "—",
            )
            stat.bestDay?.let { best ->
                VSpace(6)
                ReadOnlyRow(
                    label = "▲ Nejlepší den",
                    value = "${shortDateOf(best.date)} ${moodEmojiOf(best.mood)}",
                )
            }
            stat.worstDay?.let { worst ->
                VSpace(6)
                ReadOnlyRow(
                    label = "▼ Nejhorší den",
                    value = "${shortDateOf(worst.date)} ${moodEmojiOf(worst.mood)}",
                )
            }
        }
    }
}

/** ▲ Nejlepší den / ▼ Nejhorší den, side by side, coloured by which one it is. */
@Composable
private fun BestWorstCard(days: List<StatsDay>) {
    val best = StatsMath.bestDay(days) ?: return
    val worst = StatsMath.worstDay(days) ?: best

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DayCard(
            title = "Nejlepší den",
            arrow = "▲",
            point = best,
            days = days,
            accent = SuccessGreen,
            modifier = Modifier.weight(1f),
        )
        DayCard(
            title = "Nejhorší den",
            arrow = "▼",
            point = worst,
            days = days,
            accent = ErrorRed,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DayCard(
    title: String,
    arrow: String,
    point: MoodPoint,
    days: List<StatsDay>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val activities = days.firstOrNull { it.date == point.date }?.activities.orEmpty()
    GlassCard(modifier = modifier, accent = accent) {
        SectionHeader("$arrow $title")
        VSpace(8)
        Text(
            text = moodEmojiOf(point.mood),
            fontSize = 26.sp,
        )
        VSpace(6)
        Text(
            text = moodLabelOf(point.mood),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(2)
        Text(
            text = shortDateOf(point.date),
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        if (activities.isNotEmpty()) {
            VSpace(8)
            SectionHint(
                activities.take(3).joinToString(", ") +
                    if (activities.size > 3) " …" else "",
            )
        }
    }
}
