package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.digitalnivedomi.diarium.core.data.StatsData
import cz.digitalnivedomi.diarium.core.data.StatsRepository
import cz.digitalnivedomi.diarium.core.stats.ActivityStat
import cz.digitalnivedomi.diarium.core.stats.MonthMood
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.MOOD_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.components.ErrorBanner
import cz.digitalnivedomi.diarium.ui.checkin.components.PrimaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.ReadOnlyRow
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.checkin.components.testTagOrEmpty
import cz.digitalnivedomi.diarium.ui.components.AnimatedCounter
import cz.digitalnivedomi.diarium.ui.components.EmptyState
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassChip
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.GlassSurface
import cz.digitalnivedomi.diarium.ui.components.ScreenHeader
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.ShimmerBox
import cz.digitalnivedomi.diarium.ui.components.StaggeredItem
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.components.rememberLightHaptics
import cz.digitalnivedomi.diarium.ui.components.topSheen
import cz.digitalnivedomi.diarium.ui.theme.Dimens
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Gradients
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoDeep
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.MotionTokens
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.Spacing
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
 * 1. **Loading is visible** ("Načítám statistiky…") instead of drawing zeroes — now as
 *    skeletons shaped like the content that is coming, not a spinner.
 * 2. **A failure is visible and retryable** — the repository's own Czech sentence plus
 *    "Zkusit znovu". A dead request must never render as "you have no data yet".
 * 3. **No session still renders**: [StatsDeps.offline] lands in that same retry state.
 *
 * All the arithmetic lives in [StatsMath] so it is unit tested without a UI; this file
 * only turns those numbers into glass cards.
 */
@Composable
fun StatsScreen(
    deps: StatsDeps = remember { StatsDeps.offline() },
    // Owned by the app shell (see [cz.digitalnivedomi.diarium.ui.nav.ScreenStateCache]):
    // switching tabs must not throw away the period that was just read.
    holder: StatsStateHolder = remember { StatsStateHolder() },
) {
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
            // Placeholders only before the first read of a period lands; a reload keeps
            // the previous numbers visible instead of blanking the screen under them.
            state.loading && data == null -> LoadingCard()
            state.errorMessage != null -> ErrorCard(state.errorMessage.orEmpty()) { reloadTrigger++ }
            data != null -> StatsContent(
                data = data,
                range = state.range,
                onSelectRange = holder::selectRange,
                onReload = { reloadTrigger++ },
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

/**
 * Vertical brand-lit gradient for one column of a chart: the day's own colour at the
 * top, sinking towards the deep indigo at the baseline (same light direction the glass
 * surfaces use).
 */
private fun chartBarBrush(base: Color): Brush =
    Gradients.fill(listOf(base, lerp(base, IndigoDeep, 0.62f)), vertical = true)

/**
 * Visible loading: never a zeroed statistics screen while the read is in flight.
 *
 * Skeletons instead of a spinner — the placeholders are the shape of the content that
 * is about to land (a header line, three summary tiles, a chart frame), so the screen
 * fills in rather than jumping.
 */
@Composable
private fun LoadingCard() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.section),
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShimmerBox(
                    modifier = Modifier.size(Dimens.iconLg),
                    shape = RoundedCornerShape(Dimens.radiusXs),
                )
                Spacer(Modifier.width(Spacing.block))
                Column {
                    Text(
                        text = "Načítám statistiky…",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                    )
                    VSpace(Spacing.tiny)
                    SectionHint(
                        "Chvilku strpení, stahuju posledních ${StatsRepository.LOAD_DAYS} dní.",
                    )
                }
            }
        }

        // The shape of a summary strip plus a chart, so the arrival is a fill-in, not a jump.
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            ShimmerBox(
                modifier = Modifier.fillMaxWidth(0.42f).height(Dimens.skeletonLine),
            )
            VSpace(Spacing.block)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.block),
            ) {
                repeat(3) {
                    ShimmerBox(
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(Dimens.radiusSm),
                    )
                }
            }
            VSpace(Spacing.block)
            ShimmerBox(
                modifier = Modifier.fillMaxWidth().height(TRACK),
                shape = RoundedCornerShape(Dimens.radiusMd),
            )
            VSpace(Spacing.block)
            ShimmerBox(modifier = Modifier.fillMaxWidth(0.6f).height(Dimens.skeletonLine))
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

