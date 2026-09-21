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
    val errorMessage: String? = null,
    /**
     * True when the open day came from the database — the day already has an entry —
     * and false for a day that only exists as a draft or not at all. A stored day opens
     * as the passive view; a day nobody has written yet opens as the form.
     */
    val storedEntry: Boolean = false,
    /**
     * True while the form is open for editing. Entering a stored day does not edit it;
     * the passive view's "Upravit" flips this on, and a day switch flips it back off.
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
class CheckInStateHolder(initialDate: String = LocalDate.now().toString()) {

    var state: CheckInUiState by mutableStateOf(CheckInUiState(date = initialDate))
        private set

    val date: String get() = state.date
    val entry: DiaryEntry get() = state.entry

    /** Switching day clears the form; the screen reloads entry/draft for it. */
    fun setDate(date: String) {
        state = state.copy(
            date = date,
            entry = DiaryEntry.EMPTY,
            saving = false,
            saved = false,
            errorMessage = null,
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

    fun load(entry: DiaryEntry, stored: Boolean = false) {
        state = state.copy(
            entry = entry,
            loading = false,
            saved = false,
            storedEntry = stored,
            editing = false,
            // The day already has a reflection when the row carried one — showing
            // it straight away is what the web does on load.
            reflection = entry.aiReflection,
            reflectionLoading = false,
            reflectionError = null,
            // A freshly loaded (or restored) row is not a save, so it must not
            // pop the reflection window back up.
            showReflectionDialog = false,
        )
    }

    fun setLoading(loading: Boolean) {
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

    fun setPhotoPath(path: String?) {
        update(state.entry.copy(photoPath = path))
    }

    fun toggleWeather(key: String) {
        val weather = state.entry.weather
        update(state.entry.copy(weather = if (key in weather) weather - key else weather + key))
    }

    fun setScale(scaleId: String, value: Int) {
        val scales = state.entry.scaleValues
        update(
            state.entry.copy(
                scaleValues = if (value <= 0) scales - scaleId else scales + (scaleId to value),
            ),
        )
    }

    fun markSaving() {
        state = state.copy(saving = true, errorMessage = null)
    }

    /**
     * The save landed: the day flips to its read-only state and the reflection
     * window opens itself. This is deliberately the *only* transition that turns
     * [CheckInUiState.showReflectionDialog] on, which is what makes it exactly
     * once per save — and why a recomposition, a rotation or a later return to
     * the tab (a fresh holder) cannot bring it back.
     */
    fun markSaved() {
        state = state.copy(
            saving = false,
            saved = true,
            errorMessage = null,
            showReflectionDialog = true,
        )
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
    fun markReflectionStored() {
        state = state.copy(saving = false, saved = true, errorMessage = null)
    }

    fun markError(message: String?) {
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
        state = state.copy(entry = entry, saved = false)
    }
}
