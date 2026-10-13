package cz.digitalnivedomi.diarium.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
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
 * The defaults asserted here are the ones every generation path relies on: a fresh
 * install has AI ON and onboarding pending — the master switch only ever turns AI
 * off when the owner does.
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

    private fun newStore(file: File): Handle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        return Handle(
            ProfileStore.forDataStore(
                PreferenceDataStoreFactory.create(
                    scope = scope,
                    produceFile = { file },
                ),
            ),
            scope,
        )
    }

    private fun tempFile(): File =
        File.createTempFile("diarium_profile_test", ".preferences_pb").also { it.delete() }

    @Test
    fun `a fresh profile is AI on with onboarding pending`() = runBlocking {
        val handle = newStore(tempFile())

        val profile = handle.store.load()

        assertEquals("", profile.name)
        assertNull(profile.birthYear)
        assertTrue(profile.aiEnabled)
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
        assertTrue(profile.aiEnabled)
        assertFalse(profile.onboardingDone)
    }

    @Test
    fun `decode falls back to the defaults for a file with nothing in it`() {
        assertEquals(ProfileData(), ProfileStore.decode(emptyPreferences()))
    }
}
