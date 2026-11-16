package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import cz.digitalnivedomi.diarium.core.i18n.UiDates
import cz.digitalnivedomi.diarium.ui.i18n.Text
import cz.digitalnivedomi.diarium.ui.i18n.translate
import java.time.LocalDate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.digitalnivedomi.diarium.core.data.StatsData
import cz.digitalnivedomi.diarium.core.data.StatsRepository
import cz.digitalnivedomi.diarium.core.stats.ActivityStat
import cz.digitalnivedomi.diarium.core.stats.MonthMood
import cz.digitalnivedomi.diarium.core.stats.StatsDay
import cz.digitalnivedomi.diarium.core.stats.StatsExploration
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.MOOD_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.moodChoiceText
import cz.digitalnivedomi.diarium.ui.checkin.components.ErrorBanner
import cz.digitalnivedomi.diarium.ui.checkin.components.PrimaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.ReadOnlyRow
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.checkin.components.testTagOrEmpty
import cz.digitalnivedomi.diarium.ui.components.AnimatedCounter
import cz.digitalnivedomi.diarium.ui.components.EmptyState
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.GlassSurface
import cz.digitalnivedomi.diarium.ui.components.MoodTrendCaption
import cz.digitalnivedomi.diarium.ui.components.MoodTrendChart
import cz.digitalnivedomi.diarium.ui.components.MoodTrendSlot
import cz.digitalnivedomi.diarium.ui.components.ScreenHeader
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.ShimmerBox
import cz.digitalnivedomi.diarium.ui.components.StaggeredItem
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.components.DayDetailDialog
import cz.digitalnivedomi.diarium.ui.components.chartBarBrush
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.components.rememberLightHaptics
import cz.digitalnivedomi.diarium.ui.components.topSheen
import cz.digitalnivedomi.diarium.ui.theme.Dimens
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Gradients
import cz.digitalnivedomi.diarium.ui.theme.Indigo
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
 * One cached 400-day repository read feeds four categories. Global period and category
 * filters only slice the loaded data; changing them never triggers a fetch. The period
 * is bounded to 400 rolling days except the current calendar-year window, which fits
 * inside that ceiling. Screen-time charts and the year heatmap retain their detailed
 * interactions.
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
    /** Opens the check-in for a day from the best/worst card — owned by the app shell. */
    onOpenCheckIn: (String) -> Unit = {},
    onOpenHistoryMonth: (java.time.YearMonth) -> Unit = {},
    today: LocalDate = LocalDate.now(),
    savedRange: String? = null,
    savedCategory: String? = null,
    savedCustomDays: Int = 30,
    savedCompare: Boolean = false,
    savedCustomStart: String = "",
    savedCustomEnd: String = "",
    onCustomRangeChanged: (String, String) -> Unit = { _, _ -> },
    onFiltersChanged: (StatsRange, StatsCategory, Int, Boolean) -> Unit = { _, _, _, _ -> },
) {
    val state = holder.state
    LaunchedEffect(holder, savedRange, savedCategory, savedCustomDays, savedCompare, savedCustomStart, savedCustomEnd) {
        if (savedRange != null && savedCategory != null) holder.restore(savedRange, savedCategory, savedCustomDays, savedCompare, savedCustomStart, savedCustomEnd)
    }
    val data = state.data
    val repository = deps.stats
    var reloadTrigger by remember { mutableStateOf(0) }
    var firstResume by remember { mutableStateOf(true) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var habitLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var scaleLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var scaleMaxima by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var labelsLoaded by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (firstResume) firstResume = false else reloadTrigger++
        onPauseOrDispose { }
    }

    LaunchedEffect(deps.pickers) {
        labelsLoaded = false
        val pickers = deps.pickers
        if (pickers != null) {
            try {
                habitLabels = pickers.habits(includeHidden = true).associate { it.key to it.label }
                val scales = pickers.scales()
                scaleLabels = scales.associate { it.id to it.name }
                scaleMaxima = scales.associate { it.id to it.maxValue }
            } catch (_: Exception) {
                habitLabels = emptyMap(); scaleLabels = emptyMap(); scaleMaxima = emptyMap()
            }
        }
        labelsLoaded = true
    }

    LaunchedEffect(repository, reloadTrigger, today) {
        holder.markLoading()
        val cached = repository?.loadCached(today.toString())
        if (cached != null) holder.show(cached)
        val result = repository?.load(today.toString())
        if (result == null) holder.showError(ERROR_LOAD)
        else result.onSuccess(holder::show).onFailure { holder.showError(it.message ?: ERROR_LOAD) }
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
            state.errorMessage != null && data == null -> ErrorCard(state.errorMessage.orEmpty()) { reloadTrigger++ }
            data != null -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.block)) {
                state.errorMessage?.let { StaleDataNotice(it) }
                StatsContent(
                data = data,
                range = state.range,
                category = state.category,
                customDays = state.customDays,
                customStartDate = state.customStartDate,
                customEndDate = state.customEndDate,
                appliedCustomStartDate = state.appliedCustomStartDate,
                appliedCustomEndDate = state.appliedCustomEndDate,
                customRangeError = state.customRangeError,
                onEditCustomRange = holder::editCustomRange,
                onApplyCustomRange = {
                    if (holder.applyCustomRange()) {
                        val applied = holder.state
                        onCustomRangeChanged(applied.appliedCustomStartDate, applied.appliedCustomEndDate)
                        onFiltersChanged(StatsRange.CUSTOM, applied.category, applied.customDays, applied.comparePrevious)
                    }
                },
                comparePrevious = state.comparePrevious,
                onSelectRange = { value ->
                    if (value == StatsRange.CUSTOM && state.appliedCustomStartDate.isBlank()) {
                        val end = today
                        val baseline = if (state.range == StatsRange.CUSTOM) StatsRange.MONTH else state.range
                        val count = baseline.dayCount(state.customDays, end)
                        val start = end.minusDays((count - 1).toLong()).toString()
                        if (holder.seedCustomRange(start, end.toString())) {
                            onCustomRangeChanged(start, end.toString())
                        }
                    }
                    holder.selectRange(value)
                    onFiltersChanged(value, state.category, state.customDays, state.comparePrevious)
                },
                onSelectCategory = { value -> holder.selectCategory(value); onFiltersChanged(state.range, value, state.customDays, state.comparePrevious) },
                onCompare = { value -> holder.toggleCompare(value); onFiltersChanged(state.range, state.category, state.customDays, value) },
                onReload = { reloadTrigger++ },
                onOpenCheckIn = onOpenCheckIn,
                onOpenDay = { selectedDay = it },
                habitLabels = habitLabels, scaleLabels = scaleLabels, scaleMaxima = scaleMaxima, labelsLoaded = labelsLoaded,
                onOpenHistoryMonth = onOpenHistoryMonth,
            )
            }
        }

        VSpace(Spacing.screenBottom)
    }
    selectedDay?.let { date -> DayDetailDialog(date, data?.entryByDate?.get(date), onDismiss = { selectedDay = null }, onOpenCheckIn = onOpenCheckIn) }
}

