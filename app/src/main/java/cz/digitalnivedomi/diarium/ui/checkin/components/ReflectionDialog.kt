package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cz.digitalnivedomi.diarium.core.data.ActivityDef
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.HabitDef
import cz.digitalnivedomi.diarium.core.data.Scale
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.GlassDivider
import cz.digitalnivedomi.diarium.ui.components.SectionHeader
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.history.historyMoodEmoji
import cz.digitalnivedomi.diarium.ui.history.historyMoodLabel
import cz.digitalnivedomi.diarium.ui.history.historySleepLabel
import cz.digitalnivedomi.diarium.ui.history.historyStressLabel
import cz.digitalnivedomi.diarium.ui.history.historyWeatherText
import cz.digitalnivedomi.diarium.ui.history.scaleMaxMap
import cz.digitalnivedomi.diarium.ui.history.scaleNameMap
import cz.digitalnivedomi.diarium.ui.history.scaleValueText
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.Ink
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/** The one line shown while the endpoint writes; the same tone as the form's own. */
private const val REFLECTION_LOADING_TEXT = "Přemýšlím nad tvým dnem…"

/**
 * The day's reflection window — what pops up by itself the moment the check-in
 * save lands.
 *
 * It is a read-only companion to the form: on top of a recap of exactly what the
 * user just entered it shows the day's AI reflection (seeded from the saved row
 * when one exists, fetched otherwise) and the repository's Czech sentence when
 * generation or storage failed — a 429 cooldown is just another one of those
 * sentences and is printed verbatim.
 *
 * Everything inside is built from the pieces the rest of the app already uses:
 * [GlassCard] with the brand indigo for the surface, [ReadOnlyRow] for label /
 * value lines, the `history*` label helpers for the Czech wording, and
 * [ErrorBanner] for failures — so the window reads as part of the same app
 * instead of a second design language.
 *
 * Purely presentational: it never decides whether to open, when to fetch or what
 * to store — [cz.digitalnivedomi.diarium.ui.checkin.CheckInStateHolder] and the
 * screen own that, which is what keeps the open/close policy JVM-testable.
 */
@Composable
fun ReflectionDialog(
    entry: DiaryEntry,
    activities: List<ActivityDef>,
    habits: List<HabitDef>,
    scales: List<Scale>,
    reflection: String?,
    loading: Boolean,
    error: String?,
    canRegenerate: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                // Opaque ink under the glass: the dialog window sits on its own
                // surface, so the card must not depend on what is behind it.
                .background(Ink, shape)
                .heightIn(max = 620.dp),
            shape = shape,
            accent = Indigo,
        ) {
            // The whole window scrolls, so a day with many activities/habits and a
            // long reflection still fits a phone screen.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "🤖 Reflexe dne",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                VSpace(4)
                SectionHint("Takhle vypadal tvůj den — a co k němu napsala AI.")

                VSpace(16)
                DayRecap(entry = entry, activities = activities, habits = habits, scales = scales)

                VSpace(16)
                GlassDivider()
                VSpace(16)

                SectionHeader("🤖 AI reflexe")
                VSpace(8)

                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Indigo,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = REFLECTION_LOADING_TEXT,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    }
                }

                // Text already on screen survives a failure, and a regenerate keeps
                // the old one visible until the new one lands.
                if (!reflection.isNullOrBlank()) {
                    if (loading) VSpace(12)
                    ReflectionText(reflection.orEmpty())
                }

                error?.let { message ->
                    if (!reflection.isNullOrBlank() || loading) VSpace(12)
                    ErrorBanner(message)
                }

                VSpace(16)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Only a day that is already stored can be regenerated — and
                    // never while a request is in flight.
                    if (canRegenerate) {
                        Box(Modifier.weight(1f)) {
                            SecondaryButton(
                                text = "Znovu vygenerovat",
                                testTag = "reflection_dialog_retry",
                            ) { onRetry() }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        PrimaryButton(
                            text = "Zavřít",
                            testTag = "reflection_dialog_close",
                        ) { onDismiss() }
                    }
                }
            }
        }
    }
}

