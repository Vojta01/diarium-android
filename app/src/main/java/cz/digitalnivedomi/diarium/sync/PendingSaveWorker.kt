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
import cz.digitalnivedomi.diarium.core.data.PendingSaveStore
import cz.digitalnivedomi.diarium.core.data.SessionContext
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Preserves owner-bound outbox records for explicit recovery. The current server
 * exposes a blind full-row save RPC with no verifiable atomic compare-and-swap, so
 * this worker intentionally does not send or clear queued diary data. Its successful
 * WorkManager result means only "no background action was safe" — never "synced".
 *
 * Work requests are scoped to the owner captured at enqueue time. APPEND_OR_REPLACE
 * closes the KEEP exit race for legacy queued requests; even those legacy workers
 * now fail closed and leave their local records untouched.
 */
class PendingSaveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val session = AppClients.sessionStore(applicationContext)
        val ownerId = ownerFromInput(inputData) ?: return Result.success()
        if (SessionContext(session).userId() != ownerId) return Result.success()

        val store = PendingSaveStore.from(applicationContext, ownerId)
        if (!store.hasAny()) return Result.success()

        // save_daily_entry is a blind full-row upsert and the repository has no
        // verified atomic compare-and-swap RPC. A background replay could overwrite
        // another device's newer entry, so leave every queued record intact for the
        // Check-in recovery UI. Returning success prevents an endless retry loop;
        // it does NOT mean these local records were synced.
        return Result.success()
    }

    companion object {

        /** Base name; owner-specific suffixes prevent cross-account coalescing. */
        const val WORK_NAME = "diarium.pending.saves"

        /** Unique-work policy appends a request that races an exiting worker instead of dropping it. */
        internal val uniqueWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE
        private const val INPUT_OWNER_ID = "owner_id"

        internal fun ownerFromInput(inputData: Data): String? =
            inputData.getString(INPUT_OWNER_ID)?.takeIf { it.isNotBlank() }

        fun workName(ownerId: String): String =
            "$WORK_NAME.${Base64.getUrlEncoder().withoutPadding().encodeToString(ownerId.toByteArray(Charsets.UTF_8))}"

        /** Resolve the current owner only for legacy callers that do not hold a repository. */
        fun enqueue(context: Context) {
            val ownerId = SessionContext(AppClients.sessionStore(context)).userId() ?: return
            enqueue(context, ownerId)
        }

        /** Enqueue only the explicitly bound owner's outbox. */
        fun enqueue(context: Context, ownerId: String?) {
            val owner = ownerId?.takeIf { it.isNotBlank() } ?: return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<PendingSaveWorker>()
                .setInputData(Data.Builder().putString(INPUT_OWNER_ID, owner).build())
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName(owner), uniqueWorkPolicy, request)
        }
    }
}