/** The retry copy used when nothing could be read at all (and when there is no session). */
private const val ERROR_LOAD = "Statistiky se nepodařilo načíst."

/** Height of the chart tracks: the tallest bar fills it. */
private val TRACK = 120.dp

/** Numeric date in the UI language, used for day captions. */
private val shortDate: DateTimeFormatter
    get() = UiDates.numericDate()

/** "5. 9. 2026", or the raw value if it is not a date. */
private fun shortDateOf(date: String): String = CheckInDates.parse(date)?.format(shortDate) ?: date

/** Two-letter weekday in the UI language, Monday first — the same labels the charts print. */
private fun weekdayLabel(date: String): String {
    val dayOfWeek = CheckInDates.parse(date)?.dayOfWeek ?: return ""
    return StatsMath.weekdayShortName(dayOfWeek)
}

/** Czech plural for a day count: 1 den, 2–4 dny, 5+ dní. */
private fun dayWord(count: Int): String = when {
    count == 1 -> "den"
    count in 2..4 -> "dny"
    else -> "dní"
}

/** "17 dní" — the answered-days tile's whole label, built once so the count cannot double. */
internal fun answeredDaysText(count: Int): String = "$count ${dayWord(count)}"

private fun moodEmojiOf(mood: Int): String =
    MOOD_CHOICES.firstOrNull { it.value == mood }?.emoji ?: "⚪"

