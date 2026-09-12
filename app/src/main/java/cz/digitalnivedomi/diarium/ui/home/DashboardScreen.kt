package cz.digitalnivedomi.diarium.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cz.digitalnivedomi.diarium.core.data.AiReflectionRepository
import cz.digitalnivedomi.diarium.core.data.DashboardData
import cz.digitalnivedomi.diarium.core.data.DashboardDay
import cz.digitalnivedomi.diarium.core.data.DashboardReflection
import cz.digitalnivedomi.diarium.core.data.DashboardNewestEntry
import cz.digitalnivedomi.diarium.core.data.DashboardRepository
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.MoodDiscState
import cz.digitalnivedomi.diarium.core.data.formatTopAppDuration
import cz.digitalnivedomi.diarium.core.data.isRecordedDay
import cz.digitalnivedomi.diarium.core.data.moodDiscState
import cz.digitalnivedomi.diarium.core.data.rankTopApps
import cz.digitalnivedomi.diarium.core.stats.ScreenTimeSeries
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.MOOD_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.SLEEP_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.STRESS_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.components.ErrorBanner
import cz.digitalnivedomi.diarium.ui.checkin.components.PrimaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.ReadOnlyRow
import cz.digitalnivedomi.diarium.ui.checkin.components.SecondaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.checkin.components.formatMinutes
import cz.digitalnivedomi.diarium.ui.components.BrandSpinner
import cz.digitalnivedomi.diarium.ui.components.EmptyState
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassChip
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.IconBadge
import cz.digitalnivedomi.diarium.ui.components.ScreenHeader
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.SubScreenEntry
import cz.digitalnivedomi.diarium.ui.nav.Routes
import cz.digitalnivedomi.diarium.ui.components.StaggeredItem
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.components.rememberLightHaptics
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.Cyan
import cz.digitalnivedomi.diarium.ui.theme.Violet
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.Spacing
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import cz.digitalnivedomi.diarium.ui.theme.moodColor
import java.time.DayOfWeek
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The dashboard — "Přehled", the app's home tab.
 *
 * Answers the two questions a daily diary should answer on open: how today looks,
 * and how the last week went. Every number comes from
 * [DashboardRepository], which loads one range and derives everything from it.
 *
 * Three deliberate rules:
 *
 * 1. **Loading is visible.** The repository read takes a moment and the screen says
 *    so ("Načítám přehled…") instead of drawing zeroes.
 * 2. **A failure is visible and retryable.** A failed read shows the Czech message
 *    the repository returned plus "Zkusit znovu" — it can never quietly render an
 *    empty-looking dashboard, which is the bug this screen was written to fix.
 * 3. **No session still renders.** With [DashboardDeps.offline] the screen composes
 *    and lands in that same retry state rather than crashing on a missing client.
 *
 * The tab is refreshed when it is shown again (and not on every recomposition), so a
 * check-in saved on the "Dnes" tab appears in the numbers when the user comes back.
 */
@Composable
fun DashboardScreen(
    deps: DashboardDeps = remember { DashboardDeps.offline() },
    onOpenCheckIn: (String) -> Unit = {},
    onOpen: (String) -> Unit = {},
) {
    val holder = remember { DashboardStateHolder() }
    val state = holder.state
    val data = state.data
    val repository = deps.dashboard
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
            title = "Přehled",
            subtitle = "Tvůj denní deník",
        )
        VSpace(Spacing.section)

        when {
            state.loading -> LoadingCard()
            state.errorMessage != null -> ErrorCard(state.errorMessage.orEmpty()) { reloadTrigger++ }
            data != null -> DashboardContent(
                data = data,
                onOpenCheckIn = onOpenCheckIn,
                onOpen = onOpen,
                reflectionRepository = deps.reflection,
            )
        }

        VSpace(Spacing.screenBottom)
    }
}

/** The retry copy used when the read itself failed (and when there is no session). */
private const val ERROR_LOAD = "Přehled se nepodařilo načíst."

