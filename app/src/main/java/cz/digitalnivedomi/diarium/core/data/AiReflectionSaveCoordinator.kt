package cz.digitalnivedomi.diarium.core.data

import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Persists generated prose exactly like a foreground check-in: local draft first,
 * then an immutable owner-bound conditional-v2 write against the generation snapshot.
 */
class AiReflectionSaveCoordinator(
    private val entries: EntriesRepository,
    private val drafts: DraftStore,
) {
    suspend fun save(
        date: String,
        snapshot: StoredEntrySnapshot?,
        fallbackEntry: DiaryEntry,
        reflection: String,
    ): Result<ConditionalSaveReceipt> {
        val ownerId = entries.currentUserId()
            ?: return Result.failure(SessionOwnerMismatchException())
        if (drafts.ownerId != ownerId) {
            return Result.failure(SessionOwnerMismatchException())
        }

        val baseEntry = snapshot?.entry ?: fallbackEntry
        val entry = baseEntry.copy(aiReflection = reflection)
        val baseline = snapshot?.let {
            EntryBaseline(exists = true, id = it.id, revision = it.revision)
        } ?: EntryBaseline(exists = false, id = null, revision = null)
        val baselineUpdatedAt = snapshot?.updatedAt
        if (!baseline.isValid) {
            val staged = stageDraft(
                date = date,
                entry = entry,
                baselineUpdatedAt = baselineUpdatedAt,
                baseline = baseline,
                requestId = null,
                reflection = reflection,
            )
            return staged.exceptionOrNull()?.let { Result.failure(it) }
                ?: Result.failure(
                    ConditionalSaveUnavailableException(
                        "The server row identity or revision is unverified. The generated reflection is preserved locally.",
                    ),
                )
        }

        val request = ConditionalEntrySaveRequest(
            ownerId = ownerId,
            date = date,
            entry = entry,
            baseline = baseline,
            requestId = UUID.randomUUID().toString(),
            writeAiReflection = true,
        )
        val staged = stageDraft(
            date = date,
            entry = entry,
            baselineUpdatedAt = baselineUpdatedAt,
            baseline = baseline,
            requestId = request.requestId,
            reflection = reflection,
        )
        staged.exceptionOrNull()?.let { return Result.failure(it) }

        val result = entries.save(request)
        if (result.isSuccess) {
            try {
                drafts.clearIfUnchanged(date, entry, request.requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A retained local copy is safe; the matching CAS request is idempotent.
            }
        } else if (result.exceptionOrNull() is ConditionalSaveConflictException) {
            try {
                drafts.clearRequestForConflict(date, entry, request.requestId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Leave the draft untouched if its recovery marker cannot be updated.
            }
        }
        return result
    }

    private suspend fun stageDraft(
        date: String,
        entry: DiaryEntry,
        baselineUpdatedAt: String?,
        baseline: EntryBaseline,
        requestId: String?,
        reflection: String,
    ): Result<Unit> = try {
        drafts.stage(
            date = date,
            entry = entry,
            baselineUpdatedAt = baselineUpdatedAt,
            baselineExists = baseline.exists,
            baselineId = baseline.id,
            baselineRevision = baseline.revision,
            pendingSaveRequestId = requestId,
            pendingAiReflection = reflection,
        ).await()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
