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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.PickerDefaults
import cz.digitalnivedomi.diarium.ui.checkin.CheckInDates
import cz.digitalnivedomi.diarium.ui.checkin.MOOD_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.SLEEP_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.STRESS_CHOICES
import cz.digitalnivedomi.diarium.ui.checkin.components.PrimaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.ReadOnlyRow
import cz.digitalnivedomi.diarium.ui.checkin.components.SectionHint
import cz.digitalnivedomi.diarium.ui.checkin.components.formatMinutes
import cz.digitalnivedomi.diarium.ui.checkin.components.labelWithIcon
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.IconBadge
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.VSpace
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
    val accent = if (entry != null) moodColor(entry.mood) else Indigo

    GlassCard(modifier = modifier.fillMaxWidth(), accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                SectionHeader("📅 Detail dne")
                VSpace(6)
                Text(
                    text = longDateOf(date),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
            }
            if (entry != null) {
                IconBadge(accent = accent, size = 44) {
                    Text(text = historyMoodEmoji(entry.mood), fontSize = 22.sp)
                }
            }
        }
        VSpace(12)

        if (entry == null) {
            EmptyDay(date = date, onOpenCheckIn = onOpenCheckIn)
            return@GlassCard
        }

        // Nálada — the day's headline, so it is a sentence in the mood's own colour
        // rather than a label/value row like the numbers below it.
        val moodLabel = historyMoodLabel(entry.mood)
        if (moodLabel != null) {
            Text(
                text = moodLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = moodColor(entry.mood),
            )
        } else {
            SectionHint("Nálada pro tento den nebyla vyplněná.")
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
            if (screenTime != null) {
                // Stored in seconds (the worker's unit), shown in minutes like the dashboard.
                ReadOnlyRow(label = "Čas na obrazovce", value = formatMinutes(screenTime / 60))
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

        VSpace(16)
        PrimaryButton(
            text = "✏️ Upravit tento záznam",
            testTag = "history_open_checkin",
        ) { onOpenCheckIn(date) }
    }
}

/** A day with nothing saved yet — the way in, not a dead end. */
@Composable
private fun EmptyDay(date: String, onOpenCheckIn: (String) -> Unit) {
    Text(
        text = "Pro tento den nemáš zapsaný check-in.",
        style = MaterialTheme.typography.titleMedium,
        color = TextPrimary,
    )
    VSpace(4)
    SectionHint("Zapiš, jaký ten den byl — objeví se pak i v kalendáři.")
    VSpace(14)
    PrimaryButton(
        text = "✏️ Vyplnit check-in",
        testTag = "history_open_checkin",
    ) { onOpenCheckIn(date) }
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