/** Height of the bar track: the tallest day fills it. */
private val BAR_TRACK = 96.dp

/** Czech full date, used for the "Dnes" card. */
private val longDate = java.time.format.DateTimeFormatter.ofPattern(
    "d. MMMM yyyy",
    Locale("cs", "CZ"),
)

/** Czech numeric date, used for "which day is this from" captions. */
private val shortDate = java.time.format.DateTimeFormatter.ofPattern(
    "d. M. yyyy",
    Locale("cs", "CZ"),
)

/** "10. září 2026", or the raw value if it is not a date. */
private fun longDateOf(date: String): String = CheckInDates.parse(date)?.format(longDate) ?: date

/** "5. 9. 2026", or the raw value if it is not a date. */
private fun shortDateOf(date: String): String = CheckInDates.parse(date)?.format(shortDate) ?: date

/** "Dnes" for today, otherwise the Czech weekday abbreviation the web uses. */
private fun dayLabel(date: String, today: String): String {
    if (date == today) return "Dnes"
    return when (CheckInDates.parse(date)?.dayOfWeek) {
        DayOfWeek.MONDAY -> "Po"
        DayOfWeek.TUESDAY -> "Út"
        DayOfWeek.WEDNESDAY -> "St"
        DayOfWeek.THURSDAY -> "Čt"
        DayOfWeek.FRIDAY -> "Pá"
        DayOfWeek.SATURDAY -> "So"
        DayOfWeek.SUNDAY -> "Ne"
        null -> ""
    }
}

/** Czech plural for a day count: 1 den, 2–4 dny, 5+ dní. */
private fun dayWord(count: Int): String = when {
    count == 1 -> "den"
    count in 2..4 -> "dny"
    else -> "dní"
}

private fun moodEmojiOf(mood: Int): String =
    MOOD_CHOICES.firstOrNull { it.value == mood }?.emoji ?: "⚪"

private fun moodLabelOf(entry: DiaryEntry): String? =
    MOOD_CHOICES.firstOrNull { it.value == entry.mood }?.label
        ?: entry.moodEmoji.takeIf { it.isNotBlank() }

private fun emojiOf(entry: DiaryEntry): String =
    MOOD_CHOICES.firstOrNull { it.value == entry.mood }?.emoji ?: entry.moodEmoji.ifBlank { "⚪" }

private fun sleepLabelOf(entry: DiaryEntry): String =
    SLEEP_CHOICES.firstOrNull { it.value == entry.sleepQuality }?.label ?: "—"

private fun stressLabelOf(entry: DiaryEntry): String =
    STRESS_CHOICES.firstOrNull { it.value == entry.stress }?.label ?: "—"

/** The web prints `toFixed(1)`, so the average keeps a dot rather than a Czech comma. */
private fun formatAverageMood(value: Double): String = String.format(Locale.US, "%.1f", value)

@Composable
private fun DashboardContent(
    data: DashboardData,
    onOpenCheckIn: (String) -> Unit,
    onOpen: (String) -> Unit,
    reflectionRepository: AiReflectionRepository?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.section),
    ) {
        // Cards fade in one after another as the data lands — the stagger is
        // capped by Entrance, so even a full dashboard settles in ~300ms.
        StaggeredItem(1) { StreakCard(data = data) }
        StaggeredItem(2) { WeekCard(data = data) }
        StaggeredItem(3) { ScreenTimeCard(data = data) }
        StaggeredItem(4) { UnlocksCard(data = data) }
        StaggeredItem(5) { TopAppsCard(data = data) }
        // The card keys off the newest *recorded* day (mood filled, see RecordDay):
        // a day the phone only synced is not a record and must not blank the card
        // (2026-09-12). Hidden only when the "Dnes" card already renders that exact
        // text, so nothing shows twice.
        val anchor = data.newestRecorded ?: data.newestEntry
        val todayShowsIt = !data.todayEntry?.aiReflection.isNullOrBlank()
        if (!todayShowsIt && (anchor != null || data.reflection != null)) {
            StaggeredItem(6) {
                ReflectionCard(
                    anchor = anchor,
                    latest = data.reflection,
                    repository = reflectionRepository,
                )
            }
        }
        // The M5 screens have no tab of their own (the bar already carries five),
        // so the overview carries their entrances. Pure navigation, no read.
        StaggeredItem(5) { EntriesCard(onOpen = onOpen) }
    }
}

