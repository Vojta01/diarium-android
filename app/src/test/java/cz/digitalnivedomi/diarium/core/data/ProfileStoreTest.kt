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
 * Profile persistence over a real file-backed DataStore, the same way DraftStoreTest
 * drives the draft store: a temporary `.preferences_pb` file, an owned scope, and a
 * store closed before the file is reopened.
 *
 * Defaults are privacy-first: no account generates AI until that account has
 * explicitly opted in, and onboarding remains pending.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProfileStoreTest {

    /**
     * A store plus the scope backing it. DataStore refuses to open one file twice
     * while the first instance is alive, so [close] has to be called before a test
     * reopens the same file — which is exactly what the reopening test means.
     */
    private class Handle(val store: ProfileStore, private val scope: CoroutineScope) {
        suspend fun close() {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private fun newStore(file: File, ownerId: String = OWNER_A): Handle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        return Handle(
            ProfileStore.forDataStore(
                PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = { file },
                ),
                ownerId,
            ),
            scope,
        )
    }

    private fun tempFile(): File =
        File.createTempFile("diarium_profile_test", ".preferences_pb").also { it.delete() }

    @Test
    fun `a fresh profile is AI off with onboarding pending`() = runBlocking {
        val handle = newStore(tempFile())

        val profile = handle.store.load()

        assertEquals("", profile.name)
        assertNull(profile.birthYear)
        assertFalse(profile.aiEnabled)
        assertFalse(profile.onboardingDone)
        // The stream carries the same defaults as the one-shot read.
        assertEquals(profile, handle.store.data.first())

        handle.close()
    }

    @Test
    fun `the name round-trips trimmed and blank means empty`() = runBlocking {
        val handle = newStore(tempFile())

        handle.store.saveName("  Vojta  ")
        assertEquals("Vojta", handle.store.load().name)

        // Clearing the field clears the name — never a stray space.
        handle.store.saveName("   ")
        assertEquals("", handle.store.load().name)

        handle.close()
    }

    @Test
    fun `the birth year round-trips and null clears it`() = runBlocking {
        val handle = newStore(tempFile())

        handle.store.saveBirthYear(1990)
        assertEquals(1990, handle.store.load().birthYear)

        handle.store.saveBirthYear(null)
        assertNull(handle.store.load().birthYear)

        handle.close()
    }

    @Test
    fun `the AI master switch persists both ways`() = runBlocking {
        val handle = newStore(tempFile())

        handle.store.setAiEnabled(false)
        assertFalse(handle.store.load().aiEnabled)

        handle.store.setAiEnabled(true)
        assertTrue(handle.store.load().aiEnabled)

        handle.close()
    }

    @Test
    fun `the onboarding flag persists both ways`() = runBlocking {
        val handle = newStore(tempFile())

        handle.store.setOnboardingDone(true)
        assertTrue(handle.store.load().onboardingDone)

        // "Spustit průvodce znovu" is exactly this write.
        handle.store.setOnboardingDone(false)
        assertFalse(handle.store.load().onboardingDone)

        handle.close()
    }

    @Test
    fun `the profile survives a store reopening`() = runBlocking {
        val file = tempFile()

        val first = newStore(file)
        first.store.saveName("Vojta")
        first.store.saveBirthYear(1988)
        first.store.setAiEnabled(false)
        // Release the file so the read below starts from disk, like a cold start.
        first.close()

        val reopened = newStore(file)
        val profile = reopened.store.load()
        assertEquals("Vojta", profile.name)
        assertEquals(1988, profile.birthYear)
        assertFalse(profile.aiEnabled)
        reopened.close()
    }

    @Test
    fun `ProfileData defaults are the documented ones`() {
        val profile = ProfileData()

        assertEquals("", profile.name)
        assertNull(profile.birthYear)
        assertFalse(profile.aiEnabled)
        assertFalse(profile.onboardingDone)
    }

    @Test
    fun `profile values are isolated across owners and signout cannot read or replace them`() = runBlocking {
        val file = tempFile()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val accountA = ProfileStore.forDataStore(dataStore, OWNER_A)
        val accountB = ProfileStore.forDataStore(dataStore, OWNER_B)
        val signedOut = ProfileStore.forDataStore(dataStore, null)

        accountA.saveName("Account A")
        accountA.saveBirthYear(1988)
        accountA.setAiEnabled(false)
        accountA.setOnboardingDone(true)

        assertEquals(ProfileData(), accountB.load())
        assertEquals(ProfileData(), signedOut.load())
        signedOut.saveName("must not be assigned")
        signedOut.setAiEnabled(false)
        assertEquals("Account A", accountA.load().name)
        assertTrue(accountA.load().aiEnabled.not())
        assertTrue(accountA.load().onboardingDone)

        accountB.saveName("Account B")
        accountB.setAiEnabled(true)
        assertEquals("Account B", accountB.load().name)
        assertEquals("Account A", accountA.load().name)
        assertEquals(1988, accountA.load().birthYear)
        assertTrue(accountA.load().aiEnabled.not())
        assertTrue(accountB.load().aiEnabled)
        scope.coroutineContext[Job]?.cancelAndJoin()
        Unit
    }

    @Test
    fun `account profile survives process restart without appearing in another account`() = runBlocking {
        val file = tempFile()
        val first = newStore(file, OWNER_A)
        first.store.saveName("Account A")
        first.store.saveBirthYear(1991)
        first.store.setAiEnabled(false)
        first.store.setOnboardingDone(true)
        first.close()

        val otherAccount = newStore(file, OWNER_B)
        assertEquals(ProfileData(), otherAccount.store.load())
        otherAccount.close()

        val reopened = newStore(file, OWNER_A)
        assertEquals("Account A", reopened.store.load().name)
        assertEquals(1991, reopened.store.load().birthYear)
        assertFalse(reopened.store.load().aiEnabled)
        assertTrue(reopened.store.load().onboardingDone)
        reopened.close()
    }

    @Test
    fun `legacy optout stays off and quarantined across upgrade restart and account switch`() = runBlocking {
        val file = tempFile()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("user_name")] = "Legacy owner"
            prefs[intPreferencesKey("birth_year")] = 1980
            prefs[booleanPreferencesKey("ai_enabled")] = false
            prefs[booleanPreferencesKey("onboarding_done")] = true
        }
        val accountA = ProfileStore.forDataStore(dataStore, OWNER_A)

        val profile = accountA.load()
        assertEquals(ProfileData(legacyDataQuarantined = true), profile)
        assertFalse("unknown per-account consent must not enable AI", profile.aiEnabled)
        accountA.saveName("New account")
        val raw = dataStore.data.first()
        assertEquals("Legacy owner", raw[stringPreferencesKey("user_name")])
        assertEquals(1980, raw[intPreferencesKey("birth_year")])
        assertFalse(raw[booleanPreferencesKey("ai_enabled")] ?: true)
        assertTrue(raw[booleanPreferencesKey("onboarding_done")] ?: false)
        assertEquals("New account", accountA.load().name)
        scope.coroutineContext[Job]?.cancelAndJoin()

        val restartedA = newStore(file, OWNER_A)
        assertFalse(restartedA.store.load().aiEnabled)
        assertEquals("New account", restartedA.store.load().name)
        restartedA.close()

        val accountB = newStore(file, OWNER_B)
        assertFalse("another account has not opted in", accountB.store.load().aiEnabled)
        assertEquals("", accountB.store.load().name)
        accountB.close()

        val explicitOptInA = newStore(file, OWNER_A)
        explicitOptInA.store.setAiEnabled(true)
        assertTrue(explicitOptInA.store.load().aiEnabled)
        explicitOptInA.close()

        val rawScope = CoroutineScope(Dispatchers.IO + Job())
        val rawStore = PreferenceDataStoreFactory.create(scope = rawScope, produceFile = { file })
        val finalRaw = rawStore.data.first()
        assertEquals("Legacy owner", finalRaw[stringPreferencesKey("user_name")])
        assertEquals(1980, finalRaw[intPreferencesKey("birth_year")])
        assertFalse(finalRaw[booleanPreferencesKey("ai_enabled")] ?: true)
        rawScope.coroutineContext[Job]?.cancelAndJoin()
        Unit
    }

    private companion object {
        const val OWNER_A = "account-a"
        const val OWNER_B = "account-b"
    }
}
