package cz.digitalnivedomi.diarium.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import cz.digitalnivedomi.diarium.core.data.UsagePushOutcome
import cz.digitalnivedomi.diarium.core.data.UsageSyncRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Runs one-time and periodic screen-time work. Every supported enqueue path stores
 * the original owner in input data; legacy requests without that pin are discarded.
 */
class UsageSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val repository = UsageSyncRepository(appContext)

    override suspend fun doWork(): Result {
        val ownerId = ownerFromInput(inputData) ?: return Result.success()
        val mode = inputData.getString(INPUT_MODE) ?: "today"
        val dates = datesFor(mode)

        return withContext(Dispatchers.IO) {
            when (runUsageSync(ownerId, dates, repository::pushDay)) {
                UsageSyncAttempt.SUCCESS -> Result.success()
                UsageSyncAttempt.RETRY -> Result.retry()
            }
        }
    }

    private fun datesFor(mode: String): List<String> = when (mode) {
        "today" -> listOf(canonicalToday())
        "yesterday" -> listOf(canonicalPastDay(1))
        // i=0 is TODAY so the chart shows the current day before the evening job.
        "backfill" -> (0..7).map(::canonicalPastDay)
        else -> listOf(canonicalToday())
    }

    private fun canonicalToday(): String = canonicalPastDay(0)

    private fun canonicalPastDay(daysAgo: Int): String {
        val cal = Calendar.getInstance().apply {
            timeZone = TimeZone.getDefault()
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
            .format(cal.time)
    }

    companion object {
        private const val INPUT_MODE = "mode"
        private const val INPUT_OWNER_ID = "usage_owner_id"

        /** Immutable owner/mode payload for every scheduler and auth enqueue path. */
        internal fun inputData(mode: String, ownerId: String): Data {
            require(ownerId.isNotBlank()) { "Usage sync owner id must not be blank" }
            return Data.Builder()
                .putString(INPUT_MODE, mode)
                .putString(INPUT_OWNER_ID, ownerId)
                .build()
        }

        /** Ownerless legacy requests must not inherit whichever account is active. */
        internal fun ownerFromInput(inputData: Data): String? =
            inputData.getString(INPUT_OWNER_ID)?.takeIf { it.isNotBlank() }

        /** Same execution/result mapping used by the CoroutineWorker and retry tests. */
        internal suspend fun runUsageSync(
            ownerId: String?,
            dates: List<String>,
            pushDay: suspend (ownerId: String, date: String) -> UsagePushOutcome,
        ): UsageSyncAttempt {
            val capturedOwner = ownerId?.takeIf { it.isNotBlank() } ?: return UsageSyncAttempt.SUCCESS
            for (date in dates) {
                val outcome = try {
                    pushDay(capturedOwner, date)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.e("DiariumSync", "sync failed: ${failure.message}")
                    return UsageSyncAttempt.RETRY
                }
                when (outcome) {
                    UsagePushOutcome.NO_SESSION,
                    UsagePushOutcome.NO_USAGE_ACCESS -> return UsageSyncAttempt.RETRY

                    UsagePushOutcome.OWNER_CHANGED,
                    UsagePushOutcome.CONSENT_DISABLED -> return UsageSyncAttempt.SUCCESS

                    UsagePushOutcome.SENT,
                    UsagePushOutcome.EMPTY_DAY -> Unit
                }
            }
            return UsageSyncAttempt.SUCCESS
        }
    }
}

internal enum class UsageSyncAttempt { SUCCESS, RETRY }
