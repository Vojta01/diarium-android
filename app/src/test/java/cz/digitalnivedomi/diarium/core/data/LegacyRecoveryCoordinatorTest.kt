package cz.digitalnivedomi.diarium.core.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRecoveryCoordinatorTest {

    @Test
    fun `unverified owner and missing per-record confirmations fail closed`() = runBlocking {
        val fixture = Fixture()
        val coordinator = fixture.coordinator
        val id = fixture.draftId

        assertEquals(LegacyRecoveryScanResult.OwnerUnavailable, coordinator.scan())
        fixture.currentOwner = OWNER_A
        assertEquals(
            LegacyRecoveryAuthorizationResult.ConfirmationRequired,
            coordinator.authorize(id, OWNER_A, ownershipConfirmed = false, localPrivacyConfirmed = true),
        )
        assertEquals(
            LegacyRecoveryAuthorizationResult.ConfirmationRequired,
            coordinator.authorize(id, OWNER_A, ownershipConfirmed = true, localPrivacyConfirmed = false),
        )
        val granted = coordinator.authorize(
            id,
            OWNER_A,
            ownershipConfirmed = true,
            localPrivacyConfirmed = true,
        )
        assertTrue(granted is LegacyRecoveryAuthorizationResult.Granted)
        assertEquals(0, fixture.writer.serverWriteCount)
    }

    @Test
    fun `content is gated by attestation and copy only creates a local account draft`() = runBlocking {
        val fixture = Fixture()
        fixture.currentOwner = OWNER_A

        assertEquals(
            LegacyRecoveryPreviewResult.NotAuthorized,
            fixture.coordinator.preview(null),
        )
        val attestation = fixture.attest(fixture.draftId)
        val preview = fixture.coordinator.preview(attestation)
        assertTrue(preview is LegacyRecoveryPreviewResult.Ready)
        assertEquals("synthetic local note", (preview as LegacyRecoveryPreviewResult.Ready).contents.entry.note)
        assertEquals(LegacyRecoveryCopyResult.COPIED, fixture.coordinator.copy(attestation))
        assertEquals("synthetic local note", fixture.writer.target(OWNER_A, fixture.draftId.date)?.note)
        assertEquals(fixture.originalDraft, fixture.storage.drafts["diarium_draft_${fixture.draftId.date}"])
        assertEquals(0, fixture.writer.serverWriteCount)
    }

    @Test
    fun `account switch invalidates the old attestation and account targets remain isolated`() = runBlocking {
        val fixture = Fixture()
        fixture.currentOwner = OWNER_A
        val ownerAAttestation = fixture.attest(fixture.draftId)
        assertEquals(LegacyRecoveryCopyResult.COPIED, fixture.coordinator.copy(ownerAAttestation))
        val ownerAEntry = fixture.writer.target(OWNER_A, fixture.draftId.date)

        fixture.currentOwner = OWNER_B
        assertEquals(
            LegacyRecoveryCopyResult.OWNER_CHANGED,
            fixture.coordinator.copy(ownerAAttestation),
        )
        assertNull(fixture.writer.target(OWNER_B, fixture.draftId.date))
        val ownerBAttestation = fixture.attest(fixture.draftId)
        assertEquals(LegacyRecoveryCopyResult.COPIED, fixture.coordinator.copy(ownerBAttestation))
        assertEquals(ownerAEntry, fixture.writer.target(OWNER_A, fixture.draftId.date))
        assertEquals(ownerAEntry, fixture.writer.target(OWNER_B, fixture.draftId.date))
        assertEquals(0, fixture.writer.serverWriteCount)
    }

    @Test
    fun `owner switch after old owner persistence reports ambiguous old-account copy`() = runBlocking {
        val fixture = Fixture()
        fixture.currentOwner = OWNER_A
        val copyPersisted = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val gatedWriter = object : LegacyRecoveryWriter {
            override suspend fun copyToAccountDraft(
                ownerId: String,
                id: LegacyRecoveryRecordId,
                payload: LegacyRecoveryPayload,
            ): LegacyRecoveryCopyResult {
                val outcome = fixture.writer.copyToAccountDraft(ownerId, id, payload)
                copyPersisted.complete(Unit)
                releaseWriter.await()
                return outcome
            }

            override suspend fun confirmNoConflictAndRemoveOriginal(
                ownerId: String,
                id: LegacyRecoveryRecordId,
                payload: LegacyRecoveryPayload,
            ): LegacyRecoveryFinalizeResult =
                fixture.writer.confirmNoConflictAndRemoveOriginal(ownerId, id, payload)
        }
        val coordinator = LegacyRecoveryCoordinator(
            scanner = LegacyRecoveryScanner(fixture.storage),
            writer = gatedWriter,
            verifiedOwnerId = { fixture.currentOwner },
        )
        val attestation = fixture.attest(fixture.draftId, coordinator)
        val copy = async { coordinator.copy(attestation) }

        try {
            withTimeout(5_000) { copyPersisted.await() }
            fixture.currentOwner = OWNER_B
            releaseWriter.complete(Unit)
            val outcome = withTimeout(5_000) { copy.await() }

            assertEquals(LegacyRecoveryCopyResult.COPY_MAY_HAVE_COMPLETED_FOR_OLD_ACCOUNT, outcome)
            assertEquals("synthetic local note", fixture.writer.target(OWNER_A, fixture.draftId.date)?.note)
            assertNull(fixture.writer.target(OWNER_B, fixture.draftId.date))
            assertEquals(fixture.originalDraft, fixture.storage.drafts["diarium_draft_${fixture.draftId.date}"])
            assertEquals(
                LegacyRecoveryFinalizeResult.COPY_REQUIRED,
                coordinator.confirmNoConflictAndRemoveOriginal(attestation, confirmedNoConflict = true),
            )
            assertEquals(fixture.originalDraft, fixture.storage.drafts["diarium_draft_${fixture.draftId.date}"])
            assertEquals(0, fixture.writer.serverWriteCount)
        } finally {
            releaseWriter.complete(Unit)
            if (copy.isActive) copy.cancelAndJoin()
        }
    }

    @Test
    fun `queued save recovery copies only diary entry as a draft and retains its outbox source`() = runBlocking {
        val fixture = Fixture()
        fixture.currentOwner = OWNER_A
        val id = LegacyRecoveryRecordId(LegacyRecoveryType.PENDING_SAVE, "2026-10-04")
        val original = fixture.storage.pendingSaves[id.date]

        val attestation = fixture.attest(id)
        assertEquals(LegacyRecoveryCopyResult.COPIED, fixture.coordinator.copy(attestation))
        assertEquals(DiaryEntry(mood = 3), fixture.writer.target(OWNER_A, id.date))
        assertEquals(original, fixture.storage.pendingSaves[id.date])
        assertEquals(0, fixture.writer.serverWriteCount)
    }

    @Test
    fun `malformed and unsupported legacy entries cannot be previewed or copied`() = runBlocking {
        val fixture = Fixture().apply {
            storage.drafts["diarium_draft_2026-10-02"] = "{broken synthetic JSON"
            storage.drafts["diarium_draft_2026-10-03"] =
                "{\"note\":\"synthetic note\",\"access_token\":\"synthetic-token\"}"
        }
        fixture.currentOwner = OWNER_A
        val inventory = (fixture.coordinator.scan() as LegacyRecoveryScanResult.Ready).inventory
        assertEquals(
            LegacyRecoveryRecordStatus.MALFORMED,
            inventory.records.single { it.date == "2026-10-02" }.status,
        )
        val unsupported = inventory.records.single { it.date == "2026-10-03" }
        assertEquals(LegacyRecoveryRecordStatus.UNSUPPORTED, unsupported.status)
        assertFalse(inventory.toString().contains("synthetic-token"))
        assertFalse(inventory.toString().contains("access_token"))
        assertEquals(
            LegacyRecoveryAuthorizationResult.RecordNotRecoverable,
            fixture.coordinator.authorize(
                unsupported.id,
                OWNER_A,
                ownershipConfirmed = true,
                localPrivacyConfirmed = true,
            ),
        )
        assertEquals(0, fixture.writer.serverWriteCount)
    }

    @Test
    fun `existing account draft conflicts without changing either draft`() = runBlocking {
        val fixture = Fixture()
        fixture.currentOwner = OWNER_A
        val different = DiaryEntry(mood = 5, note = "synthetic existing account draft")
        fixture.writer.seedTarget(OWNER_A, fixture.draftId, different)
        val original = fixture.writer.target(OWNER_A, fixture.draftId.date)

        val attestation = fixture.attest(fixture.draftId)
        assertEquals(LegacyRecoveryCopyResult.TARGET_CONFLICT, fixture.coordinator.copy(attestation))
        assertEquals(original, fixture.writer.target(OWNER_A, fixture.draftId.date))
        assertEquals(fixture.originalDraft, fixture.storage.drafts["diarium_draft_${fixture.draftId.date}"])
        assertEquals(0, fixture.writer.serverWriteCount)
    }

    @Test
    fun `interrupted recovery survives restart but source removal waits for a verified save`() = runBlocking {
        val fixture = Fixture()
        fixture.currentOwner = OWNER_A
        fixture.writer.interruptAfterNextCopy = true
        val firstAttestation = fixture.attest(fixture.draftId)
        assertEquals(LegacyRecoveryCopyResult.FAILED, fixture.coordinator.copy(firstAttestation))
        assertNotNull(fixture.writer.target(OWNER_A, fixture.draftId.date))
        assertEquals(fixture.originalDraft, fixture.storage.drafts["diarium_draft_${fixture.draftId.date}"])

        // A new coordinator represents a fresh app process over the same durable stores.
        val restarted = fixture.newCoordinator()
        val attestationAfterRestart = fixture.attest(fixture.draftId, restarted)
        assertEquals(LegacyRecoveryCopyResult.ALREADY_COPIED, restarted.copy(attestationAfterRestart))
        assertEquals(
            LegacyRecoveryFinalizeResult.CONFIRMATION_REQUIRED,
            restarted.confirmNoConflictAndRemoveOriginal(attestationAfterRestart, confirmedNoConflict = false),
        )
        assertEquals(
            LegacyRecoveryFinalizeResult.SERVER_SAVE_REQUIRED,
            restarted.confirmNoConflictAndRemoveOriginal(attestationAfterRestart, confirmedNoConflict = true),
        )
        assertEquals(fixture.originalDraft, fixture.storage.drafts["diarium_draft_${fixture.draftId.date}"])
        fixture.writer.markVerifiedSave(OWNER_A, fixture.draftId)
        assertEquals(
            LegacyRecoveryFinalizeResult.ORIGINAL_REMOVED,
            restarted.confirmNoConflictAndRemoveOriginal(attestationAfterRestart, confirmedNoConflict = true),
        )
        assertNull(fixture.storage.drafts["diarium_draft_${fixture.draftId.date}"])
        assertEquals(0, fixture.writer.serverWriteCount)
    }

    @Test
    fun `edited recovered target retains source when final confirmation detects conflict`() = runBlocking {
        val fixture = Fixture()
        fixture.currentOwner = OWNER_A
        val attestation = fixture.attest(fixture.draftId)
        assertEquals(LegacyRecoveryCopyResult.COPIED, fixture.coordinator.copy(attestation))
        fixture.writer.editTarget(OWNER_A, fixture.draftId.date, DiaryEntry(mood = 1, note = "changed locally"))

        assertEquals(
            LegacyRecoveryFinalizeResult.TARGET_CHANGED,
            fixture.coordinator.confirmNoConflictAndRemoveOriginal(attestation, confirmedNoConflict = true),
        )
        assertEquals(fixture.originalDraft, fixture.storage.drafts["diarium_draft_${fixture.draftId.date}"])
        assertEquals(0, fixture.writer.serverWriteCount)
    }

    private class Fixture {
        val draftId = LegacyRecoveryRecordId(LegacyRecoveryType.DRAFT, "2026-10-01")
        val originalDraft = "{\"mood\":4,\"note\":\"synthetic local note\"}"
        val storage = MemoryRecoveryStorage().apply {
            drafts["diarium_draft_${draftId.date}"] = originalDraft
            drafts["diarium_draft_2026-10-02"] = "{broken synthetic JSON"
            drafts["diarium_draft_2026-10-03"] = "{\"note\":\"synthetic note\",\"access_token\":\"synthetic-token\"}"
            pendingSaves["2026-10-04"] = "{\"queuedAt\":4,\"entry\":{\"mood\":3}}"
            profileFields += setOf("user_name", "birth_year", "ai_enabled", "onboarding_done")
            aiSecretPresent = true
        }
        var currentOwner: String? = null
        val writer = MemoryRecoveryWriter(storage)
        val coordinator get() = newCoordinator()

        fun newCoordinator(): LegacyRecoveryCoordinator = LegacyRecoveryCoordinator(
            scanner = LegacyRecoveryScanner(storage),
            writer = writer,
            verifiedOwnerId = { currentOwner },
        )

        suspend fun attest(
            id: LegacyRecoveryRecordId,
            coordinator: LegacyRecoveryCoordinator = this.coordinator,
        ): LegacyRecoveryAttestation {
            val result = coordinator.authorize(
                id,
                currentOwner.orEmpty(),
                ownershipConfirmed = true,
                localPrivacyConfirmed = true,
            )
            assertTrue(result is LegacyRecoveryAuthorizationResult.Granted)
            val authorization = (result as LegacyRecoveryAuthorizationResult.Granted).attestation
            assertTrue(coordinator.preview(authorization) is LegacyRecoveryPreviewResult.Ready)
            return authorization
        }
    }

    private class MemoryRecoveryStorage : LegacyRecoveryStorage {
        val drafts = linkedMapOf<String, Any?>()
        val pendingSaves = linkedMapOf<String, Any?>()
        val profileFields = linkedSetOf<String>()
        var aiSecretPresent = false

        override suspend fun readDraftPreferences(): Map<String, Any?> = drafts.toMap()
        override suspend fun readPendingSavePreferences(): Map<String, Any?> = pendingSaves.toMap()
        override suspend fun readProfilePreferenceNames(): Set<String> = profileFields.toSet()
        override suspend fun legacyAiSecretStatus(): LegacySecretPresence =
            if (aiSecretPresent) LegacySecretPresence.PRESENT else LegacySecretPresence.ABSENT
    }

    private class MemoryRecoveryWriter(private val storage: MemoryRecoveryStorage) : LegacyRecoveryWriter {
        private data class Target(val origin: LegacyRecoveryRecordId?, var entry: DiaryEntry)
        private val targets = mutableMapOf<Pair<String, String>, Target>()
        private val verifiedSavedTargets = mutableSetOf<Pair<String, String>>()
        var interruptAfterNextCopy = false
        var serverWriteCount = 0
            private set

        fun seedTarget(owner: String, id: LegacyRecoveryRecordId, entry: DiaryEntry) {
            targets[owner to id.date] = Target(origin = null, entry = entry)
        }

        fun editTarget(owner: String, date: String, entry: DiaryEntry) {
            targets.getValue(owner to date).entry = entry
        }

        fun markVerifiedSave(owner: String, id: LegacyRecoveryRecordId) {
            verifiedSavedTargets += owner to id.date
        }

        fun target(owner: String, date: String): DiaryEntry? = targets[owner to date]?.entry

        override suspend fun copyToAccountDraft(
            ownerId: String,
            id: LegacyRecoveryRecordId,
            payload: LegacyRecoveryPayload,
        ): LegacyRecoveryCopyResult {
            val source = sourceValue(id)
            if (source != payload.sourceRaw) return LegacyRecoveryCopyResult.SOURCE_CHANGED
            val key = ownerId to id.date
            val prior = targets[key]
            if (prior != null) {
                return if (prior.origin == id && prior.entry == payload.entry) {
                    LegacyRecoveryCopyResult.ALREADY_COPIED
                } else {
                    LegacyRecoveryCopyResult.TARGET_CONFLICT
                }
            }
            targets[key] = Target(origin = id, entry = payload.entry)
            if (interruptAfterNextCopy) {
                interruptAfterNextCopy = false
                return LegacyRecoveryCopyResult.FAILED
            }
            return LegacyRecoveryCopyResult.COPIED
        }

        override suspend fun confirmNoConflictAndRemoveOriginal(
            ownerId: String,
            id: LegacyRecoveryRecordId,
            payload: LegacyRecoveryPayload,
        ): LegacyRecoveryFinalizeResult {
            if (sourceValue(id) != payload.sourceRaw) return LegacyRecoveryFinalizeResult.SOURCE_CHANGED
            val target = targets[ownerId to id.date] ?: return LegacyRecoveryFinalizeResult.TARGET_CHANGED
            if (target.origin != id || target.entry != payload.entry) return LegacyRecoveryFinalizeResult.TARGET_CHANGED
            if (ownerId to id.date !in verifiedSavedTargets) return LegacyRecoveryFinalizeResult.SERVER_SAVE_REQUIRED
            removeSource(id)
            return LegacyRecoveryFinalizeResult.ORIGINAL_REMOVED
        }

        private fun sourceValue(id: LegacyRecoveryRecordId): Any? = when (id.type) {
            LegacyRecoveryType.DRAFT -> storage.drafts["diarium_draft_${id.date}"]
            LegacyRecoveryType.PENDING_SAVE -> storage.pendingSaves[id.date]
        }

        private fun removeSource(id: LegacyRecoveryRecordId) {
            when (id.type) {
                LegacyRecoveryType.DRAFT -> storage.drafts.remove("diarium_draft_${id.date}")
                LegacyRecoveryType.PENDING_SAVE -> storage.pendingSaves.remove(id.date)
            }
        }
    }

    private companion object {
        const val OWNER_A = "synthetic-owner-a"
        const val OWNER_B = "synthetic-owner-b"
    }
}