/**
 * What the user just entered, in the same wording the history day detail uses.
 * Only sections with content are printed; "—" stands in for an unanswered mood,
 * exactly like the read-only history screen.
 */
@Composable
private fun DayRecap(
    entry: DiaryEntry,
    activities: List<ActivityDef>,
    habits: List<HabitDef>,
    scales: List<Scale>,
) {
    SectionHeader("📋 Co jsi dnes zapsal")
    VSpace(8)

    val moodLabel = historyMoodLabel(entry.mood)
    ReadOnlyRow(
        label = "Nálada",
        value = if (moodLabel == null) "—" else "${historyMoodEmoji(entry.mood)} $moodLabel",
    )
    VSpace(6)
    ReadOnlyRow(label = "Spánek", value = historySleepLabel(entry.sleepQuality))
    VSpace(6)
    ReadOnlyRow(label = "Stres", value = historyStressLabel(entry.stress))
    VSpace(6)
    ReadOnlyRow(label = "Počasí", value = historyWeatherText(entry.weather))

    val pickedActivities = entry.activities.filter { it.isNotBlank() }
    if (pickedActivities.isNotEmpty()) {
        VSpace(12)
        SectionHeader("🏃 Aktivity")
        VSpace(6)
        pickedActivities.forEach { raw ->
            // The catalogue definition supplies the icon; an unknown value (an old
            // row, or one the user added) is printed as stored.
            val def = activities.firstOrNull {
                it.label.equals(raw, ignoreCase = true) || it.key.equals(raw, ignoreCase = true)
            }
            Bullet(def?.let { labelWithIcon(it.icon, it.label) } ?: raw)
        }
    }

    val habitStates = entry.habits.entries.toList()
    if (habitStates.isNotEmpty()) {
        VSpace(12)
        SectionHeader("🔁 Návyky")
        VSpace(6)
        habitStates.forEach { (key, done) ->
            val def = habits.firstOrNull { it.key == key }
            ReadOnlyRow(
                label = def?.let { labelWithIcon(it.icon, it.label) } ?: key,
                value = if (done) "Ano" else "Ne",
            )
            VSpace(4)
        }
    }

    val scaleValues = entry.positiveScaleValues()
    if (scaleValues.isNotEmpty()) {
        VSpace(12)
        SectionHeader("📊 Škály")
        VSpace(6)
        val names = scaleNameMap(scales)
        val maxima = scaleMaxMap(scales)
        scaleValues.forEach { (id, value) ->
            ReadOnlyRow(
                label = names[id] ?: id,
                value = scaleValueText(value, id, maxima),
            )
            VSpace(4)
        }
    }

    val gratitude = entry.filteredGratitude()
    if (gratitude.isNotEmpty()) {
        VSpace(12)
        SectionHeader("🙏 Vděčnost")
        VSpace(6)
        gratitude.forEach { Bullet(it) }
    }

    if (entry.note.isNotBlank()) {
        VSpace(12)
        SectionHeader("📝 Poznámka")
        VSpace(6)
        Text(
            text = entry.note,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
    }

    entry.phoneScreenTime?.let { minutes ->
        VSpace(12)
        ReadOnlyRow(label = "📱 Čas na obrazovce", value = formatMinutes(minutes))
    }
}

/** One list entry of a recap section (activities, gratitude). */
@Composable
private fun Bullet(text: String) {
    Text(
        text = "• $text",
        style = MaterialTheme.typography.bodyMedium,
        color = TextPrimary,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

/** The indigo surface a reflection is always written on — same as the form's. */
@Composable
private fun ReflectionText(text: String) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Indigo.copy(alpha = 0.12f))
            .border(1.dp, Indigo.copy(alpha = 0.45f), shape)
            .padding(14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
    }
}
