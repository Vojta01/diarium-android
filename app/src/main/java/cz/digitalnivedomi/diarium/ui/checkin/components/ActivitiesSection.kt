package cz.digitalnivedomi.diarium.ui.checkin.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.ActivityDef
import cz.digitalnivedomi.diarium.core.data.PickerDefaults
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/**
 * Aktivity — multi-select chips grouped by `CATEGORY_ORDER`, plus custom
 * activities and hide/restore (the web's "⚙️ Spravovat" mode).
 *
 * The selected values stored in `entries.activities` are the LABELS, not the
 * catalogue keys, because that is what the web app writes. Categories are
 * canonicalised (see [PickerDefaults.canonicalCategory]) so data aliases such as
 * `záliby` merge into `volný čas`, and the `počasí` slice is left to
 * [WeatherSection].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivitiesSection(
    activities: List<ActivityDef>,
    hiddenActivities: List<ActivityDef>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onHide: (ActivityDef) -> Unit,
    onRestore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var managing by rememberSaveable { mutableStateOf(false) }
    var newActivity by rememberSaveable { mutableStateOf("") }

    CheckInSection(
        title = "Aktivity",
        modifier = modifier,
        trailing = {
            SecondaryButton(
                text = if (managing) "✓ Hotovo" else "⚙️ Spravovat",
                testTag = "activities_manage",
            ) { managing = !managing }
        },
    ) {
        if (managing) {
            SectionHint("Klepnutím na aktivitu ji skryješ.")
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                activities.forEach { def ->
                    SelectableChip(
                        text = "✕ ${labelWithIcon(def.icon, def.label)}",
                        selected = false,
                        testTag = "activity_hide_${def.key}",
                    ) { onHide(def) }
                }
            }
            if (hiddenActivities.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Skryté (${hiddenActivities.size})", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    hiddenActivities.forEach { def ->
                        SelectableChip(
                            text = "＋ ${labelWithIcon(def.icon, def.label)}",
                            selected = false,
                            testTag = "activity_restore_${def.key}",
                        ) { onRestore(def.key) }
                    }
                }
            }
        } else {
            groupedByCategory(activities).forEach { (category, defs) ->
                Text(
                    text = PickerDefaults.categoryLabel(category),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    defs.forEach { def ->
                        SelectableChip(
                            text = def.label,
                            icon = def.icon,
                            selected = def.label in selected,
                            testTag = "activity_${def.key}",
                        ) { onToggle(def.label) }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("➕ Přidat vlastní aktivitu", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        GlassTextField(
            value = newActivity,
            onValueChange = { newActivity = it },
            placeholder = "Název aktivity",
            testTag = "activity_new",
        )
        Spacer(Modifier.height(8.dp))
        SecondaryButton(text = "Přidat", testTag = "activity_add") {
            val label = newActivity.trim()
            if (label.isNotEmpty()) {
                onAdd(label)
                newActivity = ""
            }
        }
    }
}

/** Groups defs by canonical category in `CATEGORY_ORDER`, unknown categories last. */
internal fun groupedByCategory(defs: List<ActivityDef>): List<Pair<String, List<ActivityDef>>> {
    val byCategory = defs
        // Weather has its own [WeatherSection] lower/higher on the screen, so
        // rendering the catalogue's `počasí` items here produced a second
        // "Počasí" block. The web app has no separate weather section and lists
        // them as activities; this divergence is deliberate.
        .filter { PickerDefaults.canonicalCategory(it.category) != PickerDefaults.WEATHER_CATEGORY }
        .groupBy { PickerDefaults.canonicalCategory(it.category).ifBlank { "obecné" } }
    val ordered = PickerDefaults.CATEGORY_ORDER.filter { byCategory.containsKey(it) } +
        byCategory.keys.filterNot { it in PickerDefaults.CATEGORY_ORDER }.sorted()
    return ordered.map { it to byCategory.getValue(it) }
}

/** Weather — the catalogue's `počasí` group as a multi-select. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherSection(
    options: List<ActivityDef>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    CheckInSection(title = "Počasí", modifier = modifier) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                SelectableChip(
                    text = option.label,
                    icon = option.icon,
                    selected = option.key in selected || option.label in selected,
                    testTag = "weather_${option.key}",
                    accent = Indigo,
                ) { onToggle(option.key) }
            }
        }
        if (options.isEmpty()) {
            Text("Zatím žádné možnosti počasí.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

/** Read-only screen-time block filled by the background worker. */
@Composable
fun ScreenTimeSection(
    screenTimeMinutes: Int?,
    unlocks: Int?,
    topApps: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
) {
    CheckInSection(title = "📱 Screen time", modifier = modifier) {
        if (screenTimeMinutes == null && unlocks == null && topApps.isEmpty()) {
            Text(
                text = "Zatím žádná data o screen timu.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            return@CheckInSection
        }
        screenTimeMinutes?.let {
            Text(formatMinutes(it), style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        }
        unlocks?.let {
            Text("$it odemknutí", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        if (topApps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            topApps.take(5).forEach { (app, minutes) ->
                Text(
                    text = "• $app — ${formatMinutes(minutes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

/** 132 -> "2 h 12 min"; 45 -> "45 min". */
fun formatMinutes(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "$hours h" else "$hours h $rest min"
}