/**
 * Entrances to the M5 screens: cíle, škály, šablony poznámek and odznaky.
 *
 * Kept on the overview as well as in Nastavení on purpose — Vojta's rule is that a
 * feature has to be visible without hunting for it, and the dashboard is where the
 * day starts. The card reads nothing, so it cannot fail or go empty.
 */
@Composable
private fun EntriesCard(onOpen: (String) -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("🎯 Cíle a další")
        VSpace(4)
        SectionHint("Cíle se streakem, vlastní škály, šablony poznámek a odznaky.")
        VSpace(6)
        SubScreenEntry(
            emoji = "🎯",
            title = "Cíle",
            hint = "Streak a plnění za den, týden a měsíc",
            onClick = { onOpen(Routes.GOALS) },
        )
        GlassDivider()
        SubScreenEntry(
            emoji = "🎚️",
            title = "Škály",
            hint = "Vlastní škály a jejich rozložení za 30 dní",
            onClick = { onOpen(Routes.SCALES) },
        )
        GlassDivider()
        SubScreenEntry(
            emoji = "📄",
            title = "Šablony poznámek",
            hint = "Rychlé vložení do poznámky v check-inu",
            onClick = { onOpen(Routes.TEMPLATES) },
        )
        GlassDivider()
        SubScreenEntry(
            emoji = "🏆",
            title = "Odznaky",
            hint = "Co už je odemčené a co ještě ne",
            onClick = { onOpen(Routes.ACHIEVEMENTS) },
        )
        GlassDivider()
        SubScreenEntry(
            emoji = "🔔",
            title = "Notifikace",
            hint = "Připomenutí, reporty a čas na obrazovce",
            onClick = { onOpen(Routes.NOTIFICATIONS) },
        )
    }
}

/** Visible loading: never a zeroed dashboard while the read is in flight. */
@Composable
private fun LoadingCard() {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandSpinner()
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Načítám přehled…",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                VSpace(2)
                SectionHint("Chvilku strpení, stahuju posledních ${DashboardRepository.LOAD_DAYS} dní.")
            }
        }
    }
}

/** Failed load: the repository's own Czech sentence, plus one way out. */
@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = ErrorRed) {
        SectionHeader("Načtení přehledu")
        VSpace(10)
        ErrorBanner(message)
        VSpace(14)
        PrimaryButton(text = "Zkusit znovu", testTag = "dashboard_retry") { onRetry() }
    }
}

/**
 * "Dnes": the day's own numbers, or — when nothing is saved yet — a friendly pointer
 * to the check-in tab instead of a card full of dashes.
 */
