package cz.digitalnivedomi.diarium.ui.checkin

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import cz.digitalnivedomi.diarium.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cz.digitalnivedomi.diarium.core.data.ActivityDef
import cz.digitalnivedomi.diarium.core.data.AiReflectionRepository
import cz.digitalnivedomi.diarium.core.data.ConditionalEntrySaveRequest
import cz.digitalnivedomi.diarium.core.data.ConditionalSaveConflictException
import cz.digitalnivedomi.diarium.core.data.ConditionalSaveReceipt
import cz.digitalnivedomi.diarium.core.data.ConditionalSaveUnknownException
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.core.data.DraftRecord
import cz.digitalnivedomi.diarium.core.data.DraftStore
import cz.digitalnivedomi.diarium.core.data.EntryBaseline
import cz.digitalnivedomi.diarium.core.data.EntryMoveCandidate
import cz.digitalnivedomi.diarium.core.data.EntryMoveCapability
import cz.digitalnivedomi.diarium.core.data.EntryMoveConflictException
import cz.digitalnivedomi.diarium.core.data.EntryMoveRequest
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.EntryMoveUnavailableException
import cz.digitalnivedomi.diarium.core.data.GeneratedReflectionDraftException
import cz.digitalnivedomi.diarium.core.data.GoalProgress
import cz.digitalnivedomi.diarium.core.data.GoalsRepository
import cz.digitalnivedomi.diarium.core.data.HabitDef
import cz.digitalnivedomi.diarium.core.data.NoteTemplate
import cz.digitalnivedomi.diarium.core.data.PendingEntryMoveException
import cz.digitalnivedomi.diarium.core.data.PendingSave
import cz.digitalnivedomi.diarium.core.data.PendingSaveRecoveryIssue
import cz.digitalnivedomi.diarium.core.data.PendingSaveRecoveryIssueKind
import cz.digitalnivedomi.diarium.core.data.PickerDefaults
import cz.digitalnivedomi.diarium.core.data.QueuedOfflineException
import cz.digitalnivedomi.diarium.core.data.Scale
import cz.digitalnivedomi.diarium.core.data.SessionOwnerMismatchException
import cz.digitalnivedomi.diarium.core.data.SessionContext
import cz.digitalnivedomi.diarium.core.data.StoredEntrySnapshot
import cz.digitalnivedomi.diarium.core.data.isRecordedDay
import cz.digitalnivedomi.diarium.core.data.phoneScreenTimeMinutes
import cz.digitalnivedomi.diarium.core.media.PhotoCompressor
import cz.digitalnivedomi.diarium.ui.checkin.components.ActivitiesSection
import cz.digitalnivedomi.diarium.ui.checkin.components.CheckInGoalsSection
import cz.digitalnivedomi.diarium.ui.checkin.components.DateNav
import cz.digitalnivedomi.diarium.ui.checkin.components.ErrorBanner
import cz.digitalnivedomi.diarium.ui.checkin.components.GoalCelebration
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
import cz.digitalnivedomi.diarium.ui.i18n.translate
import cz.digitalnivedomi.diarium.ui.history.DayDetail
import cz.digitalnivedomi.diarium.ui.theme.Indigo
import cz.digitalnivedomi.diarium.ui.theme.IndigoLight
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary
import cz.digitalnivedomi.diarium.sync.PendingSaveWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-page daily check-in — native port of the web's `OnePageCheckIn.tsx`.
 *
 * Sections, in the web's order: datum, nálada, spánek, stres, aktivity, návyky,
 * vděčnost, počasí, fotka, škály, poznámka, cíle (read-only progress computed from
 * the diary), the read-only screen time and the AI reflection. Foreground saves use
 * `save_daily_entry_v2` with the row token captured by the day's successful read.
 * Stored-day moves use the separate atomic `move_daily_entry_v1`
 * contract and a durable owner-scoped retry outbox; an unavailable server function
 * never falls back to separate save/delete writes.
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
    /** The day the form treats as today; a parameter so a screenshot can pin it. */
    today: LocalDate = LocalDate.now(),
    /** Whether the post-midnight auto-step-back is active; false pins a screenshot. */
    lateNight: Boolean = CheckInDates.isAfterMidnight(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entriesRepo = deps.entries
    val reflectionRepo = deps.reflection
    val pickersRepo = deps.pickers
    val drafts = deps.drafts
    // A holder and every recovery state are scoped to the same account as the
    // repositories. Switching accounts cannot flush one user's current form into
    // another user's local draft namespace.
    val configuredOwnerId = drafts?.ownerId ?: entriesRepo?.pendingOwnerId
    val activeOwnerId = deps.sessionStore?.let { SessionContext(it).userId() }
    val ownerId = if (deps.sessionStore == null) {
        configuredOwnerId
    } else {
        configuredOwnerId?.takeIf { it == activeOwnerId }
    }
    val holder = remember(today, ownerId) {
        CheckInStateHolder(today.toString(), initiallyLoading = true)
    }
    val state = holder.state
    // Recovery must be visible to lifecycle flushes: an unresolved comparison may
    // never replace its preserved local draft.
    var draftRecovery by remember(ownerId, state.date) { mutableStateOf<DraftRecoveryView?>(null) }
    var draftRecoveryChoice by remember(ownerId, state.date) { mutableStateOf(DraftRecoveryChoice.UNRESOLVED) }
    var recoveredOwnerAttested by remember(ownerId, state.date) { mutableStateOf(false) }
    var recoveredLocalReviewed by remember(ownerId, state.date) { mutableStateOf(false) }
    var recoveredRebaseInProgress by remember(ownerId, state.date) { mutableStateOf(false) }
    var pendingRecovery by remember(ownerId, state.date) { mutableStateOf<PendingRecoveryView?>(null) }
    var pendingRecoveryIssue by remember(ownerId, state.date) { mutableStateOf<PendingSaveRecoveryIssue?>(null) }
    var pendingRecoveryChoice by remember(ownerId, state.date) { mutableStateOf(PendingRecoveryChoice.LOCAL) }
    var reloadToken by remember { mutableStateOf(0) }
    var dateNavigationInProgress by remember(ownerId) { mutableStateOf(false) }
    val dateNavigationGuard = remember(ownerId) { AtomicBoolean(false) }
    val goalsRepo: GoalsRepository? = deps.goals
    var movePickerOpen by remember(ownerId) { mutableStateOf(false) }
    var moveCandidate by remember(ownerId) { mutableStateOf<EntryMoveCandidate?>(null) }
    var moveInProgress by remember(ownerId) { mutableStateOf(false) }
    var moveCapability by remember(ownerId) { mutableStateOf<EntryMoveCapability?>(null) }
    var moveCapabilityLoading by remember(ownerId) { mutableStateOf(ownerId != null && entriesRepo != null) }
    var pendingMoveRecovery by remember(ownerId, state.date) { mutableStateOf<EntryMoveRequest?>(null) }
    var moveRecoveryError by remember(ownerId, state.date) { mutableStateOf<String?>(null) }
    var moveRecoveryLoading by remember(ownerId, state.date) { mutableStateOf(ownerId != null) }

    LaunchedEffect(ownerId, state.date, entriesRepo) {
        pendingMoveRecovery = null
        moveRecoveryError = null
        val repo = entriesRepo
        val owner = ownerId
        if (repo == null || owner == null) {
            moveRecoveryLoading = false
            return@LaunchedEffect
        }
        moveRecoveryLoading = true
        try {
            if (repo.currentUserId() != owner) return@LaunchedEffect
            val result = repo.pendingMoveForDate(state.date)
            if (repo.currentUserId() != owner) return@LaunchedEffect
            pendingMoveRecovery = result.getOrNull()
            moveRecoveryError = result.exceptionOrNull()?.message
        } finally {
            moveRecoveryLoading = false
        }
    }

    suspend fun refreshMoveCapabilityFor(owner: String, repo: EntriesRepository) {
        if (ownerId != owner || repo.currentUserId() != owner) return
        moveCapabilityLoading = true
        try {
            val result = repo.checkAtomicDateMoveCapability()
            if (ownerId == owner && repo.currentUserId() == owner) {
                moveCapability = result
            }
        } finally {
            if (ownerId == owner) moveCapabilityLoading = false
        }
    }

    fun refreshMoveCapability() {
        val repo = entriesRepo ?: return
        val owner = ownerId ?: return
        if (moveCapabilityLoading) return
        scope.launch { refreshMoveCapabilityFor(owner, repo) }
    }

    LaunchedEffect(ownerId, entriesRepo) {
        val repo = entriesRepo
        val owner = ownerId
        if (repo == null || owner == null) {
            moveCapability = EntryMoveCapability(
                available = false,
                reason = "Přesun vypnutý: přihlášený účet nelze ověřit.",
            )
            moveCapabilityLoading = false
            return@LaunchedEffect
        }
        refreshMoveCapabilityFor(owner, repo)
    }

    // Re-check on foreground return so a temporary outage or a later server rollout
    // does not leave a stale disabled state. A visible retry action handles an
    // in-place reconnect while the app remains open.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refreshMoveCapability() }

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
        if (d.ownerId != ownerId) return
        if (!s.draftDirty || s.saved || s.saving || s.draftStoreError != null || !s.entry.hasContent()) return
        if (draftRecovery != null || pendingRecovery != null || pendingRecoveryIssue != null) return
        val flush = d.flush(
            s.date,
            s.entry,
            s.baselineUpdatedAt,
            s.baselineExists,
            s.baselineId,
            s.baselineRevision,
            s.pendingSaveRequestId,
            s.pendingAiReflection,
        )
        flush.invokeOnCompletion { failure ->
            if (failure != null && failure !is CancellationException) {
                // The write outlives this screen's coroutine. Report failure back on
                // the UI thread while the process is alive, preserving the dirty form
                // and leaving the explicit retry action available.
                Handler(Looper.getMainLooper()).post {
                    val current = holder.state
                    if (current.date == s.date && current.draftDirty && !current.saved) {
                        holder.markDraftStoreFailed(DRAFT_STORE_WRITE_FAILED_MESSAGE)
                    }
                }
            }
        }
    }

    suspend fun persistDraftBeforeNavigation(snapshot: CheckInUiState): Result<Unit> {
        val d = drafts ?: return Result.success(Unit)
        if (d.ownerId != ownerId || !snapshot.draftDirty || snapshot.saved ||
            !snapshot.entry.hasContent() || draftRecovery != null || pendingRecovery != null || pendingRecoveryIssue != null
        ) {
            return Result.success(Unit)
        }
        return try {
            val completed = withTimeoutOrNull(DRAFT_NAVIGATION_TIMEOUT_MS) {
                d.save(
                    snapshot.date,
                    snapshot.entry,
                    snapshot.baselineUpdatedAt,
                    snapshot.baselineExists,
                    snapshot.baselineId,
                    snapshot.baselineRevision,
                    snapshot.pendingSaveRequestId,
                    snapshot.pendingAiReflection,
                )
                true
            } ?: false
            if (completed) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(DRAFT_NAVIGATION_TIMEOUT_MESSAGE))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException(DRAFT_NAVIGATION_FAILED_MESSAGE))
        }
    }

    val currentFlushDraft by rememberUpdatedState { flushDraft() }

    suspend fun refreshPendingRecovery(date: String) {
        val repo = entriesRepo ?: return
        if (ownerId == null || repo.currentUserId() != ownerId) return
        val record = repo.pendingSaveRecord(date)
        pendingRecoveryIssue = repo.pendingSaveRecoveryIssue(date)
        if (record == null) {
            pendingRecovery = null
            holder.setLoading(true)
            reloadToken++
            return
        }
        val remote = repo.loadRemoteEntrySnapshot(date)
        if (repo.currentUserId() != ownerId) return
        val latestRecord = repo.pendingSaveRecord(date)
        pendingRecoveryIssue = repo.pendingSaveRecoveryIssue(date)
        if (latestRecord != record) {
            pendingRecovery = null
            holder.setLoading(true)
            reloadToken++
            return
        }
        pendingRecovery = PendingRecoveryView(
            record = latestRecord,
            remote = remote.getOrNull(),
            remoteError = remote.exceptionOrNull()?.message,
        )
        pendingRecoveryChoice = PendingRecoveryChoice.LOCAL
    }

    // Workers and other app screens can finish an outbox transaction while this
    // screen is still composed. Follow the durable owner/date record, not the
    // snapshot that happened to be read when recovery began.
    LaunchedEffect(ownerId, state.date, entriesRepo) {
        val repo = entriesRepo ?: return@LaunchedEffect
        repo.observePendingSaveRecord(state.date).collect { currentRecord ->
            if (ownerId == null || repo.currentUserId() != ownerId) return@collect
            val shown = pendingRecovery ?: return@collect
            if (currentRecord != shown.record) {
                pendingRecovery = null
                pendingRecoveryIssue = repo.pendingSaveRecoveryIssue(state.date)
                holder.setLoading(true)
                reloadToken++
            }
        }
    }

    // A day picked elsewhere (the calendar tab, the dashboard's "Dnes" card): land the
    // form on it. The host is told the request was used so it can clear it — a later
    // plain visit to "Dnes" has to start on today again.
    LaunchedEffect(requestedDate, ownerId) {
        if (requestedDate != null) {
            val snapshot = holder.state
            val ownsNavigationLock = !snapshot.draftDirty || dateNavigationGuard.compareAndSet(false, true)
            if (!ownsNavigationLock || (snapshot.draftDirty && !holder.beginDateNavigation())) {
                if (ownsNavigationLock && snapshot.draftDirty) dateNavigationGuard.set(false)
                holder.markError(DRAFT_NAVIGATION_FAILED_MESSAGE, snapshot.date)
                return@LaunchedEffect
            }
            dateNavigationInProgress = snapshot.draftDirty
            try {
                val result = persistDraftBeforeNavigation(snapshot)
                if (result.isFailure) {
                    holder.markError(
                        result.exceptionOrNull()?.message ?: DRAFT_NAVIGATION_FAILED_MESSAGE,
                        snapshot.date,
                    )
                    return@LaunchedEffect
                }
                holder.setDate(requestedDate)
                // A day opened from another screen is meant to be edited, not read —
                // the double tap was the owner's complaint (2026-10-01).
                holder.requestEdit()
                onRequestedDateConsumed()
            } finally {
                if (snapshot.draftDirty) {
                    holder.endDateNavigation()
                    dateNavigationGuard.set(false)
                }
                dateNavigationInProgress = false
            }
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
    var activePhotoUpload by remember { mutableStateOf<Long?>(null) }
    val uploadingPhoto = activePhotoUpload != null
    val currentUploadingPhoto = rememberUpdatedState(uploadingPhoto)
    // A selected-day read, save, photo upload, date-flush or unresolved move owns the form until safe.
    val busy = state.loading || state.saving || uploadingPhoto || dateNavigationInProgress ||
        moveInProgress || moveRecoveryLoading || recoveredRebaseInProgress
    // Bumped by the load error's retry button: the load effect keys on it as well as
    // the date, so a failed read can be attempted again without leaving the day.
    fun retryDayLoad() {
        val snapshot = holder.state
        traceDraftRecovery("retry click loading=${snapshot.loading} dirty=${snapshot.draftDirty} storeError=${snapshot.draftStoreError != null}")
        if (snapshot.loading) return
        holder.setLoading(true)
        val staged = drafts?.takeIf { it.ownerId == ownerId }?.let { store ->
            if (snapshot.draftDirty && !snapshot.saved && snapshot.entry.hasContent()) {
                store.stage(
                    snapshot.date,
                    snapshot.entry,
                    snapshot.baselineUpdatedAt,
                    snapshot.baselineExists,
                    snapshot.baselineId,
                    snapshot.baselineRevision,
                    snapshot.pendingSaveRequestId,
                    snapshot.pendingAiReflection,
                )
            } else {
                null
            }
        }
        scope.launch {
            traceDraftRecovery("retry launch staged=${staged != null}")
            try {
                staged?.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                holder.markDraftStoreFailed(DRAFT_STORE_WRITE_FAILED_MESSAGE)
                return@launch
            }
            traceDraftRecovery("retry stage complete; bump reload")
            reloadToken++
        }
    }
    suspend fun recoveryOwnerIsCurrent(owner: String, repository: EntriesRepository, store: DraftStore): Boolean {
        if (store.ownerId != owner || repository.currentUserId() != owner) return false
        val sessionStore = deps.sessionStore ?: return true
        return SessionContext(sessionStore).userId() == owner
    }

    fun blockRecoveredRebase(recovery: DraftRecoveryView, message: String) {
        draftRecovery = DraftRecoveryView(recovery.record, remote = null, remoteError = message)
        draftRecoveryChoice = DraftRecoveryChoice.UNRESOLVED
        recoveredOwnerAttested = false
        recoveredLocalReviewed = false
        holder.markLoadFailed(
            message = message,
            draft = recovery.record.entry,
            baselineUpdatedAt = recovery.record.baselineUpdatedAt,
            baselineExists = recovery.record.baselineExists,
            baselineId = recovery.record.baselineId,
            baselineRevision = recovery.record.baselineRevision,
            pendingSaveRequestId = null,
            pendingAiReflection = recovery.record.pendingAiReflection,
        )
    }

    fun rebaseRecoveredDraft(recovery: DraftRecoveryView) {
        val owner = ownerId ?: return
        val repository = entriesRepo ?: return
        val store = drafts?.takeIf { it.ownerId == owner } ?: return
        val legacy = recovery.record.legacyRecoveryOrigin != null
        if (recoveredRebaseInProgress || !recoveredLocalReviewed ||
            draftRecoveryChoice != DraftRecoveryChoice.LOCAL || recovery.remoteError != null ||
            (legacy && !recoveredOwnerAttested)
        ) return
        recoveredRebaseInProgress = true
        scope.launch {
            try {
                if (!recoveryOwnerIsCurrent(owner, repository, store)) {
                    blockRecoveredRebase(recovery, "Účet se během obnovy změnil. Nic nebylo uloženo; znovu ověř účet a načti zápisy.")
                    return@launch
                }
                val refreshed = repository.loadRemoteEntrySnapshot(recovery.record.date)
                if (!recoveryOwnerIsCurrent(owner, repository, store)) {
                    blockRecoveredRebase(recovery, "Účet se během načítání změnil. Místní i serverová kopie zůstaly zachovány.")
                    return@launch
                }
                if (refreshed.isFailure) {
                    blockRecoveredRebase(
                        recovery,
                        refreshed.exceptionOrNull()?.message ?: "Serverovou kopii se nepodařilo znovu ověřit.",
                    )
                    return@launch
                }
                val remote = refreshed.getOrNull()
                if (remote != null && !EntryBaseline(true, remote.id, remote.revision).isValid) {
                    blockRecoveredRebase(recovery, "Serverová kopie nemá ověřenou identitu a revizi. Zkus ji načíst znovu.")
                    return@launch
                }
                val current = store.loadRecord(recovery.record.date)
                if (current != recovery.record) {
                    blockRecoveredRebase(recovery, "Místní návrh se změnil. Znovu načti a prohlédni obě kopie.")
                    return@launch
                }
                val rebased = if (legacy) {
                    store.rebaseLegacyRecovery(recovery.record.date, recovery.record, remote)
                } else {
                    store.rebaseDraft(recovery.record.date, recovery.record, remote)
                }
                if (rebased == null) {
                    blockRecoveredRebase(recovery, "Původní místní záznam se změnil nebo jej nelze ověřit. Nic nebylo přepsáno.")
                    return@launch
                }
                if (!recoveryOwnerIsCurrent(owner, repository, store)) {
                    blockRecoveredRebase(recovery, "Účet se po načtení změnil. Nic nebylo odesláno; přihlas se zpět a zkus znovu.")
                    return@launch
                }
                draftRecovery = null
                draftRecoveryChoice = DraftRecoveryChoice.LOCAL
                recoveredOwnerAttested = false
                recoveredLocalReviewed = false
                holder.load(
                    entry = rebased.entry,
                    stored = remote != null && isRecordedDay(remote.entry.mood),
                    baselineUpdatedAt = remote?.updatedAt,
                    baselineExists = remote != null,
                    baselineId = remote?.id,
                    baselineRevision = remote?.revision,
                    pendingSaveRequestId = null,
                    pendingAiReflection = rebased.pendingAiReflection,
                    editingOverride = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                blockRecoveredRebase(
                    recovery,
                    failure.message ?: "Obnovu se nepodařilo bezpečně ověřit. Místní i serverová kopie zůstaly zachovány.",
                )
            } finally {
                recoveredRebaseInProgress = false
            }
        }
    }

    // Why the last photo pick failed, shown right under the buttons (the shared
    // ErrorBanner sits at the top of the form, far above the photo section, so a
    // photo failure was invisible while the user was scrolled down).
    var photoError by remember { mutableStateOf<String?>(null) }

    // A late result from an upload for the previous date must not update this form
    // (nor leave its spinner active) after the date changes.
    LaunchedEffect(state.date) {
        activePhotoUpload = null
        photoError = null
    }

    // Goals this save pushed over their target. Shown one at a time, at most three;
    // the queue also gates the reflection dialog, so both never stack.
    var celebrationQueue by remember { mutableStateOf<List<GoalProgress>>(emptyList()) }
    var celebrationSeen by remember { mutableStateOf(0) }

    // ── The 2026-09 additions ──
    // One-shot guard for the after-midnight step back to yesterday (see the load effect).
    var lateNightChecked by remember { mutableStateOf(false) }
    // The habit editor window: the habit being edited (null = a new one is being
    // created), whether the window is open, and whether that write is in flight.
    var habitEditor by remember { mutableStateOf<HabitDef?>(null) }
    var habitEditorOpen by remember { mutableStateOf(false) }
    var habitSaving by remember { mutableStateOf(false) }
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

    // Entry load is deliberately split into the owner-scoped draft/outbox and the
    // actual server row. A local overlay alone cannot show the user what changed on
    // another device, and a read failure must never be mistaken for an empty day.
    LaunchedEffect(ownerId, state.date, reloadToken, entriesRepo, drafts) {
        traceDraftRecovery("load effect token=$reloadToken")
        holder.setLoading(true)
        recoveredOwnerAttested = false
        recoveredLocalReviewed = false
        val repo = entriesRepo
        val savedDraft = try {
            drafts?.loadRecord(state.date)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            traceDraftRecovery("draft read failed")
            draftRecovery = null
            pendingRecovery = null
            holder.markDraftStoreFailed(DRAFT_STORE_READ_FAILED_MESSAGE)
            return@LaunchedEffect
        }
        val queued = repo?.pendingSaveRecord(state.date)
        pendingRecoveryIssue = repo?.pendingSaveRecoveryIssue(state.date)
        pendingRecoveryChoice = PendingRecoveryChoice.LOCAL
        if (repo == null) {
            pendingRecovery = null
            if (savedDraft != null) {
                draftRecovery = DraftRecoveryView(
                    record = savedDraft,
                    remote = null,
                    remoteError = SERVER_UNAVAILABLE_MESSAGE,
                )
                draftRecoveryChoice = DraftRecoveryChoice.UNRESOLVED
                holder.load(
                    savedDraft.entry,
                    stored = true,
                    baselineUpdatedAt = savedDraft.baselineUpdatedAt,
                    baselineExists = savedDraft.baselineExists,
                    baselineId = savedDraft.baselineId,
                    baselineRevision = savedDraft.baselineRevision,
                    pendingSaveRequestId = savedDraft.pendingSaveRequestId,
                    pendingAiReflection = savedDraft.pendingAiReflection,
                    editingOverride = false,
                )
            } else {
                draftRecovery = null
                holder.load(DiaryEntry.EMPTY, stored = false, baselineExists = false)
            }
            return@LaunchedEffect
        }

        traceDraftRecovery("remote read starting")
        val loaded = repo.loadRemoteEntrySnapshot(state.date)
        val latestQueued = repo.pendingSaveRecord(state.date)
        if (latestQueued != queued) {
            pendingRecovery = null
            pendingRecoveryIssue = repo.pendingSaveRecoveryIssue(state.date)
            holder.setLoading(true)
            reloadToken++
            return@LaunchedEffect
        }
        if (loaded.isFailure) {
            val failure = loaded.exceptionOrNull()?.message ?: LOAD_FAILED_MESSAGE
            val retryEntry = holder.state.entry.takeIf { holder.state.loadError != null && it.hasContent() }
            val local = savedDraft?.let { it.copy(entry = retryEntry ?: it.entry) }
                ?: queued?.let {
                    DraftRecord(
                        ownerId = it.ownerId,
                        date = it.date,
                        entry = retryEntry ?: it.entry,
                        baselineUpdatedAt = it.baselineUpdatedAt,
                        baselineExists = it.baselineExists,
                    )
                }
            pendingRecovery = queued?.let { PendingRecoveryView(it, null, failure) }
            draftRecovery = savedDraft?.let { DraftRecoveryView(local ?: it, null, failure) }
            if (draftRecovery != null) draftRecoveryChoice = DraftRecoveryChoice.UNRESOLVED
            holder.markLoadFailed(
                message = failure,
                draft = local?.entry ?: retryEntry,
                queuedOffline = queued != null,
                baselineUpdatedAt = local?.baselineUpdatedAt,
                baselineExists = local?.baselineExists,
                baselineId = local?.baselineId,
                baselineRevision = local?.baselineRevision,
                pendingSaveRequestId = local?.pendingSaveRequestId,
                pendingAiReflection = local?.pendingAiReflection,
            )
            return@LaunchedEffect
        }

        val remote = loaded.getOrNull()
        val retryEntry = holder.state.entry.takeIf { holder.state.loadError != null && it.hasContent() }
        val effectiveDraft = savedDraft?.let { it.copy(entry = retryEntry ?: it.entry) }
            ?: retryEntry?.let {
                DraftRecord(
                    ownerId = repo.currentUserId().orEmpty(),
                    date = state.date,
                    entry = it,
                    baselineUpdatedAt = null,
                    baselineExists = null,
                )
            }
        val conflictingDraft = effectiveDraft?.takeIf {
            it.pendingSaveRequestId == null && it.conflictsWith(
                remoteExists = remote != null,
                remoteUpdatedAt = remote?.updatedAt,
                remoteId = remote?.id,
                remoteRevision = remote?.revision,
            )
        }
        draftRecovery = conflictingDraft?.let { DraftRecoveryView(it, remote, null) }
        draftRecoveryChoice = if (conflictingDraft != null) {
            DraftRecoveryChoice.UNRESOLVED
        } else {
            DraftRecoveryChoice.LOCAL
        }
        pendingRecovery = queued?.let { PendingRecoveryView(it, remote, null) }

        repo.cleanupPhotoCandidates(
            state.date,
            preservePaths = setOfNotNull(remote?.entry?.photoPath, effectiveDraft?.entry?.photoPath, queued?.entry?.photoPath),
        )

        val localEntry = effectiveDraft?.entry ?: queued?.entry
        val unresolved = conflictingDraft != null
        val displayed = if (unresolved) {
            remote?.entry ?: DiaryEntry.EMPTY
        } else {
            localEntry ?: remote?.entry ?: DiaryEntry.EMPTY
        }
        val stored = unresolved || (localEntry == null && isRecordedDay(remote?.entry?.mood))
        val baselineUpdatedAt = when {
            unresolved -> remote?.updatedAt
            effectiveDraft != null -> effectiveDraft.baselineUpdatedAt
            queued != null -> queued.baselineUpdatedAt
            else -> remote?.updatedAt
        }
        val baselineExists = when {
            unresolved -> remote != null
            effectiveDraft != null -> effectiveDraft.baselineExists
            queued != null -> queued.conditionalRequest?.baseline?.exists ?: queued.baselineExists
            else -> remote != null
        }
        val baselineId = when {
            unresolved -> remote?.id
            effectiveDraft != null -> effectiveDraft.baselineId
            queued != null -> queued.conditionalRequest?.baseline?.id ?: queued.baselineId
            else -> remote?.id
        }
        val baselineRevision = when {
            unresolved -> remote?.revision
            effectiveDraft != null -> effectiveDraft.baselineRevision
            queued != null -> queued.conditionalRequest?.baseline?.revision ?: queued.baselineRevision
            else -> remote?.revision
        }
        val pendingRequestId = if (unresolved) null else {
            effectiveDraft?.pendingSaveRequestId ?: queued?.conditionalRequest?.requestId
        }
        val localNeedsEditing = localEntry != null && !unresolved
        holder.load(
            displayed,
            stored = stored,
            baselineUpdatedAt = baselineUpdatedAt,
            baselineExists = baselineExists,
            baselineId = baselineId,
            baselineRevision = baselineRevision,
            pendingSaveRequestId = pendingRequestId,
            pendingAiReflection = if (unresolved) null else effectiveDraft?.pendingAiReflection,
            queuedOffline = queued != null,
            editingOverride = if (unresolved) false else localNeedsEditing,
        )

        // The after-midnight step applies only to a genuinely untouched remote day.
        if (!lateNightChecked && !isRecordedDay(remote?.entry?.mood) &&
            localEntry == null && state.date == today.toString() && lateNight
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
    LaunchedEffect(ownerId, drafts, state.date, state.entry, state.saved, state.saving, state.draftDirty, state.baselineUpdatedAt, state.baselineExists, state.baselineId, state.baselineRevision, state.pendingSaveRequestId, state.pendingAiReflection, draftRecovery, pendingRecovery) {
        if (drafts == null) return@LaunchedEffect
        if (state.saved || state.saving || !state.draftDirty || !state.entry.hasContent()) return@LaunchedEffect
        if (draftRecovery != null || pendingRecovery != null || pendingRecoveryIssue != null) return@LaunchedEffect
        if (state.draftStoreError != null) return@LaunchedEffect
        delay(1200)
        try {
            drafts.save(
                state.date,
                state.entry,
                state.baselineUpdatedAt,
                state.baselineExists,
                state.baselineId,
                state.baselineRevision,
                state.pendingSaveRequestId,
                state.pendingAiReflection,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            holder.markDraftStoreFailed(DRAFT_STORE_WRITE_FAILED_MESSAGE)
        }
    }

    // ON_STOP starts the last best-effort local write. Its result is observed above,
    // but Android may kill the process before an asynchronous DataStore transaction
    // finishes; this callback is not a promise of process-death durability.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { currentFlushDraft() }

    // Leaving the screen inside the app (a tab switch) disposes it — same flush.
    DisposableEffect(ownerId, drafts, holder) { onDispose { flushDraft() } }

    /**
     * The RPC call itself, shared by [save] and [generateReflection]: the AI
     * endpoint builds today's prompt from the stored row, so the reflection flow
     * has to await the very write the save button performs.
     *
     * Date and entry are parameters, not reads of [holder]: a save is a snapshot of
     * what the user saw when the button was pressed. Reading the holder again after
     * the RPC (or during it) is how a save used to land on a day the user had
     * already switched away from.
     */
    suspend fun persistEntry(
        request: ConditionalEntrySaveRequest,
        baselineUpdatedAt: String?,
        stagedDraft: Deferred<Unit>? = null,
    ): Result<ConditionalSaveReceipt> {
        val repo = entriesRepo
            ?: return Result.failure(IllegalStateException("Ukládání vyžaduje přihlášení."))
        val draftStore = drafts?.takeIf { it.ownerId == request.ownerId }
            ?: return Result.failure(
                IllegalStateException("Místní návrh nelze bezpečně uložit pro tento účet. Server nebyl změněn."),
            )
        try {
            (stagedDraft ?: draftStore.stage(
                request.date,
                request.entry,
                baselineUpdatedAt,
                request.baseline.exists,
                request.baseline.id,
                request.baseline.revision,
                request.requestId,
                request.entry.aiReflection.takeIf { request.writeAiReflection },
            )).await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return Result.failure(failure)
        }
        val saved = repo.save(request)
        if (saved.exceptionOrNull() is ConditionalSaveUnknownException) {
            val queued = repo.pendingSaveRecord(request.date)
            if (queued?.ownerId == request.ownerId &&
                queued.conditionalRequest?.requestId == request.requestId && queued.isReplayable
            ) {
                PendingSaveWorker.enqueue(context, request.ownerId)
            }
        }
        if (saved.isSuccess) {
            try {
                draftStore.clearIfUnchanged(request.date, request.entry, request.requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                Log.w("CheckInDraft", "Local cleanup failed after server save (${failure.javaClass.simpleName})")
            }
        } else {
            val conflict = saved.exceptionOrNull() as? ConditionalSaveConflictException
            if (conflict != null) {
                try {
                    if (!draftStore.clearRequestForConflict(request.date, request.entry, request.requestId)) {
                        Log.w("CheckInDraft", "Conflict request marker no longer matched the stored draft")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.w("CheckInDraft", "Conflict marker cleanup failed (${failure.javaClass.simpleName})")
                    if (holder.date == request.date && ownerId == request.ownerId) {
                        holder.markDraftStoreFailed(DRAFT_STORE_WRITE_FAILED_MESSAGE)
                    }
                }
                if (holder.date == request.date && ownerId == request.ownerId) {
                    val record = DraftRecord(
                        ownerId = request.ownerId,
                        date = request.date,
                        entry = request.entry,
                        baselineUpdatedAt = baselineUpdatedAt,
                        baselineExists = request.baseline.exists,
                        baselineId = request.baseline.id,
                        baselineRevision = request.baseline.revision,
                        pendingSaveRequestId = null,
                        pendingAiReflection = request.entry.aiReflection.takeIf { request.writeAiReflection },
                    )
                    draftRecovery = DraftRecoveryView(record, conflict.currentSnapshot, conflict.currentReadError)
                    draftRecoveryChoice = DraftRecoveryChoice.UNRESOLVED
                    pendingRecovery = null
                    if (conflict.currentReadError != null) {
                        holder.markLoadFailed(
                            message = conflict.currentReadError,
                            draft = request.entry,
                            baselineUpdatedAt = baselineUpdatedAt,
                            baselineExists = request.baseline.exists,
                            baselineId = request.baseline.id,
                            baselineRevision = request.baseline.revision,
                            pendingSaveRequestId = null,
                            pendingAiReflection = request.entry.aiReflection.takeIf { request.writeAiReflection },
                        )
                    }
                }
            }
        }
        return saved
    }

    var dispatchMoveHandler: ((EntryMoveCandidate, Boolean) -> Unit)? = null

    fun beginMove(targetDate: String) {
        val repo = entriesRepo ?: run {
            holder.markError("Přesun vyžaduje přihlášení.")
            return
        }
        val owner = ownerId ?: run {
            holder.markError("Účet se změnil. Přesun nebyl zahájen.")
            return
        }
        val snapshot = holder.state
        if (moveInProgress || moveRecoveryLoading || pendingMoveRecovery != null ||
            moveRecoveryError != null || snapshot.loading || snapshot.saving || snapshot.reflectionLoading ||
            snapshot.loadError != null || snapshot.draftDirty || !snapshot.showsPassiveDay ||
            snapshot.baselineExists != true || snapshot.baselineId.isNullOrBlank() ||
            snapshot.baselineRevision == null
        ) {
            holder.markError("Nejdřív načti uložený den bez místních změn.")
            return
        }
        movePickerOpen = false
        moveCandidate = null
        moveInProgress = true
        scope.launch {
            var candidate: EntryMoveCandidate? = null
            try {
                val capability = repo.checkAtomicDateMoveCapability()
                if (!capability.available) {
                    if (repo.currentUserId() == owner && ownerId == owner) {
                        moveCapability = capability
                        holder.markError(capability.reason, snapshot.date)
                    }
                    return@launch
                }
                val draftStore = drafts?.takeIf { it.ownerId == owner }
                    ?: throw IllegalStateException("Místní návrhy nelze ověřit; přesun nebyl odeslán.")
                if (draftStore.loadRecord(snapshot.date) != null || draftStore.loadRecord(targetDate) != null) {
                    throw PendingEntryMoveException()
                }
                if (repo.currentUserId() != owner) return@launch
                candidate = repo.prepareMove(
                    fromDate = snapshot.date,
                    toDate = targetDate,
                    expectedSourceId = snapshot.baselineId,
                    expectedSourceRevision = snapshot.baselineRevision,
                ).getOrElse { throw it }
                if (repo.currentUserId() != owner) return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (repo.currentUserId() == owner && ownerId == owner) {
                    holder.markError(failure.message ?: "Přesun se nepodařilo připravit.", snapshot.date)
                }
            } finally {
                if (ownerId == owner) moveInProgress = false
            }
            val prepared = candidate ?: return@launch
            if (prepared.targetSnapshot != null && prepared.sourceDate != prepared.targetDate) {
                moveCandidate = prepared
                movePickerOpen = true
            } else {
                dispatchMoveHandler?.invoke(prepared, false)
            }
        }
    }

    fun performMoveRequest(candidate: EntryMoveCandidate, confirmOverwrite: Boolean) {
        val repo = entriesRepo ?: return
        val owner = candidate.ownerId
        if (moveInProgress || ownerId != owner) return
        moveInProgress = true
        movePickerOpen = false
        moveCandidate = null
        scope.launch {
            try {
                val draftStore = drafts?.takeIf { it.ownerId == owner }
                    ?: throw IllegalStateException("Místní návrhy nelze ověřit; přesun nebyl odeslán.")
                if (draftStore.loadRecord(candidate.sourceDate) != null ||
                    draftStore.loadRecord(candidate.targetDate) != null
                ) {
                    throw PendingEntryMoveException()
                }
                if (repo.currentUserId() != owner) return@launch
                val result = repo.move(candidate, confirmOverwrite)
                if (ownerId != owner || repo.currentUserId() != owner) return@launch
                if (result.isSuccess) {
                    pendingMoveRecovery = null
                    moveRecoveryError = null
                    try {
                        goalProgress = goalsRepo?.loadProgress()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        goalProgress = null
                    }
                    if (candidate.targetDate == holder.date) {
                        holder.setLoading(true)
                        reloadToken++
                    } else {
                        holder.setDate(candidate.targetDate)
                    }
                } else {
                    val failure = result.exceptionOrNull()
                    if (failure is EntryMoveUnavailableException) {
                        moveCapability = EntryMoveCapability(
                            available = false,
                            reason = failure.message ?: "Přesun vypnutý: serverová podpora není ověřena.",
                        )
                    }
                    holder.markError(failure?.message ?: "Přesun se nezdařil.", candidate.sourceDate)
                    val pending = repo.pendingMoveForDate(candidate.sourceDate)
                    if (ownerId == owner && repo.currentUserId() == owner) {
                        pendingMoveRecovery = pending.getOrNull()
                        moveRecoveryError = pending.exceptionOrNull()?.message
                    }
                    if (failure is EntryMoveConflictException) {
                        holder.setLoading(true)
                        reloadToken++
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (ownerId == owner && repo.currentUserId() == owner) {
                    if (failure is EntryMoveUnavailableException) {
                        moveCapability = EntryMoveCapability(
                            available = false,
                            reason = failure.message ?: "Přesun vypnutý: serverová podpora není ověřena.",
                        )
                    }
                    holder.markError(failure.message ?: "Přesun se nezdařil.", candidate.sourceDate)
                    pendingMoveRecovery = repo.pendingMoveForDate(candidate.sourceDate).getOrNull()
                }
            } finally {
                if (ownerId == owner) moveInProgress = false
            }
        }
    }

    dispatchMoveHandler = ::performMoveRequest

    fun retryMove(request: EntryMoveRequest) {
        val repo = entriesRepo ?: return
        val owner = request.ownerId
        if (moveInProgress || ownerId != owner) return
        moveInProgress = true
        scope.launch {
            try {
                val result = repo.retryPendingMove(request.requestId)
                if (ownerId != owner || repo.currentUserId() != owner) return@launch
                if (result.isSuccess) {
                    pendingMoveRecovery = null
                    moveRecoveryError = null
                    try {
                        goalProgress = goalsRepo?.loadProgress()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        goalProgress = null
                    }
                    if (holder.date == request.sourceDate) {
                        holder.setDate(request.targetDate)
                    } else if (holder.date == request.targetDate) {
                        holder.setLoading(true)
                        reloadToken++
                    }
                } else {
                    val failure = result.exceptionOrNull()
                    if (failure is EntryMoveUnavailableException) {
                        moveCapability = EntryMoveCapability(
                            available = false,
                            reason = failure.message ?: "Přesun vypnutý: serverová podpora není ověřena.",
                        )
                    }
                    holder.markError(failure?.message ?: "Přesun stále čeká na potvrzení.", holder.date)
                    val pending = repo.pendingMoveForDate(holder.date)
                    if (ownerId == owner && repo.currentUserId() == owner) {
                        pendingMoveRecovery = pending.getOrNull()
                        moveRecoveryError = pending.exceptionOrNull()?.message
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (ownerId == owner && repo.currentUserId() == owner) {
                    holder.markError(failure.message ?: "Přesun stále čeká na potvrzení.", holder.date)
                }
            } finally {
                if (ownerId == owner) moveInProgress = false
            }
        }
    }

    val haptics = rememberHaptics()

    // A saved check-in gets a physical confirmation instead of a silent swap.
    LaunchedEffect(state.saved) {
        if (state.saved) haptics.success()
    }

    fun save() {
        if (dateNavigationGuard.get()) return
        if (entriesRepo == null) {
            holder.markError("Ukládání vyžaduje přihlášení.")
            return
        }
        if (moveRecoveryLoading || pendingMoveRecovery != null || moveRecoveryError != null || moveInProgress) {
            holder.markError("Nejdřív ověř nebo zopakuj čekající přesun.")
            return
        }
        if (draftRecovery != null || pendingRecovery != null || pendingRecoveryIssue != null) {
            holder.markError(DRAFT_CONFLICT_BLOCKED_MESSAGE)
            return
        }
        if (holder.state.loadError != null) {
            holder.markError(LOAD_BLOCKED_MESSAGE)
            return
        }
        if (holder.state.loading || holder.state.saving || holder.state.reflectionLoading) return
        val baselineUpdatedAt = holder.state.baselineUpdatedAt
        val request = ownerId?.let(holder::beginSaveRequest)
        if (request == null) {
            holder.markError(CONDITIONAL_BASELINE_REQUIRED_MESSAGE)
            return
        }
        val stagedDraft = drafts?.takeIf { it.ownerId == request.ownerId }?.stage(
            request.date,
            request.entry,
            baselineUpdatedAt,
            request.baseline.exists,
            request.baseline.id,
            request.baseline.revision,
            request.requestId,
            request.entry.aiReflection.takeIf { request.writeAiReflection },
        )
        scope.launch {
            val result = persistEntry(request, baselineUpdatedAt, stagedDraft)
            if (result.isSuccess) {
                val receipt = result.getOrThrow()
                holder.markSaved(request.date, receipt)
                val progressBefore = goalProgress
                val progressAfter = goalsRepo?.loadProgress()
                goalProgress = progressAfter
                val newlyMet = newlyMetGoals(progressBefore, progressAfter)
                if (newlyMet.isNotEmpty()) {
                    celebrationSeen = 0
                    celebrationQueue = newlyMet
                }
            } else {
                val error = result.exceptionOrNull()
                if (error is QueuedOfflineException) {
                    holder.markQueuedOffline(request.date)
                    refreshPendingRecovery(request.date)
                } else if (error is ConditionalSaveUnknownException &&
                    entriesRepo.pendingSaveRecord(request.date)?.conditionalRequest?.requestId == request.requestId
                ) {
                    holder.markQueuedOffline(request.date)
                    refreshPendingRecovery(request.date)
                } else {
                    holder.markError(error?.message ?: "Uložení se nezdařilo.", request.date)
                }
            }
        }
    }

    /**
     * AI generation is serialized with foreground saves and pinned to the exact
     * server snapshot used in the prompt. Generated text is staged before the UI
     * receives it; the second write then goes through the normal v2 CAS path.
     */
    fun generateReflection() {
        if (dateNavigationGuard.get()) return
        val repo = reflectionRepo
        if (repo == null) {
            holder.markReflectionError(AiReflectionRepository.MESSAGE_CONNECT)
            return
        }
        if (moveRecoveryLoading || pendingMoveRecovery != null || moveRecoveryError != null || moveInProgress) {
            holder.markReflectionError("Nejdřív ověř nebo zopakuj čekající přesun.")
            return
        }
        if (holder.state.loadError != null) {
            holder.markReflectionError(LOAD_BLOCKED_MESSAGE)
            return
        }
        if (draftRecovery != null || pendingRecovery != null || pendingRecoveryIssue != null) {
            holder.markReflectionError(DRAFT_CONFLICT_BLOCKED_MESSAGE)
            return
        }
        if (holder.state.reflectionLoading || holder.state.saving || holder.state.loading) return

        val date = holder.date
        val initialEntry = holder.entry
        // Lock Save, editing, and date navigation before yielding to the coroutine.
        holder.markReflectionLoading()
        scope.launch {
            try {
                val profile = deps.profile?.load()
                if (profile?.aiEnabled != true) {
                    holder.markReflectionError(AI_DISABLED_MESSAGE)
                    return@launch
                }

                val owner = ownerId
                val entries = entriesRepo
                val draftStore = drafts?.takeIf { it.ownerId == owner }
                if (owner == null || entries == null || draftStore == null ||
                    entries.currentUserId() != owner
                ) {
                    holder.markReflectionError(AiReflectionRepository.MESSAGE_SIGNED_OUT)
                    return@launch
                }

                // Unsaved form data must land before the model is asked to reflect on it.
                if (initialEntry.hasContent() && !holder.state.saved) {
                    val request = holder.beginSaveRequest(owner, allowDuringReflection = true)
                    if (request == null) {
                        holder.markReflectionError(CONDITIONAL_BASELINE_REQUIRED_MESSAGE)
                        return@launch
                    }
                    val stagedDraft = draftStore.stage(
                        request.date,
                        request.entry,
                        holder.state.baselineUpdatedAt,
                        request.baseline.exists,
                        request.baseline.id,
                        request.baseline.revision,
                        request.requestId,
                        request.entry.aiReflection.takeIf { request.writeAiReflection },
                    )
                    val stored = persistEntry(request, holder.state.baselineUpdatedAt, stagedDraft)
                    if (stored.isFailure) {
                        val failure = stored.exceptionOrNull()
                        if (failure is QueuedOfflineException || failure is ConditionalSaveUnknownException) {
                            holder.markQueuedOffline(date)
                            refreshPendingRecovery(date)
                        } else {
                            holder.markError(failure?.message ?: "Uložení se nezdařilo.", date)
                        }
                        holder.markReflectionError("Nejdřív je potřeba bezpečně uložit dnešní zápis.")
                        return@launch
                    }
                    if (holder.date != date) return@launch
                    if (entries.currentUserId() != owner) {
                        holder.markReflectionError(AiReflectionRepository.MESSAGE_SIGNED_OUT)
                        return@launch
                    }
                    holder.markReflectionStored(date, stored.getOrThrow())
                }

                // Capture the live row identity/revision, not an old dashboard/UI copy.
                val remote = entries.loadRemoteEntrySnapshot(date)
                if (remote.isFailure) {
                    holder.markReflectionError(remote.exceptionOrNull()?.message ?: LOAD_BLOCKED_MESSAGE)
                    return@launch
                }
                val snapshot = remote.getOrNull()
                if (snapshot == null && holder.state.baselineExists != false) {
                    holder.markReflectionError("Serverový zápis už není ověřený. Obnov deník a zkus to znovu.")
                    return@launch
                }
                val promptEntry = snapshot?.entry ?: holder.entry
                val baseline = snapshot?.let {
                    EntryBaseline(true, it.id, it.revision)
                } ?: EntryBaseline(false, null, null)
                val profileName = profile.name?.takeIf { it.isNotBlank() } ?: repo.userName()
                repo.generate(
                    date = date,
                    entry = promptEntry,
                    userName = profileName,
                    birthYear = profile.birthYear,
                    onGenerated = { text ->
                        if (draftStore.ownerId != owner) {
                            throw SessionOwnerMismatchException()
                        }
                        draftStore.stage(
                            date = date,
                            entry = promptEntry.copy(aiReflection = text),
                            baselineUpdatedAt = snapshot?.updatedAt,
                            baselineExists = baseline.exists,
                            baselineId = baseline.id,
                            baselineRevision = baseline.revision,
                            pendingSaveRequestId = null,
                            pendingAiReflection = text,
                        ).await()
                    },
                ).onSuccess { text ->
                    if (holder.date != date) return@onSuccess
                    if (entries.currentUserId() != owner) {
                        holder.markReflectionError(AiReflectionRepository.MESSAGE_SIGNED_OUT)
                        return@onSuccess
                    }
                    holder.setReflection(text, promptEntry, baseline, snapshot?.updatedAt)
                    val request = holder.beginSaveRequest(owner, writeAiReflection = true)
                    if (request == null) {
                        holder.markReflectionError(CONDITIONAL_BASELINE_REQUIRED_MESSAGE)
                        return@onSuccess
                    }
                    val stagedReflection = draftStore.stage(
                        request.date,
                        request.entry,
                        snapshot?.updatedAt,
                        request.baseline.exists,
                        request.baseline.id,
                        request.baseline.revision,
                        request.requestId,
                        request.entry.aiReflection.takeIf { request.writeAiReflection },
                    )
                    persistEntry(request, snapshot?.updatedAt, stagedReflection)
                        .onSuccess { receipt -> holder.markReflectionStored(date, receipt) }
                        .onFailure { failure ->
                            if (failure is QueuedOfflineException || failure is ConditionalSaveUnknownException) {
                                holder.markQueuedOffline(date)
                                refreshPendingRecovery(date)
                            } else {
                                holder.markError(
                                    failure.message ?: "Reflexi se nepodařilo uložit k zápisu.",
                                    date,
                                )
                            }
                            holder.markReflectionError("Reflexi se nepodařilo bezpečně uložit k zápisu.")
                        }
                }.onFailure { failure ->
                    if (holder.date == date) {
                        val generated = failure as? GeneratedReflectionDraftException
                        if (generated != null && entries.currentUserId() == owner) {
                            holder.setReflection(generated.generatedText, promptEntry, baseline, snapshot?.updatedAt)
                        }
                        holder.markReflectionError(failure.message ?: "Reflexi se nepodařilo vygenerovat.")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (holder.date == date) {
                    holder.markError(failure.message ?: "Uložení se nezdařilo.", date)
                    holder.markReflectionError(failure.message ?: "Reflexi se nepodařilo bezpečně uložit.")
                }
            }
        }
    }

    fun uploadPhoto(uri: Uri) {
        if (dateNavigationGuard.get()) return
        val repo = entriesRepo ?: return
        if (holder.state.loadError != null) {
            photoError = LOAD_BLOCKED_MESSAGE
            return
        }
        if (uploadingPhoto || holder.state.saving || holder.state.loading || holder.state.reflectionLoading) return
        // The upload and the path it produces belong to the day that was open when
        // the picker started; a switch during the upload must not attach the photo
        // to the newly shown day.
        val date = holder.date
        val uploadToken = holder.beginPhotoUpload()
        activePhotoUpload = uploadToken
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                PhotoCompressor.compress(context, uri)
                    // A source the decoder cannot handle (HEIC below API 28, a
                    // container BitmapFactory rejects) still goes up untouched when
                    // it is small enough for the bucket — the pre-existing path.
                    ?: runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()?.takeIf { it.size <= PhotoCompressor.MAX_BYTES }
            }
            if (!holder.isPhotoUploadCurrent(uploadToken, date)) {
                if (activePhotoUpload == uploadToken) activePhotoUpload = null
                return@launch
            }
            if (bytes == null) {
                if (activePhotoUpload == uploadToken) activePhotoUpload = null
                photoError = "Fotku se nepodařilo načíst z telefonu."
                return@launch
            }
            val result = repo.uploadPhoto(date, bytes)
            if (activePhotoUpload == uploadToken) activePhotoUpload = null
            if (!holder.isPhotoUploadCurrent(uploadToken, date)) return@launch
            if (result.url != null) {
                photoError = null
                holder.setPhotoPath(result.url)
                // P1 is a distinct staged candidate after P2 succeeds. Reconcile
                // only after the upload and owner-scoped reference read; keep P2.
                repo.cleanupPhotoCandidates(date, preservePaths = setOf(result.url))
            } else {
                photoError = "Fotku se nepodařilo nahrát (${result.error ?: "neznámá chyba"})."
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
            // A save/upload snapshot is taken when the action starts; letting any
            // control (mood, note, scales, date) change underneath it would make the
            // payload disagree with the form. The whole form is frozen instead, and
            // DateNav/PhotoSection also render their own disabled state.
            .consumeInputsWhile(busy)
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

        if (state.loading) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics {
                        contentDescription = translate("Načítám zápisy…")
                    },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Načítám zápisy…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            return@Column
        }

        state.errorMessage?.let { message ->
            ErrorBanner(message)
            Spacer(Modifier.height(10.dp))
        }

        state.loadError?.let { message ->
            ErrorBanner(message)
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (state.draftStoreError != null) {
                    "Místní návrh zatím není uložený. Obsah zůstává zobrazený; zkus to znovu před odchodem."
                } else {
                    "Ukládání je zablokované, dokud se den nenačte."
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(text = "🔄 Zkusit znovu", testTag = "checkin_load_retry") {
                retryDayLoad()
            }
            Spacer(Modifier.height(10.dp))
        }

        draftRecovery?.let { recovery ->
            val canChooseCopy = recovery.remoteError == null && state.loadError == null
            DraftRecoveryPanel(
                recovery = recovery,
                choice = draftRecoveryChoice,
                canChooseCopy = canChooseCopy,
                ownerAttested = recoveredOwnerAttested,
                localReviewed = recoveredLocalReviewed,
                rebaseInProgress = recoveredRebaseInProgress,
                canRebase = canChooseCopy && ownerId != null && entriesRepo != null &&
                    drafts?.ownerId == ownerId && recoveredLocalReviewed &&
                    (recovery.record.legacyRecoveryOrigin == null || recoveredOwnerAttested) &&
                    draftRecoveryChoice == DraftRecoveryChoice.LOCAL && !recoveredRebaseInProgress,
                onOwnerAttestedChanged = { recoveredOwnerAttested = it },
                onLocalReviewedChanged = { recoveredLocalReviewed = it },
                onRebase = { rebaseRecoveredDraft(recovery) },
                onReviewLocal = {
                    if (canChooseCopy) {
                        recoveredOwnerAttested = false
                        recoveredLocalReviewed = false
                        draftRecoveryChoice = DraftRecoveryChoice.LOCAL
                        holder.load(
                            entry = recovery.record.entry,
                            stored = recovery.remote != null && isRecordedDay(recovery.remote.entry.mood),
                            baselineUpdatedAt = recovery.record.baselineUpdatedAt,
                            baselineExists = recovery.record.baselineExists,
                            baselineId = recovery.record.baselineId,
                            baselineRevision = recovery.record.baselineRevision,
                            pendingSaveRequestId = null,
                            pendingAiReflection = recovery.record.pendingAiReflection,
                            editingOverride = true,
                        )
                    }
                },
                onReviewServer = {
                    if (canChooseCopy) {
                        recoveredOwnerAttested = false
                        recoveredLocalReviewed = false
                        draftRecoveryChoice = DraftRecoveryChoice.SERVER
                        val snapshot = recovery.remote
                        holder.load(
                            entry = snapshot?.entry ?: DiaryEntry.EMPTY,
                            stored = snapshot != null && isRecordedDay(snapshot.entry.mood),
                            baselineUpdatedAt = snapshot?.updatedAt,
                            baselineExists = snapshot != null,
                            baselineId = snapshot?.id,
                            baselineRevision = snapshot?.revision,
                            editingOverride = false,
                        )
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        pendingRecovery?.let { recovery ->
            val canChooseCopy = recovery.remoteError == null && state.loadError == null
            PendingRecoveryPanel(
                recovery = recovery,
                choice = pendingRecoveryChoice,
                canChooseCopy = canChooseCopy,
                onShowLocal = {
                    if (canChooseCopy) {
                        pendingRecoveryChoice = PendingRecoveryChoice.LOCAL
                        val request = recovery.record.conditionalRequest
                        holder.load(
                            entry = recovery.record.entry,
                            baselineUpdatedAt = recovery.record.baselineUpdatedAt,
                            baselineExists = request?.baseline?.exists ?: recovery.record.baselineExists,
                            baselineId = request?.baseline?.id ?: recovery.record.baselineId,
                            baselineRevision = request?.baseline?.revision ?: recovery.record.baselineRevision,
                            pendingSaveRequestId = request?.requestId,
                            pendingAiReflection = request?.entry?.aiReflection.takeIf { request?.writeAiReflection == true },
                            queuedOffline = true,
                            editingOverride = true,
                        )
                    }
                },
                onShowServer = {
                    if (canChooseCopy) {
                        pendingRecoveryChoice = PendingRecoveryChoice.SERVER
                        val snapshot = recovery.remote
                        holder.load(
                            entry = snapshot?.entry ?: DiaryEntry.EMPTY,
                            stored = snapshot != null && isRecordedDay(snapshot.entry.mood),
                            baselineUpdatedAt = snapshot?.updatedAt,
                            baselineExists = snapshot != null,
                            baselineId = snapshot?.id,
                            baselineRevision = snapshot?.revision,
                            editingOverride = false,
                        )
                    }
                },
                onRetry = if (recovery.remoteError != null && state.loadError == null) {
                    { retryDayLoad() }
                } else {
                    null
                },
            )
            Spacer(Modifier.height(12.dp))
        }

        pendingRecoveryIssue?.let { issue ->
            PendingSaveRecoveryIssuePanel(issue)
            Spacer(Modifier.height(12.dp))
        }

        moveRecoveryError?.let { message ->
            MoveRecoveryErrorPanel(message)
            Spacer(Modifier.height(48.dp))
            return@Column
        }
        pendingMoveRecovery?.let { request ->
            PendingEntryMovePanel(
                request = request,
                retryEnabled = !moveInProgress && !moveRecoveryLoading && !moveCapabilityLoading &&
                    moveCapability?.available == true,
                retryCapabilityLoading = moveCapabilityLoading,
                retryBlockedReason = moveCapability?.reason
                    ?: "Serverová podpora přesunu zatím nebyla ověřena.",
                onRetry = { retryMove(request) },
                onRefreshCapability = ::refreshMoveCapability,
            )
            Spacer(Modifier.height(48.dp))
            return@Column
        }

        DateNav(
            date = state.date,
            onDateChange = { nextDate ->
                if (nextDate != holder.date && !busy && holder.state.loadError == null &&
                    !holder.state.loading && !holder.state.saving && !currentUploadingPhoto.value &&
                    dateNavigationGuard.compareAndSet(false, true)
                ) {
                    val snapshot = holder.state
                    if (!snapshot.draftDirty) {
                        try {
                            holder.setDate(nextDate)
                        } finally {
                            dateNavigationGuard.set(false)
                        }
                    } else if (holder.beginDateNavigation()) {
                        dateNavigationInProgress = true
                        scope.launch {
                            try {
                                val result = persistDraftBeforeNavigation(snapshot)
                                if (holder.date != snapshot.date) return@launch
                                if (result.isSuccess) {
                                    holder.setDate(nextDate)
                                } else {
                                    holder.markError(
                                        result.exceptionOrNull()?.message ?: DRAFT_NAVIGATION_FAILED_MESSAGE,
                                        snapshot.date,
                                    )
                                }
                            } finally {
                                holder.endDateNavigation()
                                dateNavigationGuard.set(false)
                                dateNavigationInProgress = false
                            }
                        }
                    } else {
                        dateNavigationGuard.set(false)
                    }
                }
            },
            today = today,
            enabled = !busy && state.loadError == null,
        )

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

        if (draftRecovery != null || pendingRecovery != null || pendingRecoveryIssue != null) {
            // Recovery copies are review-only. A form here would invite edits that
            // cannot be safely saved and could obscure which persisted copy is being
            // inspected. Both source records remain untouched.
            Spacer(Modifier.height(48.dp))
            return@Column
        }

        if (state.draftStoreError != null) {
            // Never render an empty editable form when the durable local source itself
            // could not be read. Any in-memory content stays visible, read-only.
            if (state.entry.hasContent()) {
                RecoveryCopyPreview("Návrh zachovaný v tomto telefonu", state.entry)
            }
            Spacer(Modifier.height(48.dp))
            return@Column
        }

        // A stored day reads passively: the check-in tab is where the day is *seen* until
        // the owner asks to change it. An unwritten day opens straight into the form.
        if (state.showsPassiveDay) {
            if (draftRecovery == null) {
                StoredDayView(
                    date = state.date,
                    entry = state.entry,
                    scales = scales,
                    editEnabled = !moveInProgress && !moveRecoveryLoading,
                    moveEnabled = !busy && !state.reflectionLoading &&
                        !moveCapabilityLoading && moveCapability?.available == true && ownerId != null &&
                        pendingMoveRecovery == null && moveRecoveryError == null,
                    moveCapability = moveCapability,
                    moveCapabilityLoading = moveCapabilityLoading,
                    onRefreshMoveCapability = ::refreshMoveCapability,
                    onEdit = { holder.startEditing() },
                    onMoveDate = {
                        moveCandidate = null
                        movePickerOpen = true
                    },
                )
            }
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
            enabled = !busy && !state.loading && state.loadError == null,
            error = photoError,
            onPhotoSelected = { uploadPhoto(it) },
            onRemove = {
                photoError = null
                holder.setPhotoPath(null)
            },
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
            // A day whose load failed or whose save is in flight is not writable.
            canGenerate = state.entry.hasContent() && state.canSave && !uploadingPhoto &&
                draftRecovery == null && pendingRecovery == null,
        )

        if (draftRecovery == null && pendingRecovery == null) {
            PrimaryButton(
                text = if (state.saving) "⏳ Ukládám..." else "✓ Uložit check-in",
                enabled = state.canSave && !uploadingPhoto,
                testTag = "save_button",
            ) { save() }
        }

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

        if (state.queuedOffline && pendingRecovery == null) {
            // The record remains local and reviewable; this status never means it reached the server.
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
                        text = "Nesynchronizováno — uloženo jen v tomto telefonu. Automaticky se neodešle.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }

    // The reflection dialog waits behind the celebration queue: the AI request has
    // already started (the LaunchedEffect above fires on `showReflectionDialog`), so
    // the dialog opens with the text ready — never as a second window above it.
    if (movePickerOpen) {
        MoveDateDialog(
            currentDate = state.date,
            conflictDate = moveCandidate?.targetDate,
            onDismiss = {
                movePickerOpen = false
                moveCandidate = null
            },
            onPickDate = ::beginMove,
            onConfirmOverwrite = {
                moveCandidate?.let { dispatchMoveHandler?.invoke(it, true) }
            },
        )
    }

    if (state.showReflectionDialog && celebrationQueue.isEmpty()) {
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

    // Goal celebration (backlog 29, variant A): one goal at a time, at most three,
    // tap or 2.2 s dismisses. The third card also carries the remainder count.
    celebrationQueue.firstOrNull()?.let { goal ->
        GoalCelebration(
            goal = goal,
            extra = if (celebrationSeen == 2) (celebrationQueue.size - 1).coerceAtLeast(0) else 0,
            onDone = {
                celebrationSeen += 1
                celebrationQueue =
                    if (celebrationSeen >= 3) emptyList() else celebrationQueue.drop(1)
            },
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
    editEnabled: Boolean = true,
    moveEnabled: Boolean,
    moveCapability: EntryMoveCapability?,
    moveCapabilityLoading: Boolean,
    onRefreshMoveCapability: () -> Unit,
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
                enabled = editEnabled,
                testTag = "checkin_edit",
            ) { onEdit() }
            SecondaryButton(
                text = "🗓 Změnit datum",
                enabled = moveEnabled,
                testTag = "checkin_move",
                onClick = onMoveDate,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                moveCapabilityLoading -> "Ověřuji dostupnost serverové metody přesunu…"
                moveCapability != null -> moveCapability.reason
                else -> "Přesun vypnutý: podporu serveru se nepodařilo ověřit."
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Ověření potvrzuje pouze dostupnost serverového RPC; nekoordinuje tento přesun se staršími klienty ani jinými přímými zápisy.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        if (!moveCapabilityLoading && moveCapability?.available != true) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = "🔄 Znovu ověřit podporu serveru",
                modifier = Modifier.fillMaxWidth(),
                enabled = true,
                testTag = "checkin_move_capability_retry",
                onClick = onRefreshMoveCapability,
            )
        }
    }
}

/** A corrupt/unreadable outbox is never treated as empty; writes stay blocked. */
@Composable
private fun MoveRecoveryErrorPanel(message: String) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Text(
            text = "Čekající přesun nelze bezpečně ověřit",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Místní kopie se nemažou a nic se znovu neposílá. Úpravy tohoto dne zůstávají zablokované, dokud nebude možné obnovovací soubor bezpečně přečíst.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        if (message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

/** Review-only local snapshots plus an idempotent retry of the exact durable request. */
@Composable
private fun PendingEntryMovePanel(
    request: EntryMoveRequest,
    retryEnabled: Boolean,
    retryCapabilityLoading: Boolean,
    retryBlockedReason: String,
    onRetry: () -> Unit,
    onRefreshCapability: () -> Unit,
) {
    val source = request.sourceSnapshot.entry
    val target = request.targetSnapshot?.entry
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Text(
            text = "Výsledek přesunu zatím není potvrzený",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Požadavek ${request.requestId} zůstává uložený pro tento účet. Opakování použije stejné ID i stejné verze obou dnů; nevytvoří nový přesun.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Požadavek se nemaže ani neposílá znovu, dokud serverová podpora není ověřena. Výsledek neznámého požadavku nelze bezpečně zrušit z aplikace.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = moveSnapshotSummary("Místní zdroj", request.sourceDate, source),
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (target != null) {
                moveSnapshotSummary("Místní cíl", request.targetDate, target)
            } else {
                "Cíl ${CheckInDates.display(request.targetDate)} (${request.targetDate}): při kontrole nebyl nalezen deníkový řádek."
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Tyto snímky zůstávají zachované až do potvrzeného výsledku. Fotky v úložišti se automaticky nemažou; neověřený objekt se musí ponechat.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        if (!retryEnabled) {
            Text(
                text = if (retryCapabilityLoading) {
                    "Ověřuji dostupnost serverové metody; požadavek zůstává zachovaný."
                } else {
                    retryBlockedReason
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            if (!retryCapabilityLoading) {
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    text = "🔄 Znovu ověřit podporu serveru",
                    modifier = Modifier.fillMaxWidth(),
                    testTag = "checkin_move_capability_retry",
                    onClick = onRefreshCapability,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        SecondaryButton(
            text = when {
                retryEnabled -> "🔄 Zopakovat stejný přesun"
                retryCapabilityLoading -> "⏳ Ověřuji serverovou podporu…"
                else -> "🔒 Opakování přesunu vypnuto"
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = retryEnabled,
            testTag = "checkin_move_retry",
            onClick = onRetry,
        )
    }
}

private fun moveSnapshotSummary(title: String, date: String, entry: DiaryEntry): String {
    val scales = entry.scaleValues.entries
        .sortedBy { it.key }
        .joinToString { "${it.key}: ${it.value}" }
        .ifBlank { "žádné" }
    val note = entry.note?.takeIf { it.isNotBlank() } ?: "bez poznámky"
    val photo = if (entry.photoPath.isNullOrBlank()) "bez fotky" else "fotka zachována"
    return "$title — ${CheckInDates.display(date)} ($date): ${entry.moodEmoji} nálada ${entry.mood}; $note; škály: $scales; $photo."
}

private enum class DraftRecoveryChoice { UNRESOLVED, LOCAL, SERVER }
private enum class PendingRecoveryChoice { LOCAL, SERVER }

private data class DraftRecoveryView(
    val record: DraftRecord,
    val remote: StoredEntrySnapshot?,
    val remoteError: String?,
)

private data class PendingRecoveryView(
    val record: PendingSave,
    val remote: StoredEntrySnapshot?,
    val remoteError: String?,
)

/** Shows both persisted copies and makes comparison a deliberate, non-writing action. */
@Composable
private fun DraftRecoveryPanel(
    recovery: DraftRecoveryView,
    choice: DraftRecoveryChoice,
    canChooseCopy: Boolean,
    ownerAttested: Boolean,
    localReviewed: Boolean,
    rebaseInProgress: Boolean,
    canRebase: Boolean,
    onOwnerAttestedChanged: (Boolean) -> Unit,
    onLocalReviewedChanged: (Boolean) -> Unit,
    onRebase: () -> Unit,
    onReviewLocal: () -> Unit,
    onReviewServer: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
            Text(
                text = if (recovery.remoteError != null) {
                    "Serverovou kopii se nepodařilo načíst"
                } else {
                    "Serverová verze se od zahájení úpravy změnila"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (recovery.remoteError != null) {
                    "Místní návrh zůstává uložený. Nejdřív zkus znovu načíst serverovou kopii."
                } else {
                    "Na tento den jsou dvě verze. Místní návrh i kopie na serveru zůstávají zachované; prohlédni si obě."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            if (recovery.remoteError == null && recovery.remote == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Na serveru pro tento den už žádný záznam není.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Volba pouze zobrazí kopii; nic neodešle a žádnou kopii nesmaže.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            when (choice) {
                DraftRecoveryChoice.LOCAL -> Text(
                    text = "Prohlížíš místní návrh. Kopie na serveru se nezměnila.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                DraftRecoveryChoice.SERVER -> Text(
                    text = "Prohlížíš serverovou verzi. Místní návrh zůstává uložený v telefonu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                DraftRecoveryChoice.UNRESOLVED -> Unit
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (recovery.remoteError != null) {
                    "Ukládání je zablokované, dokud se nepodaří načíst serverovou kopii."
                } else {
                    "Ukládání zůstává zablokované. Prohlédni si obě kopie; výběr nic nepřepíše."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = "Prohlédnout místní návrh",
                modifier = Modifier
                    .fillMaxWidth()
                    .recoveryChoiceSemantics("Prohlédnout místní návrh"),
                enabled = canChooseCopy,
                testTag = "checkin_review_local_draft",
                onClick = onReviewLocal,
            )
            Spacer(Modifier.height(6.dp))
            val serverChoiceLabel = if (recovery.remote == null && recovery.remoteError == null) {
                "Prohlédnout prázdný stav serveru"
            } else {
                "Prohlédnout uložený den"
            }
            SecondaryButton(
                text = serverChoiceLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .recoveryChoiceSemantics(serverChoiceLabel),
                enabled = canChooseCopy,
                testTag = "checkin_review_server",
                onClick = onReviewServer,
            )
            val legacyDraft = recovery.record.legacyRecoveryOrigin != null
            Spacer(Modifier.height(8.dp))
            if (legacyDraft) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = ownerAttested,
                        onCheckedChange = { if (canChooseCopy && !rebaseInProgress) onOwnerAttestedChanged(it) },
                        enabled = canChooseCopy && !rebaseInProgress,
                        modifier = Modifier.testTag("checkin_rebase_owner_attestation"),
                    )
                    Text(
                        "Potvrzuji, že jsem přihlášený vlastník této obnovené kopie.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = localReviewed,
                    onCheckedChange = { if (canChooseCopy && choice == DraftRecoveryChoice.LOCAL && !rebaseInProgress) onLocalReviewedChanged(it) },
                    enabled = canChooseCopy && choice == DraftRecoveryChoice.LOCAL && !rebaseInProgress,
                    modifier = Modifier.testTag("checkin_rebase_confirmation"),
                )
                Text(
                    "Prohlédl(a) jsem místní kopii a chci ji uložit na server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Text(
                "Příprava znovu načte serverovou revizi a nic neodešle. Potom stiskni Uložit. Uložení nahradí serverovou kopii touto místní.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            SecondaryButton(
                text = if (rebaseInProgress) "Ověřuji aktuální serverovou verzi…" else "Připravit místní kopii k uložení",
                modifier = Modifier.fillMaxWidth(),
                enabled = canRebase,
                testTag = "checkin_rebase_recovered_draft",
                onClick = onRebase,
            )
        }
        RecoveryCopyPreview("Návrh v tomto telefonu", recovery.record.entry)
        if (recovery.remote != null) {
            RecoveryCopyPreview("Uložený den na serveru", recovery.remote.entry)
        } else if (recovery.remoteError == null) {
            RecoveryCopyPreview("Uložený den na serveru", DiaryEntry.EMPTY)
        }
    }
}

/** Shows an unsynced local outbox copy and the server copy without dispatching either. */
@Composable
private fun PendingRecoveryPanel(
    recovery: PendingRecoveryView,
    choice: PendingRecoveryChoice,
    canChooseCopy: Boolean,
    onShowLocal: () -> Unit,
    onShowServer: () -> Unit,
    onRetry: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
            Text(
                text = "Nesynchronizovaný zápis v tomto telefonu",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    recovery.record.recoveryReason != null ->
                        "Automatické opakování bylo zastaveno. Místní kopie i serverová verze zůstávají zachované; je nutná kontrola uživatele."
                    recovery.record.isReplayable ->
                        "Bezpečné automatické opakování používá stejný požadavek, původní revizi a účet. Zápis není potvrzený jako synchronizovaný, dokud server nepotvrdí výsledek."
                    else ->
                        "Tento starší záznam nemá ověřenou původní revizi a identitu CAS. Zůstává nedotčený a nebude automaticky odeslán; je nutná kontrola uživatele."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (recovery.remoteError != null) {
                    "Serverovou kopii se nepodařilo načíst. Místní kopie zůstává uložená."
                } else if (recovery.remote == null) {
                    "Na serveru pro tento den není žádný záznam. Výběr nic neodešle ani nesmaže."
                } else {
                    "Vyber, kterou kopii chceš prohlédnout. Výběr nic neodešle ani nesmaže."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            if (choice == PendingRecoveryChoice.LOCAL) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Prohlížíš místní zápis. Kopie na serveru se nezměnila.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            } else if (recovery.remoteError == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Prohlížíš serverovou verzi. Místní zápis zůstává uložený v telefonu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (recovery.remoteError != null) {
                    "Ukládání je zablokované, dokud se nepodaří načíst serverovou kopii."
                } else {
                    "Ukládání zůstává zablokované. Prohlédni si obě kopie; výběr nic nepřepíše."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            onRetry?.let { retry ->
                Spacer(Modifier.height(8.dp))
                SecondaryButton(
                    text = "🔄 Zkusit znovu",
                    testTag = "checkin_pending_retry",
                    onClick = retry,
                )
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                text = "Prohlédnout místní zápis",
                modifier = Modifier
                    .fillMaxWidth()
                    .recoveryChoiceSemantics("Prohlédnout místní zápis"),
                enabled = canChooseCopy,
                testTag = "checkin_pending_show_local",
                onClick = onShowLocal,
            )
            Spacer(Modifier.height(6.dp))
            val serverChoiceLabel = if (recovery.remote == null && recovery.remoteError == null) {
                "Prohlédnout prázdný stav serveru"
            } else {
                "Prohlédnout kopii na serveru"
            }
            SecondaryButton(
                text = serverChoiceLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .recoveryChoiceSemantics(serverChoiceLabel),
                enabled = canChooseCopy,
                testTag = "checkin_pending_show_server",
                onClick = onShowServer,
            )
        }
        RecoveryCopyPreview("Zápis uložený jen v telefonu", recovery.record.entry)
        if (recovery.remote != null) {
            RecoveryCopyPreview("Uložený den na serveru", recovery.remote.entry)
        } else if (recovery.remoteError == null) {
            RecoveryCopyPreview("Uložený den na serveru", DiaryEntry.EMPTY)
        }
    }
}

@Composable
private fun PendingSaveRecoveryIssuePanel(issue: PendingSaveRecoveryIssue) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Text(
            text = "Místní zápis vyžaduje ruční obnovu",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (issue.kind) {
                PendingSaveRecoveryIssueKind.OWNERLESS ->
                    "Starší zápis nemá přiřazený účet. Nebyl přiřazen k tomuto přihlášení ani odeslán; zůstává v telefonu."
                PendingSaveRecoveryIssueKind.MALFORMED ->
                    "Místní záznam nelze bezpečně ověřit. Zůstal nedotčený a automatické odeslání je zakázané."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Den: ${issue.date}. Ukládání je zablokované, dokud kopii nezkontroluješ.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

private fun Modifier.recoveryChoiceSemantics(label: String): Modifier = semantics(mergeDescendants = true) {
    role = Role.Button
    contentDescription = translate(label)
}

@Composable
private fun RecoveryCopyPreview(title: String, entry: DiaryEntry) {
    GlassCard(modifier = Modifier.fillMaxWidth(), accent = Indigo) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        val rows = recoverySummaryRows(entry)
        if (rows.isEmpty()) {
            Text("Záznam neobsahuje žádné údaje.", color = TextSecondary)
        } else {
            rows.forEach { (label, value) ->
                Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                Text(value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

private fun recoverySummaryRows(entry: DiaryEntry): List<Pair<String, String>> = buildList {
    add("Nálada" to if (entry.mood > 0) "${entry.moodEmoji} ${entry.mood}/5".trim() else "—")
    add("Spánek" to entry.sleepQuality.takeIf { it > 0 }?.toString().orEmpty().ifBlank { "—" })
    add("Stres" to entry.stress.takeIf { it > 0 }?.toString().orEmpty().ifBlank { "—" })
    add("Aktivity" to entry.activities.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "—" })
    add(
        "Návyky" to entry.habits.entries.joinToString(", ") { (name, done) ->
            "$name: ${if (done) "Splněno" else "Nesplněno"}"
        }.ifBlank { "—" },
    )
    add("Vděčnost" to entry.filteredGratitude().joinToString(" · ").ifBlank { "—" })
    add("Počasí" to entry.weather.filter { it.isNotBlank() }.joinToString(", ").ifBlank { "—" })
    add("Poznámka" to entry.note.ifBlank { "—" })
    add("Fotka" to if (entry.photoPath.isNullOrBlank()) "Nepřiložena" else "Přiložena")
    if (entry.positiveScaleValues().isNotEmpty()) {
        add("Škály" to entry.positiveScaleValues().entries.joinToString(", ") { (id, value) -> "$id: $value" })
    }
    entry.phoneScreenTimeMinutes?.let { add("Čas na obrazovce" to "$it min") }
    entry.phoneUnlocks?.let { add("Odemknutí" to it.toString()) }
    if (entry.phoneTopApps.isNotEmpty()) {
        add("Nejpoužívanější aplikace" to entry.phoneTopApps.joinToString(", ") { it.app })
    }
    entry.aiReflection?.takeIf { it.isNotBlank() }?.let { add("AI reflexe" to it) }
}

/** A DataStore write is asynchronous, but navigation must remain bounded and cancellable. */
private fun traceDraftRecovery(message: String) {
    runCatching {
        java.io.File("/root/.hermes/profiles/coding/cache/scratch/checkin-trace.txt")
            .appendText("$message\\n")
    }
}

private const val DRAFT_NAVIGATION_TIMEOUT_MS = 3_000L
private const val DRAFT_NAVIGATION_TIMEOUT_MESSAGE =
    "Uložení místního návrhu trvalo příliš dlouho. Změna data byla zrušena — zkus to znovu."
private const val DRAFT_NAVIGATION_FAILED_MESSAGE =
    "Místní návrh se nepodařilo uložit. Změna data byla zrušena — zkus to znovu."

/** Shown when the reflection flow is asked to run while the AI master switch is off. */
private const val AI_DISABLED_MESSAGE = "AI je vypnutá — zapneš ji v Nastavení."

private const val DRAFT_STORE_READ_FAILED_MESSAGE = "Načtení se nezdařilo"
private const val DRAFT_STORE_WRITE_FAILED_MESSAGE = "Uložení se nezdařilo."

/** A server read is required before the server copy can be reviewed. */
private const val SERVER_UNAVAILABLE_MESSAGE = "Serverovou kopii se nepodařilo načíst."

/** A conflict never overwrites either copy; review is explicit and non-writing. */
private const val DRAFT_CONFLICT_BLOCKED_MESSAGE =
    "Ukládání zůstává zablokované. Prohlédni si obě kopie; výběr nic nepřepíše."

private const val CONDITIONAL_BASELINE_REQUIRED_MESSAGE =
    "Verzi serveru se nepodařilo bezpečně ověřit. Návrh zůstává v telefonu; zkus den znovu načíst."

/** Fallback sentence for a read that failed without a message of its own. */
private const val LOAD_FAILED_MESSAGE = "Zápis se nepodařilo načíst."

/**
 * Refusal shown by every write path while the stored day is unknown. Deliberately
 * actionable: the retry button is the only way out, because writing now would
 * overwrite a day we could not read.
 */
private const val LOAD_BLOCKED_MESSAGE =
    "Den se nepodařilo načíst — zkus to znovu, než budeš ukládat."

/**
 * Freezes every pointer target under this modifier while a day is loading, a save or
 * photo upload is in flight, or a date change is waiting on its local draft. The form
 * must not accept input that a pending server read could replace.
 *
 * A parent consuming events in the initial pass is the one place that covers every
 * section without adding an `enabled` parameter to nine separate composables.
 */
private fun Modifier.consumeInputsWhile(blocked: Boolean): Modifier =
    if (!blocked) {
        this
    } else {
        pointerInput(blocked) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
    }
