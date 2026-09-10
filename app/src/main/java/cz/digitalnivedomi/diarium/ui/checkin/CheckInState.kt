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
    val reflection: String? = null,
    val reflectionLoading: Boolean = false,
    val reflectionError: String? = null,
) {
    val hasContent: Boolean get() = entry.hasContent()
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
            saved = false,
            errorMessage = null,
            reflection = null,
            reflectionLoading = false,
            reflectionError = null,
        )
    }

    fun load(entry: DiaryEntry) {
        state = state.copy(
            entry = entry,
            loading = false,
            saved = false,
            // The day already has a reflection when the row carried one — showing
            // it straight away is what the web does on load.
            reflection = entry.aiReflection,
            reflectionLoading = false,
            reflectionError = null,
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

    fun markSaved() {
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