/** "3 dny · 38 %" — the distribution card's tap detail, matched by a whole-string rule. */
internal fun moodShareText(count: Int, percent: Int): String =
    "$count ${dayWord(count)} · $percent %"

/** The web prints `toFixed(1)`, so averages keep a dot rather than a Czech comma. */
private fun formatAverageMood(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun formatAverage(value: Double): String = String.format(Locale.US, "%.1f", value)

private fun signed(value: Double): String = String.format(Locale.US, "%+.1f", value)

private fun signedSeconds(value: Double): String = (if (value > 0) "+" else if (value < 0) "−" else "") + formatSeconds(kotlin.math.abs(value))

private fun formatSeconds(seconds: Double): String {
    val totalMinutes = (seconds / 60.0).roundToInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours} h ${minutes} min" else "$minutes min"
}

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
private fun StaleDataNotice(message: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader("Zobrazená data mohou být zastaralá")
        VSpace(6)
        SectionHint("Aktualizace ze serveru se nezdařila: $message. Zobrazuji poslední ověřenou místní kopii; nejde o potvrzenou aktuální verzi.")
    }
}

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
 * 7 / 30 / 90 days / This year / Custom — one shared selector for every statistics category.
 * segmented glass control: a single track with a brand-gradient indicator that slides
 * to the selected window. Existing period test tags remain stable, with new tags for
 * 90 days and the custom duration.
 */
