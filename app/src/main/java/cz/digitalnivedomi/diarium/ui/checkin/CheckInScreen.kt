package cz.digitalnivedomi.diarium.ui.checkin

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import cz.digitalnivedomi.diarium.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cz.digitalnivedomi.diarium.core.data.ActivityDef
import cz.digitalnivedomi.diarium.core.data.AiReflectionRepository
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.core.data.GoalsRepository
import cz.digitalnivedomi.diarium.core.data.HabitDef
import cz.digitalnivedomi.diarium.core.data.NoteTemplate
import cz.digitalnivedomi.diarium.core.data.PickerDefaults
import cz.digitalnivedomi.diarium.core.data.QueuedOfflineException
import cz.digitalnivedomi.diarium.core.data.Scale
import cz.digitalnivedomi.diarium.core.data.isRecordedDay
import cz.digitalnivedomi.diarium.core.data.phoneScreenTimeMinutes
import cz.digitalnivedomi.diarium.sync.PendingSaveWorker
import cz.digitalnivedomi.diarium.ui.checkin.components.ActivitiesSection
import cz.digitalnivedomi.diarium.ui.checkin.components.CheckInGoalsSection
import cz.digitalnivedomi.diarium.ui.checkin.components.DateNav
import cz.digitalnivedomi.diarium.ui.checkin.components.ErrorBanner
import cz.digitalnivedomi.diarium.ui.checkin.components.GratitudeSection
import cz.digitalnivedomi.diarium.ui.checkin.components.HabitEditorDialog
import cz.digitalnivedomi.diarium.ui.checkin.components.HabitsSection
import cz.digitalnivedomi.diarium.ui.checkin.components.MoodSection
import cz.digitalnivedomi.diarium.ui.checkin.components.MoveDateDialog
import cz.digitalnivedomi.diarium.ui.checkin.components.NoteSection
import cz.digitalnivedomi.diarium.ui.checkin.components.PhotoSection
import cz.digitalnivedomi.diarium.ui.checkin.components.PrimaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.ReflectionDialog
import cz.digitalnivedomi.diarium.ui.checkin.components.ReflectionSection
import cz.digitalnivedomi.diarium.ui.checkin.components.ScalesSection
import cz.digitalnivedomi.diarium.ui.checkin.components.ScreenTimeSection
import cz.digitalnivedomi.diarium.ui.checkin.components.SecondaryButton
import cz.digitalnivedomi.diarium.ui.checkin.components.SleepSection
import cz.digitalnivedomi.diarium.ui.checkin.components.StressSection
import cz.digitalnivedomi.diarium.ui.checkin.components.WeatherSection
import cz.digitalnivedomi.diarium.ui.components.GlassCard
import cz.digitalnivedomi.diarium.ui.components.rememberHaptics
import cz.digitalnivedomi.diarium.ui.history.DayDetail
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
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
 * vděčnost, počasí, fotka, škály, poznámka, cíle (read-only progress computed from
 * the diary), the read-only screen time and the AI reflection. Saving goes through
 * `save_daily_entry(jsonb)`; the
 * reflection comes from our `/api/ai/reflect` endpoint, called with the signed-in
 * user's JWT, and is stored on the entry by a second save.
 *
 * State lives in [CheckInStateHolder] so the whole form is drivable from a JVM
 * test; [CheckInDeps] carries the repositories so the screen renders offline.
 */
