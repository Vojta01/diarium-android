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
 *  - [aiEnabled] is this account's explicit AI-consent switch: with it off nothing is
 *    generated, neither reflections nor reports, and the DeepSeek key simply stays stored;
 *  - [onboardingDone] records that the first-run guide was walked through, so
 *    "Spustit průvodce znovu" (Nastavení → Profil) can flip it back.
 *
 * Like goals and drafts, the profile is stored on this phone (Preferences
 * DataStore), not synchronized as profile data. Name and birth year are included
 * in AI prompts when reflections or reports are generated.
 */
data class ProfileData(
    val name: String = "",
    val birthYear: Int? = null,
    /** Off until this account has explicitly opted in to AI processing. */
    val aiEnabled: Boolean = false,
    val onboardingDone: Boolean = false,
    /** Ownerless values from the pre-scoping profile file are kept but never assigned. */
    val legacyDataQuarantined: Boolean = false,
)

/**
 * Profile persistence over Preferences DataStore. Every account reads and writes
 * only keys in its own namespace; ownerless builds are read-only defaults.
 */
data class LegacyProfileValues(val name: String?, val birthYear: Int?)

class ProfileStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val ownerId: String?,
) {

    val data: Flow<ProfileData> = dataStore.data.map { decode(it, ownerId) }

    suspend fun load(): ProfileData = decode(dataStore.data.first(), ownerId)

    /** Names only: legacy profile values never leave this store. */
    internal suspend fun legacyRecoveryPreferenceNames(): Set<String> =
        dataStore.data.first().asMap().keys
            .asSequence()
            .map { it.name }
            .filter { it in LEGACY_KEY_NAMES }
            .toSet()

    /** Legacy ownerless values, read only for an explicit one-time adoption. */
    suspend fun legacyProfileValues(): LegacyProfileValues {
        val prefs = dataStore.data.first()
        return LegacyProfileValues(
            name = prefs[stringPreferencesKey("user_name")]?.trim()?.takeIf { it.isNotBlank() },
            birthYear = prefs[intPreferencesKey("birth_year")],
        )
    }

    /**
     * Copies legacy ownerless name and birth year into this account. It fills
     * only empty fields, never clears anything and never adopts AI consent or
     * the guide flag: consent must be given again by the signed-in account.
     * Returns how many profile fields were written.
     */
    suspend fun adoptLegacyProfile(): Int {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return 0
        val prefs = dataStore.data.first()
        val legacyName = prefs[stringPreferencesKey("user_name")]?.trim()?.takeIf { it.isNotBlank() }
        val legacyYear = prefs[intPreferencesKey("birth_year")]
        val nameKey = stringPreferencesKey(key(owner, "name"))
        val yearKey = intPreferencesKey(key(owner, "birth_year"))
        val writeName = prefs[nameKey].isNullOrBlank() && legacyName != null
        val writeYear = prefs[yearKey] == null && legacyYear != null
        if (!writeName && !writeYear) return 0
        dataStore.edit { mutable ->
            if (writeName) mutable[nameKey] = legacyName!!
            if (writeYear) mutable[yearKey] = legacyYear!!
        }
        return (if (writeName) 1 else 0) + (if (writeYear) 1 else 0)
    }

    suspend fun saveName(name: String) {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return
        dataStore.edit { prefs -> prefs[stringPreferencesKey(key(owner, "name"))] = name.trim() }
    }

    suspend fun saveBirthYear(year: Int?) {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return
        val key = intPreferencesKey(key(owner, "birth_year"))
        dataStore.edit { prefs ->
            if (year == null) prefs.remove(key) else prefs[key] = year
        }
    }

    suspend fun setAiEnabled(enabled: Boolean) {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return
        dataStore.edit { prefs -> prefs[booleanPreferencesKey(key(owner, "ai_enabled"))] = enabled }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        val owner = ownerId?.takeIf { it.isNotBlank() } ?: return
        dataStore.edit { prefs -> prefs[booleanPreferencesKey(key(owner, "onboarding_done"))] = done }
    }

    companion object {
        fun from(context: Context, ownerId: String?): ProfileStore =
            ProfileStore(context.profileDataStore, ownerId?.takeIf { it.isNotBlank() })

        /** Test/preview seam over a real or in-memory Preferences DataStore. */
        internal fun forDataStore(
            dataStore: DataStore<Preferences>,
            ownerId: String?,
        ): ProfileStore = ProfileStore(dataStore, ownerId?.takeIf { it.isNotBlank() })

        fun decode(prefs: Preferences, ownerId: String?): ProfileData {
            val owner = ownerId?.takeIf { it.isNotBlank() }
            val legacyPresent = LEGACY_KEYS.any { prefs[it] != null }
            if (owner == null) return ProfileData(legacyDataQuarantined = legacyPresent)
            return ProfileData(
                name = prefs[stringPreferencesKey(key(owner, "name"))].orEmpty().trim(),
                birthYear = prefs[intPreferencesKey(key(owner, "birth_year"))],
                aiEnabled = prefs[booleanPreferencesKey(key(owner, "ai_enabled"))] ?: false,
                onboardingDone = prefs[booleanPreferencesKey(key(owner, "onboarding_done"))] ?: false,
                legacyDataQuarantined = legacyPresent,
            )
        }

        private fun key(owner: String, field: String): String = "profile_v2_${owner}_$field"

        private val LEGACY_KEYS = listOf(
            stringPreferencesKey("user_name"),
            intPreferencesKey("birth_year"),
            booleanPreferencesKey("ai_enabled"),
            booleanPreferencesKey("onboarding_done"),
        )

        private val LEGACY_KEY_NAMES = LEGACY_KEYS.mapTo(mutableSetOf()) { it.name }
    }
}

private val Context.profileDataStore by preferencesDataStore(name = "diarium_profile")
