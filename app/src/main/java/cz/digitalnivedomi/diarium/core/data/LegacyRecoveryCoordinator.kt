package cz.digitalnivedomi.diarium.core.data

import kotlinx.coroutines.CancellationException

/**
 * The only write-side port used by manual legacy recovery. It intentionally has no
 * network, repository, queue, or server-save dependency: imports create local drafts.
 */
interface LegacyRecoveryWriter {
    suspend fun copyToAccountDraft(
        ownerId: String,
        id: LegacyRecoveryRecordId,
        payload: LegacyRecoveryPayload,
    ): LegacyRecoveryCopyResult

    suspend fun confirmNoConflictAndRemoveOriginal(
        ownerId: String,
        id: LegacyRecoveryRecordId,
        payload: LegacyRecoveryPayload,
    ): LegacyRecoveryFinalizeResult
}

class LegacyRecoveryAttestation internal constructor(
    val recordId: LegacyRecoveryRecordId,
    val ownerId: String,
) {
    internal var contentsPreviewed: Boolean = false
    internal var copyCompleted: Boolean = false
}

sealed interface LegacyRecoveryScanResult {
    data class Ready(val ownerId: String, val inventory: LegacyRecoveryInventory) : LegacyRecoveryScanResult
    data object OwnerUnavailable : LegacyRecoveryScanResult
    data object OwnerChanged : LegacyRecoveryScanResult
    data object ReadFailed : LegacyRecoveryScanResult
}

sealed interface LegacyRecoveryAuthorizationResult {
    data class Granted(val attestation: LegacyRecoveryAttestation) : LegacyRecoveryAuthorizationResult
    data object ConfirmationRequired : LegacyRecoveryAuthorizationResult
    data object OwnerUnavailable : LegacyRecoveryAuthorizationResult
    data object OwnerChanged : LegacyRecoveryAuthorizationResult
    data object RecordNotRecoverable : LegacyRecoveryAuthorizationResult
    data object ReadFailed : LegacyRecoveryAuthorizationResult
}

sealed interface LegacyRecoveryPreviewResult {
    data class Ready(val contents: LegacyRecoveryContents) : LegacyRecoveryPreviewResult
    data object NotAuthorized : LegacyRecoveryPreviewResult
    data object OwnerUnavailable : LegacyRecoveryPreviewResult
    data object OwnerChanged : LegacyRecoveryPreviewResult
    data object RecordNotRecoverable : LegacyRecoveryPreviewResult
    data object ReadFailed : LegacyRecoveryPreviewResult
}

enum class LegacyRecoveryCopyResult {
    COPIED,
    ALREADY_COPIED,
    TARGET_CONFLICT,
    SOURCE_CHANGED,
    OWNER_UNAVAILABLE,
    OWNER_CHANGED,
    PREVIEW_REQUIRED,
    RECORD_NOT_RECOVERABLE,
    FAILED,
}

enum class LegacyRecoveryFinalizeResult {
    ORIGINAL_REMOVED,
    CONFIRMATION_REQUIRED,
    TARGET_CHANGED,
    SOURCE_CHANGED,
    OWNER_UNAVAILABLE,
    OWNER_CHANGED,
    COPY_REQUIRED,
    RECORD_NOT_RECOVERABLE,
    FAILED,
}

/**
 * Mediates the manual workflow. Inventory is text-free. A selected record's
 * contents are returned only after the caller explicitly attests both ownership
 * and local privacy. Every operation rechecks the persisted verified owner.
 */
