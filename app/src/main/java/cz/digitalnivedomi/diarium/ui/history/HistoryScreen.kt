package cz.digitalnivedomi.diarium.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.HistoryData
import cz.digitalnivedomi.diarium.ui.checkin.components.ErrorBanner
import cz.digitalnivedomi.diarium.ui.checkin.components.GlassIconButton
import cz.digitalnivedomi.diarium.ui.checkin.components.PrimaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import cz.digitalnivedomi.diarium.ui.theme.moodColor
import java.time.LocalDate

/**
 * Historie — a month at a glance, and one day in full.
 *
 * The calendar answers "what did this month look like" (a mood dot on every day
 * that has an entry, nothing on the days that do not), and tapping a day opens its
 * detail below the grid. The month header is the navigation; the grid is the data.
 *
 * Four deliberate rules, the same ones the dashboard holds:
 *
 * 1. **Loading is visible.** The grid is replaced by a spinner while the month is
 *    being read, instead of drawing an empty month that would read as "nothing
 *    written".
 * 2. **A failure is visible and retryable.** A failed read keeps the month header
 *    usable but shows the repository's Czech sentence plus "Zkusit znovu" — a dead
 *    request can never render as a calendar with no entries in it.
 * 3. **Days that cannot have an entry are inert.** Future days (and the padding
 *    cells outside the month) are drawn but not tappable: there is nothing behind
 *    them to open, and a tappable dead cell is worse than a grey one.
 * 4. **No session still renders.** With [HistoryDeps.offline] the screen composes and
 *    lands in the retry state rather than crashing on a missing client.
 *
 * The month is refreshed when the tab is shown again (not on every recomposition),
 * so a check-in saved on the "Dnes" tab is in the grid when the user comes back.
 *
 * One extra read feeds the day detail: the user's scales, so a row keyed by scale
 * uuid can be titled with the scale's name (see [HistoryDeps.pickers]).
 */
@Composable
fun HistoryScreen(
    deps: HistoryDeps = remember { HistoryDeps.offline() },
    onOpenCheckIn: (String) -> Unit = {},
) {
    val holder = remember { HistoryStateHolder() }
    val state = holder.state
    val repository = deps.history
    val pickers = deps.pickers
    // "Today" is fixed for the whole screen: it decides which days are future days,
    // so it must not drift mid-session between the grid and the detail.
    val today = remember { HistoryCalendar.today() }

    var year by remember { mutableStateOf(today.year) }
    var month by remember { mutableStateOf(today.monthValue) }
    var selected by remember { mutableStateOf<String?>(null) }
    var reloadTrigger by remember { mutableStateOf(0) }
    // Scale id -> label / max, so the day detail names a scale instead of showing
    // the uuid an entry's `scale_values` is keyed by.
    var scaleNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var scaleMax by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (firstResume) firstResume = false else reloadTrigger++
        onPauseOrDispose { }
    }

    // The scales are independent of the selected month, so they are read once per
    // set of deps rather than on every day/month change.
    LaunchedEffect(pickers) {
        if (pickers != null) {
            val scales = pickers.scales()
            scaleNames = scaleNameMap(scales)
            scaleMax = scaleMaxMap(scales)
        }
    }

    LaunchedEffect(repository, year, month, reloadTrigger) {
        holder.markLoading()
        val result = repository?.load(year, month, today.toString())
        if (result == null) {
            holder.showError(ERROR_LOAD)
        } else {
            result
                .onSuccess { loaded -> holder.show(loaded) }
                .onFailure { error -> holder.showError(error.message ?: ERROR_LOAD) }
        }
    }

    // Only the month the grid is currently drawing may paint dots on it — a month
    // that is still loading must not wear the previous month's entries.
    val monthData = state.data?.takeIf { it.year == year && it.month == month }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        VSpace(12)
        Text(
            text = "Historie",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        VSpace(2)
        Text(
            text = "Kalendář a zápisy po dnech",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        VSpace(16)

        CalendarCard(
            year = year,
            month = month,
            today = today,
            data = monthData,
            loading = state.loading,
            selected = selected,
            onPrevious = {
                val (previousYear, previousMonth) = HistoryCalendar.previousMonth(year, month)
                year = previousYear
                month = previousMonth
                selected = null
            },
            onNext = {
                val (nextYear, nextMonth) = HistoryCalendar.nextMonth(year, month)
                year = nextYear
                month = nextMonth
                selected = null
            },
            onSelect = { iso -> selected = iso },
        )

        VSpace(14)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val error = state.errorMessage
            val day = selected
            when {
                error != null -> ErrorCard(error) { reloadTrigger++ }
                monthData != null && day != null -> DayDetail(
                    date = day,
                    entry = monthData.entryOn(day),
                    scaleNames = scaleNames,
                    scaleMax = scaleMax,
                    onOpenCheckIn = onOpenCheckIn,
                )
                monthData != null -> HintCard(monthData)
                else -> LoadingCard(year = year, month = month)
            }
        }

        VSpace(36)
    }
}

