package cz.digitalnivedomi.diarium.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.digitalnivedomi.diarium.core.data.AppClients
import cz.digitalnivedomi.diarium.core.data.DraftStore
import cz.digitalnivedomi.diarium.core.data.EntriesRepository
import cz.digitalnivedomi.diarium.core.data.PendingSaveStore
import cz.digitalnivedomi.diarium.core.data.SessionContext
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Flushes the offline outbox (see [PendingSaveStore]): re-sends every check-in
 * that was saved while the phone had no network, oldest day first, until the
 * store is empty.
 *
 * Deliberately builds an [EntriesRepository] *without* the pending store: a
 * flush that fails again must leave the row queued for WorkManager's retry, not
 * re-enqueue it through the repository's own offline path (which would just
 * write the same blob back). The cache is not wired here either — the screen
 * that queued the save already fed it, and the next successful save/read keeps
 * it fresh.
 *
 * The work is scheduled by the app itself (the save path and `SyncScheduler`),
 * never by a server — the app-driven timing rule the whole sync layer follows.
 */
class PendingSaveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val store = PendingSaveStore.from(applicationContext)
        if (!store.hasAny()) return Result.success()

        val session = AppClients.sessionStore(applicationContext)
        val entries = EntriesRepository(
            AppClients.supabaseClient(applicationContext),
            SessionContext(session),
        )
        val drafts = DraftStore.from(applicationContext)

        // A loop, not a single pass: a day queued while this worker runs (the
        // user saved again before the flush finished) is picked up next round.
        while (true) {
            val next = store.peekAll().firstOrNull() ?: return Result.success()
            val outcome = try {
                entries.save(next.entry, next.date)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (outcome == null || outcome.isFailure) {
                // Still offline (or a server hiccup): keep the row and let
                // WorkManager retry with backoff. The overlay keeps showing the
                // day as saved meanwhile.
                return Result.retry()
            }
            store.remove(next.date)
            // The local draft has served its purpose once the day is on the
            // server (the check-in screen clears it at queue time too — this
            // covers a queued save the screen never had a draft for).
            runCatching { drafts.clear(next.date) }
        }
    }

    companion object {

        /** Unique name — one flush at a time is enough; enqueues are deduplicated. */
        const val WORK_NAME = "diarium.pending.saves"

        /**
         * Enqueues a flush attempt. `KEEP` on purpose: the running (or waiting)
         * flush will pick up anything queued meanwhile, so a second request does
         * not need a second job.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<PendingSaveWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
