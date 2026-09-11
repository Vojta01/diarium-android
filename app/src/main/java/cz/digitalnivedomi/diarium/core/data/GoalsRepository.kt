package cz.digitalnivedomi.diarium.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Goals ("cíle") — a named activity with a target count over a period, plus the
 * 🔥 streak of consecutive days the activity was logged.
 *
 * Contract copied from the web app's goals feature:
 *  - table `goals`: id, user_id, activity_key, name, target_count, frequency
 *    ('daily' | 'weekly' | 'monthly'), is_active, created_at.
 *  - week  = (today − 7 days) … today inclusive; month = (today − 1 month) … today.
 *  - the period count is the number of **days** in the range whose `entries`
 *    row lists `goal.activity_key` in its `activities` array — one count per day,
 *    never per occurrence.
 *  - `target_met` is `count >= target_count`.
 *  - the streak walks the distinct dates (descending) that have the activity and
 *    adds one per consecutive day, breaking at the first gap that is not exactly
 *    one day. There is no "still alive today" grace: a gap resets it to the run
 *    that ended at the newest logged day.
 *
 * All maths lives in the pure `internal` functions below so the counting and the
 * streak are unit-tested on the JVM without a device, a network or a clock.
 *
 * The client carries only the anon key from [SupabaseClient]'s BuildConfig plus
 * the signed-in user's own JWT; every read and write goes through that JWT and
 * the repository never touches (or needs) a service-role key.
 */
class GoalsRepository(
    private val client: SupabaseClient,
    private val session: SessionContext = SessionContext(),
    /** Injectable clock so a test can pin "today"; production reads the device clock. */
    private val today: () -> String = { currentIsoDate() },
) {

    /**
     * The user's active goals with their progress, or `null` when a read failed.
     *
     * `null` means "we could not verify anything" and is what drives the screen's
     * error + retry state; an empty list means "this account has no active goals"
     * and drives the empty card. The two are deliberately different so a flaky
     * network never renders as "you have no goals".
     *
     * Ordering is applied client-side (no `order=` parameter), exactly like
     * [PickersRepository] — a column rename in the database must not turn the
     * whole read into a 400.
     */
    suspend fun loadProgress(): List<GoalProgress>? = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext null

        val goals = readGoals(userId) ?: return@withContext null
        val active = goals.filter { it.isActive }
        if (active.isEmpty()) return@withContext emptyList()

        val todayIso = today()
        // One entries read covers every goal's period and a year of streak, so a
        // daily streak longer than a month is not silently truncated at the edge
        // of the monthly window.
        val from = dateMinusDays(todayIso, STREAK_LOOKBACK_DAYS)
        val days = readDays(userId, from) ?: return@withContext null

        active.map { goal ->
            val start = periodStart(todayIso, goal.frequency)
            GoalProgress(
                goal = goal,
                count = countActivityInPeriod(days, goal.activityKey, start, todayIso),
                streak = streakOf(activityDates(days, goal.activityKey, from, todayIso)),
            )
        }
    }

    /** All goals including archived ones, newest first, or `null` on a failed read. */
    suspend fun list(): List<Goal>? = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext null
        readGoals(userId)
    }

    /** Creates a goal for the signed-in user. */
    suspend fun create(
        name: String,
        activityKey: String,
        targetCount: Int,
        frequency: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        val cleanName = name.trim()
        val cleanKey = activityKey.trim()
        if (cleanName.isBlank() || cleanKey.isBlank()) return@withContext false

        val body = JSONObject().apply {
            put("user_id", userId)
            put("activity_key", cleanKey)
            put("name", cleanName)
            put("target_count", targetCount.coerceAtLeast(1))
            put("frequency", normalizeFrequency(frequency))
            put("is_active", true)
        }
        client.post("goals", body).isSuccessful
    }

    /** Updates a goal in place. The `user_id` filter is a safety net, never a hint. */
    suspend fun update(
        id: String,
        name: String,
        activityKey: String,
        targetCount: Int,
        frequency: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        val cleanName = name.trim()
        val cleanKey = activityKey.trim()
        if (id.isBlank() || cleanName.isBlank() || cleanKey.isBlank()) return@withContext false

        val body = JSONObject().apply {
            put("name", cleanName)
            put("activity_key", cleanKey)
            put("target_count", targetCount.coerceAtLeast(1))
            put("frequency", normalizeFrequency(frequency))
        }
        client.patch(
            "goals",
            body,
            mapOf("id" to "eq.$id", "user_id" to "eq.$userId"),
        ).isSuccessful
    }

    /** Deletes a goal. Scoped by `user_id` so a stale id can never hit a foreign row. */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        if (id.isBlank()) return@withContext false
        client.delete("goals", mapOf("id" to "eq.$id", "user_id" to "eq.$userId")).isSuccessful
    }

    // ── internals ───────────────────────────────────────────────────────────

    private fun readGoals(userId: String): List<Goal>? {
        val resp = client.get("goals", mapOf("user_id" to "eq.$userId", "select" to "*"))
        if (!resp.isSuccessful) return null
        val array: JSONArray = resp.asJsonArray() ?: return null
        return (0 until array.length())
            .mapNotNull { array.optJSONObject(it)?.let(::goalFrom) }
            .sortedWith(goalOrder)
    }

    /**
     * `entries` rows for the user at or after [from], reduced to the two columns
     * the maths needs. A day with several entries is merged into one day so the
     * count is per day and not per occurrence.
     */
    private fun readDays(userId: String, from: String): List<DayActivities>? {
        val resp = client.get(
            "entries",
            mapOf(
                "user_id" to "eq.$userId",
                "date" to "gte.$from",
                "select" to "date,activities",
            ),
        )
        if (!resp.isSuccessful) return null
        val array: JSONArray = resp.asJsonArray() ?: return null
        return daysFrom((0 until array.length()).mapNotNull { array.optJSONObject(it) })
    }

    private fun goalFrom(row: JSONObject): Goal? {
        val id = row.plainString("id").takeIf { it.isNotBlank() } ?: return null
        val key = row.plainString("activity_key").ifBlank { row.plainString("name") }
        if (key.isBlank()) return null
        return Goal(
            id = id,
            activityKey = key,
            name = row.plainString("name").ifBlank { key },
            targetCount = row.optInt("target_count", 1).coerceAtLeast(1),
            frequency = normalizeFrequency(row.plainString("frequency")),
            isActive = row.optBoolean("is_active", true),
            createdAt = row.plainString("created_at"),
            completedAt = row.stringOrNull("completed_at"),
        )
    }

    private companion object {
        /**
         * A year of history is read so a long daily streak survives; the period
         * count then filters to the goal's own window out of the same rows.
         */
        const val STREAK_LOOKBACK_DAYS = 365

        /** created_at ascending (blank last), name as a stable tie-break. */
        val goalOrder = compareBy<Goal>({ it.createdAt.isBlank() }, { it.createdAt }, { it.name })
    }
}

