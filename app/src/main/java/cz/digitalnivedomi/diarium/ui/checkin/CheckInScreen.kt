package cz.digitalnivedomi.diarium.ui.checkin

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cz.digitalnivedomi.diarium.core.data.ActivityDef
import cz.digitalnivedomi.diarium.core.data.AiReflectionRepository
import cz.digitalnivedomi.diarium.core.data.DailyGoal
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.HabitDef
import cz.digitalnivedomi.diarium.core.data.NoteTemplate
import cz.digitalnivedomi.diarium.core.data.PickerDefaults
import cz.digitalnivedomi.diarium.core.data.Scale
import cz.digitalnivedomi.diarium.ui.checkin.components.ActivitiesSection
import cz.digitalnivedomi.diarium.ui.checkin.components.DateNav
import cz.digitalnivedomi.diarium.ui.checkin.components.ErrorBanner
import cz.digitalnivedomi.diarium.ui.checkin.components.GoalsSection
import cz.digitalnivedomi.diarium.ui.checkin.components.GratitudeSection
import cz.digitalnivedomi.diarium.ui.checkin.components.HabitsSection
import cz.digitalnivedomi.diarium.ui.checkin.components.MoodSection
import cz.digitalnivedomi.diarium.ui.checkin.components.NoteSection
import cz.digitalnivedomi.diarium.ui.checkin.components.PhotoSection
import cz.digitalnivedomi.diarium.ui.checkin.components.PrimaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.ReflectionSection
import cz.digitalnivedomi.diarium.ui.checkin.components.ScalesSection
import cz.digitalnivedomi.diarium.ui.checkin.components.ScreenTimeSection
import cz.digitalnivedomi.diarium.ui.checkin.components.SleepSection
import cz.digitalnivedomi.diarium.ui.checkin.components.StressSection
import cz.digitalnivedomi.diarium.ui.checkin.components.WeatherSection
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One-page daily check-in — native port of the web's `OnePageCheckIn.tsx`.
 *
 * Sections, in the web's order: datum, nálada, spánek, stres, aktivity, návyky,
 * vděčnost, počasí, fotka, škály, poznámka, denní cíle, the read-only screen time
 * and the AI reflection. Saving goes through `save_daily_entry(jsonb)`; the
 * reflection comes from our `/api/ai/reflect` endpoint, called with the signed-in
 * user's JWT, and is stored on the entry by a second save.
 *
 * State lives in [CheckInStateHolder] so the whole form is drivable from a JVM
 * test; [CheckInDeps] carries the repositories so the screen renders offline.
 */
