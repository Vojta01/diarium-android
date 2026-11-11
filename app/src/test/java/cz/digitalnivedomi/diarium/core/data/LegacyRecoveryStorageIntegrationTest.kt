package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(2, inventory.unsupportedCount)
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

        val secrets = EncryptedSecretSlot(context)
        val storage = object : LegacyRecoveryStorage {
            override suspend fun readDraftPreferences(): Map<String, Any?> = emptyMap()

            override suspend fun readPendingSavePreferences(): Map<String, Any?> = emptyMap()

            override suspend fun readProfilePreferenceNames(): Set<String> = emptySet()

            override suspend fun legacyAiSecretStatus(): LegacySecretPresence = secrets.legacyValueStatus()
        }
        val inventory = LegacyRecoveryScanner(storage).scan()

        assertEquals(LegacySecretPresence.UNKNOWN, inventory.legacyAiSecretStatus)
        assertNull(inventory.legacyAiSecretPresent)
        assertFalse("a read-only scan must not create encrypted prefs", secretFile.exists())
    }

    @Test
    fun `real recovery writer copies only to owner drafts and preserves originals across restart`() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val root = File.createTempFile("manual-recovery-test", ".tmp").apply {
            delete()
            mkdirs()
        }
        val context = SyntheticContext(baseContext, root)
        val draftFile = datastoreFile(context, LegacyRecoveryScanner.DRAFTS_DATASTORE_NAME)
        val date = "2026-10-20"
        val conflictDate = "2026-10-21"
        val draftRaw = """{"mood":4,"note":"synthetic-recovery-fixture"}"""
        val conflictRaw = """{"mood":3,"note":"synthetic-conflict-fixture"}"""
        val currentOwner: String? = OWNER_A
        val draftId = LegacyRecoveryRecordId(LegacyRecoveryType.DRAFT, date)
        val conflictId = LegacyRecoveryRecordId(LegacyRecoveryType.DRAFT, conflictDate)
        val existingTarget = DiaryEntry(mood = 1, note = "synthetic-existing-account-draft")

        withPreferencesDataStore(draftFile) { dataStore ->
            seedPreferences(dataStore) { prefs ->
                prefs[stringPreferencesKey(DraftStore.legacyKey(date))] = draftRaw
                prefs[stringPreferencesKey(DraftStore.legacyKey(conflictDate))] = conflictRaw
            }
            val targetA = DraftStore(dataStore, OWNER_A)
            targetA.save(conflictDate, existingTarget)
            val coordinator = localRecoveryCoordinator(context, dataStore) { currentOwner }
            val inventory = coordinator.scan() as LegacyRecoveryScanResult.Ready
            val inventoryIds = inventory.inventory.records.map { "${it.type}:${it.date}:${it.status}" }.toSet()
            assertEquals(setOf("DRAFT:$date:PREVIEWABLE", "DRAFT:$conflictDate:PREVIEWABLE"), inventoryIds)

            val attestation = (coordinator.authorize(
                draftId,
                OWNER_A,
                ownershipConfirmed = true,
                localPrivacyConfirmed = true,
            ) as LegacyRecoveryAuthorizationResult.Granted).attestation
            val preview = coordinator.preview(attestation) as LegacyRecoveryPreviewResult.Ready
            assertEquals("synthetic-recovery-fixture", preview.contents.entry.note)
            assertEquals(LegacyRecoveryCopyResult.COPIED, coordinator.copy(attestation))
            val imported = targetA.loadRecord(date)
            assertEquals(DiaryEntry(mood = 4, note = "synthetic-recovery-fixture"), imported?.entry)
            assertEquals(draftId, imported?.legacyRecoveryOrigin)
            assertNull(imported?.pendingSaveRequestId)
            assertEquals(existingTarget, targetA.loadRecord(conflictDate)?.entry)
            assertNull(DraftStore(dataStore, OWNER_B).loadRecord(date))
            assertTrue(PendingSaveStore.from(context, OWNER_A).peekAll().isEmpty())
            val recoveryStorage = localRecoveryStorage(context, dataStore)
            assertEquals(draftRaw, recoveryStorage.readDraftPreferences()[DraftStore.legacyKey(date)])
            assertEquals(conflictRaw, recoveryStorage.readDraftPreferences()[DraftStore.legacyKey(conflictDate)])
        }

        // Re-open a fresh DataStore instance from disk to model a process restart.
        withPreferencesDataStore(draftFile) { dataStore ->
            val targetA = DraftStore(dataStore, OWNER_A)
            val recoveryStorage = localRecoveryStorage(context, dataStore)
            val restarted = localRecoveryCoordinator(context, dataStore) { currentOwner }
            val restartedAttestation = (restarted.authorize(
                draftId,
                OWNER_A,
                ownershipConfirmed = true,
                localPrivacyConfirmed = true,
            ) as LegacyRecoveryAuthorizationResult.Granted).attestation
            assertTrue(restarted.preview(restartedAttestation) is LegacyRecoveryPreviewResult.Ready)
            assertEquals(LegacyRecoveryCopyResult.ALREADY_COPIED, restarted.copy(restartedAttestation))
            assertEquals(draftRaw, recoveryStorage.readDraftPreferences()[DraftStore.legacyKey(date)])
            val recovered = targetA.loadRecord(date)!!
            val rebased = targetA.rebaseLegacyRecovery(date, recovered, remote = null)!!
            assertEquals(false, rebased.baselineExists)
            saveRecoveredDraftViaCas(context, targetA, date, rebased.entry)
            assertNull(targetA.loadRecord(date))
            assertEquals(draftRaw, recoveryStorage.readDraftPreferences()[DraftStore.legacyKey(date)])

            val conflictAttestation = (restarted.authorize(
                conflictId,
                OWNER_A,
                ownershipConfirmed = true,
                localPrivacyConfirmed = true,
            ) as LegacyRecoveryAuthorizationResult.Granted).attestation
            assertTrue(restarted.preview(conflictAttestation) is LegacyRecoveryPreviewResult.Ready)
            assertEquals(LegacyRecoveryCopyResult.TARGET_CONFLICT, restarted.copy(conflictAttestation))
            assertEquals(existingTarget, targetA.loadRecord(conflictDate)?.entry)
            assertEquals(conflictRaw, recoveryStorage.readDraftPreferences()[DraftStore.legacyKey(conflictDate)])
            assertNull(targetA.loadRecord(date))
        }
    }

    @Test
    fun `real queued-save recovery copies and saves via CAS while retaining ownerless source`() = runBlocking {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val root = File.createTempFile("manual-pending-recovery-test", ".tmp").apply {
            delete()
            mkdirs()
        }
        val context = SyntheticContext(baseContext, root)
        val draftFile = datastoreFile(context, LegacyRecoveryScanner.DRAFTS_DATASTORE_NAME)
        val date = "2026-10-22"
        val pendingRaw = """{"queuedAt":12,"entry":{"mood":3,"note":"synthetic-pending-fixture"}}"""
        withPreferencesDataStore(draftFile) { dataStore ->
            val pendingPrefs = context.getSharedPreferences(PendingSaveStore.PREFS_NAME, Context.MODE_PRIVATE)
            assertTrue(pendingPrefs.edit().putString(date, pendingRaw).commit())
            val coordinator = localRecoveryCoordinator(context, dataStore) { OWNER_A }
            val inventory = coordinator.scan() as LegacyRecoveryScanResult.Ready
            val inventoryIds = inventory.inventory.records.map { "${it.type}:${it.date}:${it.status}" }
            assertEquals(listOf("PENDING_SAVE:$date:PREVIEWABLE"), inventoryIds)
            val id = inventory.inventory.records.single {
                it.type == LegacyRecoveryType.PENDING_SAVE && it.date == date
            }.id
            val attestation = (coordinator.authorize(
                id,
                OWNER_A,
                ownershipConfirmed = true,
                localPrivacyConfirmed = true,
            ) as LegacyRecoveryAuthorizationResult.Granted).attestation
            assertEquals("synthetic-pending-fixture", (coordinator.preview(attestation) as LegacyRecoveryPreviewResult.Ready).contents.entry.note)
            assertEquals(LegacyRecoveryCopyResult.COPIED, coordinator.copy(attestation))
            val targetA = DraftStore(dataStore, OWNER_A)
            assertEquals(DiaryEntry(mood = 3, note = "synthetic-pending-fixture"), targetA.loadRecord(date)?.entry)
            assertNull(DraftStore(dataStore, OWNER_B).loadRecord(date))
            assertEquals(pendingRaw, pendingPrefs.getString(date, null))
            assertTrue(PendingSaveStore.from(context, OWNER_A).peekAll().isEmpty())
            assertEquals(pendingRaw, pendingPrefs.getString(date, null))
            val recovered = targetA.loadRecord(date)!!
            val rebased = targetA.rebaseLegacyRecovery(date, recovered, remote = null)!!
            saveRecoveredDraftViaCas(context, targetA, date, rebased.entry)
            assertNull(targetA.loadRecord(date))
            assertEquals(pendingRaw, pendingPrefs.getString(date, null))
        }
    }

    private suspend fun saveRecoveredDraftViaCas(
        context: Context,
        store: DraftStore,
        date: String,
        entry: DiaryEntry,
    ) {
        val requestId = "30000000-0000-4000-8000-${date.replace("-", "")}0000"
        val baseline = EntryBaseline(exists = false, id = null, revision = null)
        store.save(
            date = date,
            entry = entry,
            baselineUpdatedAt = null,
            baselineExists = false,
            baselineId = null,
            baselineRevision = null,
            pendingSaveRequestId = requestId,
        )
        val request = ConditionalEntrySaveRequest(OWNER_A, date, entry, baseline, requestId)
        val sessionStore = SessionStore(context).apply {
            save(
                JSONObject().apply {
                    put("access_token", "synthetic-owner-a-jwt")
                    put("refresh_token", "synthetic-refresh")
                    put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                    put("user", JSONObject().put("id", OWNER_A))
                },
            )
        }
        val repository = EntriesRepository(
            client = SupabaseClient(
                sessionStore = sessionStore,
                transport = HttpTransport { httpRequest ->
                    when {
                        httpRequest.url.endsWith("/rpc/save_daily_entry_v2") -> HttpResponse(
                            200,
                            JSONObject()
                                .put("status", "saved")
                                .put("entry_id", "10000000-0000-4000-8000-000000000001")
                                .put("revision", 1)
                                .put("replayed", false)
                                .toString(),
                        )
                        httpRequest.url.endsWith("/rpc/check_achievements") -> HttpResponse(200, "{}")
                        else -> HttpResponse(200, "[]")
                    }
                },
                baseUrl = "https://example.supabase.co/rest/v1",
                anonKey = "synthetic-anon-key",
            ),
            session = SessionContext(sessionStore),
            expectedOwnerId = OWNER_A,
        )
        assertTrue(repository.save(request).isSuccess)
        assertTrue(store.clearIfUnchanged(date, entry, requestId))
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

    private fun localRecoveryStorage(
        context: Context,
        dataStore: DataStore<Preferences>,
    ): PreferenceLegacyRecoveryStorage = PreferenceLegacyRecoveryStorage(
        drafts = DraftStore(dataStore, ownerId = null),
        pendingSaves = PendingSaveStore.from(context, ownerId = null),
        profile = ProfileStore.forDataStore(dataStore, ownerId = null),
        secrets = EncryptedSecretSlot(context),
    )

    private fun localRecoveryCoordinator(
        context: Context,
        dataStore: DataStore<Preferences>,
        verifiedOwnerId: () -> String?,
    ): LegacyRecoveryCoordinator = LegacyRecoveryCoordinator(
        scanner = LegacyRecoveryScanner(localRecoveryStorage(context, dataStore)),
        writer = PreferenceLegacyRecoveryWriter(context) { ownerId -> DraftStore(dataStore, ownerId) },
        verifiedOwnerId = verifiedOwnerId,
    )

    private suspend fun <T> withPreferencesDataStore(
        file: File,
        block: suspend (DataStore<Preferences>) -> T,
    ): T {
        file.parentFile?.mkdirs()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return try {
            block(store)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private suspend fun seedPreferences(
        dataStore: DataStore<Preferences>,
        seed: (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ) {
        dataStore.edit { preferences -> seed(preferences) }
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
        const val OWNER_B = "synthetic-owner-b"
    }
}