// ── Data shapes ─────────────────────────────────────────────────────────────

/** A row of `goals`. [frequency] is already normalised to daily/weekly/monthly. */
data class Goal(
    val id: String,
    val activityKey: String,
    val name: String,
    val targetCount: Int,
    val frequency: String,
    val isActive: Boolean,
    val createdAt: String = "",
    val completedAt: String? = null,
)

/** A goal plus its progress for the current period. */
data class GoalProgress(
    val goal: Goal,
    /** Days in the goal's period that logged the activity — one per day. */
    val count: Int,
    /** Consecutive days up to the newest logged day, web algorithm, 0 when none. */
    val streak: Int,
) {
    val targetMet: Boolean get() = isTargetMet(count, goal.targetCount)
    val remaining: Int get() = (goal.targetCount - count).coerceAtLeast(0)
    val periodLabel: String get() = periodLabelFor(goal.frequency)

    /** 0f…1f, clamped, so the progress bar can never overflow its track. */
    val fraction: Float
        get() = if (goal.targetCount <= 0) 0f
        else (count.toFloat() / goal.targetCount.toFloat()).coerceIn(0f, 1f)
}

/** One day's activity keys (already unioned across that day's entries). */
internal data class DayActivities(val date: String, val keys: List<String>)

// ── Pure progress maths (JVM-tested, no Android, no clock, no network) ───────

/**
 * Number of days that logged [activityKey].
 *
 * One count per DAY: a day whose list repeats the key (several entries, or the
 * activity twice in one entry) still counts once, and a day without the key is
 * skipped entirely.
 */
internal fun countActivity(activitiesPerDay: List<List<String>>, activityKey: String): Int =
    activitiesPerDay.count { day -> day.any { it == activityKey } }

/** The days in `[from, to]` that logged [activityKey], distinct and descending. */
internal fun activityDates(
    days: List<DayActivities>,
    activityKey: String,
    from: String,
    to: String,
): List<String> =
    days.asSequence()
        .filter { it.date in from..to && it.keys.contains(activityKey) }
        .map { it.date }
        .distinct()
        .sortedDescending()
        .toList()

/** Days in the goal's period that logged [activityKey] — the goal's `count`. */
internal fun countActivityInPeriod(
    days: List<DayActivities>,
    activityKey: String,
    from: String,
    to: String,
): Int = activityDates(days, activityKey, from, to).size

/** `count >= target` — a goal hit exactly on target is met. */
internal fun isTargetMet(count: Int, targetCount: Int): Boolean = count >= targetCount

/**
 * The web's streak algorithm, quirk included: newest-first dates, streak starts
 * at 1 for the newest day, +1 for every day whose gap to the previous one is
 * exactly 1 day, and the walk BREAKS at the first gap that is not 1. Input is
 * de-duplicated and sorted descending here, so a caller may pass the raw dates.
 */
