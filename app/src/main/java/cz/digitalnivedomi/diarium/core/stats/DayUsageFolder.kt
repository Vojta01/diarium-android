package cz.digitalnivedomi.diarium.core.stats

/**
 * The arithmetic behind [cz.digitalnivedomi.diarium.stats.UsageStatsProvider], lifted
 * out of Android so it can be tested on the JVM.
 *
 * The screen-time algorithm is the one Digital Wellbeing's numbers were matched
 * against and it must not drift: the provider used to interleave
 * `UsageStatsManager` with the fold in one method, so the only thing a unit test
 * could reach was [StatsMath]. Here the provider becomes a thin adapter (query the
 * events, resolve a label per package) and every rule that decides the numbers
 * lives in [fold]:
 *
 *  - `totalSec`  = display-ON time (`SCREEN_INTERACTIVE` → `SCREEN_NON_INTERACTIVE`),
 *  - `unlocks`   = the number of `SCREEN_INTERACTIVE` events,
 *  - per-app     = `ACTIVITY_RESUMED` → `ACTIVITY_PAUSED`, only while the screen is on.
 *
 * This is deliberately **not** [StatsMath]: that object is the web port's arithmetic
 * over *stored* days (windows, averages, top apps). This is the phone's own event
 * stream, which the web never sees.
 */
object DayUsageFolder {