/**
 * 7 dní / 30 dní / Tento rok — the web's three controls, rebuilt as one premium
 * segmented glass control: a single track with a brand-gradient indicator that slides
 * to the selected window. Same three windows, same labels and the same test tags the
 * old chips carried.
 */
@Composable
private fun RangeSelector(range: StatsRange, onSelect: (StatsRange) -> Unit) {
    val haptics = rememberHaptics()
    val options = StatsRange.entries
    val selectedIndex = options.indexOf(range).coerceAtLeast(0)

    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.radiusMd),
        accent = Indigo,
        contentPadding = PaddingValues(Dimens.border),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(Dimens.controlHeight),
        ) {
            val segmentWidth = maxWidth / options.size
            val indicatorOffset by animateDpAsState(
                targetValue = segmentWidth * selectedIndex,
                animationSpec = tween(
                    durationMillis = MotionTokens.mediumMillis,
                    easing = MotionTokens.standardEasing,
                ),
                label = "rangeIndicator",
            )

            // The sliding indicator sits behind the labels and carries the brand ramp.
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .padding(3.dp)
                    .clip(RoundedCornerShape(Dimens.radiusSm))
                    .background(Gradients.fill(Gradients.brand))
                    .topSheen(accent = Color.White, alpha = 0.18f),
            )

            Row(modifier = Modifier.fillMaxSize()) {
                options.forEach { option ->
                    val selected = option == range
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTagOrEmpty(option.testTag)
                            .clickable {
                                if (!selected) {
                                    // Selecting a window is a value change: the softer
                                    // "selection" tick, not a full tap.
                                    haptics.selection()
                                    onSelect(option)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) Color.White else TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsContent(
    data: StatsData,
    range: StatsRange,
    onSelectRange: (StatsRange) -> Unit,
    onReload: () -> Unit,
) {
    val days = data.forRange(range)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.section),
    ) {
        StaggeredItem(0) { RangeSelector(range = range, onSelect = onSelectRange) }

        // Cards land one after another as the window changes, the same ladder the
        // dashboard and history use. Past the sixth block the stagger is capped, so the
        // tail of a long screen does not flicker in late.
        StaggeredItem(1) { SectionHeader("Nálada") }

        if (days.isEmpty()) {
            StaggeredItem(2) { EmptyWindowCard(rangeLabel = range.label, onReload = onReload) }
        } else {
            StaggeredItem(2) { MoodDistributionCard(days = days, rangeLabel = range.label) }
            // The year hands the chart its calendar year, which is what switches it from
            // one bar per day (254 of them, unreadable) to one bar per month.
            StaggeredItem(3) {
                MoodTrendCard(days = days, year = if (range.year) data.year else null)
            }
            StaggeredItem(4) { WeekdayCard(days = days) }
            StaggeredItem(5) { ActivityCard(days = days) }
            // The extremes own their own entrance (a header + one card per side), so
            // the stagger lives inside the block, not in a second wrapping animatable.
            BestWorstDayBlock(days = days, entryByDate = data.entryByDate)
        }

        // Hairline rule between two blocks, instead of whitespace alone.
        GlassDivider()

        // The screen-time card owns the 7/30-day switch (the web's chart is always
        // 7 days) and the unlock chart under it shows the same window, so both are fed
        // the whole read rather than the mood window.
        StaggeredItem(6) { SectionHeader("Digitální návyky") }
        StaggeredItem(6) { ScreenTimeCharts(entries = data.days, today = data.today) }

        // The year grid is the "Tento rok" tab's second half, exactly like the web
        // shows `YearInPixels` next to the year's mood numbers.
        if (range.year) {
            GlassDivider()
            StaggeredItem(6) { SectionHeader("Roční přehled") }
            StaggeredItem(6) {
                YearInPixels(days = data.yearDays(), year = data.year, today = data.today)
            }
        }
    }
}

@Composable
private fun EmptyWindowCard(rangeLabel: String, onReload: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("📊 Nálada")
        VSpace(Spacing.block)
        EmptyState(
            emoji = "📊",
            title = "Za posledních $rangeLabel žádný zápis",
            message = "Zapiš dnešní den na kartě „Dnes“ a čísla se objeví tady.",
            action = {
                PrimaryButton(text = "Načíst znovu", testTag = "stats_empty_retry") { onReload() }
            },
        )
    }
}

