package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegacyRecoveryStorageIntegrationTest {

    @Test
    fun `real local stores inventory beta5 records read only and key read failures remain unknown`() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val root = File.createTempFile("legacy-recovery-test", ".tmp").apply {
            delete()
            mkdirs()
        }
        val context = SyntheticContext(baseContext, root)
        val draftFile = datastoreFile(context, LegacyRecoveryScanner.DRAFTS_DATASTORE_NAME)
        val profileFile = datastoreFile(context, LegacyRecoveryScanner.PROFILE_DATASTORE_NAME)

        val draftRaw = " { \"mood\" : 4, \"note\" : \"legacy-diary-marker\", \"access_token\" : \"legacy-token-marker\" } "
        val pendingRaw = "{\"queuedAt\":17,\"entry\":{\"mood\":2,\"note\":\"pending-diary-marker\",\"apiKey\":\"legacy-ai-key-marker\"},\"access_token\":\"legacy-token-marker\"}"
        seedPreferences(draftFile) { prefs ->
            prefs[stringPreferencesKey("diarium_draft_2026-10-01")] = draftRaw
            prefs[stringPreferencesKey("diarium_draft_2026-10-02")] = "{broken legacy JSON"
            // Account-scoped values share the file but are not ownerless recovery candidates.
            prefs[stringPreferencesKey("diarium_draft_v2_owner-a_2026-10-03")] =
                "{\"entry\":{\"note\":\"scoped-private-marker\"}}"
        }
        seedPreferences(profileFile) { prefs ->
            prefs[stringPreferencesKey("user_name")] = "legacy-profile-marker"
            prefs[intPreferencesKey("birth_year")] = 1984
            prefs[booleanPreferencesKey("ai_enabled")] = true
            prefs[booleanPreferencesKey("onboarding_done")] = true
            prefs[stringPreferencesKey("profile_v2_owner-a_name")] = "scoped-profile-marker"
        }

        val pendingPreferences = context.getSharedPreferences(
            PendingSaveStore.PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        check(
            pendingPreferences.edit()
                .putString("2026-10-01", pendingRaw)
                .putString("2026-10-03", "not-json")
                .putString("v2/b3duZXItYQ/2026-10-02", "{\"entry\":{\"note\":\"scoped-pending-marker\"}}")
                .commit(),
        )

        FakeAndroidKeyStore.install()
        val encryptedSecretPreferences = EncryptedSharedPreferences.create(
            context,
            AiKeyStore.FILE_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        check(
            encryptedSecretPreferences.edit()
                .putString(AiKeyStore.KEY, "legacy-ai-key-marker")
                .commit(),
        )

        val draftBytesBefore = draftFile.readBytes()
        val profileBytesBefore = profileFile.readBytes()
        val pendingBytesBefore = sharedPreferencesFile(baseContext, context, PendingSaveStore.PREFS_NAME).readBytes()
        val pendingValuesBefore = pendingPreferences.all.toMap()
        val secretFileBefore = sharedPreferencesFile(baseContext, context, AiKeyStore.FILE_NAME).readBytes()

        // Open the normal owner-bound stores first. The scanner must reuse their
        // Context delegates instead of opening duplicate DataStores for these files.
        val existingDraftStore = DraftStore.from(context, OWNER_A)
        val existingProfileStore = ProfileStore.from(context, OWNER_A)
        assertNull(existingDraftStore.load("2026-10-01"))
        assertEquals("", existingProfileStore.load().name)

        val initializedSecretSlot = EncryptedSecretSlot(context)
        // This deliberately opens a store that the fixture already created. The
        // scanner may inspect it only through this pre-initialized slot.
        assertNull(AiKeyStore(initializedSecretSlot, OWNER_A).key())
        val inventory = LegacyRecoveryScanner.from(context, initializedSecretSlot).scan()

        assertEquals(4, inventory.recordCount)
        assertEquals(2, inventory.draftCount)
        assertEquals(2, inventory.pendingSaveCount)
        assertEquals(2, inventory.malformedCount)
        assertEquals(
            setOf(
                LegacyProfileField.NAME,
                LegacyProfileField.BIRTH_YEAR,
                LegacyProfileField.AI_ENABLED,
                LegacyProfileField.ONBOARDING_DONE,
            ),
            inventory.profileFieldsPresent,
        )
        assertEquals(LegacySecretPresence.PRESENT, inventory.legacyAiSecretStatus)

        val safeInventory = inventory.toString()
        listOf(
            "legacy-diary-marker",
            "pending-diary-marker",
            "legacy-profile-marker",
            "legacy-ai-key-marker",
            "legacy-token-marker",
            "scoped-private-marker",
            "scoped-profile-marker",
            "scoped-pending-marker",
            "access_token",
            "apiKey",
        ).forEach { marker -> assertFalse("Inventory leaked $marker", safeInventory.contains(marker)) }

        // Byte snapshots include the exact DataStore files; SharedPreferences assertions
        // compare the original legacy JSON strings and all other stored values exactly.
        assertArrayEquals(draftBytesBefore, draftFile.readBytes())
        assertArrayEquals(profileBytesBefore, profileFile.readBytes())
        assertArrayEquals(
            pendingBytesBefore,
            sharedPreferencesFile(baseContext, context, PendingSaveStore.PREFS_NAME).readBytes(),
        )
        assertEquals(pendingValuesBefore, pendingPreferences.all)
        assertEquals(pendingRaw, pendingPreferences.all["2026-10-01"])
        assertArrayEquals(
            secretFileBefore,
            sharedPreferencesFile(baseContext, context, AiKeyStore.FILE_NAME).readBytes(),
        )

        val failedKeyRead = LegacyRecoveryScanner.from(
            context,
            EncryptedSecretSlot(FailingPreferencesContext(context)),
        ).scan()
        assertEquals(LegacySecretPresence.UNKNOWN, failedKeyRead.legacyAiSecretStatus)
        assertNull(failedKeyRead.legacyAiSecretPresent)
        assertEquals(pendingValuesBefore, pendingPreferences.all)
        assertArrayEquals(draftBytesBefore, draftFile.readBytes())
        assertArrayEquals(profileBytesBefore, profileFile.readBytes())
        assertArrayEquals(
            pendingBytesBefore,
            sharedPreferencesFile(baseContext, context, PendingSaveStore.PREFS_NAME).readBytes(),
        )
        assertArrayEquals(
            secretFileBefore,
            sharedPreferencesFile(baseContext, context, AiKeyStore.FILE_NAME).readBytes(),
        )
    }

    @Test
    fun `scan reports unknown and does not create an absent encrypted preferences file`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteSharedPreferences(AiKeyStore.FILE_NAME)
        val secretFile = File(
            File(context.applicationInfo.dataDir, "shared_prefs"),
            "${AiKeyStore.FILE_NAME}.xml",
        )
        assertFalse("fixture must start without the encrypted store", secretFile.exists())

        val inventory = LegacyRecoveryScanner.from(context).scan()

        assertEquals(LegacySecretPresence.UNKNOWN, inventory.legacyAiSecretStatus)
        assertNull(inventory.legacyAiSecretPresent)
        assertFalse("a read-only scan must not create encrypted prefs", secretFile.exists())
    }

    private class SyntheticContext(
        private val base: Context,
        private val root: File,
    ) : ContextWrapper(base) {
        val sharedPreferencesPrefix: String = "${root.name}-legacy"

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences(
                if (name == AiKeyStore.FILE_NAME) name else "$sharedPreferencesPrefix-$name",
                mode,
            )
    }

    private class FailingPreferencesContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            throw IllegalStateException("synthetic encrypted-preferences read failure")
    }

    private suspend fun seedPreferences(
        file: File,
        seed: (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        file.parentFile?.mkdirs()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file },
            )
            store.edit { preferences -> seed(preferences) }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private fun datastoreFile(context: Context, name: String): File =
        File(File(context.filesDir, "datastore"), "$name.preferences_pb")

    private fun sharedPreferencesFile(base: Context, context: SyntheticContext, name: String): File {
        val fileName = if (name == AiKeyStore.FILE_NAME) name else "${context.sharedPreferencesPrefix}-$name"
        return File(File(base.applicationInfo.dataDir, "shared_prefs"), "$fileName.xml")
    }

    private companion object {
        const val OWNER_A = "synthetic-owner-a"
    }
}
