package cz.digitalnivedomi.diarium.ui.checkin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import java.time.LocalDate

/**
 * Everything the check-in form renders from.
 *
 * `saved` flips true after a successful RPC and is what the draft autosave keys
 * off (no draft is written for an already-saved day).
 *
 * The `reflection*` trio belongs to the AI section at the bottom of the form:
 * `reflection` is the server's Czech text (seeded from the stored row, never
 * edited here), `reflectionLoading` drives the spinner and `reflectionError`
 * carries the sentence for the last failed generation.
 */
data class CheckInUiState(
    val date: String,
    val entry: DiaryEntry = DiaryEntry.EMPTY,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
    /** True only after a user edit since the last server/local recovery load. */
    val draftDirty: Boolean = false,
    /**
     * True when a save is in the local outbox. It is not [saved] and is never
     * presented as server-synced; the worker retains it for explicit recovery.
     */
    val queuedOffline: Boolean = false,
    /** The server revision captured when this edit began; null means unverified. */
    val baselineUpdatedAt: String? = null,
    /** Whether a server row existed when this edit began; null means unverified. */
    val baselineExists: Boolean? = null,
    val errorMessage: String? = null,
    /** A local draft read/write failed; retry before treating this day as editable. */
    val draftStoreError: String? = null,
    /**
     * The stored day could not be read (offline, HTTP error, bad body). The form may
     * still show a draft, but nothing may be written back: saving an entry built
     * from an unread day is exactly how a network blip used to erase the stored
     * mood, stress and arrays. Cleared only by a successful [CheckInStateHolder.load].
     */
    val loadError: String? = null,
    /**
     * True when the open day came from the database — the day already has an entry —
     * and false for a day that only exists as a draft or not at all. A stored day opens
     * as the passive view; a day nobody has written yet opens as the form.
     */
    val storedEntry: Boolean = false,
    /**
     * True while the form is open for editing. Entering a stored day does not edit it
     * by itself; the passive view's "Upravit" flips this on, and a day switch flips it
     * back off. A day requested from another screen arrives through
     * [CheckInStateHolder.requestEdit], so its load starts in the form.
     */
    val editing: Boolean = false,
    /**
     * True once the check-in is finished: the save landed and the reflection window
     * that save opened has been closed. The host navigates home on it — a check-in ends
     * on the overview, not on the form — and clears it through [CheckInStateHolder.consumeFinished].
     */
    val finished: Boolean = false,
    /**
     * True when the form stepped back to yesterday on its own (the owner writes the day
     * up after midnight). The screen says so, because a silent date change would look
     * like a bug.
     */
    val lateNightHint: Boolean = false,
    val reflection: String? = null,
    val reflectionLoading: Boolean = false,
    val reflectionError: String? = null,
    /**
     * The day's reflection window. [CheckInStateHolder.markSaved] opens it exactly
     * once per successful save; only the user's "Zavřít" (or a day switch) closes
     * it. Nothing in the render path touches it, so recomposition, rotation or
     * coming back to the tab can never re-open it on its own.
     */
    val showReflectionDialog: Boolean = false,
) {
    val hasContent: Boolean get() = entry.hasContent()

    /**
     * Nothing may be written while a save is in flight, the selected server day could
     * not be read, or the local draft store itself failed. The screen uses this for
     * save and AI/photo actions; the holder's [CheckInStateHolder.beginSave] also guards.
     */
    val canSave: Boolean get() = !saving && !loading && loadError == null && draftStoreError == null

    /**
     * True when the day must be shown passively: it is stored and the user has not
     * asked to edit it. The check-in tab then reads like the history tab's day card
     * with an "Upravit" action instead of an editable form.
     */
    val showsPassiveDay: Boolean get() = storedEntry && !editing

    /**
     * True when the open window still has to ask the server: no text seeded from
     * the row and no request already in flight. A day that already carries an
     * `aiReflection` (seeded by [CheckInStateHolder.load]) reports false, so
     * opening its window costs no network call.
     */
    val reflectionNeedsRequest: Boolean get() = reflection.isNullOrBlank() && !reflectionLoading
}

/**
 * Plain state holder for the check-in form — no ViewModel, no Android
 * dependency, so the whole form can be driven and asserted from a JVM test.
 *
 * Every mutator is named after a user gesture (`selectMood`, `toggleActivity`…)
 * and goes through [update], which clears the "saved" flag so a change after a
 * save re-arms the draft autosave.
 */