@Composable
private fun RangeSelector(range: StatsRange, onSelect: (StatsRange) -> Unit, enabled: Boolean = true) {
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
                            .selectable(selected = selected, enabled = enabled, role = Role.Tab) {
                                if (!selected) {
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
    category: StatsCategory,
    customDays: Int,
    customStartDate: String,
    customEndDate: String,
    appliedCustomStartDate: String,
    appliedCustomEndDate: String,
    customRangeError: String?,
    onEditCustomRange: (String, String) -> Unit,
    onApplyCustomRange: () -> Unit,
    comparePrevious: Boolean,
    onSelectRange: (StatsRange) -> Unit,
    onSelectCategory: (StatsCategory) -> Unit,
    onCompare: (Boolean) -> Unit,
    onReload: () -> Unit,
    onOpenCheckIn: (String) -> Unit = {},
    onOpenDay: (String) -> Unit = {},
    onOpenHistoryMonth: (java.time.YearMonth) -> Unit = {},
    habitLabels: Map<String, String>, scaleLabels: Map<String, String>, scaleMaxima: Map<String, Int>, labelsLoaded: Boolean,
) {
    val appliedRange = validateCustomDateRange(appliedCustomStartDate, appliedCustomEndDate, data.today)
    val days = if (range == StatsRange.CUSTOM && appliedRange != null) data.days.filter { it.date >= appliedRange.startInclusive.toString() && it.date <= appliedRange.endInclusive.toString() } else data.forRange(range, customDays)
    val previous = if (range == StatsRange.CUSTOM && appliedRange != null) {
        val count = appliedRange.dayCount
        val prevEnd = appliedRange.startInclusive.minusDays(1)
        val prevStart = prevEnd.minusDays((count - 1).toLong())
        data.days.filter { it.date >= prevStart.toString() && it.date <= prevEnd.toString() }
    } else data.previousRange(range, customDays)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.section)) {
        RangeSelector(range = range, onSelect = onSelectRange, enabled = category != StatsCategory.YEAR)
        StatsCategorySelector(category = category, onSelect = onSelectCategory)
        if (category == StatsCategory.YEAR) {
            SectionHint("Rok zobrazuje celý kalendářní rok; globální období a srovnání se na tuto kategorii nevztahují.")
        } else {
            PeriodCaption(data, range, customDays, days, appliedRange)
            CompareControl(comparePrevious, enabled = true, onChange = onCompare)
        }
        if (range == StatsRange.CUSTOM && category != StatsCategory.YEAR) CustomDateRangeControl(customStartDate, customEndDate, customRangeError, onEditCustomRange, onApplyCustomRange)
        if (range == StatsRange.CUSTOM && appliedRange == null) {
            SectionHint("Nejdřív zadej a použij platné vlastní období.")
        } else {
            if (comparePrevious && category != StatsCategory.YEAR) ComparisonCard(
                data = data,
                current = days,
                category = category,
                currentRange = comparisonWindow(range, appliedRange, LocalDate.parse(data.today), customDays),
            )
            when (category) {
                StatsCategory.MOOD -> MoodCategory(data, days, range, onReload, onOpenCheckIn, onOpenDay,
                    startDate = appliedRange?.startInclusive ?: LocalDate.parse(data.today).minusDays((range.dayCount(customDays, LocalDate.parse(data.today)) - 1).toLong()),
                    endDate = appliedRange?.endInclusive ?: LocalDate.parse(data.today))
                StatsCategory.HABITS -> HabitsCategory(days, habitLabels, scaleLabels, scaleMaxima, labelsLoaded)
                StatsCategory.DIGITAL -> DigitalCategory(days, data.today, appliedRange?.dayCount ?: range.dayCount(customDays, LocalDate.parse(data.today)), appliedRange, onOpenDay)
                StatsCategory.YEAR -> YearCategory(data, onOpenDay, onOpenHistoryMonth)
            }
        }
    }
}