internal fun streakOf(datesDescending: List<String>): Int {
    val days = datesDescending.filter { isoToEpochDay(it) != null }
        .distinct()
        .sortedDescending()
    if (days.isEmpty()) return 0

    var streak = 1
    for (i in 1 until days.size) {
        if (daysBetween(days[i], days[i - 1]) == 1L) streak++ else break
    }
    return streak
}

// ── Pure date helpers (ISO yyyy-MM-dd, proleptic Gregorian) ──────────────────

/** Whole days from [fromIso] to [toIso]; null when either string is not a date. */
internal fun daysBetween(fromIso: String, toIso: String): Long? {
    val from = isoToEpochDay(fromIso) ?: return null
    val to = isoToEpochDay(toIso) ?: return null
    return to - from
}

/** [iso] minus [days] days. */
internal fun dateMinusDays(iso: String, days: Int): String {
    val day = isoToEpochDay(iso) ?: return iso
    return epochDayToIso(day - days)
}

/**
 * Start of the goal's current period, inclusive:
 * daily → today, weekly → today − 7 days (the web's window), monthly → one
 * calendar month back (clamped to the shorter month's last day).
 */
internal fun periodStart(todayIso: String, frequency: String): String = when (normalizeFrequency(frequency)) {
    "daily" -> todayIso
    "monthly" -> minusOneMonth(todayIso)
    else -> dateMinusDays(todayIso, 7)
}

/** Czech period label shown on the card. */
internal fun periodLabelFor(frequency: String): String = when (normalizeFrequency(frequency)) {
    "daily" -> "den"
    "monthly" -> "měsíc"
    else -> "týden"
}

/** Anything the database holds that is not daily/monthly behaves as weekly. */
internal fun normalizeFrequency(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
    "daily", "den", "denne", "denně" -> "daily"
    "monthly", "mesic", "měsíc", "mesicne", "měsíčně" -> "monthly"
    else -> "weekly"
}

/** One calendar month back, clamped: 2026-03-31 → 2026-02-28, 2024-03-31 → 2024-02-29. */
internal fun minusOneMonth(iso: String): String {
    val parts = iso.trim().split("-")
    if (parts.size != 3) return iso
    val year = parts[0].toIntOrNull() ?: return iso
    val month = parts[1].toIntOrNull() ?: return iso
    val day = parts[2].toIntOrNull() ?: return iso
    if (month !in 1..12 || day !in 1..31) return iso

    val targetMonth = if (month == 1) 12 else month - 1
    val targetYear = if (month == 1) year - 1 else year
    val clampedDay = day.coerceAtMost(daysInMonth(targetYear, targetMonth))
    return formatIso(targetYear, targetMonth, clampedDay)
}

private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (isLeapYear(year)) 29 else 28
}

private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

/**
 * Days since 1970-01-01 by Howard Hinnant's `days_from_civil` — pure integer
 * arithmetic, so it behaves identically on every API level instead of needing
 * `java.time` (API 26) or a desugared `Calendar`.
 */
internal fun isoToEpochDay(iso: String): Long? {
    val parts = iso.trim().split("-")
    if (parts.size != 3) return null
    val year = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val day = parts[2].toIntOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31) return null

    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400                                            // [0, 399]
    val doy = (153L * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy                    // [0, 146096]
    return era * 146097 + doe - 719468
}

/** Inverse of [isoToEpochDay]. */
internal fun epochDayToIso(epochDay: Long): String {
    val z = epochDay + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097                                         // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365    // [0, 399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)                  // [0, 365]
    val mp = (5 * doy + 2) / 153                                       // [0, 11]
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = if (month <= 2) y + 1 else y
    return formatIso(year.toInt(), month.toInt(), day.toInt())
}

private fun formatIso(year: Int, month: Int, day: Int): String =
    "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}"

private fun pad(value: Int, width: Int): String {
    val s = value.toString()
    return if (s.length >= width) s else "0".repeat(width - s.length) + s
}

/** `entries` rows → one [DayActivities] per date, keys unioned across the day. */
internal fun daysFrom(rows: List<JSONObject>): List<DayActivities> {
    val byDate = LinkedHashMap<String, LinkedHashSet<String>>()
    rows.forEach { row ->
        val date = row.plainString("date").takeIf { it.isNotBlank() } ?: return@forEach
        val keys = byDate.getOrPut(date) { LinkedHashSet<String>() }
        row.optJSONArray("activities")?.stringList()?.forEach { key ->
            if (key.isNotBlank()) keys.add(key)
        }
    }
    return byDate.map { DayActivities(it.key, it.value.toList()) }
}

/** Today as the web writes it (`yyyy-MM-dd`, local time). */
internal fun currentIsoDate(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
