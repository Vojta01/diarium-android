package cz.digitalnivedomi.diarium.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.PickerDefaults
import cz.digitalnivedomi.diarium.core.data.isRecordedDay
import cz.digitalnivedomi.diarium.core.data.phoneScreenTimeMinutes
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.MOOD_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.SLEEP_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.STRESS_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.components.PrimaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.ReadOnlyRow
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.checkin.components.formatMinutes
import cz.digitalnivedomi.diarium.ui.checkin.components.labelWithIcon
import cz.digitalnivedomi.diarium.ui.components.EmptyState
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.IconBadge
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.components.rememberLightHaptics
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.ui.theme.TextTertiary
import cz.digitalnivedomi.diarium.ui.theme.moodColor
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One day of the calendar, opened: what was written that day, read-only.
 *
 * This is the detail half of the history feature, and it follows the same rule as
 * the rest of the app — it never shows a fact it does not have. A day with no
 * check-in says so and offers the check-in instead of rendering a card full of
 * dashes; a section whose field is empty on a *saved* day is left out entirely
 * rather than padded with placeholders.
 *
 * One more rule, the owner's own (2026-09-11, see [isRecordedDay]): a day is a
 * record only when the mood is filled. The phone-sync worker writes an `entries` row
 * for every synced day, so a row with screen time but no mood (2026-09-07) is listed
 * here — the synced numbers are real and the owner may want them — but the card is
 * dimmed and carries a "check-in nevyplněn" hint instead of posing as a journaled
 * day. It is never dropped silently.
 *
 * Everything but the reflection comes from the [DiaryEntry] the calendar already
 * loaded, so opening a day costs no request. The AI reflection is simply part of
 * that row, so it appears when it exists and is absent otherwise.
 *
 * The scales section is the one piece that needs a second source: an entry's
 * `scale_values` is keyed by scale uuid, so [scaleNames]/[scaleMax] carry the
 * user's scales (read once by the history screen) to render "⚡ Energie — 3 / 5"
 * instead of a raw uuid. When they are absent the key and a `/ 5` fallback are
 * shown, so the section never disappears.
 *
 * The edit action is an up-callback ([onOpenCheckIn], given the ISO date) so this
 * has no idea what navigation is — the same contract the dashboard uses.
 */