@Composable
internal fun StatsCategorySelector(category: StatsCategory, onSelect: (StatsCategory) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatsCategory.entries.forEach { option ->
            val selected = option == category
            Text(
                text = option.label,
                modifier = Modifier.weight(1f).testTagOrEmpty(option.testTag)
                    .clip(RoundedCornerShape(Dimens.radiusSm))
                    .background(if (selected) Indigo.copy(alpha = .35f) else Color.White.copy(alpha = .04f))
                    .selectable(selected = selected, role = Role.Tab) { onSelect(option) }
                    .padding(vertical = 12.dp).heightIn(min = 44.dp),
                textAlign = TextAlign.Center,
                color = if (selected) TextPrimary else TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun PeriodCaption(data: StatsData, range: StatsRange, customDays: Int, days: List<StatsDay>, customRange: ValidatedStatsDateRange?) {
    val end = customRange?.endInclusive ?: LocalDate.parse(data.today)
    val count = customRange?.dayCount ?: range.dayCount(customDays, LocalDate.parse(data.today))
    val start = customRange?.startInclusive ?: LocalDate.parse(data.today).minusDays((count - 1).toLong())
    SectionHint("Období: ${start.format(shortDate)} – ${end.format(shortDate)} · ${days.size}/$count dnů se záznamem · ${StatsMath.answeredDays(days)} náladových odpovědí · limit 400 dní")
}

@Composable
private fun CompareControl(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .heightIn(min = 44.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (checked) "☑" else "□", color = IndigoLight, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        Text("Srovnat s předchozím obdobím", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun CustomDateRangeControl(start: String, end: String, error: String?, onChange: (String, String) -> Unit, onApply: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHint("Zadej začátek a konec včetně (RRRR-MM-DD). Rozsah musí být v načtených 400 dnech.")
        OutlinedTextField(value = start, onValueChange = { onChange(it, end) }, modifier = Modifier.testTag("stats_custom_start"), label = { Text("Od · RRRR-MM-DD") }, singleLine = true)
        OutlinedTextField(value = end, onValueChange = { onChange(start, it) }, modifier = Modifier.testTag("stats_custom_end"), label = { Text("Do · RRRR-MM-DD") }, singleLine = true)
        error?.let { Text(it, color = ErrorRed, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = onApply, modifier = Modifier.testTag("stats_custom_apply")) { Text("Použít období") }
    }
}

@Composable
private fun ComparisonCard(
    data: StatsData,
    current: List<StatsDay>,
    category: StatsCategory,
    currentRange: cz.digitalnivedomi.diarium.core.stats.StatsDateRange,
) {
    val today = LocalDate.parse(data.today)
    val previousRange = currentRange.previous()
    val loadedStart = today.minusDays((StatsRepository.LOAD_DAYS - 1).toLong())
    val windowsCovered = previousRange.startInclusive >= loadedStart && currentRange.startInclusive >= loadedStart
    val previous = data.days.filter { it.date >= previousRange.startInclusive.toString() && it.date <= previousRange.endInclusive.toString() }
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader("Srovnání stejně dlouhých období")
        VSpace(8)
        if (!windowsCovered) {
            SectionHint("Srovnání není dostupné: předchozí kalendářní období přesahuje načtené pokrytí.")
        } else when (category) {
            StatsCategory.MOOD, StatsCategory.YEAR -> {
                val currentCount = StatsMath.answeredDays(current)
                val previousCount = StatsMath.answeredDays(previous)
                val a = StatsMath.averageMood(current)
                val b = StatsMath.averageMood(previous)
                if (currentCount < 2 || previousCount < 2 || a == null || b == null) {
                    SectionHint("Srovnání nálady není dostupné: potřeba alespoň 2 zaznamenané nálady v každém období; nyní $currentCount a $previousCount.")
                } else {
                    val delta = a - b
                    ReadOnlyRow("Průměr nálady", "${formatAverageMood(a)} / ${formatAverageMood(b)} · Δ ${signed(delta)}")
                    comparisonCoverage(previousRange, currentRange, previousCount, currentCount)
                }
            }
            StatsCategory.DIGITAL -> {
                val currentScreen = current.mapNotNull { it.screenTimeSeconds?.takeIf { value -> value >= 0 } }
                val previousScreen = previous.mapNotNull { it.screenTimeSeconds?.takeIf { value -> value >= 0 } }
                val currentUnlocks = current.mapNotNull { it.unlocks?.takeIf { value -> value >= 0 } }
                val previousUnlocks = previous.mapNotNull { it.unlocks?.takeIf { value -> value >= 0 } }
                if (currentScreen.size < 2 || previousScreen.size < 2) {
                    SectionHint("Digitální srovnání času není dostupné: potřeba alespoň 2 synchronizované dny v každém období; nyní ${currentScreen.size} a ${previousScreen.size}. Chybějící synchronizace není nula.")
                } else {
                    val screenDelta = currentScreen.average() - previousScreen.average()
                    ReadOnlyRow("Průměr času / synchronizovaný den", "${formatSeconds(currentScreen.average())} / ${formatSeconds(previousScreen.average())} · Δ ${signedSeconds(screenDelta)}")
                    if (currentUnlocks.size >= 2 && previousUnlocks.size >= 2) {
                        val unlockDelta = currentUnlocks.average() - previousUnlocks.average()
                        ReadOnlyRow("Průměr odemknutí / synchronizovaný den", "${formatAverage(currentUnlocks.average())} / ${formatAverage(previousUnlocks.average())} · Δ ${signed(unlockDelta)}")
                    } else SectionHint("Odemknutí: nedostatek synchronizovaných dnů; není zobrazeno jako nula.")
                    comparisonCoverage(previousRange, currentRange, previousScreen.size, currentScreen.size)
                }
            }
            StatsCategory.HABITS -> SectionHint("Srovnání návyků není dostupné: konkrétní návykové metriky nejsou pro toto období připravené. Nálada se zde nesrovnává.")
        }
    }
}

@Composable
private fun comparisonCoverage(
    previousRange: cz.digitalnivedomi.diarium.core.stats.StatsDateRange,
    currentRange: cz.digitalnivedomi.diarium.core.stats.StatsDateRange,
    previousCount: Int,
    currentCount: Int,
) {
    SectionHint("${previousRange.startInclusive.format(shortDate)} – ${previousRange.endInclusive.format(shortDate)} vs. ${currentRange.startInclusive.format(shortDate)} – ${currentRange.endInclusive.format(shortDate)} · $previousCount a $currentCount odpovědí. Jde o popisné srovnání, ne příčinu.")
}

@Composable
private fun MoodCategory(data: StatsData, days: List<StatsDay>, range: StatsRange, onReload: () -> Unit, onOpenCheckIn: (String) -> Unit, onOpenDay: (String) -> Unit, startDate: LocalDate, endDate: LocalDate) {
    var selectedMood by remember { mutableStateOf<Int?>(null) }
    val visibleDays = remember(days, selectedMood) { StatsMath.filterMood(days, selectedMood) }
    SectionHeader("Nálada")
    if (days.isEmpty()) EmptyWindowCard(range, onReload) else {
        MoodDistributionCard(days, range.label, selectedMood, onSelectMood = { selectedMood = it })
        MoodTrendCard(visibleDays, year = if (range.year) data.year else null, onOpenDay = if (range.year) null else onOpenDay,
            axisStart = startDate, axisEnd = endDate)
        WeekdayCard(visibleDays)
        ActivityCard(visibleDays)
        BestWorstDayBlock(visibleDays, entryByDate = data.entryByDate, onOpenCheckIn = onOpenCheckIn)
    }
}

@Composable
private fun HabitsCategory(days: List<StatsDay>, habitLabels: Map<String, String>, scaleLabels: Map<String, String>, scaleMaxima: Map<String, Int>, labelsLoaded: Boolean) {
    SectionHeader("Návyky")
    if (days.isEmpty()) { EmptyState(emoji = "✓", title = "Zatím bez dat", message = "V tomto období nejsou načtené dny.") ; return }
    val keys = days.flatMap { it.habits.keys }.distinct().sorted()
    if (keys.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth()) { SectionHeader("Návyky"); SectionHint("V tomto období nebyly v načtených zápisech nalezeny odpovědi návyků.") }
    }
    val namedHabitKeys = keys.filter { it in habitLabels }
    namedHabitKeys.forEach { key ->
        val d = StatsExploration.habitDistribution(days, key)
        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader(habitLabels.getValue(key))
            val total = days.size.coerceAtLeast(1)
            listOf("Splněno" to d.yes, "Nesplněno" to d.no, "Bez odpovědi" to d.unanswered).forEach { (label, count) ->
                ReadOnlyRow(label, count.toString())
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .06f))) {
                    Box(Modifier.fillMaxWidth(count.toFloat() / total).fillMaxHeight().background(when (label) {
                        "Splněno" -> cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
                        "Nesplněno" -> ErrorRed
                        else -> TextTertiary
                    }))
                }
                VSpace(6)
            }
        }
    }
    if (keys.isNotEmpty() && namedHabitKeys.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth()) { SectionHeader("Návyky")
            SectionHint(if (!labelsLoaded) "Načítám názvy návyků…" else "Názvy návyků nejsou dostupné; jejich identifikátory nezobrazuji.")
        }
    }
    DistributionCard("Spánek", StatsExploration.sleepDistribution(days), "sleep")
    DistributionCard("Stres", StatsExploration.stressDistribution(days), "stress")
    val namedScales = scaleLabels.keys.filter { id -> days.any { id in it.scaleValues } }
    if (namedScales.isEmpty()) {
        GlassCard(Modifier.fillMaxWidth()) { SectionHeader("Vlastní škály")
            SectionHint(if (!labelsLoaded) "Načítám názvy škál…" else "V tomto období nejsou dostupné pojmenované odpovědi škál.")
        }
    } else namedScales.forEach { id ->
        val distribution = StatsExploration.scaleDistribution(days, id)
        DistributionCard(scaleLabels.getValue(id), distribution, "scale_$id", scaleMaxima[id])
    }
}

