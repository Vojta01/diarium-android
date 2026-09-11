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
import cz.digitalnivedomi.diarium.core.data.DashboardNewestEntry
import cz.digitalnivedomi.diarium.core.data.DashboardRepository
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.MoodDiscState
import cz.digitalnivedomi.diarium.core.data.formatTopAppDuration
import cz.digitalnivedomi.diarium.core.data.moodDiscState
import cz.digitalnivedomi.diarium.core.data.rankTopApps
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
import cz.digitalnivedomi.diarium.ui.components.StaggeredItem
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.components.rememberLightHaptics
import cz.digitalnivedomi.diarium.ui.theme.ErrorRed
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
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
    reflectionRepository: AiReflectionRepository?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.section),
    ) {
        // Cards fade in one after another as the data lands — the stagger is
        // capped by Entrance, so even a full dashboard settles in ~300ms.
        StaggeredItem(0) { TodayCard(data = data, onOpenCheckIn = onOpenCheckIn) }
        StaggeredItem(1) { StreakCard(data = data) }
        StaggeredItem(2) { WeekCard(data = data) }
        StaggeredItem(3) { ScreenTimeCard(data = data) }
        // The reflection card keys off the newest logged day, never off the newest
        // day that happens to carry a reflection: showing "Ze dne 9. 9." while
        // 10. 9. was on screen is the bug this fixes. It is hidden only when the
        // "Dnes" card already renders that exact text, so nothing shows twice.
        val newest = data.newestEntry
        if (newest != null && !(newest.date == data.today && newest.reflection != null)) {
            StaggeredItem(4) {
                ReflectionCard(newest = newest, repository = reflectionRepository)
            }
        }
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
            data.todayEntry == null -> SectionHint("Dnešní zápis ještě chybí, série ale pokračuje.")
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
                val state = moodDiscState(day.hasEntry, day.mood)
                val mood = moodColor(day.mood)
                // Three deliberate marks, not two: a logged day without a mood is
                // its own state, so it can never render as an empty grey hole the
                // user reads as broken (the Monday disc that prompted this fix).
                // Every branch must be a Brush: there is no `background(Color)`
                // overload here, so an even fill is a solid-colour brush.
                val fill: Brush = when (state) {
                    MoodDiscState.Mood ->
                        // A soft glow: the mood colour fades down the disc instead
                        // of filling it fully saturated.
                        Brush.verticalGradient(
                            listOf(mood.copy(alpha = 0.30f), mood.copy(alpha = 0.10f)),
                        )
                    // A tidy neutral indigo disc — clearly deliberate, clearly not
                    // a mood value.
                    MoodDiscState.LoggedWithoutMood -> SolidColor(Indigo.copy(alpha = 0.08f))
                    MoodDiscState.NoEntry -> SolidColor(Color.White.copy(alpha = 0.04f))
                }
                val borderWidth =
                    if (isToday && state != MoodDiscState.LoggedWithoutMood) 2.dp else 1.2.dp
                val borderColor = when (state) {
                    MoodDiscState.Mood -> if (isToday) Indigo else mood.copy(alpha = 0.55f)
                    MoodDiscState.LoggedWithoutMood -> Indigo.copy(alpha = 0.35f)
                    MoodDiscState.NoEntry -> if (isToday) Indigo else Outline.copy(alpha = 0.5f)
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
                            // An en-dash: a day that was filled in, just without a
                            // mood. Never the empty/grey treatment.
                            MoodDiscState.LoggedWithoutMood -> Text(
                                text = "–",
                                fontSize = 15.sp,
                                color = TextTertiary,
                            )
                            MoodDiscState.NoEntry -> Text(
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
        // The legend appears only when a neutral disc is actually on screen, so the
        // en-dash can never be read as a rendering bug.
        if (data.week.any { moodDiscState(it.hasEntry, it.mood) == MoodDiscState.LoggedWithoutMood }) {
            VSpace(8)
            SectionHint("– = den bez vyplněné nálady")
        }
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
 * 📱 Screen time — a bar per day plus the window's totals.
 *
 * A day the worker has not synced yet has no value at all (`null`, not zero), and the
 * card says so instead of drawing flat bars: "nothing synced" and "almost no screen
 * time" must not look the same.
 */
@Composable
private fun ScreenTimeCard(data: DashboardData) {
    val maxSeconds = data.week.mapNotNull { it.screenTimeSeconds }.maxOrNull() ?: 0
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("📱 Screen time")
        VSpace(4)
        Text(
            text = "Posledních ${DashboardRepository.WEEK_DAYS} dní",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(10)

        if (data.screenTimeMinutes == null && data.unlocks == null) {
            SectionHint(
                "Data o screen timu se ještě nenasynchronizovala. " +
                    "Až je telefon odešle, uvidíš tady sloupce za posledních " +
                    "${DashboardRepository.WEEK_DAYS} dní.",
            )
            return@GlassCard
        }

        data.screenTimeMinutes?.let { minutes ->
            Text(
                text = formatMinutes(minutes),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            SectionHint("Celkem za posledních ${DashboardRepository.WEEK_DAYS} dní")
        }
        data.unlocks?.let { count ->
            VSpace(6)
            GlassChip(text = "🔓 $count odemknutí")
        }
        VSpace(16)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            data.week.forEach { day ->
                val seconds = day.screenTimeSeconds
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.height(BAR_TRACK),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        if (seconds == null || maxSeconds == 0) {
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
                                    .height((BAR_TRACK.value * seconds / maxSeconds).coerceAtLeast(6f).dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Brush.verticalGradient(listOf(IndigoLight, Indigo))),
                            )
                        }
                    }
                    VSpace(6)
                    Text(
                        text = dayLabel(day.date, data.today),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (day.date == data.today) TextPrimary else TextTertiary,
                    )
                }
            }
        }

        data.topApps?.let { topApps ->
            VSpace(14)
            GlassDivider()
            VSpace(10)
            SectionHeader("Nejpoužívanější aplikace")
            VSpace(2)
            SectionHint("Ze dne ${shortDateOf(topApps.date)}")
            VSpace(10)
            // Ranked here, not rendered straight from the query: the raw database
            // order buried the real leaders (Snooker 37 min) behind five rows that
            // happened to come first (Hermes WebUI 8 min).
            val ranked = rankTopApps(topApps.apps)
            if (ranked.size < 3) {
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
}

/**
 * 🤖 The AI reflection of the newest logged day.
 *
 * The card keys off the newest entry, never off the newest day that happens to
 * carry a reflection: it shows that day's text when it has one, and otherwise
 * offers to generate one for it. A generation request fires only on the tap —
 * nothing network-bound runs while the screen loads.
 */
@Composable
private fun ReflectionCard(
    newest: DashboardNewestEntry,
    repository: AiReflectionRepository?,
) {
    val scope = rememberCoroutineScope()
    var text by remember(newest.date) { mutableStateOf(newest.reflection) }
    var loading by remember(newest.date) { mutableStateOf(false) }
    var error by remember(newest.date) { mutableStateOf<String?>(null) }

    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader("🤖 AI Reflexe")
        VSpace(4)
        Text(
            text = "Naposledy vygenerovaná",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(2)
        SectionHint("Ze dne ${shortDateOf(newest.date)}")
        VSpace(10)

        val current = text
        if (!current.isNullOrBlank()) {
            ReflectionBox(text = current)
        } else {
            SectionHint("Reflexe pro tento den ještě není vygenerovaná.")
            VSpace(12)
            GlassActionButton(
                text = if (loading) "Generuji reflexi…" else "Vygenerovat reflexi",
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
                        repo.generate(newest.date, newest.entry, repo.userName())
                            .onSuccess { generated ->
                                text = generated
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