@Composable
fun DayDetail(
    date: String,
    entry: DiaryEntry?,
    modifier: Modifier = Modifier,
    scaleNames: Map<String, String> = emptyMap(),
    /**
     * Scale id -> the scale's maximum, so a row reads "3 / 5" from the real scale
     * instead of a hardcoded 5. Empty falls back to `/ 5`.
     */
    scaleMax: Map<String, Int> = emptyMap(),
    onOpenCheckIn: (String) -> Unit,
) {
    // No row at all: the owner never opened this day, so offer the check-in.
    if (entry == null) {
        EmptyDayCard(date = date, modifier = modifier, onOpenCheckIn = onOpenCheckIn)
        return
    }

    // The owner's rule (see isRecordedDay): a day counts as a record only when the
    // mood is filled. A row the phone synced — screen time, unlocks, top apps — has no
    // mood, so it is NOT a journaled day: it takes the neutral indigo accent and a
    // visible "check-in nevyplněn" hint instead of a mood colour and emoji.
    val recorded = isRecordedDay(entry.mood)
    val accent = if (recorded) moodColor(entry.mood) else Indigo
    val haptics = rememberLightHaptics()

    GlassCard(modifier = modifier.fillMaxWidth(), accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                SectionHeader("📅 Detail dne")
                VSpace(6)
                Text(
                    text = longDateOf(date),
                    style = MaterialTheme.typography.titleMedium,
                    // Dimmed on a synced-only day: nothing here is a record.
                    color = if (recorded) TextPrimary else TextSecondary,
                )
            }
            if (recorded) {
                IconBadge(accent = accent, size = 44) {
                    Text(text = historyMoodEmoji(entry.mood), fontSize = 22.sp)
                }
            }
        }
        VSpace(12)

        // The body of a synced-only day is faded so the card reads as "listed, but not
        // journaled", while the call-to-action below keeps full strength — fixing the
        // day is the one thing that must not fade with it.
        Column(modifier = Modifier.alpha(if (recorded) 1f else UNRECORDED_ALPHA)) {
            if (recorded) {
                // Nálada — the day's headline, a sentence in the mood's own colour
                // rather than a label/value row like the numbers below it.
                val moodLabel = historyMoodLabel(entry.mood)
                if (moodLabel != null) {
                    Text(
                        text = moodLabel,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = moodColor(entry.mood),
                    )
                }
            } else {
                // Say it plainly: the row is here only because the phone synced it,
                // the owner's check-in is still missing.
                SectionHint(UNRECORDED_HINT)
                VSpace(2)
                SectionHint(
                    "Data níže pochází z automatické synchronizace telefonu. " +
                        "Doplň check-in a den se začne počítat mezi zápisy.",
                )
            }
            VSpace(12)
            GlassDivider()
            VSpace(12)

            ReadOnlyRow(label = "😴 Spánek", value = historySleepLabel(entry.sleepQuality))
            VSpace(6)
            ReadOnlyRow(label = "😰 Stres", value = historyStressLabel(entry.stress))
            VSpace(6)
            ReadOnlyRow(label = "🌤️ Počasí", value = historyWeatherText(entry.weather))

            val activities = entry.activities.filter { it.isNotBlank() }
            if (activities.isNotEmpty()) {
                VSpace(14)
                SectionHeader("🏃 Aktivity")
                VSpace(6)
                BulletList(activities)
            }

            val habits = entry.habits.entries.toList()
            if (habits.isNotEmpty()) {
                VSpace(14)
                SectionHeader("🔁 Návyky")
                VSpace(6)
                habits.forEach { (key, done) ->
                    ReadOnlyRow(
                        label = historyHabitLabel(key),
                        value = if (done) "Ano" else "Ne",
                    )
                    VSpace(4)
                }
            }

            val gratitude = entry.filteredGratitude()
            if (gratitude.isNotEmpty()) {
                VSpace(14)
                SectionHeader("🙏 Vděčnost")
                VSpace(6)
                BulletList(gratitude)
            }

            val note = entry.note
            if (note.isNotBlank()) {
                VSpace(14)
                SectionHeader("📝 Poznámka")
                VSpace(6)
                Text(text = note, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }

            val scales = entry.positiveScaleValues()
            if (scales.isNotEmpty()) {
                VSpace(14)
                SectionHeader("📊 Škály")
                VSpace(6)
                scales.forEach { (key, value) ->
                    ReadOnlyRow(
                        label = scaleNames[key] ?: key,
                        value = scaleValueText(value, key, scaleMax),
                    )
                    VSpace(4)
                }
            }

            val screenTime = entry.phoneScreenTime
            val unlocks = entry.phoneUnlocks
            val topApps = entry.phoneTopApps
            if (screenTime != null || unlocks != null || topApps.isNotEmpty()) {
                VSpace(14)
                SectionHeader("📱 Screen time")
                VSpace(6)
                entry.phoneScreenTimeMinutes?.let { minutes ->
                    // phoneScreenTime is seconds; the property does the conversion, so this
                    // row can never drift from the check-in section again.
                    ReadOnlyRow(label = "Čas na obrazovce", value = formatMinutes(minutes))
                }
                if (unlocks != null) {
                    VSpace(4)
                    ReadOnlyRow(label = "Odemknutí", value = unlocks.toString())
                }
                if (topApps.isNotEmpty()) {
                    VSpace(6)
                    SectionHint("Nejpoužívanější aplikace")
                    topApps.take(5).forEach { app ->
                        Text(
                            text = "• ${app.app} — ${formatMinutes(app.minutes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }

            VSpace(14)
            SectionHeader("📷 Fotka")
            VSpace(6)
            ReadOnlyRow(
                label = "Fotka",
                value = if (entry.photoPath.isNullOrBlank()) "Nepřiložena" else "Přiložena",
            )

            // Only rendered when the day actually has one: an AI section that says
            // "no reflection yet" on every past day would be noise, not information.
            val reflection = entry.aiReflection
            if (!reflection.isNullOrBlank()) {
                VSpace(14)
                SectionHeader("🤖 AI Reflexe")
                VSpace(6)
                ReflectionBox(text = reflection)
            }
        }

        VSpace(16)
        PrimaryButton(
            // A synced-only day has no journaled record yet, so the action offers to
            // create one rather than to edit something that does not exist.
            text = if (recorded) "✏️ Upravit tento záznam" else "✏️ Vyplnit check-in",
            testTag = "history_open_checkin",
        ) {
            haptics()
            onOpenCheckIn(date)
        }
    }
}

/** Short Czech marker for a day the phone synced but nobody journaled. */
internal const val UNRECORDED_HINT = "bez nálady — check-in nevyplněn"

/** How much a synced-only day's body is faded: listed, but visibly not a record. */
private const val UNRECORDED_ALPHA = 0.72f

/** A day with no row at all — the same card frame, filled with the way in. */
@Composable
private fun EmptyDayCard(
    date: String,
    modifier: Modifier,
    onOpenCheckIn: (String) -> Unit,
) {
    GlassCard(modifier = modifier.fillMaxWidth(), accent = Indigo) {
        SectionHeader("📅 Detail dne")
        VSpace(6)
        Text(
            text = longDateOf(date),
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        VSpace(12)
        EmptyDay(date = date, onOpenCheckIn = onOpenCheckIn)
    }
}

/** A day with nothing saved yet — the way in, not a dead end. */
@Composable
private fun EmptyDay(date: String, onOpenCheckIn: (String) -> Unit) {
    val haptics = rememberLightHaptics()
    EmptyState(
        emoji = "🗓️",
        title = "Pro tento den nemáš check-in",
        message = "Zapiš, jaký ten den byl — objeví se pak i v kalendáři.",
        action = {
            PrimaryButton(
                text = "✏️ Vyplnit check-in",
                testTag = "history_open_checkin",
            ) {
                haptics()
                onOpenCheckIn(date)
            }
        },
    )
}

/** Simple bulleted lines (activities, gratitude) — the web's `list-disc` rows. */
@Composable
private fun BulletList(items: List<String>) {
    items.forEach { item ->
        Text(
            text = "• $item",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.padding(vertical = 1.dp),
        )
    }
}

/**
 * The indigo reflection surface, the same treatment as the dashboard's and
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

// --- Czech labels, ported from the web's `cs.ts` via the check-in option lists -----

private val longDate = DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale("cs", "CZ"))

/** "10. září 2026", or the raw value if it is not a date. */
internal fun longDateOf(date: String): String =
    CheckInDates.parse(date)?.format(longDate) ?: date

internal fun historyMoodEmoji(mood: Int): String =
    MOOD_CHOICES.firstOrNull { it.value == mood }?.emoji ?: "⚪"

/** null when the day has no mood, so the caller can print its own sentence instead. */
internal fun historyMoodLabel(mood: Int): String? =
    MOOD_CHOICES.firstOrNull { it.value == mood }?.label

internal fun historySleepLabel(value: Int): String =
    SLEEP_CHOICES.firstOrNull { it.value == value }?.label ?: "—"

internal fun historyStressLabel(value: Int): String =
    STRESS_CHOICES.firstOrNull { it.value == value }?.label ?: "—"

/**
 * The day's weather as the chips the check-in saved ("☀️ Slunečno, 🌧️ Déšť"), falling
 * back to the raw stored value when it is not one of the catalogue's keys — older
 * rows and worker-written ones must still read as something.
 */
internal fun historyWeatherText(values: List<String>): String {
    if (values.isEmpty()) return "—"
    return values.joinToString(", ") { raw ->
        val match = PickerDefaults.WEATHER_OPTIONS.firstOrNull {
            it.key.equals(raw, ignoreCase = true) || it.label.equals(raw, ignoreCase = true)
        }
        if (match == null) raw else labelWithIcon(match.icon, match.label)
    }
}

/** Habit key -> its Czech catalogue label; unknown keys stay as the key. */
internal fun historyHabitLabel(key: String): String {
    val match = PickerDefaults.HABIT_FALLBACK.firstOrNull { it.key == key }
    return if (match == null) key else labelWithIcon(match.icon, match.label)
}