@Composable
private fun DistributionCard(title: String, distribution: Map<Int, Int>, @Suppress("UNUSED_PARAMETER") tag: String, maxValue: Int? = null) {
    GlassCard(Modifier.fillMaxWidth()) {
        SectionHeader(title)
        if (distribution.isEmpty()) { SectionHint("V tomto období nejsou odpovědi."); return@GlassCard }
        val top = maxValue ?: distribution.keys.maxOrNull() ?: 1
        val total = distribution.values.sum().coerceAtLeast(1)
        distribution.toSortedMap().forEach { (value, count) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$value", Modifier.width(30.dp), color = TextSecondary)
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .06f))) {
                    Box(Modifier.fillMaxWidth(count.toFloat() / total).fillMaxHeight().background(moodColor(((value.toFloat() / top) * 5).roundToInt().coerceIn(1, 5))))
                }
                Text("  $count", color = TextPrimary)
            }
        }
        SectionHint("Odpovědi: ${distribution.values.sum()} · chybějící hodnoty se nezapočítávají.")
    }
}

@Composable
private fun DigitalCategory(days: List<StatsDay>, today: String, periodDays: Int, customRange: ValidatedStatsDateRange?, onOpenDay: (String) -> Unit) {
    SectionHeader("Digitální")
    if (days.none { it.screenTimeSeconds != null && it.screenTimeSeconds >= 0 } && days.none { it.unlocks != null }) {
        EmptyState(emoji = "📱", title = "Digitální data zatím nedostupná", message = "V tomto období není synchronizovaný čas u obrazovky.")
    } else {
        val rangeDays = periodDays.coerceIn(1, 400)
        val synced = days.count { it.screenTimeSeconds != null }
        GlassCard(Modifier.fillMaxWidth()) {
            SectionHeader("Pokrytí synchronizace")
            SectionHint("$synced z $rangeDays dnů · chybějící synchronizace není nula")
        }
        val end = customRange?.endInclusive ?: LocalDate.parse(today)
        val start = customRange?.startInclusive ?: end.minusDays((periodDays - 1).coerceAtLeast(0).toLong())
        ScreenTimeCharts(entries = days, today = today, startDate = start.toString(), endDate = end.toString(), onOpenDay = onOpenDay)
    }
}

