package cz.digitalnivedomi.diarium.core.data

import android.content.Context

/**
 * Local-only implementation of [LegacyRecoveryWriter]. It copies into DraftStore
 * and never touches EntriesRepository, PendingSaveWorker, or any HTTP client.
 */
class PreferenceLegacyRecoveryWriter(
    context: Context,
    draftStoreFactory: ((String) -> DraftStore)? = null,
) : LegacyRecoveryWriter {
    private val appContext = context.applicationContext
    private val draftStoreForOwner = draftStoreFactory ?: { ownerId -> DraftStore.from(appContext, ownerId) }
    private val ownerlessPendingSaves = PendingSaveStore.from(appContext, ownerId = null)

    override suspend fun copyToAccountDraft(
        ownerId: String,
        id: LegacyRecoveryRecordId,
        payload: LegacyRecoveryPayload,
    ): LegacyRecoveryCopyResult {
        if (ownerId.isBlank()) return LegacyRecoveryCopyResult.OWNER_UNAVAILABLE
        if (id.date.isBlank()) return LegacyRecoveryCopyResult.RECORD_NOT_RECOVERABLE
        val target = draftStoreForOwner(ownerId)
        val sourceUnchanged = {
            when (id.type) {
                LegacyRecoveryType.DRAFT -> true // Checked atomically inside the shared DataStore edit.
                LegacyRecoveryType.PENDING_SAVE -> ownerlessPendingSaves.legacyRecoveryValue(id.date) == payload.sourceRaw
            }
        }
        return target.importLegacyRecoveryIfAbsent(
            date = id.date,
            entry = payload.entry,
            origin = id,
            sourceRaw = payload.sourceRaw,
            sourceStillUnchanged = sourceUnchanged,
        )
    }

    override suspend fun confirmNoConflictAndRemoveOriginal(
        ownerId: String,
        id: LegacyRecoveryRecordId,
        payload: LegacyRecoveryPayload,
    ): LegacyRecoveryFinalizeResult {
        if (ownerId.isBlank()) return LegacyRecoveryFinalizeResult.OWNER_UNAVAILABLE
        val target = draftStoreForOwner(ownerId)
        return when (id.type) {
            LegacyRecoveryType.DRAFT -> target.confirmAndRemoveLegacyDraftSource(
                date = id.date,
                origin = id,
                sourceRaw = payload.sourceRaw,
                expectedEntry = payload.entry,
            )
            LegacyRecoveryType.PENDING_SAVE -> target.confirmAndRemoveLegacyPendingSource(
                date = id.date,
                origin = id,
                expectedEntry = payload.entry,
                removeSourceIfUnchanged = {
                    ownerlessPendingSaves.removeLegacyRecoveryValueIfUnchanged(id.date, payload.sourceRaw)
                },
            )
        }
    }
}
