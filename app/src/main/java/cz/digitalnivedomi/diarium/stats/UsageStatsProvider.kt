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

    // System/launcher packages that Digital Wellbeing does not count as "app time".
    private val ignored = setOf(
        "android", // Android system package (not an app)
        "com.google.android.apps.nexuslauncher", // Pixel launcher
        "com.android.launcher3",
        "com.urbandroid.sleep", // sleep tracking lockscreen
        "com.android.systemui",
        "com.google.android.inputmethod.latin", // Gboard
        "com.android.permissioncontroller",
        "com.google.android.as", // Android System Intelligence
        "com.google.android.gms", // Google Play services
        "com.google.android.googlequicksearchbox", // Launcher search widget placeholder
    )

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
     * Per-app stats for [date] in local timezone (full calendar day).
     * Returns JSON:
     *   { date, totalSec, unlocks, apps: [{package, app, timeSec, timeHuman}] }
     *
     * EVENT-BASED (matches Digital Wellbeing exactly):
     *   - totalSec = display-ON time (SCREEN_INTERACTIVE → SCREEN_NON_INTERACTIVE)
     *   - unlocks  = count of SCREEN_INTERACTIVE events
     *   - per-app  = ACTIVITY_RESUMED → ACTIVITY_PAUSED, only while screen is ON
     * This replaces the old queryUsageStats().totalTimeInForeground approach,
     * which aggregates whole usage "day buckets" and over-counts (a reboot or
     * an interval that spans the query window inflates totals; screen-off
     * foreground time also leaked in). The Wellbeing numbers and the old
     * values differed by ~2.5× on some days.
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
        val apps = JSONArray()

        var totalSec = 0L
        var unlocks = 0L
        var screenOnSince = -1L
        val perApp = LinkedHashMap<String, Pair<String, Long>>() // package → (label, sec)
        val active = HashMap<String, Long>() // package → last ACTIVITY_RESUMED ts

        val events = usm.queryEvents(dayStart, dayEnd)
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val ts = ev.timeStamp
            when (ev.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> {
                    unlocks++
                    if (screenOnSince == -1L) screenOnSince = ts
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (screenOnSince != -1L) {
                        totalSec += (ts - screenOnSince).coerceAtLeast(0) / 1000
                        screenOnSince = -1L
                    }
                    active.clear() // app intervals end once the screen goes off
                }
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val pkg = ev.packageName ?: continue
                    if (pkg in ignored) continue
                    if (screenOnSince != -1L) active[pkg] = ts else active.remove(pkg)
                }
                UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val pkg = ev.packageName ?: continue
                    val start = active.remove(pkg) ?: continue
                    if (start < 0) continue
                    val sec = ((ts - start).coerceAtLeast(0)) / 1000
                    if (sec < 2) continue
                    val label = knownLabels[pkg] ?: try {
                        val info = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(info).toString()
                    } catch (_: PackageManager.NameNotFoundException) {
                        knownLabels[pkg] ?: pkg.substringAfterLast('.').ifBlank { pkg }
                    }
                    val prev = perApp[pkg]
                    perApp[pkg] = (label to (prev?.second ?: 0L) + sec)
                }
            }
        }
        // Close an interval still open when the query window ends.
        // CRITICAL: for today the window end is NOW, not midnight — closing
        // at dayEnd extrapolated the running screen session (and any still-
        // open app) all the way to 00:00, producing e.g. 15h54m of a day
        // that was only 6h old (Instagram 862 min of a morning sync).
        val now = System.currentTimeMillis()
        val closeAt = if (dayEnd > now) now else dayEnd
        if (screenOnSince != -1L) {
            totalSec += (closeAt - screenOnSince).coerceAtLeast(0) / 1000
        }
        for ((pkg, start) in active) {
            val sec = ((closeAt - start).coerceAtLeast(0)) / 1000
            if (sec < 2) continue
            val label = try {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                knownLabels[pkg] ?: pkg.substringAfterLast('.').ifBlank { pkg }
            }
            val prev = perApp[pkg]
            perApp[pkg] = (label to (prev?.second ?: 0L) + sec)
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