    /**
     * System/launcher packages that Digital Wellbeing does not count as "app time".
     *
     * Moved verbatim from the provider (it used to be a private field there) so the
     * ignore list is part of the pure fold and its rules can be asserted on the JVM.
     * [cz.digitalnivedomi.diarium.stats.UsageStatsProvider] still owns a member named
     * `ignored`, pointing at exactly this set — one source of truth.
     */
    val DEFAULT_IGNORED: Set<String> = setOf(
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

    /**
     * Shortest per-app interval that counts. A sub-two-second foreground blip is
     * never real usage (a package bounce, a permission dialog) and used to pollute
     * the chart with 0‑second apps — the legacy rule was `if (sec < 2) continue`.
     */
    const val MIN_INTERVAL_SEC = 2L

    /** One day, in millis, for turning an ISO date into a window end. */
    const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * Where an unfinished query window really ends.
     *
     * **CRITICAL, this is the 15h54m regression guard.** For a day that is still
     * running the window's end is *now*, not the next midnight: closing a running
     * screen session (or a still-open app) at midnight extrapolated it to the end
     * of the day, so a 6-hour-old morning reported "15h54m of screen time" and
     * Instagram 862 minutes. For a finished day `now` is well past `dayEnd`, so this
     * returns `dayEnd` and a full day is counted whole.
     *
     * See `UsageStatsProvider.kt:196–199` (pre-M6 numbering) for the original comment.
     */
    fun closeAt(dayEnd: Long, now: Long): Long = if (dayEnd > now) now else dayEnd

    /**
     * Folds one day's screen events into the numbers the worker pushes.
     *
     * [events] must be in the order `UsageStatsManager.queryEvents` returned them
     * (timestamp ascending). [dayStart] is the lower bound a session is clamped to,
     * so an event leaking in from a neighbouring day can never make an interval
     * negative. [closeAt] is [closeAt]`(dayEnd, now)` — the adapter's job, because
     * it is the only part that needs the clock. [label] resolves a package to the
     * name the chart shows; it is called once per credited app, never per event, so
     * the provider's `PackageManager` lookup is not on the event loop.
     *
     * Apps come back in the order they were *first* credited (the legacy
     * `LinkedHashMap` order), which is the order the payload builder truncates to
     * its top 15 — preserved on purpose.
     */
    fun fold(
        events: List<UsageEvent>,
        dayStart: Long,
        closeAt: Long,
        label: (String) -> String,
        ignored: Set<String> = DEFAULT_IGNORED,
    ): DayUsage {
        var totalSec = 0L
        var unlocks = 0L
        var screenOnSince = -1L
        val perApp = LinkedHashMap<String, Long>()
        val labels = HashMap<String, String>()
        val active = HashMap<String, Long>() // package → last ACTIVITY_RESUMED ts

        /**
         * Credits [pkg] the interval `from`→`to`, clamped to the day and to
         * [MIN_INTERVAL_SEC]. Shared by the `ACTIVITY_PAUSED` case and the
         * end-of-window sweep, which used to duplicate this block.
         */
        fun credit(pkg: String, from: Long, to: Long) {
            val sec = (to - from.coerceAtLeast(dayStart)).coerceAtLeast(0) / 1000
            if (sec < MIN_INTERVAL_SEC) return
            labels.getOrPut(pkg) { label(pkg) }
            perApp[pkg] = (perApp[pkg] ?: 0L) + sec
        }

        for (event in events) {
            val ts = event.timeStamp
            when (event.type) {
                UsageEventType.SCREEN_INTERACTIVE -> {
                    unlocks++
                    if (screenOnSince == -1L) screenOnSince = ts
                }

                UsageEventType.SCREEN_NON_INTERACTIVE -> {
                    if (screenOnSince != -1L) {
                        totalSec += (ts - screenOnSince).coerceAtLeast(0) / 1000
                        screenOnSince = -1L
                    }
                    // App intervals end once the screen goes off — an open interval is
                    // dropped, never carried across the gap to a later pause event.
                    active.clear()
                }

                UsageEventType.ACTIVITY_RESUMED -> {
                    val pkg = event.packageName ?: continue
                    if (pkg in ignored) continue
                    // Only foreground time with the screen on counts; a resume while
                    // the screen is off also cancels any stale open interval.
                    if (screenOnSince != -1L) active[pkg] = ts else active.remove(pkg)
                }

                UsageEventType.ACTIVITY_PAUSED -> {
                    val pkg = event.packageName ?: continue
                    val start = active.remove(pkg) ?: continue
                    if (start < 0) continue
                    credit(pkg, start, ts)
                }
            }
        }

        // Close the intervals still open when the query window ends.
        if (screenOnSince != -1L) {
            totalSec += (closeAt - screenOnSince.coerceAtLeast(dayStart)).coerceAtLeast(0) / 1000
        }
        for ((pkg, start) in active) {
            credit(pkg, start, closeAt)
        }

        return DayUsage(
            totalSec = totalSec,
            unlocks = unlocks,
            apps = perApp.map { (pkg, seconds) -> DayAppUsage(pkg, labels.getValue(pkg), seconds) },
        )
    }
}

/** The four `UsageEvents.Event` types the fold understands. */
enum class UsageEventType {
    SCREEN_INTERACTIVE,
    SCREEN_NON_INTERACTIVE,
    ACTIVITY_RESUMED,
    ACTIVITY_PAUSED,
}

/**
 * One `UsageEvents.Event`, stripped of the Android framework so [DayUsageFolder.fold]
 * is plain JVM code. The provider maps `ev.eventType` onto [UsageEventType] and
 * passes the timestamp and package through untouched.
 */
data class UsageEvent(
    val type: UsageEventType,
    val timeStamp: Long,
    val packageName: String? = null,
)

/** One app's foreground time on a day. [label] is already resolved for the chart. */
/**
 * One app's foreground time on a day. [label] is already resolved for the chart.
 *
 * Distinct from [cz.digitalnivedomi.diarium.core.stats.AppUsage], which is the
 * chart's day-window share; this one is raw seconds for the sync payload.
 */
data class DayAppUsage(
    val packageName: String,
    val label: String,
    val seconds: Long,
)

/**
 * A day of phone usage as the fold produces it.
 *
 * `totalSec`/`unlocks` are what the `save_daily_entry` payload calls
 * `phone_screen_time`/`phone_unlocks`; [apps] is what becomes `phone_top_apps`.
 * There is no `date` here — the fold has no clock, so the caller pairs it with the
 * date it asked for.
 */
data class DayUsage(
    val totalSec: Long,
    val unlocks: Long,
    val apps: List<DayAppUsage>,
)
