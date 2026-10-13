package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The owner's on-device profile.
 *
 * It carries what makes the AI personal and what turns it off:
 *  - [name] and [birthYear] travel with the reflection prompt so the model can speak
 *    to its owner (both optional — the prompt falls back to the session name);
 *  - [aiEnabled] is the app-wide AI master switch: with it off nothing is generated,
 *    neither reflections nor reports, and the DeepSeek key simply stays stored;
 *  - [onboardingDone] records that the first-run guide was walked through, so
 *    "Spustit průvodce znovu" (Nastavení → Profil) can flip it back.
 *
 * Like goals and drafts, the profile lives only in this phone (Preferences
 * DataStore) — nothing here is ever synced.
 */
data class ProfileData(
    val name: String = "",
    val birthYear: Int? = null,
    /** Default ON: a fresh install generates reflections/reports like it always did. */
    val aiEnabled: Boolean = true,
    val onboardingDone: Boolean = false,
)

/**
 * Profile persistence over Preferences DataStore — the native twin of the web's
 * profile keys, and the single place the AI master switch lives.
 */
class ProfileStore private constructor(private val dataStore: DataStore<Preferences>) {

    /** The profile as a stream, for screens that need to react to a change. */
    val data: Flow<ProfileData> = dataStore.data.map { decode(it) }

    /** The one-shot read every generation path does before it acts. */
    suspend fun load(): ProfileData = decode(dataStore.data.first())

    /** The name is stored trimmed; a blank name is stored as an empty string. */
    suspend fun saveName(name: String) {
        dataStore.edit { prefs -> prefs[KEY_NAME] = name.trim() }
    }

    /** `null` (or a blank field) clears the birth year. */
    suspend fun saveBirthYear(year: Int?) {
        dataStore.edit { prefs ->
            if (year == null) prefs.remove(KEY_BIRTH_YEAR) else prefs[KEY_BIRTH_YEAR] = year
        }
    }

    suspend fun setAiEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_AI_ENABLED] = enabled }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ONBOARDING_DONE] = done }
    }

    companion object {

        fun from(context: Context): ProfileStore = ProfileStore(context.profileDataStore)

        /**
         * A store over any [DataStore] — the test/preview seam. The app itself only
         * ever builds the store through [from]; this exists so a JVM test can drive
         * the real file-backed store from a temporary file (see ProfileStoreTest),
         * exactly the way DraftStoreTest does.
         */
        internal fun forDataStore(dataStore: DataStore<Preferences>): ProfileStore =
            ProfileStore(dataStore)

        /** The defaults for keys the file has never carried (or lost). */
        fun decode(prefs: Preferences): ProfileData = ProfileData(
            name = prefs[KEY_NAME].orEmpty().trim(),
            birthYear = prefs[KEY_BIRTH_YEAR],
            aiEnabled = prefs[KEY_AI_ENABLED] ?: true,
            onboardingDone = prefs[KEY_ONBOARDING_DONE] ?: false,
        )
    }
}

private val KEY_NAME = stringPreferencesKey("user_name")
private val KEY_BIRTH_YEAR = intPreferencesKey("birth_year")
private val KEY_AI_ENABLED = booleanPreferencesKey("ai_enabled")
private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")

private val Context.profileDataStore by preferencesDataStore(name = "diarium_profile")