class LegacyRecoveryCoordinator(
    private val scanner: LegacyRecoveryScanner,
    private val writer: LegacyRecoveryWriter,
    private val verifiedOwnerId: () -> String?,
) {
    suspend fun scan(): LegacyRecoveryScanResult {
        val owner = verifiedOwnerId()?.takeIf { it.isNotBlank() }
            ?: return LegacyRecoveryScanResult.OwnerUnavailable
        val inventory = try {
            scanner.scan()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LegacyRecoveryScanResult.ReadFailed
        }
        if (verifiedOwnerId() != owner) return LegacyRecoveryScanResult.OwnerChanged
        return LegacyRecoveryScanResult.Ready(owner, inventory)
    }

    suspend fun authorize(
        id: LegacyRecoveryRecordId,
        assertedOwnerId: String,
        ownershipConfirmed: Boolean,
        localPrivacyConfirmed: Boolean,
    ): LegacyRecoveryAuthorizationResult {
        val owner = verifiedOwnerId()?.takeIf { it.isNotBlank() }
            ?: return LegacyRecoveryAuthorizationResult.OwnerUnavailable
        if (owner != assertedOwnerId) return LegacyRecoveryAuthorizationResult.OwnerChanged
        if (!ownershipConfirmed || !localPrivacyConfirmed) {
            return LegacyRecoveryAuthorizationResult.ConfirmationRequired
        }
        val inventory = try {
            scanner.scan()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LegacyRecoveryAuthorizationResult.ReadFailed
        }
        if (verifiedOwnerId() != owner) return LegacyRecoveryAuthorizationResult.OwnerChanged
        val record = inventory.records.firstOrNull { it.id == id }
            ?: return LegacyRecoveryAuthorizationResult.RecordNotRecoverable
        if (record.status != LegacyRecoveryRecordStatus.PREVIEWABLE) {
            return LegacyRecoveryAuthorizationResult.RecordNotRecoverable
        }
        return LegacyRecoveryAuthorizationResult.Granted(LegacyRecoveryAttestation(id, owner))
    }

    suspend fun preview(attestation: LegacyRecoveryAttestation?): LegacyRecoveryPreviewResult {
        val authorization = attestation ?: return LegacyRecoveryPreviewResult.NotAuthorized
        val currentOwner = verifiedOwnerId()?.takeIf { it.isNotBlank() }
            ?: return LegacyRecoveryPreviewResult.OwnerUnavailable
        if (currentOwner != authorization.ownerId) return LegacyRecoveryPreviewResult.OwnerChanged
        val payload = readAuthorizedPayload(authorization.recordId)
            ?: return if (verifiedOwnerId() == authorization.ownerId) {
                LegacyRecoveryPreviewResult.RecordNotRecoverable
            } else {
                LegacyRecoveryPreviewResult.OwnerChanged
            }
        if (verifiedOwnerId() != authorization.ownerId) return LegacyRecoveryPreviewResult.OwnerChanged
        authorization.contentsPreviewed = true
        return LegacyRecoveryPreviewResult.Ready(LegacyRecoveryContents(authorization.recordId, payload.entry))
    }

    suspend fun copy(attestation: LegacyRecoveryAttestation): LegacyRecoveryCopyResult {
        if (!attestation.contentsPreviewed) return LegacyRecoveryCopyResult.PREVIEW_REQUIRED
        val owner = verifiedOwnerId()?.takeIf { it.isNotBlank() }
            ?: return LegacyRecoveryCopyResult.OWNER_UNAVAILABLE
        if (owner != attestation.ownerId) return LegacyRecoveryCopyResult.OWNER_CHANGED
        val payload = readAuthorizedPayload(attestation.recordId)
            ?: return if (verifiedOwnerId() == owner) {
                LegacyRecoveryCopyResult.RECORD_NOT_RECOVERABLE
            } else {
                LegacyRecoveryCopyResult.OWNER_CHANGED
            }
        val outcome = try {
            writer.copyToAccountDraft(owner, attestation.recordId, payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LegacyRecoveryCopyResult.FAILED
        }
        if (verifiedOwnerId() != owner) return LegacyRecoveryCopyResult.OWNER_CHANGED
        if (outcome == LegacyRecoveryCopyResult.COPIED || outcome == LegacyRecoveryCopyResult.ALREADY_COPIED) {
            attestation.copyCompleted = true
        }
        return outcome
    }

    suspend fun confirmNoConflictAndRemoveOriginal(
        attestation: LegacyRecoveryAttestation,
        confirmedNoConflict: Boolean,
    ): LegacyRecoveryFinalizeResult {
        if (!confirmedNoConflict) return LegacyRecoveryFinalizeResult.CONFIRMATION_REQUIRED
        if (!attestation.copyCompleted) return LegacyRecoveryFinalizeResult.COPY_REQUIRED
        val owner = verifiedOwnerId()?.takeIf { it.isNotBlank() }
            ?: return LegacyRecoveryFinalizeResult.OWNER_UNAVAILABLE
        if (owner != attestation.ownerId) return LegacyRecoveryFinalizeResult.OWNER_CHANGED
        val payload = readAuthorizedPayload(attestation.recordId)
            ?: return if (verifiedOwnerId() == owner) {
                LegacyRecoveryFinalizeResult.RECORD_NOT_RECOVERABLE
            } else {
                LegacyRecoveryFinalizeResult.OWNER_CHANGED
            }
        val outcome = try {
            writer.confirmNoConflictAndRemoveOriginal(owner, attestation.recordId, payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LegacyRecoveryFinalizeResult.FAILED
        }
        if (verifiedOwnerId() != owner) return LegacyRecoveryFinalizeResult.OWNER_CHANGED
        return outcome
    }

    private suspend fun readAuthorizedPayload(id: LegacyRecoveryRecordId): LegacyRecoveryPayload? {
        return try {
            val inventory = scanner.scan()
            val record = inventory.records.firstOrNull { it.id == id } ?: return null
            if (record.status != LegacyRecoveryRecordStatus.PREVIEWABLE) return null
            scanner.readPayload(id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
