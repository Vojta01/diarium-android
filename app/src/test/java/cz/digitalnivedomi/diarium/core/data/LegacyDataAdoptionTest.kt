package cz.digitalnivedomi.diarium.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Adoption of ownerless beta5 profile values and the beta5 AI key into the
 * account that is signed in now. The tests pin the safety rules: fill only
 * empty fields, never adopt AI consent, never cross accounts, never delete the
 * legacy bytes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegacyDataAdoptionTest {

    private class FakeSlot : SecretSlot {
        val values = mutableMapOf<String, String>()
        var legacy: String? = null

        override fun read(ownerId: String): String? = values[ownerId]
        override fun write(ownerId: String, value: String) {
            values[ownerId] = value
        }

        override fun erase(ownerId: String) {
            values.remove(ownerId)
        }

        override fun readLegacy(): String? = legacy
    }

    private class Harness {
        private val file = File.createTempFile("legacy-adoption", ".preferences_pb").also { it.delete() }
        private val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        val slot = FakeSlot()

        fun adoption(owner: String?, ownerProvider: () -> String? = { owner }): LegacyDataAdoption =
            LegacyDataAdoption(
                profileFor = { id -> ProfileStore.forDataStore(dataStore, id) },
                keyFor = { id -> AiKeyStore(slot, id) },
                verifiedOwnerId = ownerProvider,
            )

        suspend fun close() {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private fun withHarness(block: suspend (Harness) -> Unit) = runBlocking {
        val harness = Harness()
        try {
            block(harness)
        } finally {
            harness.close()
        }
    }

    private suspend fun seedLegacy(
        harness: Harness,
        name: String? = "Vojta",
        birthYear: Int? = 1990,
        aiEnabled: Boolean = true,
        onboardingDone: Boolean = true,
        key: String? = "sk-legacy-key",
    ) {
        harness.dataStore.edit { prefs ->
            if (name != null) prefs[stringPreferencesKey("user_name")] = name
            if (birthYear != null) prefs[intPreferencesKey("birth_year")] = birthYear
            prefs[booleanPreferencesKey("ai_enabled")] = aiEnabled
            prefs[booleanPreferencesKey("onboarding_done")] = onboardingDone
        }
        harness.slot.legacy = key
    }

    @Test
    fun `adoption fills empty account fields and copies the key`() = withHarness { harness ->
        seedLegacy(harness)
        val adoption = harness.adoption("account-a")

        val availability = adoption.availability()
        assertTrue(availability.hasProfile)
        assertTrue(availability.keyPresent)

        val result = adoption.adopt()
        assertTrue(result is LegacyAdoptionResult.Adopted)
        result as LegacyAdoptionResult.Adopted
        assertEquals(2, result.profileFields)
        assertTrue(result.keyAdopted)

        val profile = ProfileStore.forDataStore(harness.dataStore, "account-a").load()
        assertEquals("Vojta", profile.name)
        assertEquals(1990, profile.birthYear)
        assertEquals("sk-legacy-key", harness.slot.values["account-a"])
        assertEquals("sk-legacy-key", harness.slot.legacy)
    }

    @Test
    fun `adoption never adopts ai consent or the guide flag`() = withHarness { harness ->
        seedLegacy(harness, aiEnabled = true, onboardingDone = true)

        harness.adoption("account-a").adopt()

        val profile = ProfileStore.forDataStore(harness.dataStore, "account-a").load()
        assertFalse(profile.aiEnabled)
        assertFalse(profile.onboardingDone)
    }

    @Test
    fun `adoption never overwrites values the account already has`() = withHarness { harness ->
        seedLegacy(harness)
        val store = ProfileStore.forDataStore(harness.dataStore, "account-a")
        store.saveName("Nové jméno")
        store.saveBirthYear(2001)
        harness.slot.values["account-a"] = "sk-new-key"

        val result = harness.adoption("account-a").adopt()

        assertTrue(result is LegacyAdoptionResult.NothingToAdopt)
        val profile = store.load()
        assertEquals("Nové jméno", profile.name)
        assertEquals(2001, profile.birthYear)
        assertEquals("sk-new-key", harness.slot.values["account-a"])
    }

    @Test
    fun `adoption is refused without a verified account`() = withHarness { harness ->
        seedLegacy(harness)
        val result = harness.adoption(owner = null).adopt()

        assertTrue(result is LegacyAdoptionResult.OwnerUnavailable)
        assertTrue(harness.slot.values.isEmpty())
        val profile = ProfileStore.forDataStore(harness.dataStore, null).load()
        assertEquals("", profile.name)
    }

    @Test
    fun `account switch during adoption writes nothing to the new account`() = withHarness { harness ->
        seedLegacy(harness)
        var calls = 0
        val adoption = harness.adoption(owner = "account-a") {
            calls += 1
            if (calls <= 2) "account-a" else "account-b"
        }

        val result = adoption.adopt()

        assertTrue(result is LegacyAdoptionResult.OwnerChanged)
        assertNull(harness.slot.values["deepseek_api_key_v2_account-b"])
    }

    @Test
    fun `second adoption has nothing left to do`() = withHarness { harness ->
        seedLegacy(harness)
        val adoption = harness.adoption("account-a")
        assertTrue(adoption.adopt() is LegacyAdoptionResult.Adopted)

        assertTrue(adoption.adopt() is LegacyAdoptionResult.NothingToAdopt)
        assertFalse(adoption.availability().hasAnything)
    }

    @Test
    fun `availability reports nothing when the phone has no legacy data`() = withHarness { harness ->
        val availability = harness.adoption("account-a").availability()

        assertFalse(availability.hasAnything)
    }

    @Test
    fun `profile fields are adopted even when the legacy key is missing`() = withHarness { harness ->
        seedLegacy(harness, key = null)

        val result = harness.adoption("account-a").adopt() as LegacyAdoptionResult.Adopted

        assertEquals(2, result.profileFields)
        assertFalse(result.keyAdopted)
    }

    @Test
    fun `a blank legacy name is not adopted`() = withHarness { harness ->
        seedLegacy(harness, name = "   ", birthYear = null, key = null)

        val result = harness.adoption("account-a").adopt()

        assertTrue(result is LegacyAdoptionResult.NothingToAdopt)
        assertEquals("", ProfileStore.forDataStore(harness.dataStore, "account-a").load().name)
    }

    @Test
    fun `legacy values stay readable after adoption`() = withHarness { harness ->
        seedLegacy(harness)
        harness.adoption("account-a").adopt()

        val prefs = harness.dataStore.data.first()
        assertEquals("Vojta", prefs[stringPreferencesKey("user_name")])
        assertEquals(1990, prefs[intPreferencesKey("birth_year")])
        assertEquals("sk-legacy-key", harness.slot.legacy)
    }
}