/**
 * The window's two headline numbers, as their own elevated glass tile row — the brand
 * accent marks it as the summary of the block above it.
 */
@Composable
private fun MoodSummaryTiles(average: Double, answered: Int) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo, elevated = true) {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(label = "Průměrná nálada", modifier = Modifier.weight(1f)) {
                AnimatedCounter(
                    value = average,
                    decimals = 1,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    suffix = " / 5",
                )
            }
            StatTile(label = "Zapsaných dní", modifier = Modifier.weight(1f)) {
                AnimatedCounter(
                    value = answered,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    suffix = " ${dayWord(answered)}",
                )
            }
        }
    }
}

/** A small TextSecondary caption above an animated value — the atom of a summary tile. */
@Composable
private fun StatTile(
    label: String,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
        )
        VSpace(Spacing.tiny)
        value()
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
                // The columns grow into their new share whenever the window changes.
                val fraction by animateFloatAsState(
                    targetValue = (0.04f + share * 0.9f).coerceIn(0.04f, 0.94f),
                    animationSpec = tween(
                        durationMillis = MotionTokens.mediumMillis,
                        easing = MotionTokens.standardEasing,
                    ),
                    label = "moodDistributionBar",
                )
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
                                .fillMaxHeight(fraction)
                                .clip(RoundedCornerShape(topStart = Dimens.radiusXs, topEnd = Dimens.radiusXs))
                                .background(chartBarBrush(moodColor(mood)))
                                .then(if (dim) Modifier.alpha(0.3f) else Modifier)
                                .topSheen(accent = Color.White, alpha = 0.10f),
                        )
                    }
                    VSpace(6)
                    Text(text = moodEmojiOf(mood), fontSize = 18.sp)
                }
            }
        }

        // Hairline baseline the columns stand on.
        VSpace(Spacing.tiny)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.border)
                .background(Outline.copy(alpha = 0.55f)),
        )

        VSpace(12)
        GlassDivider()
        VSpace(Spacing.block)
        val average = StatsMath.averageMood(days)
        if (average == null) {
            SectionHint("V tomto období nemáš zapsanou žádnou náladu.")
        } else {
            MoodSummaryTiles(average = average, answered = answered)
            selected?.let { mood ->
                VSpace(Spacing.block)
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
 * 📈 Vývoj nálady — one bar per column plus the window's moving average drawn as an
 * indigo line over the bars.
 *
 * The window picks the columns. A 7- or 30-day window keeps **one bar per day**; the year
 * window cannot — 254 days across a phone is about a pixel per bar, which reads as noise
 * rather than a trend, and it was exactly what made the year view illegible. The year
 * therefore draws **one bar per month**, the mean of that month's answered days, with a
 * 3-month moving average across the twelve points. The drawing, the tap detail and the
 * arithmetic ([StatsMath.movingAverageOf]) are the same code for both — only the buckets
 * differ.
 *
 * Unanswered days and unwritten months stay in as a near-flat grey stub so the slot still
 * exists on the time axis, but they are excluded from the line — see
 * [StatsMath.movingAverage].
 */
@Composable
private fun MoodTrendCard(days: List<StatsDay>, year: Int? = null) {
    val monthly = year != null
    val months = if (year != null) StatsMath.monthlyMood(days, year) else emptyList()
    val points = if (monthly) emptyList() else StatsMath.moodTrend(days)

    val slots: List<TrendSlot> = if (monthly) {
        months.map { month ->
            val monthAverage = month.averageMood
            TrendSlot(
                key = "m${month.month}",
                label = StatsMath.monthShortName(month.month),
                title = "${StatsMath.monthFullName(month.month)} $year",
                mood = monthAverage?.roundToInt() ?: 0,
                written = month.answered,
                moodText = monthAverage?.let {
                    "${moodEmojiOf(it.roundToInt())} ${moodLabelOf(it.roundToInt())}"
                } ?: "Bez odpovědi",
                detailRows = if (monthAverage == null) {
                    emptyList()
                } else {
                    listOf(
                        "Ø nálada" to "${formatAverageMood(monthAverage)} / 5",
                        "Zapsaných dní" to "${month.answeredDays} ${dayWord(month.answeredDays)}",
                    )
                },
            )
        }
    } else {
        points.map { point ->
            val written = point.mood in StatsMath.MOOD_MIN..StatsMath.MOOD_MAX
            TrendSlot(
                key = point.date,
                label = shortDateOf(point.date),
                title = shortDateOf(point.date),
                mood = point.mood,
                written = written,
                moodText = if (written) {
                    "${moodEmojiOf(point.mood)} ${moodLabelOf(point.mood)}"
                } else {
                    "Bez odpovědi"
                },
                detailRows = emptyList(),
            )
        }
    }

    // Twelve monthly points smooth with a 3-month window, not the daily 7-day one: the
    // series is ten times shorter, so the same window would flatten a whole quarter.
    val average: List<Double?> = if (monthly) {
        StatsMath.movingAverageOf(
            values = months.map { it.averageMood },
            window = StatsMath.MONTH_MOVING_AVERAGE_WINDOW,
        )
    } else {
        StatsMath.movingAverage(days)
    }
    val averageLabel = if (monthly) {
        "${StatsMath.MONTH_MOVING_AVERAGE_WINDOW}měsíční klouzavý průměr"
    } else {
        "${StatsMath.MOVING_AVERAGE_WINDOW}denní klouzavý průměr"
    }

    var selected by remember(monthly, points.size, months.size) { mutableStateOf<Int?>(null) }
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

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            "📈 Vývoj nálady",
            trailing = {
                Text(
                    text = if (monthly) {
                        // The days count of a year ("254 dní") says nothing about the
                        // chart; the year itself is what the columns belong to.
                        "· $year"
                    } else {
                        "· ${slots.size} ${dayWord(slots.size)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            },
        )
        VSpace(4)
        SectionHint(
            if (monthly) {
                "Sloupce = průměrná nálada za měsíc, indigo čára = $averageLabel. " +
                    "Klepni na měsíc pro detail."
            } else {
                "Sloupce = denní nálada, indigo čára = $averageLabel. Klepni na den pro detail."
            },
        )
        VSpace(14)

        Box(modifier = Modifier.fillMaxWidth().height(TRACK)) {
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
        if (monthly) {
            // All twelve months are captioned — that is the whole point of the year view:
            // the reader can name a column without tapping it. Months the read holds
            // nothing for stay dim, so the row still lines up with the calendar instead of
            // skipping them (the web's grid does the same).
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
        } else if (slots.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(shortDateOf(slots.first().key), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(shortDateOf(slots.last().key), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }

        // The year's takeaway in one line, right under the columns: with twelve bars the
        // shape alone does not say which month was the good one.
        if (monthly) {
            monthSummary(months)?.let { summary ->
                VSpace(8)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        }

        VSpace(10)
        // Legend as a glass chip instead of a bare dot + line.
        GlassChip(text = averageLabel, accent = IndigoLight)

        selected?.let { index ->
            val column = slots.getOrNull(index)
            if (column != null) {
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
                        label = if (monthly) averageLabel else "7denní průměr",
                        value = "${formatAverageMood(value)} / 5",
                    )
                }
            }
        }
    }
}

/**
 * One column of the trend chart: a day in the short windows, a month in the year window.
 *
 * Flattening both into one shape is what lets the year reuse the daily chart's drawing,
 * tap handling and detail rows instead of a second chart that would drift from it.
 */
private data class TrendSlot(
    /** Identity for the grow animation and the reveal — a date, or `m3` for March. */
    val key: String,
    /** The caption under the column ("Led"). */
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

/**
 * "Nejlepší: Srpen 4,1 / 5 · nejslabší: Únor 2,9 / 5" — the year's one-line takeaway, or
 * null when no month of that year was answered (an empty year must not print a winner).
 */
private fun monthSummary(months: List<MonthMood>): String? {
    val best = StatsMath.bestMonth(months) ?: return null
    val worst = StatsMath.worstMonth(months) ?: return null
    val bestAverage = best.averageMood ?: return null
    val worstAverage = worst.averageMood ?: return null
    return "Nejlepší: ${StatsMath.monthFullName(best.month)} ${formatAverageMood(bestAverage)} / 5 · " +
        "nejslabší: ${StatsMath.monthFullName(worst.month)} ${formatAverageMood(worstAverage)} / 5"
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