class CheckInStateHolder(
    initialDate: String = LocalDate.now().toString(),
    initiallyLoading: Boolean = false,
) {

    var state: CheckInUiState by mutableStateOf(
        CheckInUiState(date = initialDate, loading = initiallyLoading),
    )
        private set

    /** Upload callbacks are valid only for the latest upload on the current day. */
    private var photoUploadGeneration = 0L
    /** Blocks stale UI callbacks while a date switch waits for its draft transaction. */
    private var dateNavigationLocked = false

    val date: String get() = state.date
    val entry: DiaryEntry get() = state.entry

    /**
     * A one-shot "open the form" request for the next [load]: the screen asks for it
     * when the check-in tab is entered for a specific day from another screen, and
     * [load] consumes it. Deliberately not part of [CheckInUiState] — nothing renders
     * from it, and it must not outlive the load it belongs to.
     */
    private var pendingEdit = false

    /**
     * Asks the next [load] to open the day in the form rather than the passive view.
     * One-shot: [load] consumes it, and [setDate] clears it, so a day switch without
     * a fresh request never edits.
     */
    fun requestEdit() {
        pendingEdit = true
    }

    /** Blocks stale form callbacks while a date navigation flush is in flight. */
    fun beginDateNavigation(): Boolean {
        if (dateNavigationLocked || state.saving || state.loading) return false
        dateNavigationLocked = true
        return true
    }

    fun endDateNavigation() {
        dateNavigationLocked = false
    }

    /** Switching day clears the form and locks it until the screen loads that day. */
    fun setDate(date: String) {
        invalidatePhotoUploads()
        // The flag belongs to one requested arrival only — a day switch without a
        // fresh [requestEdit] must never open the form on its own.
        pendingEdit = false
        state = state.copy(
            date = date,
            entry = DiaryEntry.EMPTY,
            loading = true,
            saving = false,
            saved = false,
            draftDirty = false,
            queuedOffline = false,
            baselineUpdatedAt = null,
            baselineExists = null,
            errorMessage = null,
            loadError = null,
            draftStoreError = null,
            // A day is opened passively (or as a fresh form) whichever day it is; the
            // "Upravit" flag and the finished/hint flags never survive a day switch.
            storedEntry = false,
            editing = false,
            finished = false,
            lateNightHint = false,
            reflection = null,
            reflectionLoading = false,
            reflectionError = null,
            showReflectionDialog = false,
        )
    }

    /**
     * Steps back to yesterday, right after midnight: the day the owner is still writing
     * up is the one that just ended, and the app used to file the whole check-in under
     * the new date. Called only when today's form is untouched, and only once per visit.
     */
    fun startLateNight() {
        setDate(LocalDate.now().minusDays(1).toString())
        state = state.copy(lateNightHint = true)
    }

    /** Opens the form on the day being shown ("Upravit" on the passive view). */
    fun startEditing() {
        if (state.loading || state.saving || dateNavigationLocked) return
        state = state.copy(editing = true, saved = false)
    }

    /**
     * True exactly once per finished check-in: the host reads it, navigates home and
     * clears it here, so a recomposition or a later return cannot navigate again.
     */
    fun consumeFinished(): Boolean {
        if (!state.finished) return false
        state = state.copy(finished = false)
        return true
    }

    fun load(
        entry: DiaryEntry,
        stored: Boolean = false,
        baselineUpdatedAt: String? = null,
        baselineExists: Boolean? = stored,
        queuedOffline: Boolean = false,
        editingOverride: Boolean? = null,
    ) {
        invalidatePhotoUploads()
        state = state.copy(
            entry = entry,
            loading = false,
            saved = false,
            draftDirty = false,
            queuedOffline = queuedOffline,
            baselineUpdatedAt = baselineUpdatedAt,
            baselineExists = baselineExists,
            loadError = null,
            draftStoreError = null,
            storedEntry = stored,
            // A requested arrival (see [requestEdit]) opens the stored day straight
            // in the form; an ordinary (re)load stays passive.
            editing = editingOverride ?: pendingEdit,
            // The day already has a reflection when the row carried one — showing
            // it straight away is what the web does on load.
            reflection = entry.aiReflection,
            reflectionLoading = false,
            reflectionError = null,
            // A freshly loaded (or restored) row is not a save, so it must not
            // pop the reflection window back up.
            showReflectionDialog = false,
        )
        // Consumed — one requested arrival means exactly one editing session.
        pendingEdit = false
    }

    /**
     * The stored day could not be read. A draft (if one exists) is still restored so
     * the user's in-progress text is not thrown away, but the day is marked
     * unsavable: [CheckInUiState.canSave] is false until a [load] succeeds, so a
     * failed read can never be followed by an empty write over the real row.
     *
     * A day that is not stored is unaffected by the failure in the sense that there
     * is nothing to overwrite, but we cannot know that while the read fails — the
     * screen offers retry instead of guessing.
     */
    fun markLoadFailed(
        message: String,
        draft: DiaryEntry? = null,
        queuedOffline: Boolean = false,
        baselineUpdatedAt: String? = null,
        baselineExists: Boolean? = null,
    ) {
        invalidatePhotoUploads()
        state = state.copy(
            entry = draft ?: DiaryEntry.EMPTY,
            loading = false,
            saving = false,
            saved = false,
            queuedOffline = queuedOffline,
            baselineUpdatedAt = baselineUpdatedAt,
            baselineExists = baselineExists,
            errorMessage = null,
            loadError = message,
            draftStoreError = null,
            storedEntry = false,
            editing = true,
            reflection = draft?.aiReflection,
            reflectionLoading = false,
            reflectionError = null,
            showReflectionDialog = false,
        )
        pendingEdit = false
    }

    /**
     * A local draft could not be read or safely written. Keep the current in-memory
     * entry and dirty flag intact; a retry must not reinterpret this as an empty day.
     */
    fun markDraftStoreFailed(message: String) {
        state = state.copy(
            loading = false,
            saving = false,
            saved = false,
            errorMessage = null,
            loadError = message,
            draftStoreError = message,
        )
    }

    fun setLoading(loading: Boolean) {
        // Preserve the failure through a retry. The load effect uses it to snapshot
        // offline edits before a response can replace the form; only [load] clears it.
        state = state.copy(loading = loading)
    }

    fun selectMood(value: Int, emoji: String) {
        update(state.entry.copy(mood = value, moodEmoji = emoji))
    }

    fun setSleep(value: Int) {
        update(state.entry.copy(sleepQuality = value))
    }

    fun setStress(value: Int) {
        update(state.entry.copy(stress = value))
    }

    fun toggleActivity(label: String) {
        val current = state.entry.activities
        update(state.entry.copy(activities = if (label in current) current - label else current + label))
    }

    fun setActivities(labels: List<String>) {
        update(state.entry.copy(activities = labels))
    }

    fun toggleHabit(key: String) {
        val habits = state.entry.habits
        update(state.entry.copy(habits = habits + (key to !(habits[key] ?: false))))
    }

    fun setGratitude(index: Int, text: String) {
        val slots = state.entry.gratitude.toMutableList()
        while (slots.size <= index) slots.add("")
        slots[index] = text
        update(state.entry.copy(gratitude = slots.toList()))
    }

    fun setNote(text: String) {
        update(state.entry.copy(note = text))
    }

    /**
     * Sets or removes the day's photo. Passing null records an explicit
     * [DiaryEntry.clearPhoto] intent only when the day actually had a photo — an
     * ordinary edit of a day without one must never send a clearing PATCH. Picking a
     * new photo drops the intent again.
     */
    fun setPhotoPath(path: String?) {
        invalidatePhotoUploads()
        val clean = path?.takeIf { it.isNotBlank() }
        val entry = state.entry
        val pathToDelete = entry.photoPathToDelete
            ?: entry.photoPath?.takeIf { it != clean }
        update(
            if (clean == null) {
                entry.copy(
                    photoPath = null,
                    clearPhoto = pathToDelete != null || entry.clearPhoto,
                    photoPathToDelete = pathToDelete,
                )
            } else {
                entry.copy(
                    photoPath = clean,
                    clearPhoto = false,
                    photoPathToDelete = pathToDelete,
                )
            },
        )
    }

    /** Starts an upload and returns its stale-callback guard. */
    fun beginPhotoUpload(): Long {
        photoUploadGeneration += 1
        return photoUploadGeneration
    }

    /** True only while [token] still belongs to the day and upload that started it. */
    fun isPhotoUploadCurrent(token: Long, expectedDate: String): Boolean =
        token == photoUploadGeneration && expectedDate == state.date

    private fun invalidatePhotoUploads() {
        photoUploadGeneration += 1
    }

    fun toggleWeather(key: String) {
        val weather = state.entry.weather
        update(state.entry.copy(weather = if (key in weather) weather - key else weather + key))
    }

    /**
     * Sets a scale value; 0 clears it. Clearing records the scale id in
     * [DiaryEntry.clearedScaleIds] so the save can delete the mirrored
     * `scale_entries` row — dropping it from `scale_values` alone would leave the
     * day in that scale's statistics. Only a scale that actually had a value gets
     * the intent, so touching an untouched slider at 0 can never delete anything.
     */
    fun setScale(scaleId: String, value: Int) {
        val scales = state.entry.scaleValues
        val cleared = state.entry.clearedScaleIds
        update(
            if (value <= 0) {
                state.entry.copy(
                    scaleValues = scales - scaleId,
                    clearedScaleIds = if (scaleId in scales) cleared + scaleId else cleared,
                )
            } else {
                state.entry.copy(
                    scaleValues = scales + (scaleId to value),
                    clearedScaleIds = cleared - scaleId,
                )
            },
        )
    }

    /**
     * Starts a save. Returns false when one is already in flight, which is what
     * makes a double tap on "Uložit" a no-op instead of two RPC writes.
     */
    fun beginSave(): Boolean {
        if (state.saving || state.loading || state.loadError != null || dateNavigationLocked) return false
        invalidatePhotoUploads()
        state = state.copy(saving = true, queuedOffline = false, errorMessage = null)
        return true
    }

    /** Kept for callers that only need the flag; [beginSave] is the guarded form. */
    fun markSaving() {
        beginSave()
    }

    /**
     * The save landed: the day flips to its read-only state and the reflection
     * window opens itself. This is deliberately the *only* transition that turns
     * [CheckInUiState.showReflectionDialog] on, which is what makes it exactly
     * once per save — and why a recomposition, a rotation or a later return to
     * the tab (a fresh holder) cannot bring it back.
     *
     * [expectedDate] pins the result to the day the save was started for: a save
     * that finishes after the user switched days must not mark the new day saved,
     * open its reflection window or clear its "Upravit" state.
     */
    fun markSaved(expectedDate: String? = null) {
        if (expectedDate != null && expectedDate != state.date) return
        state = state.copy(
            entry = state.entry.copy(clearPhoto = false, photoPathToDelete = null),
            saving = false,
            saved = true,
            draftDirty = false,
            queuedOffline = false,
            baselineUpdatedAt = null,
            baselineExists = true,
            errorMessage = null,
            showReflectionDialog = true,
        )
    }

    /**
     * The save is local only. The outbox retains it for user-directed recovery; it
     * must not be treated as remotely saved or assumed to replay automatically.
     */
    fun markQueuedOffline(expectedDate: String? = null) {
        if (expectedDate != null && expectedDate != state.date) return
        state = state.copy(saving = false, saved = false, draftDirty = false, queuedOffline = true, errorMessage = null)
    }

    /** The user closed the reflection window; another save is what brings it back. */
    fun dismissReflectionDialog() {
        // The window only ever opens on a save (see [markSaved]), so closing it means
        // the check-in is done: the host takes the owner back to the overview.
        state = state.copy(showReflectionDialog = false, finished = true)
    }

    /**
     * The AI flow's own second save (writing the generated text back to the day).
     * It marks the day stored like [markSaved] but never opens the window: a
     * regenerate from inside the dialog must not resurrect a window the user
     * already dismissed, and a manual "Napsat reflexi" should not pop it open.
     */
    fun markReflectionStored(expectedDate: String? = null) {
        if (expectedDate != null && expectedDate != state.date) return
        state = state.copy(
            entry = state.entry.copy(clearPhoto = false, photoPathToDelete = null),
            saving = false,
            saved = true,
            draftDirty = false,
            baselineUpdatedAt = null,
            baselineExists = true,
            errorMessage = null,
        )
    }

    fun markError(message: String?, expectedDate: String? = null) {
        if (expectedDate != null && expectedDate != state.date) return
        state = state.copy(saving = false, errorMessage = message)
    }

    /** The AI request is in flight; a previous failure is dropped (one state at a time). */
    fun markReflectionLoading() {
        state = state.copy(reflectionLoading = true, reflectionError = null)
    }

    /**
     * The AI text arrived. `saved` is cleared on purpose: the reflection is not in
     * the database yet, the screen saves the entry again right after this call,
     * and until that second save lands the day is genuinely not fully stored.
     */
    fun setReflection(text: String) {
        state = state.copy(
            reflection = text,
            reflectionLoading = false,
            reflectionError = null,
            saved = false,
            draftDirty = true,
        )
    }

    /**
     * Generation or storage failed. Text already on screen survives — it stays
     * readable, the message only explains why it may not have been stored.
     */
    fun markReflectionError(message: String?) {
        state = state.copy(reflectionLoading = false, reflectionError = message)
    }

    private fun update(entry: DiaryEntry) {
        // The save snapshot is taken before the RPC goes out; letting a control edit
        // the model mid-flight would make the payload and the visible form disagree.
        if (state.saving || state.loading || dateNavigationLocked) return
        state = state.copy(entry = entry, saved = false, draftDirty = true, queuedOffline = false)
    }
}