@Composable
fun CheckInScreen(
    deps: CheckInDeps = remember { CheckInDeps.offline() },
    requestedDate: String? = null,
    onRequestedDateConsumed: () -> Unit = {},
    /**
     * The check-in is finished: it was saved and the reflection window that save opened
     * has been closed. The host switches back to the overview — a check-in ends on the
     * dashboard, not on the form — and this screen only reports the moment.
     */
    onFinished: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { CheckInStateHolder() }
    val state = holder.state

    val entriesRepo = deps.entries
    val reflectionRepo = deps.reflection
    val pickersRepo = deps.pickers
    val drafts = deps.drafts
    val goalsRepo: GoalsRepository? = deps.goals

    /**
     * Flushes the pending debounced draft — leaving the screen (a tab switch) or the
     * app stopping must not lose the write the 1.2 s autosave was still holding
     * (2026-09-25). The guards mirror the autosave: a saved day and a day with no
     * content never get a draft. [CheckInStateHolder.state] is read here at call time
     * on purpose — a closure over the composition's `state` value would flush a stale
     * entry when onDispose fires.
     */
    fun flushDraft() {
        val d = drafts ?: return
        val s = holder.state
        if (s.saved || !s.entry.hasContent()) return
        d.flush(s.date, s.entry)
    }

    // A day picked elsewhere (the calendar tab, the dashboard's "Dnes" card): land the
    // form on it. The host is told the request was used so it can clear it — a later
    // plain visit to "Dnes" has to start on today again.
    LaunchedEffect(requestedDate) {
        if (requestedDate != null) {
            // The day being left gets its pending draft flushed before the switch.
            flushDraft()
            holder.setDate(requestedDate)
            // A day opened from another screen is meant to be edited, not read —
            // the double tap was the owner's complaint (2026-10-01).
            holder.requestEdit()
            onRequestedDateConsumed()
        }
    }

    var activities by remember { mutableStateOf(PickerDefaults.ACTIVITY_FALLBACK) }
    var hiddenActivities by remember { mutableStateOf<List<ActivityDef>>(emptyList()) }
    var habits by remember { mutableStateOf(PickerDefaults.HABIT_FALLBACK) }
    var scales by remember { mutableStateOf<List<Scale>>(emptyList()) }
    var templates by remember { mutableStateOf(PickerDefaults.DEFAULT_TEMPLATES) }
    // Read-only goal progress: null while unknown (the section hides), a list once
    // [GoalsRepository.loadProgress] has answered — computed from the diary.
    var goalProgress by remember { mutableStateOf<List<GoalProgress>?>(null) }
    var uploadingPhoto by remember { mutableStateOf(false) }

    // ── The 2026-09 additions ──
    // One-shot guard for the after-midnight step back to yesterday (see the load effect).
    var lateNightChecked by remember { mutableStateOf(false) }
    // The habit editor window: the habit being edited (null = a new one is being
    // created), whether the window is open, and whether that write is in flight.
    var habitEditor by remember { mutableStateOf<HabitDef?>(null) }
    var habitEditorOpen by remember { mutableStateOf(false) }
    var habitSaving by remember { mutableStateOf(false) }
    // The "Změnit datum" flow: the picker, the day it came back with, and whether that
    // day already holds an entry (which turns the dialog into its overwrite step).
    var moveDialogOpen by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<String?>(null) }

    // Pickers + goal progress once per screen. The progress is read-only and
    // computed from the diary, so the check-in only ever displays it.
    LaunchedEffect(deps) {
        if (pickersRepo != null) {
            activities = pickersRepo.activities().ifEmpty { PickerDefaults.ACTIVITY_FALLBACK }
            hiddenActivities = pickersRepo.hiddenActivities()
            habits = pickersRepo.habits().ifEmpty { PickerDefaults.HABIT_FALLBACK }
            scales = pickersRepo.scales()
            templates = pickersRepo.templates().ifEmpty { PickerDefaults.DEFAULT_TEMPLATES }
        }
        if (goalsRepo != null) {
            goalProgress = goalsRepo.loadProgress()
        }
    }

    // Entry for the selected day: DB first, then draft, then empty (web rules). A day
    // that came from the database is *stored*, which is what makes the screen show the
    // passive day view instead of the form.
    LaunchedEffect(state.date) {
        holder.setLoading(true)
        val fromDb = entriesRepo?.loadEntry(state.date)
        val restored = fromDb ?: drafts?.load(state.date) ?: DiaryEntry.EMPTY
        // A day reads passively only when it is a *record* — mood filled in (the owner's
        // rule, see isRecordedDay). A row that only carries the phone's synced screen
        // time is a day nobody journaled yet, so it opens as the form.
        holder.load(restored, stored = isRecordedDay(fromDb?.mood))

        // Just after midnight the day being written up is still the one that ended, and
        // the app used to file that whole check-in under the new date. Step back once,
        // and only while today is untouched — a day with any content is never moved.
        if (!lateNightChecked && !isRecordedDay(fromDb?.mood) && !restored.hasContent() &&
            state.date == CheckInDates.today() && CheckInDates.isAfterMidnight()
        ) {
            lateNightChecked = true
            holder.startLateNight()
        }
    }

    // The check-in is done (saved, reflection window closed): back to the overview.
    LaunchedEffect(state.finished) {
        if (state.finished && holder.consumeFinished()) onFinished()
    }

    // Debounced draft autosave (the web's localStorage `diarium_draft_{date}`).
    LaunchedEffect(state.date, state.entry, state.saved) {
        if (drafts == null) return@LaunchedEffect
        if (state.saved || !state.entry.hasContent()) return@LaunchedEffect
        delay(1200)
        drafts.save(state.date, state.entry)
    }

    // The pending debounced draft must not die with the process (2026-09-25 fix):
    // ON_STOP is the last callback the app is guaranteed to get before it can be killed.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { flushDraft() }

    // Leaving the screen inside the app (a tab switch) disposes it — same flush.
    DisposableEffect(Unit) { onDispose { flushDraft() } }

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

    val haptics = rememberHaptics()

    // A saved check-in gets a physical confirmation instead of a silent swap.
    LaunchedEffect(state.saved) {
        if (state.saved) haptics.success()
    }

    fun save() {
        val repo = entriesRepo
        if (repo == null) {
            holder.markError("Ukládání vyžaduje přihlášení.")
            return
        }
        holder.markSaving()
        scope.launch {
            val result = persistEntry()
            if (result.isSuccess) {
                holder.markSaved()
                // The diary just changed under the goals: recompute the read-only
                // progress so the section reflects the save without a reload.
                goalProgress = goalsRepo?.loadProgress()
            } else {
                val error = result.exceptionOrNull()
                if (error is QueuedOfflineException) {
                    // Offline: the day is safe in the outbox (PendingSaveStore).
                    // The card says „Uloženo v telefonu…"; the reflection window
                    // stays closed on purpose — it needs the server.
                    holder.markQueuedOffline()
                    drafts?.clear(holder.date)
                    PendingSaveWorker.enqueue(context)
                } else {
                    holder.markError(error?.message ?: "Uložení se nezdařilo.")
                }
            }
        }
    }

    /**
     * The move itself: the entry is written to the target day first, the old row is
     * deleted after — a failed write can then never cost the owner his check-in. A
     * failed delete is said out loud instead of being swallowed: the entry has moved,
     * but a stale copy would silently duplicate the day.
     */
    fun performMove(target: String) {
        val repo = entriesRepo ?: return
        val entry = holder.entry
        val from = holder.date
        scope.launch {
            holder.markSaving()
            val written = repo.save(entry, target)
            if (written.isFailure) {
                holder.markError(written.exceptionOrNull()?.message ?: "Přesun se nezdařil.")
                return@launch
            }
            if (repo.delete(from).isFailure) {
                holder.markError("Zápis je přesunutý, ale původní den se nepodařilo smazat.")
            }
            drafts?.clear(from)
            // The entry moved between days, so the days that logged an activity
            // changed: recompute the read-only goal progress from the diary.
            goalProgress = goalsRepo?.loadProgress()
            moveDialogOpen = false
            moveTarget = null
            // Landing on the target day re-reads it, so the screen shows the moved entry
            // as the stored day it now is.
            holder.setDate(target)
        }
    }

    /**
     * "Změnit datum": the picker came back with a day. A target that already holds an
     * entry turns the dialog into its overwrite step rather than silently replacing it.
     */
    fun requestMove(target: String) {
        if (target == holder.date) {
            moveDialogOpen = false
            return
        }
        val repo = entriesRepo
        if (repo == null) {
            holder.markError("Přesun vyžaduje přihlášení.")
            return
        }
        scope.launch {
            val occupied = repo.loadEntry(target)?.hasContent() == true
            if (occupied) moveTarget = target else performMove(target)
        }
    }

    /**
     * The AI reflection request — the web's AI block, native. It is fired by the
     * save itself (and by the "Vygenerovat reflexi" action on an already stored
     * day), never by a button that competes with "Uložit check-in"; whatever the
     * server answers is persisted by [AiReflectionRepository.generate].
     *
     * Order matters: anything unsaved is pushed first, and a failed save aborts
     * the request instead of letting the AI reflect on a day it cannot see. The
     * generated text is then written by a second save — that pass is the only one
     * whose payload carries `ai_reflection`.
     *
     * The on-device profile decides first: with the AI master switch off
     * (Nastavení → AI) no request is fired at all, the window just says so.
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
            // The master switch is read once, before anything is saved or sent:
            // with AI off the whole flow is skipped.
            val profile = deps.profile?.load()
            if (profile != null && !profile.aiEnabled) {
                holder.markReflectionError(AI_DISABLED_MESSAGE)
                return@launch
            }

            if (holder.state.hasContent && !holder.state.saved) {
                holder.markSaving()
                val stored = persistEntry()
                if (stored.isFailure) {
                    if (stored.exceptionOrNull() is QueuedOfflineException) {
                        // Offline: the entry is in the outbox. The reflection is a
                        // server call, so it waits for the flush — say so instead of
                        // an error the user cannot act on.
                        holder.markQueuedOffline()
                        drafts?.clear(holder.date)
                        PendingSaveWorker.enqueue(context)
                        holder.markReflectionError("Zápis je uložený v telefonu — reflexi vygeneruješ po připojení.")
                    } else {
                        holder.markError(stored.exceptionOrNull()?.message ?: "Uložení se nezdařilo.")
                        holder.markReflectionError("Nejdřív je potřeba uložit dnešní zápis.")
                    }
                    return@launch
                }
                // Not markSaved(): the manual flow must not pop the window open by
                // itself, only the save button does.
                holder.markReflectionStored()
            }

            holder.markReflectionLoading()
            val date = holder.date
            // The owner's own name (and birth year) make the reflection personal; the
            // session's name stays the fallback for a profile that has none.
            val profileName = profile?.name?.takeIf { it.isNotBlank() } ?: repo.userName()
            repo.generate(date, holder.entry, profileName, profile?.birthYear)
                .onSuccess { text ->
                    holder.setReflection(text)
                    persistEntry()
                        .onSuccess { holder.markReflectionStored() }
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

    // The save just flipped the day into its read-only state, so its reflection
    // window opens itself. Keyed on the state flag (never on a plain recomposition)
    // so a rotation or a later return to the tab cannot bring it back. A day whose
    // row already carried an `aiReflection` is shown from state with no request; a
    // blank one is fetched while the dialog shows its spinner.
    LaunchedEffect(state.showReflectionDialog) {
        if (holder.state.showReflectionDialog && holder.state.reflectionNeedsRequest) {
            generateReflection()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Keep the focused field above the keyboard (the app draws
            // edge-to-edge, so nothing else reserves the IME inset).
            .imePadding()
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

        if (state.lateNightHint) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "🌙 Je po půlnoci — otevírám včerejší zápis. Datum se dá změnit v hlavičce.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))

        // A stored day reads passively: the check-in tab is where the day is *seen* until
        // the owner asks to change it. An unwritten day opens straight into the form.
        if (state.showsPassiveDay) {
            StoredDayView(
                date = state.date,
                entry = state.entry,
                scales = scales,
                onEdit = { holder.startEditing() },
                onMoveDate = { moveDialogOpen = true },
            )
            Spacer(Modifier.height(48.dp))
            return@Column
        }

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
            onEdit = { habit ->
                habitEditor = habit
                habitEditorOpen = true
            },
            onAddHabit = {
                habitEditor = null
                habitEditorOpen = true
            },
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

        // Read-only: the progress is computed from the diary by GoalsRepository;
        // ticking and editing live on the Cíle screen.
        CheckInGoalsSection(progress = goalProgress)

        ScreenTimeSection(
            // phoneScreenTime is seconds; the property converts to the minutes this
            // section (and every other screen) shows.
            screenTimeMinutes = state.entry.phoneScreenTimeMinutes,
            unlocks = state.entry.phoneUnlocks,
            topApps = state.entry.phoneTopApps.map { it.app to it.minutes },
        )

        ReflectionSection(
            reflection = state.reflection,
            loading = state.reflectionLoading,
            error = state.reflectionError,
            onGenerate = { generateReflection() },
            // Only a day that is really stored can have a reflection filled in
            // later; an unsaved (or empty) day just says the save will write it.
            canGenerate = state.entry.hasContent(),
        )

        PrimaryButton(
            text = if (state.saving) "⏳ Ukládám..." else "✓ Uložit check-in",
            enabled = !state.saving,
            testTag = "save_button",
        ) { save() }

        if (state.saved) {
            Spacer(Modifier.height(10.dp))
            GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = IndigoLight,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Uloženo",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                }
            }
        }

        if (state.queuedOffline) {
            // Saved while offline: it reads as saved (it is — on the phone), with
            // the send note instead of the reflection window. See PendingSaveWorker.
            Spacer(Modifier.height(10.dp))
            GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "📥",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = IndigoLight,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Uloženo v telefonu — odešle se, jakmile budeš online.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }

    if (state.showReflectionDialog) {
        ReflectionDialog(
            entry = state.entry,
            activities = activities,
            habits = habits,
            scales = scales,
            reflection = state.reflection,
            loading = state.reflectionLoading,
            error = state.reflectionError,
            canRegenerate = state.saved && !state.reflectionLoading,
            onRetry = { generateReflection() },
            onDismiss = { holder.dismissReflectionDialog() },
        )
    }

    // ── "Změnit datum": move a stored entry to another day ──
    if (moveDialogOpen) {
        MoveDateDialog(
            currentDate = state.date,
            conflictDate = moveTarget,
            onDismiss = {
                moveDialogOpen = false
                moveTarget = null
            },
            onPickDate = { target -> requestMove(target) },
            onConfirmOverwrite = { moveTarget?.let { performMove(it) } },
        )
    }

    // ── Habit editor: the icon and label of one habit, or a brand-new one ──
    if (habitEditorOpen) {
        HabitEditorDialog(
            habit = habitEditor,
            saving = habitSaving,
            onDismiss = {
                habitEditorOpen = false
                habitEditor = null
            },
            onSave = { label, icon, isNegative ->
                val editing = habitEditor
                habitSaving = true
                scope.launch {
                    if (editing == null) {
                        // Create mode: the repository slugs the label, uniquifies the key
                        // and defaults the icon; the returned row is appended to the local
                        // list — the same refresh-local way the edit below works.
                        val created = pickersRepo?.addHabit(label, icon)
                        habitSaving = false
                        if (created != null) {
                            habits = habits + created
                            habitEditorOpen = false
                            habitEditor = null
                        } else {
                            holder.markError("Návyk se nepodařilo přidat.")
                        }
                    } else {
                        val saved = pickersRepo?.updateHabit(editing.key, label, icon, isNegative) == true
                        habitSaving = false
                        if (saved) {
                            // The grid reads the local list, so the edited row changes at
                            // once; the write is what makes it survive the next read.
                            habits = habits.map { item ->
                                if (item.key == editing.key) {
                                    item.copy(label = label, icon = icon, isNegative = isNegative)
                                } else {
                                    item
                                }
                            }
                            habitEditorOpen = false
                            habitEditor = null
                        } else {
                            holder.markError("Návyk se nepodařilo uložit.")
                        }
                    }
                }
            },
            onRemove = {
                val removing = habitEditor
                if (removing != null) {
                    habitSaving = true
                    scope.launch {
                        val removed = pickersRepo?.removeHabit(removing) == true
                        habitSaving = false
                        if (removed) {
                            // Removal is the web's soft delete: the row leaves the grid,
                            // but the ticks past days stored under its key stay.
                            habits = habits.filterNot { it.key == removing.key }
                            habitEditorOpen = false
                            habitEditor = null
                        } else {
                            holder.markError("Návyk se nepodařilo odebrat.")
                        }
                    }
                }
            },
        )
    }
}

/**
 * The day the "Dnes" tab shows when it is already stored: the same read-only card the
 * history tab opens ([DayDetail]) plus the two actions a stored day needs — "Upravit"
 * (hands the day back to the form) and "Změnit datum" (moves the entry to another day).
 *
 * Both are up-callbacks, so this stays a pure render of one day and the screen keeps
 * owning the repositories.
 */
@Composable
private fun StoredDayView(
    date: String,
    entry: DiaryEntry,
    scales: List<Scale>,
    onEdit: () -> Unit,
    onMoveDate: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        DayDetail(
            date = date,
            entry = entry,
            scaleNames = scales.associate { it.id to it.name },
            scaleMax = scales.associate { it.id to it.maxValue },
            onOpenCheckIn = { onEdit() },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(
                text = "✏️ Upravit",
                modifier = Modifier.weight(1f),
                testTag = "checkin_edit",
            ) { onEdit() }
            SecondaryButton(text = "🗓 Změnit datum", testTag = "checkin_move") { onMoveDate() }
        }
    }
}

/** Shown when the reflection flow is asked to run while the AI master switch is off. */
private const val AI_DISABLED_MESSAGE = "AI je vypnutá — zapneš ji v Nastavení."
