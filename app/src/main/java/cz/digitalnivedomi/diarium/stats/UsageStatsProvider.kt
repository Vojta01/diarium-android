package cz.digitalnivedomi.diarium.stats

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
 */
class UsageStatsProvider(private val context: Context) {

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
     * Per-app stats for [date] in local timezone (full calendar day).
     * Returns JSON:
     *   { date, totalSec, unlocks, apps: [{package, app, timeSec, timeHuman}] }
     */
    fun dayStats(date: String): JSONObject {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance().apply {
            timeZone = TimeZone.getDefault()
            val parts = date.split("-")
            set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayStart = cal.timeInMillis
        val dayEnd = dayStart + 24 * 60 * 60 * 1000L

        val result = JSONObject()
        result.put("date", date)
        result.put("available", hasUsageAccess())

        val pm = context.packageManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, dayEnd)
        val apps = JSONArray()

        var totalSec = 0L
        val perApp = LinkedHashMap<String, Pair<String, Long>>() // package → (label, sec)

        // System/launcher packages that Digital Wellbeing does not count as "app time".
        val ignored = setOf(
            "android", // Android system package (not an app)
            "com.google.android.apps.nexuslauncher", // Pixel launcher
            "com.android.launcher3",
            "com.urbandroid.sleep", // sleep tracking lockscreen
            "com.android.systemui",
            "com.google.android.inputmethod.latin", // Gboard
            "com.android.permissioncontroller",
        )

        for (s in stats) {
            val pkg = s.packageName ?: continue
            if (pkg in ignored) continue
            // Only count when the app was actually in the foreground.
            val sec = s.totalTimeInForeground / 1000
            if (sec < 2) continue
            val label = try {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                pkg.substringAfterLast('.').ifBlank { pkg }
            }
            perApp[pkg] = (label to sec)
            totalSec += sec
        }

        for ((pkg, pair) in perApp) {
            val (label, sec) = pair
            val o = JSONObject()
            o.put("package", pkg)
            o.put("app", label)
            o.put("timeSec", sec)
            o.put("timeHuman", human(sec))
            apps.put(o)
        }
        result.put("totalSec", totalSec)
        result.put("totalHuman", human(totalSec))
        result.put("apps", apps)

        // Unlock count via usage events (EVENT_SCREEN_INTERACTIVE = screen unlocked).
        var unlocks = 0L
        val events = usm.queryEvents(dayStart, dayEnd)
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.SCREEN_INTERACTIVE) unlocks++
        }
        result.put("unlocks", unlocks)

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

    private fun human(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}