@Composable
private fun YearCategory(data: StatsData, onOpenDay: (String) -> Unit, onOpenHistoryMonth: (java.time.YearMonth) -> Unit) {
    val calendarYearDays = data.yearDays()
    SectionHeader("Kalendářní rok ${data.year}")
    if (calendarYearDays.isEmpty()) SectionHint("V tomto roce zatím nejsou záznamy.")
    MoodTrendCard(calendarYearDays, year = data.year)
    YearInPixels(days = calendarYearDays, year = data.year, today = data.today, onOpenDay = onOpenDay, onOpenHistoryMonth = onOpenHistoryMonth)
}

@Composable
private fun EmptyWindowCard(range: StatsRange, onReload: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("📊 Nálada")
        VSpace(Spacing.block)
        EmptyState(emoji = "📊", title = emptyWindowTitle(range), message = "Zapiš den na kartě „Dnes“ a čísla se objeví tady.", action = {
            PrimaryButton(text = "Načíst znovu", testTag = "stats_empty_retry") { onReload() }
        })
    }
}

/**
 * The empty-window headline in proper Czech.
 *
 * A day-count window says "Za posledních 7 dní žádný zápis"; the year tab follows the
 * calendar, so dropping its "Tento rok" label into that template produced the
 * ungrammatical "Za posledních Tento rok žádný zápis". The year gets its own sentence
 * at the source instead of a translation rule papering over the broken Czech.
 */