/** The retry copy used when the read itself failed (and when there is no session). */
private const val ERROR_LOAD = "Historii se nepodařilo načíst."

/** Gap between calendar cells, in dp — the same value in the header row and the grid. */
private const val CELL_GAP_DP = 4

/** 🇨🇿 Month grid: Czech month + year header, ← / →, then Monday-first days. */
@Composable
private fun CalendarCard(
    year: Int,
    month: Int,
    today: LocalDate,
    data: HistoryData?,
    loading: Boolean,
    selected: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val todayIso = HistoryCalendar.iso(today)

    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            GlassIconButton(text = "◀", testTag = "history_prev", onClick = onPrevious)
            Text(
                text = HistoryCalendar.title(year, month),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            GlassIconButton(text = "▶", testTag = "history_next", onClick = onNext)
        }
        VSpace(14)

        // The header row carries the same 7 weights and the same gap as the grid, so
        // the abbreviations sit over the columns they name.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP_DP.dp),
        ) {
            HistoryCalendar.WEEKDAY_NAMES.forEach { name ->
                Text(
                    text = name.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        VSpace(6)

        val grid = HistoryCalendar.monthGrid(year, month)
        if (loading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(184.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = IndigoLight,
                )
            }
        } else {
            grid.chunked(HistoryCalendar.DAYS_PER_WEEK).forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(CELL_GAP_DP.dp),
                ) {
                    week.forEach { date ->
                        if (date == null) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            val iso = HistoryCalendar.iso(date)
                            DayCell(
                                dayOfMonth = date.dayOfMonth,
                                entry = data?.entryOn(iso),
                                isToday = iso == todayIso,
                                isSelected = iso == selected,
                                selectable = HistoryCalendar.isSelectable(date, today),
                                testTag = "history_day_$iso",
                                onClick = { onSelect(iso) },
                            )
                        }
                    }
                }
                VSpace(CELL_GAP_DP)
            }
        }
    }
}

/**
 * One day of the grid.
 *
 * A day with an entry takes the mood's own colour as its marker (emerald, amber,
 * red…), a day without stays a faint cell — the grid is readable as a mood chart at
 * a glance. Future days are muted AND unclickable: no ripple, no callback, nothing
 * to open.
 */
@Composable
private fun RowScope.DayCell(
    dayOfMonth: Int,
    entry: DiaryEntry?,
    isToday: Boolean,
    isSelected: Boolean,
    selectable: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val accent = if (entry != null) moodColor(entry.mood) else Indigo
    val borderColor = when {
        isSelected -> accent
        isToday -> Indigo
        else -> Outline.copy(alpha = 0.7f)
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .testTag(testTag)
            .clip(shape)
            .background(
                if (entry != null) accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.04f),
            )
            .border(
                width = if (isSelected || isToday) 2.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .then(if (selectable) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = when {
                    !selectable -> TextTertiary.copy(alpha = 0.5f)
                    entry != null -> TextPrimary
                    else -> TextSecondary
                },
                fontWeight = if (entry != null) FontWeight.Medium else FontWeight.Normal,
            )
            if (entry != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = historyMoodEmoji(entry.mood), fontSize = 12.sp, maxLines = 1)
                    if (!entry.photoPath.isNullOrBlank()) {
                        Text(text = "📷", fontSize = 8.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Visible loading: never an empty month while the read is in flight. */
@Composable
private fun LoadingCard(year: Int, month: Int) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = IndigoLight,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Načítám historii…",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                VSpace(2)
                SectionHint(
                    "Chvilku strpení, stahuju zápisy za " +
                        HistoryCalendar.title(year, month).replaceFirstChar { it.lowercase() } + ".",
                )
            }
        }
    }
}

/** Failed load: the repository's own Czech sentence, plus one way out. */
@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = ErrorRed) {
        SectionHeader("Načtení historie")
        VSpace(10)
        ErrorBanner(message)
        VSpace(14)
        PrimaryButton(text = "Zkusit znovu", testTag = "history_retry") { onRetry() }
    }
}

/** Nothing picked yet — say so, and say what an empty month means. */
@Composable
private fun HintCard(data: HistoryData) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("🗓️ Kalendář")
        VSpace(6)
        Text(
            text = HistoryCalendar.title(data.year, data.month),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(4)
        if (data.entries.isEmpty()) {
            SectionHint("V tomto měsíci zatím nemáš žádné zápisy.")
        } else {
            SectionHint(
                if (data.entries.size == 1) "V tomto měsíci máš 1 zápis."
                else "V tomto měsíci máš ${data.entries.size} zápisů.",
            )
        }
        VSpace(4)
        SectionHint("Klikni na den v kalendáři a uvidíš, co jsi ten den zapsal.")
    }
}
