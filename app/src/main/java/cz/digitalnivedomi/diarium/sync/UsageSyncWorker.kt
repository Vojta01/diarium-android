package cz.digitalnivedomi.diarium.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cz.digitalnivedomi.diarium.core.data.UsagePushOutcome
import cz.digitalnivedomi.diarium.core.data.UsageSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Background sync worker:
 *  - mode "today"     : snapshot current day's usage (used by the 21:00 job)
 *  - mode "yesterday" : full previous day (used by the 07:00 backfill + on-open check)
 *  - mode "backfill"  : last 7 days (used after install)
 *
 * Since M6 the push itself lives in [UsageSyncRepository]: this class only decides
 * *which* days to send and how WorkManager should treat the result. The repository
 * talks to Supabase's `save_daily_entry` RPC with the user's own JWT from
 * [cz.digitalnivedomi.diarium.auth.SessionStore] (via `SupabaseClient`), so there is
 * no longer a `BuildConfig.SAVE_ENTRY_URL` hop through Vercel and no server secret
 * in a public APK.
 */
class UsageSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val repository = UsageSyncRepository(appContext)

    override suspend fun doWork(): Result {
        val mode = inputData.getString("mode") ?: "today"
        return withContext(Dispatchers.IO) {
            try {
                val dates = when (mode) {
                    "today" -> listOf(canonicalToday())
                    "yesterday" -> listOf(canonicalYesterday())
                    // i=0 is TODAY — the chart must show the current day even before
                    // the 21:00 job fires. (Previously this loop started at 1, so
                    // today was never pushed and the current day stayed empty until
                    // the evening job.)
                    "backfill" -> (0..7).map { canonicalPastDay(it) }
                    else -> listOf(canonicalToday())
                }

                for (date in dates) {
                    when (repository.pushDay(date)) {
                        // Not logged in yet, or usage access not granted — retry
                        // later so the daily backfill still happens once the user
                        // signs in / flips the permission. validAccessToken()
                        // transparently refreshes an expired access token before
                        // any push is attempted.
                        UsagePushOutcome.NO_SESSION,
                        UsagePushOutcome.NO_USAGE_ACCESS -> return@withContext Result.retry()

                        // SENT, or nothing captured that day.
                        UsagePushOutcome.SENT,
                        UsagePushOutcome.EMPTY_DAY -> Unit
                    }
                }
                Result.success()
            } catch (e: Exception) {
                // A failed HTTP push throws out of the repository on purpose — the
                // next run retries and refreshes the token via validAccessToken().
                Log.e("DiariumSync", "sync failed: ${e.message}")
                Result.retry()
            }
        }
    }

    private fun canonicalToday(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        return sdf.format(Calendar.getInstance().time)
    }

    private fun canonicalYesterday(): String {
        return canonicalPastDay(1)
    }

    private fun canonicalPastDay(daysAgo: Int): String {
        val cal = Calendar.getInstance().apply { timeZone = TimeZone.getDefault() }
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
            .format(cal.time)
    }
}
