package cz.digitalnivedomi.diarium.stats

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import cz.digitalnivedomi.diarium.core.stats.DayUsage
import cz.digitalnivedomi.diarium.core.stats.DayUsageFolder
import cz.digitalnivedomi.diarium.core.stats.UsageEvent
import cz.digitalnivedomi.diarium.core.stats.UsageEventType
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Reads the exact Android usage statistics the Digital Wellbeing screen shows —
 * per-app foreground time, total time, and unlock count.
 *
 * Uses [UsageStatsManager] with PACKAGE_USAGE_STATS (user grants via
 * Settings → Apps with usage access). No HA, no heuristics: this is the source
 * of truth the phone itself reports.
 *
 * Since M6 this class is a **thin Android adapter**: it queries the events and
 * resolves an app label, and hands both to [DayUsageFolder.fold], where all the
 * arithmetic lives and which is unit tested on the JVM. The fold is exactly the
 * algorithm this file used to inline (see `UsageStatsProvider.kt:117–233` before
 * M6) — it was lifted, not rewritten, so the Digital Wellbeing parity is unchanged.
 */
class UsageStatsProvider(private val context: Context) {

    /** [DayUsageFolder.DEFAULT_IGNORED] — one source of truth, still exposed here. */
    private val ignored = DayUsageFolder.DEFAULT_IGNORED

    // Known package→friendly label fallbacks for apps where package
    // visibility hides the real label (Android 11+).
    private val knownLabels = mapOf(
        "org.telegram.messenger" to "Telegram",
        "com.google.android.apps.maps" to "Mapy",
        "com.google.android.apps.photos" to "Fotky",
        "com.google.android.dialer" to "Telefon",
        "com.google.android.gm" to "Gmail",
        "com.android.browser" to "Prohlížeč",
    )

    /** Whether the user has granted "usage access" to this app. */
    @Suppress("DEPRECATION")
    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Usage-access state for onboarding copy:
     *  - MODE_ALLOWED → granted
     *  - MODE_DENIED  → the system remembers an explicit denial; it persists
     *    even after reinstalling (the toggle shows greyed out). User must
     *    uninstall + reinstall once, or the app can't be granted again.
     *  - else        → not decided yet, a normal prompt is possible.
     */
    @Suppress("DEPRECATION")
    fun usageAccessState(): String {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                context.packageName
            )
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_ERRORED -> "denied" // system remembers explicit denial
            else -> "undecided"
        }
    }

    fun openUsageAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /**
     * Per-app stats for [date] in local timezone (full calendar day), as the pure
     * [DayUsageFolder] computes them.
     *
     * EVENT-BASED (matches Digital Wellbeing exactly):
     *   - totalSec = display-ON time (SCREEN_INTERACTIVE → SCREEN_NON_INTERACTIVE)
     *   - unlocks  = count of SCREEN_INTERACTIVE events
     *   - per-app  = ACTIVITY_RESUMED → ACTIVITY_PAUSED, only while screen is ON
     * This replaces the old `queryUsageStats().totalTimeInForeground` approach,
     * which aggregates whole usage "day buckets" and over-counts (a reboot or
     * an interval that spans the query window inflates totals; screen-off
     * foreground time also leaked in). The Wellbeing numbers and the old
     * values differed by ~2.5× on some days.
     *
     * The only two things this method adds around the fold are the query itself and
     * [closeAt]: for today the window ends *now*, not at midnight — see
     * [DayUsageFolder.closeAt] for the 15h54m regression that rule guards.
     */
    fun dayUsage(date: String): DayUsage {
        val dayStart = startOfDay(date)
        val dayEnd = dayStart + DayUsageFolder.DAY_MILLIS
        val closeAt = DayUsageFolder.closeAt(dayEnd, System.currentTimeMillis())
        return DayUsageFolder.fold(
            events = readEvents(dayStart, dayEnd),
            dayStart = dayStart,
            closeAt = closeAt,
            label = ::appLabel,
            ignored = ignored,
        )
    }

    /**
     * Per-app stats for [date] as JSON:
     *   { date, totalSec, unlocks, apps: [{package, app, timeSec, timeHuman}] }
     *
     * Kept for the JSON-shaped callers ([lastDaysStats]); it is now just the JSON
     * projection of [dayUsage].
     */
    fun dayStats(date: String): JSONObject {
        val usage = dayUsage(date)
        val result = JSONObject()
        result.put("date", date)
        result.put("available", hasUsageAccess())

        val apps = JSONArray()
        for (app in usage.apps) {
            apps.put(
                JSONObject().apply {
                    put("package", app.packageName)
                    put("app", app.label)
                    put("timeSec", app.seconds)
                    put("timeHuman", human(app.seconds))
                },
            )
        }
        result.put("totalSec", usage.totalSec)
        result.put("totalHuman", human(usage.totalSec))
        result.put("apps", apps)
        result.put("unlocks", usage.unlocks)

        return result
    }

    /** Aggregated stats for the last [days] days (for backfill after install). */
    fun lastDaysStats(days: Int): JSONArray {
        val arr = JSONArray()
        val cal = Calendar.getInstance().apply { timeZone = TimeZone.getDefault() }
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }
        for (i in 1..days) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
            val ds = sdf.format(cal.time)
            arr.put(dayStats(ds))
        }
        return arr
    }

    /** Midnight of [date] (ISO `yyyy-MM-dd`) in the device's timezone. */
    private fun startOfDay(date: String): Long {
        val parts = date.split("-")
        return Calendar.getInstance().apply {
            timeZone = TimeZone.getDefault()
            set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * The day's events the fold understands. Events of any other type are dropped,
     * which is what the legacy `when` without an `else` branch did.
     */
    private fun readEvents(dayStart: Long, dayEnd: Long): List<UsageEvent> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val out = ArrayList<UsageEvent>()
        val events = usm.queryEvents(dayStart, dayEnd)
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val type = when (ev.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> UsageEventType.SCREEN_INTERACTIVE
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageEventType.SCREEN_NON_INTERACTIVE
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageEventType.ACTIVITY_RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageEventType.ACTIVITY_PAUSED
                else -> continue
            }
            out += UsageEvent(type, ev.timeStamp, ev.packageName)
        }
        return out
    }

    /**
     * The chart name for [pkg]: the known-label fallback first, then the installed
     * app's own label, then the last segment of the package. Package visibility
     * (Android 11+) can hide the label, hence the fallbacks.
     */
    private fun appLabel(pkg: String): String {
        knownLabels[pkg]?.let { return it }
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg.substringAfterLast('.').ifBlank { pkg }
        }
    }

    private fun human(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