@Composable
private fun TodayCard(data: DashboardData, onOpenCheckIn: (String) -> Unit) {
    val entry = data.todayEntry
    val haptics = rememberLightHaptics()
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accent = moodColor(entry?.mood),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                SectionHeader("📅 Dnes")
                VSpace(4)
                Text(
                    text = longDateOf(data.today),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
            }
            if (entry != null) {
                IconBadge(accent = moodColor(entry.mood), size = 44) {
                    Text(text = emojiOf(entry), fontSize = 24.sp)
                }
            }
        }
        VSpace(12)

        if (entry == null) {
            EmptyState(
                emoji = "✍️",
                title = "Dnes ještě nemáš check-in",
                message = "Zapiš, jaký byl den — čísla se pak objeví i tady.",
                action = {
                    PrimaryButton(text = "✏️ Check-in", testTag = "dashboard_open_checkin") {
                        haptics()
                        onOpenCheckIn(data.today)
                    }
                },
            )
            return@GlassCard
        }

        moodLabelOf(entry)?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = moodColor(entry.mood),
            )
            VSpace(10)
        }

        GlassDivider()
        VSpace(10)
        ReadOnlyRow(label = "Spánek", value = sleepLabelOf(entry))
        VSpace(6)
        ReadOnlyRow(label = "Stres", value = stressLabelOf(entry))

        data.lastGratitude?.let { gratitude ->
            VSpace(12)
            SectionHeader("🙏 Poslední vděčnost")
            VSpace(6)
            if (gratitude.date != data.today) {
                SectionHint("Naposledy zapsaná ${shortDateOf(gratitude.date)}.")
                VSpace(4)
            }
            gratitude.lines.forEach { line ->
                Text(
                    text = "• $line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }

        entry.aiReflection?.takeIf { it.isNotBlank() }?.let { reflection ->
            VSpace(12)
            ReflectionBox(text = reflection)
        }

        VSpace(14)
        SecondaryButton(text = "✏️ Upravit dnešní check-in") {
            haptics()
            onOpenCheckIn(data.today)
        }
    }
}

/**
 * 🔥 Série — the running streak and the longest run in the loaded window.
 *
 * The streak counts back from today but survives an unfinished today (see
 * [DashboardRepository.currentStreak]), so the hint says so when today is still blank:
 * the user must not read a live streak as a broken one.
 */
@Composable
private fun StreakCard(data: DashboardData) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader("🔥 Série")
        VSpace(8)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = data.streak.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (data.streak > 0) "${dayWord(data.streak)} v řadě" else "dnů v řadě",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        VSpace(6)
        when {
            data.streak == 0 -> SectionHint("Zatím žádná série — začni dnešním zápisem.")
            // A synced row with no mood is not a record, so the streak does not
            // count today and this reads exactly like an unwritten today.
            !isRecordedDay(data.todayEntry?.mood) ->
                SectionHint("Dnešní zápis ještě chybí, série ale pokračuje.")
            else -> SectionHint("Série pokračuje dnešním zápisem.")
        }
        VSpace(12)
        GlassDivider()
        VSpace(10)
        ReadOnlyRow(
            label = "Nejdelší série (za ${DashboardRepository.LOAD_DAYS} dní)",
            value = "${data.longestStreak} ${dayWord(data.longestStreak)}",
        )
    }
}

/** 🎭 Nálada — one cell per day of the week window, coloured by mood. */
@Composable
private fun WeekCard(data: DashboardData) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("🎭 Nálada")
        VSpace(4)
        Text(
            text = "Posledních ${DashboardRepository.WEEK_DAYS} dní",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(14)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            data.week.forEach { day ->
                val isToday = day.date == data.today
                // Only the mood decides the mark: a synced row with no mood is
                // missing, exactly like a day with no row (the owner's rule).
                val state = moodDiscState(day.mood)
                val mood = moodColor(day.mood)
                // Two marks, not three: a day without a mood is missing whether
                // or not the phone synced a row for it, so it can never render as
                // a logged day. Every branch must be a Brush: there is no
                // `background(Color)` overload here, so an even fill is a
                // solid-colour brush.
                val fill: Brush = when (state) {
                    MoodDiscState.Mood ->
                        // A soft glow: the mood colour fades down the disc instead
                        // of filling it fully saturated.
                        Brush.verticalGradient(
                            listOf(mood.copy(alpha = 0.30f), mood.copy(alpha = 0.10f)),
                        )
                    // The quiet empty disc: a day that reads as "still missing".
                    MoodDiscState.Missing -> SolidColor(Color.White.copy(alpha = 0.04f))
                }
                val borderWidth = if (isToday) 2.dp else 1.2.dp
                val borderColor = when (state) {
                    MoodDiscState.Mood -> if (isToday) Indigo else mood.copy(alpha = 0.55f)
                    MoodDiscState.Missing -> if (isToday) Indigo else Outline.copy(alpha = 0.5f)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(fill)
                            .border(width = borderWidth, color = borderColor, shape = CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (state) {
                            // The emoji carries the mood colour in its own glyphs;
                            // the disc stays translucent so the two do not fight.
                            MoodDiscState.Mood -> Text(
                                text = moodEmojiOf(day.mood),
                                fontSize = 17.sp,
                                color = Color.Unspecified,
                            )
                            // A quiet dot: a day without a mood reads as missing,
                            // exactly like a day the phone never synced.
                            MoodDiscState.Missing -> Text(
                                text = "·",
                                fontSize = 17.sp,
                                color = TextTertiary,
                            )
                        }
                    }
                    VSpace(4)
                    Text(
                        text = dayLabel(day.date, data.today),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isToday) TextPrimary else TextTertiary,
                    )
                }
            }
        }
        // No legend: there is no neutral "–" disc any more. A day without a mood
        // reads as missing, so nothing here needs explaining.
        VSpace(12)
        GlassDivider()
        VSpace(10)
        val average = data.averageMood
        if (average == null) {
            SectionHint("Tento týden ještě nemáš zapsanou žádnou náladu.")
        } else {
            ReadOnlyRow(
                label = "Ø nálada v týdnu",
                value = "${formatAverageMood(average)} / 5",
            )
        }
    }
}