@Composable
fun CheckInScreen(deps: CheckInDeps = remember { CheckInDeps.offline() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { CheckInStateHolder() }
    val state = holder.state

    val entriesRepo = deps.entries
    val reflectionRepo = deps.reflection
    val pickersRepo = deps.pickers
    val drafts = deps.drafts
    val goalsStore = deps.goals

    var activities by remember { mutableStateOf(PickerDefaults.ACTIVITY_FALLBACK) }
    var hiddenActivities by remember { mutableStateOf<List<ActivityDef>>(emptyList()) }
    var habits by remember { mutableStateOf(PickerDefaults.HABIT_FALLBACK) }
    var scales by remember { mutableStateOf<List<Scale>>(emptyList()) }
    var templates by remember { mutableStateOf(PickerDefaults.DEFAULT_TEMPLATES) }
    var goals by remember { mutableStateOf(GoalsStoreDefaults.DEFAULT) }
    var uploadingPhoto by remember { mutableStateOf(false) }

    // Pickers + goals once per screen (they do not depend on the selected day).
    LaunchedEffect(deps) {
        if (pickersRepo != null) {
            activities = pickersRepo.activities().ifEmpty { PickerDefaults.ACTIVITY_FALLBACK }
            hiddenActivities = pickersRepo.hiddenActivities()
            habits = pickersRepo.habits().ifEmpty { PickerDefaults.HABIT_FALLBACK }
            scales = pickersRepo.scales()
            templates = pickersRepo.templates().ifEmpty { PickerDefaults.DEFAULT_TEMPLATES }
        }
        if (goalsStore != null) {
            goals = goalsStore.load().ifEmpty { GoalsStoreDefaults.DEFAULT }
        }
    }

    // Entry for the selected day: DB first, then draft, then empty (web rules).
    LaunchedEffect(state.date) {
        holder.setLoading(true)
        val fromDb = entriesRepo?.loadEntry(state.date)
        val restored = fromDb ?: drafts?.load(state.date) ?: DiaryEntry.EMPTY
        holder.load(restored)
    }

    // Debounced draft autosave (the web's localStorage `diarium_draft_{date}`).
    LaunchedEffect(state.date, state.entry, state.saved) {
        if (drafts == null) return@LaunchedEffect
        if (state.saved || !state.entry.hasContent()) return@LaunchedEffect
        delay(1200)
        drafts.save(state.date, state.entry)
    }

    /**
     * The RPC call itself, shared by [save] and [generateReflection]: the AI
     * endpoint builds today's prompt from the stored row, so the reflection flow
     * has to await the very write the save button performs.
     */
    suspend fun persistEntry(): Result<Unit> {
        val repo = entriesRepo
            ?: return Result.failure(IllegalStateException("Ukládání vyžaduje přihlášení."))
        val date = holder.date
        return repo.save(holder.entry, date).onSuccess { drafts?.clear(date) }
    }

    fun save() {
        val repo = entriesRepo
        if (repo == null) {
            holder.markError("Ukládání vyžaduje přihlášení.")
            return
        }
        holder.markSaving()
        scope.launch {
            persistEntry()
                .onSuccess { holder.markSaved() }
                .onFailure { holder.markError(it.message ?: "Uložení se nezdařilo.") }
        }
    }

    /**
     * "Napsat reflexi" — the web's AI block, native.
     *
     * Order matters: anything unsaved is pushed first, and a failed save aborts
     * the request instead of letting the AI reflect on a day it cannot see. The
     * generated text is then written by a second save — that pass is the only one
     * whose payload carries `ai_reflection`.
     */
    fun generateReflection() {
        val repo = reflectionRepo
        if (repo == null) {
            holder.markReflectionError(AiReflectionRepository.MESSAGE_CONNECT)
            return
        }
        // A second tap while the pre-save or the request runs would duplicate the
        // work; the endpoint caches, but the button must not look idle.
        if (holder.state.reflectionLoading || holder.state.saving) return
        scope.launch {
            if (holder.state.hasContent && !holder.state.saved) {
                holder.markSaving()
                val stored = persistEntry()
                if (stored.isFailure) {
                    holder.markError(stored.exceptionOrNull()?.message ?: "Uložení se nezdařilo.")
                    holder.markReflectionError("Nejdřív je potřeba uložit dnešní zápis.")
                    return@launch
                }
                holder.markSaved()
            }

            holder.markReflectionLoading()
            val date = holder.date
            repo.generate(date, holder.entry, repo.userName())
                .onSuccess { text ->
                    holder.setReflection(text)
                    persistEntry()
                        .onSuccess { holder.markSaved() }
                        .onFailure {
                            holder.markReflectionError("Reflexi se nepodařilo uložit k zápisu.")
                        }
                }
                .onFailure {
                    holder.markReflectionError(it.message ?: "Reflexi se nepodařilo vygenerovat.")
                }
        }
    }

    fun uploadPhoto(uri: Uri) {
        val repo = entriesRepo ?: return
        uploadingPhoto = true
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null) {
                uploadingPhoto = false
                holder.markError("Fotku se nepodařilo načíst.")
                return@launch
            }
            val url = repo.uploadPhoto(holder.date, bytes)
            uploadingPhoto = false
            if (url != null) {
                holder.setPhotoPath(url)
            } else {
                holder.markError("Fotku se nepodařilo nahrát.")
            }
        }
    }

    fun persistGoals() {
        scope.launch { goalsStore?.save(goals) }
    }

    fun toggleGoal(id: String) {
        val date = holder.date
        goals = goals.map { goal ->
            if (goal.id != id) goal
            else goal.copy(
                completedDates = if (date in goal.completedDates) {
                    goal.completedDates - date
                } else {
                    goal.completedDates + date
                },
            )
        }
        persistGoals()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Text("Dnešní zápis", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Text(
            text = "Zapiš, jaký byl den — všechno jde do tvého vaultu.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(14.dp))

        state.errorMessage?.let { message ->
            ErrorBanner(message)
            Spacer(Modifier.height(10.dp))
        }

        DateNav(date = state.date, onDateChange = { holder.setDate(it) })
        Spacer(Modifier.height(16.dp))

        MoodSection(
            selected = state.entry.mood,
            onSelect = { value, emoji -> holder.selectMood(value, emoji) },
        )
        SleepSection(selected = state.entry.sleepQuality, onSelect = { holder.setSleep(it) })
        StressSection(selected = state.entry.stress, onSelect = { holder.setStress(it) })

        ActivitiesSection(
            activities = activities,
            hiddenActivities = hiddenActivities,
            selected = state.entry.activities,
            onToggle = { holder.toggleActivity(it) },
            onAdd = { label ->
                activities = activities + ActivityDef(
                    key = label.lowercase().replace(' ', '_'),
                    label = label,
                    icon = "✨",
                    category = "vlastní",
                    source = "user",
                )
                holder.toggleActivity(label)
                scope.launch { pickersRepo?.addActivity(label) }
            },
            onHide = { def ->
                activities = activities - def
                hiddenActivities = hiddenActivities + def
                if (def.label in holder.entry.activities) {
                    holder.setActivities(holder.entry.activities - def.label)
                }
                scope.launch { pickersRepo?.hideActivity(def) }
            },
            onRestore = { key ->
                val def = hiddenActivities.firstOrNull { it.key == key }
                if (def != null) {
                    hiddenActivities = hiddenActivities - def
                    activities = activities + def
                }
                scope.launch { pickersRepo?.restoreActivity(key) }
            },
        )

        HabitsSection(
            habits = habits,
            values = state.entry.habits,
            onToggle = { holder.toggleHabit(it) },
        )

        GratitudeSection(
            gratitude = state.entry.gratitude,
            onChange = { index, text -> holder.setGratitude(index, text) },
        )

        WeatherSection(
            options = PickerDefaults.WEATHER_OPTIONS,
            selected = state.entry.weather,
            onToggle = { holder.toggleWeather(it) },
        )

        PhotoSection(
            photoPath = state.entry.photoPath,
            uploading = uploadingPhoto,
            onPhotoSelected = { uploadPhoto(it) },
            onRemove = { holder.setPhotoPath(null) },
        )

        ScalesSection(
            scales = scales,
            values = state.entry.scaleValues,
            onChange = { id, value -> holder.setScale(id, value) },
        )

        NoteSection(
            note = state.entry.note,
            templates = templates,
            onChange = { holder.setNote(it) },
        )

        GoalsSection(
            goals = goals,
            date = state.date,
            onToggle = { toggleGoal(it) },
            onAdd = { name ->
                goals = goals + DailyGoal(
                    id = "goal-${System.currentTimeMillis()}",
                    emoji = "🎯",
                    name = name,
                )
                persistGoals()
            },
            onRemove = { id ->
                goals = goals.filterNot { it.id == id }
                persistGoals()
            },
        )

        ScreenTimeSection(
            screenTimeMinutes = state.entry.phoneScreenTime,
            unlocks = state.entry.phoneUnlocks,
            topApps = state.entry.phoneTopApps.map { it.app to it.minutes },
        )

        ReflectionSection(
            reflection = state.reflection,
            loading = state.reflectionLoading,
            error = state.reflectionError,
            onGenerate = { generateReflection() },
        )

        PrimaryButton(
            text = if (state.saving) "⏳ Ukládám..." else "✓ Uložit do vaultu",
            enabled = !state.saving,
            testTag = "save_button",
        ) { save() }

        if (state.saved) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "✓ Uloženo",
                style = MaterialTheme.typography.bodyMedium,
                color = Indigo,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(48.dp))
    }
}

/** Mirrors [cz.digitalnivedomi.diarium.core.data.GoalsStore.DEFAULT_GOALS]. */
private object GoalsStoreDefaults {
    val DEFAULT: List<DailyGoal> = listOf(
        DailyGoal(id = "1", emoji = "🏋️", name = "Krátké cvičení"),
    )
}
