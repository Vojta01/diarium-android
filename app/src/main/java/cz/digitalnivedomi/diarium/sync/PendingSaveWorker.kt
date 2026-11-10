package cz.digitalnivedomi.diarium.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.digitalnivedomi.diarium.core.data.AppClients
import cz.digitalnivedomi.diarium.core.data.DraftStore
import cz.digitalnivedomi.diarium.core.data.EntriesCacheStore
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.PendingSaveFlush
import cz.digitalnivedomi.diarium.core.data.PendingSaveStore
import cz.digitalnivedomi.diarium.core.data.PhotoCandidateStore
import cz.digitalnivedomi.diarium.core.data.SessionContext
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Replays only explicit user saves through immutable, owner-bound v2 CAS requests. */
class PendingSaveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val session = AppClients.sessionStore(applicationContext)
        val ownerId = ownerFromInput(inputData) ?: return Result.success()
        if (session.userSubject() != ownerId) return Result.success()

        val store = PendingSaveStore.from(applicationContext, ownerId)
        val queued = store.peekAll()
        if (queued.isEmpty()) return Result.success()
        val sessionContext = SessionContext(session)
        val repository = EntriesRepository(
            client = AppClients.supabaseClient(applicationContext),
            session = sessionContext,
            cache = EntriesCacheStore.from(applicationContext, ownerId),
            pending = store,
            expectedOwnerId = ownerId,
            photoCandidates = PhotoCandidateStore.from(applicationContext, ownerId),
            photoCleanupOutbox = store,
        )

        for (candidate in queued) {
            if (!candidate.isReplayable) continue
            val outcome = store.flushRecord(
                date = candidate.date,
                expectedOwnerId = ownerId,
                currentOwnerId = { session.userSubject() },
                dispatch = { record ->
                    val request = record.conditionalRequest
                        ?: return@flushRecord Result.failure(IllegalStateException("CAS request is missing."))
                    repository.replayPendingSave(request).map { Unit }
                },
                afterSaved = { record ->
                    record.conditionalRequest?.let { request ->
                        DraftStore.from(applicationContext, ownerId)
                            .clearIfUnchanged(request.date, request.entry, request.requestId)
                    }
                },
            )
            when (outcome) {
                PendingSaveFlush.Retry -> return Result.retry()
                PendingSaveFlush.SessionChanged -> return Result.success()
                PendingSaveFlush.Changed -> Unit
                is PendingSaveFlush.RecoveryRequired -> Unit
                is PendingSaveFlush.Saved -> Unit
            }
        }
        return Result.success()
    }

    companion object {

        /** Base name; owner-specific suffixes prevent cross-account coalescing. */
        const val WORK_NAME = "diarium.pending.saves"
        const val TAG_PENDING_SAVE = "diarium.pending.saves"

        /** Unique-work policy appends a request that races an exiting worker instead of dropping it. */
        internal val uniqueWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE
        private const val INPUT_OWNER_ID = "owner_id"

        internal fun ownerFromInput(inputData: Data): String? =
            inputData.getString(INPUT_OWNER_ID)?.takeIf { it.isNotBlank() }

        fun workName(ownerId: String): String =
            "$WORK_NAME.${Base64.getUrlEncoder().withoutPadding().encodeToString(ownerId.toByteArray(Charsets.UTF_8))}"

        internal fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        internal fun workRequest(ownerId: String): androidx.work.OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<PendingSaveWorker>()
                .setInputData(Data.Builder().putString(INPUT_OWNER_ID, ownerId).build())
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG_PENDING_SAVE)
                .build()

        /** Enqueue only the explicitly bound owner's outbox. */
        fun enqueue(context: Context, ownerId: String?) {
            val owner = ownerId?.takeIf { it.isNotBlank() } ?: return
            if (AppClients.sessionStore(context).userSubject() != owner) return
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(owner), uniqueWorkPolicy, workRequest(owner))
        }

        /** Re-arm only the current account's verified queue after process restart. */
        fun ensureScheduled(context: Context) {
            val owner = AppClients.sessionStore(context).userSubject() ?: return
            if (PendingSaveStore.from(context, owner).hasReplayable()) enqueue(context, owner)
        }

        /** Account changes cancel dispatches but never clear either owner's local data. */
        fun onSessionChanged(context: Context, expectedOwnerId: String) {
            cancelAll(context)
            if (AppClients.sessionStore(context).userSubject() != expectedOwnerId) return
            if (PendingSaveStore.from(context, expectedOwnerId).hasReplayable()) {
                enqueue(context, expectedOwnerId)
            }
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG_PENDING_SAVE)
        }
    }
}