/**
 * 📱 The screen-time chart of the week window.
 *
 * Split from the unlock count on 2026-09-12: the owner asked for one chart for the
 * time on screen and its own chart for the unlocks, instead of one card carrying
 * both. A shared [UsageCard] keeps the two looking like parts of the same app.
 */
@Composable
private fun ScreenTimeCard(data: DashboardData) {
    UsageCard(
        title = "📱 Čas na obrazovce",
        week = data.week,
        today = data.today,
        valueOf = { day -> day.screenTimeSeconds },
        valueLabel = { seconds -> ScreenTimeSeries.secondsLabel(seconds) },
        barBrush = Brush.verticalGradient(listOf(IndigoLight, Indigo)),
        total = data.screenTimeMinutes?.let { minutes -> formatMinutes(minutes) },
        totalHint = "Celkem za posledních ${DashboardRepository.WEEK_DAYS} dní",
        emptyHint = "Data o screen timu se ještě nenasynchronizovala. " +
            "Až je telefon odešle, uvidíš tady sloupce za posledních " +
            "${DashboardRepository.WEEK_DAYS} dní.",
        legend = "Číslo pod sloupcem = čas na obrazovce ten den.",
    )
}

/**
 * 🔓 The same seven days, one bar per day's unlock count.
 *
 * Its own card and its own scale: 200 unlocks used to be invisible next to a
 * six-hour screen-time bar. Cyan-to-violet keeps it a sibling of the screen-time
 * chart rather than a second indigo one.
 */
@Composable
private fun UnlocksCard(data: DashboardData) {
    UsageCard(
        title = "🔓 Odemknutí",
        week = data.week,
        today = data.today,
        valueOf = { day -> day.unlocks },
        valueLabel = { unlocks -> unlocks?.toString() ?: ScreenTimeSeries.NO_DATA },
        barBrush = Brush.verticalGradient(listOf(Cyan, Violet)),
        total = data.unlocks?.let { count -> "$count odemknutí" },
        totalHint = "Celkem za posledních ${DashboardRepository.WEEK_DAYS} dní",
        emptyHint = "Počet odemknutí se ještě nenasynchronizoval. " +
            "Až je telefon odešle, uvidíš tady sloupce za posledních " +
            "${DashboardRepository.WEEK_DAYS} dní.",
        legend = "Číslo pod sloupcem = počet odemknutí ten den.",
    )
}

/**
 * 🏆 The newest day whose app list is long enough to rank.
 *
 * The card prefers a complete snapshot from an older day over the two or three apps
 * a morning sync has captured — the defect the owner reported on 2026-09-12
 * ("stejně tak seznam používaných aplikací"). The day is always printed, so an older
 * list never passes for today's.
 */