internal fun emptyWindowTitle(range: StatsRange): String =
    if (range.year) {
        "Tento rok žádný zápis"
    } else {
        "Za posledních ${range.label} žádný zápis"
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
                    label = ::answeredDaysText,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
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
private fun MoodDistributionCard(days: List<StatsDay>, rangeLabel: String, selected: Int?, onSelectMood: (Int?) -> Unit) {
    val counts = StatsMath.moodDistribution(days)
    val answered = StatsMath.answeredDays(days)
    val haptics = rememberLightHaptics()

    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader("📊 Rozložení nálad", trailing = { Text("· $rangeLabel", style = MaterialTheme.typography.labelSmall, color = TextTertiary) })
        VSpace(4)
        SectionHint("Výběr nálady filtruje ostatní náladové přehledy; klepnutím znovu se filtr zruší.")
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
                        .selectable(selected = selected == mood, role = Role.RadioButton) {
                            haptics()
                            onSelectMood(if (selected == mood) null else mood)
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
                    label = moodChoiceText(mood),
                    value = moodShareText(count, percent),
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
 * differ; the drawing itself lives in the shared [MoodTrendChart].
 *
 * Unanswered days and unwritten months stay in as a near-flat grey stub so the slot still
 * exists on the time axis, but they are excluded from the line — see
 * [StatsMath.movingAverage].
 */
@Composable
private fun MoodTrendCard(days: List<StatsDay>, year: Int? = null, onOpenDay: ((String) -> Unit)? = null, axisStart: LocalDate? = null, axisEnd: LocalDate? = null) {
    val monthly = year != null
    val months = if (year != null) StatsMath.monthlyMood(days, year) else emptyList()
    val calendarDays = if (!monthly && axisStart != null && axisEnd != null) StatsMath.calendarMoodDays(days, axisStart, axisEnd) else days
    val points = if (monthly) emptyList() else StatsMath.moodTrend(calendarDays)

    val slots: List<MoodTrendSlot> = if (monthly) {
        months.map { month ->
            val monthAverage = month.averageMood
            MoodTrendSlot(
                key = "m${month.month}",
                label = StatsMath.monthShortName(month.month),
                title = "${StatsMath.monthFullName(month.month)} $year",
                mood = monthAverage?.roundToInt() ?: 0,
                written = month.answered,
                moodText = monthAverage?.let {
                    moodChoiceText(it.roundToInt())
                } ?: translate("Bez odpovědi"),
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
            MoodTrendSlot(
                key = point.date,
                label = shortDateOf(point.date),
                title = shortDateOf(point.date),
                mood = point.mood,
                written = written,
                moodText = if (written) {
                    moodChoiceText(point.mood)
                } else {
                    translate("Bez odpovědi")
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
        StatsMath.movingAverage(calendarDays)
    }
    val averageLabel = if (monthly) {
        "${StatsMath.MONTH_MOVING_AVERAGE_WINDOW}měsíční klouzavý průměr"
    } else {
        "${StatsMath.MOVING_AVERAGE_WINDOW}denní klouzavý průměr"
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

        MoodTrendChart(
            slots = slots,
            average = average,
            averageLabel = averageLabel,
            averageDetailLabel = if (monthly) averageLabel else "7denní průměr",
            caption = if (monthly) MoodTrendCaption.PerSlot else MoodTrendCaption.EdgeDates,
            summary = if (monthly) monthSummary(months) else null,
            onOpenDay = if (monthly) null else onOpenDay,
        )
    }
}

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


