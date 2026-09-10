package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Per-date drafts — the native twin of the web's `diarium_draft_{date}`
 * localStorage keys.
 *
 * Semantics (identical to the web):
 *  - autosaved while editing (the screen debounces), and
 *  - restored only when the database has no entry for that date, and
 *  - cleared after a successful save.
 *
 * Backed by Preferences DataStore, so a draft survives process death without
 * touching SharedPreferences or the auth store.
 */
class DraftStore(private val dataStore: DataStore<Preferences>) {

    suspend fun save(date: String, entry: DiaryEntry) {
        val key = stringPreferencesKey(key(date))
        dataStore.edit { prefs -> prefs[key] = entry.toJson().toString() }
    }

    suspend fun load(date: String): DiaryEntry? {
        val raw = dataStore.data.first()[stringPreferencesKey(key(date))] ?: return null
        return DiaryEntry.fromJson(raw)
    }

    suspend fun clear(date: String) {
        val key = stringPreferencesKey(key(date))
        dataStore.edit { prefs -> prefs.remove(key) }
    }

    companion object {
        /** Same key format the web persisted, so the naming stays recognisable. */
        fun key(date: String): String = "diarium_draft_$date"

        fun from(context: Context): DraftStore = DraftStore(context.draftDataStore)
    }
}

private val Context.draftDataStore by preferencesDataStore(name = "diarium_drafts")