@Composable
private fun TopAppsCard(data: DashboardData) {
    val topApps = data.rankedTopApps ?: data.topApps ?: return
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Nejpoužívanější aplikace")
        VSpace(2)
        SectionHint("Ze dne ${shortDateOf(topApps.date)}")
        VSpace(10)
        // Ranked here, not rendered straight from the query: the raw database
        // order buried the real leaders (Snooker 37 min) behind five rows that
        // happened to come first (Hermes WebUI 8 min).
        val ranked = rankTopApps(topApps.apps)
        if (ranked.size < DashboardRepository.RANKED_TOP_APPS_MIN) {
            // Two rows cannot show a ranking; an honest hint beats a near-empty
            // list that reads as missing data.
            SectionHint("Pro žebříček nejpoužívanějších aplikací je tu zatím málo dat.")
        } else {
            val maxMinutes = ranked.first().minutes
            ranked.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = app.app,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatTopAppDuration(app.minutes),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiary,
                    )
                }
                VSpace(5)
                // A thin bar scaled to the largest value shown, so the ranking
                // is visible at a glance.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.07f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(
                                (app.minutes.toFloat() / maxMinutes).coerceIn(0f, 1f),
                            )
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Brush.horizontalGradient(listOf(IndigoLight, Indigo))),
                    )
                }
                VSpace(11)
            }
        }
    }
}

/**
 * One usage chart: a title, the window's total, a bar per day with that day's value
 * printed under it, and the weekday captions.
 *
 * Shared by [ScreenTimeCard] and [UnlocksCard], so the two charts differ only in the
 * number they plot — a split must not make them look like two different apps. A day
 * the phone never synced draws a stub bar and prints "—", so "nothing reported" can
 * never read as a very quiet day.
 */
@Composable
private fun UsageCard(
    title: String,
    week: List<DashboardDay>,
    today: String,
    valueOf: (DashboardDay) -> Int?,
    valueLabel: (Int?) -> String,
    barBrush: Brush,
    total: String?,
    totalHint: String,
    emptyHint: String,
    legend: String,
) {
    val maxValue = week.mapNotNull(valueOf).maxOrNull() ?: 0
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title)
        VSpace(4)
        Text(
            text = "Posledních ${DashboardRepository.WEEK_DAYS} dní",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(10)

        if (total == null) {
            SectionHint(emptyHint)
            return@GlassCard
        }

        Text(
            text = total,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
        SectionHint(totalHint)
        VSpace(16)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            week.forEach { day ->
                val value = valueOf(day)
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.height(BAR_TRACK),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (value == null || maxValue == 0) {
                            // Nothing synced (or nothing to scale against): a stub,
                            // not a bar that could be mistaken for a small value.
                            Box(
                                modifier = Modifier
                                    .width(16.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.10f)),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .width(16.dp)
                                    .height(
                                        (BAR_TRACK.value * value / maxValue)
                                            .coerceAtLeast(6f)
                                            .dp,
                                    )
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(barBrush),
                            )
                        }
                    }
                    VSpace(4)
                    Text(
                        text = valueLabel(value),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.wrapContentWidth(
                            align = Alignment.CenterHorizontally,
                            unbounded = true,
                        ),
                    )
                    VSpace(2)
                    Text(
                        text = dayLabel(day.date, today),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (day.date == today) TextPrimary else TextTertiary,
                    )
                }
            }
        }

        VSpace(8)
        SectionHint(legend)
    }
}

/**
 * 🤖 The AI reflection the dashboard shows.
 *
 * Two rules, both from 2026-09-12:
 *
 * 1. The card speaks about the newest **recorded** day (mood filled) — a day the
 *    phone merely synced is not a record, so its empty reflection must not hide the
 *    text of the last day that has one ("denní reflexe se zde opět nezobrazuje, chci
 *    tam zatím tu včerejší z 11. 9.").
 * 2. When that day has no text yet, the newest reflection there is stays on screen
 *    with its own date — the card is never blank while a reflection exists. The
 *    generate button still belongs to the recorded day, which is the only day a
 *    reflection can be generated for.
 *
 * A generation request fires only on the tap — nothing network-bound runs while the
 * screen loads.
 */
@Composable
private fun ReflectionCard(
    anchor: DashboardNewestEntry?,
    latest: DashboardReflection?,
    repository: AiReflectionRepository?,
) {
    val scope = rememberCoroutineScope()
    val anchorText = anchor?.reflection?.takeIf { it.isNotBlank() }
    val shown = anchorText ?: latest?.text
    val shownDate = if (anchorText != null) anchor?.date else latest?.date
    var text by remember(shownDate) { mutableStateOf(shown) }
    var label by remember(shownDate) { mutableStateOf(shownDate) }
    var loading by remember(shownDate) { mutableStateOf(false) }
    var error by remember(shownDate) { mutableStateOf<String?>(null) }

    // Only a recorded day can have a reflection generated: an unrecorded one has
    // nothing to reflect on.
    val missing = anchor?.takeIf { it.reflection.isNullOrBlank() }

    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader("🤖 AI Reflexe")
        VSpace(4)
        Text(
            text = if (anchorText != null) "Naposledy vygenerovaná" else "Poslední reflexe",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(2)
        label?.let { date -> SectionHint("Ze dne ${shortDateOf(date)}") }
        VSpace(10)

        val current = text
        if (!current.isNullOrBlank()) {
            ReflectionBox(text = current)
        } else {
            SectionHint("Reflexe ještě nebyla vygenerovaná.")
        }

        if (missing != null) {
            VSpace(10)
            if (!current.isNullOrBlank()) {
                SectionHint("Za ${shortDateOf(missing.date)} ji ještě nemáš.")
            }
            VSpace(12)
            GlassActionButton(
                text = if (loading) {
                    "Generuji reflexi…"
                } else {
                    "Vygenerovat reflexi za ${shortDateOf(missing.date)}"
                },
                enabled = !loading,
                leadingSpinner = loading,
            ) {
                // The only place a request is ever fired: the user's tap.
                val repo = repository
                if (repo == null) {
                    error = AiReflectionRepository.MESSAGE_CONNECT
                } else if (!loading) {
                    loading = true
                    error = null
                    scope.launch {
                        repo.generate(missing.date, missing.entry, repo.userName())
                            .onSuccess { generated ->
                                text = generated
                                label = missing.date
                                loading = false
                            }
                            .onFailure { failure ->
                                // The repository's own Czech sentence, verbatim: a
                                // 429 cooldown must be shown as-is, never retried
                                // in a loop.
                                error = failure.message ?: AiReflectionRepository.MESSAGE_GENERIC
                                loading = false
                            }
                    }
                }
            }
        }

        error?.let { message ->
            VSpace(10)
            ErrorBanner(message)
        }
    }
}

/**
 * Full-width glass action in the brand indigo, used by the reflection card's
 * "generate" button. Deliberately glass (translucent indigo fill + border) so it
 * sits with the rest of the card rather than shouting like a filled primary.
 */
@Composable
private fun GlassActionButton(
    text: String,
    enabled: Boolean = true,
    leadingSpinner: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Indigo.copy(alpha = if (enabled) 0.18f else 0.07f))
            .border(1.dp, Indigo.copy(alpha = if (enabled) 0.50f else 0.20f), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = IndigoLight,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) TextPrimary else TextTertiary,
            )
        }
    }
}

/**
 * The indigo reflection surface, byte-for-byte the same treatment as
 * `ui/checkin/components/ReflectionSection.kt` — a reflection looks identical
 * wherever it appears in the app.
 */
@Composable
private fun ReflectionBox(text: String) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Indigo.copy(alpha = 0.12f))
            .border(1.dp, Indigo.copy(alpha = 0.45f), shape)
            .padding(14.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}
