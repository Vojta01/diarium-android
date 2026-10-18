package cz.digitalnivedomi.diarium.core.data

import cz.digitalnivedomi.diarium.core.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Goals ("cíle") — a named activity with a target count over a period, plus the
 * 🔥 streak of consecutive days the activity was logged.
 *
 * Contract copied from the web app's goals feature:
 *  - table `goals`: id, user_id, activity_key, name, target_count, frequency
 *    ('daily' | 'weekly' | 'monthly'), is_active, created_at.
 *  - week  = the current CALENDAR week (Monday … today); month = the current
 *    CALENDAR month (the 1st … today). Past periods are their whole calendar
 *    weeks (Mon–Sun) / months. (Rolling windows until 2026-10-14 — the owner
 *    wants „tento týden/měsíc" to mean the calendar week and month.)
 *  - the period count is the number of **days** in the range whose `entries`
 *    row lists `goal.activity_key` in its `activities` array — one count per day,
 *    never per occurrence. Values are compared through [normalizeActivityValue],
 *    because in practice `activities` holds display labels (`Chůze`) while goals
 *    hold slug keys (`chuze`); a `true` value for the key in the row's `habits`
 *    object fulfils that day too.
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
                periods = computeHistory(days, goal, todayIso),
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
     * `entries` rows for the user at or after [from], reduced to the columns the
     * maths needs: `activities` (the day's logged values) and `habits` (a
     * key→Boolean object naming the habits fulfilled that day). A day with
     * several entries is merged into one day so the count is per day and not
     * per occurrence.
     */
    private fun readDays(userId: String, from: String): List<DayActivities>? {
        val resp = client.get(
            "entries",
            mapOf(
                "user_id" to "eq.$userId",
                "date" to "gte.$from",
                "select" to "date,activities,habits",
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
    /**
     * Recent period results, OLDEST first, the current period LAST: 14 periods
     * (days) for a daily goal, 8 (weeks) weekly, 6 (months) monthly. Each item
     * carries its window, the days that logged the activity and the met flag
     * (`true` = the window reached [Goal.targetCount], `false` = it did not,
     * `null` = no `entries` rows in that window at all — "no data", never shown
     * as a missed period). The last item measures exactly the same window as
     * [count]. Empty when the progress was built without history — the getters
     * below then report "nothing known".
     */
    val periods: List<GoalPeriodResult> = emptyList(),
) {
    val targetMet: Boolean get() = isTargetMet(count, goal.targetCount)
    val remaining: Int get() = (goal.targetCount - count).coerceAtLeast(0)
    val periodLabel: String get() = periodLabelFor(goal.frequency)

    /** [periods] reduced to the met/missed/no-data flags, oldest first. */
    val history: List<Boolean?> get() = periods.map { it.met }

    /** The period right before the current one; `null` when unknown/unavailable. */
    val previousMet: Boolean? get() = periods.dropLast(1).lastOrNull()?.met

    /** Periods in [periods] that were met. */
    val metPeriods: Int get() = periods.count { it.met == true }

    /** Periods in [periods] that have data at all (met or missed). */
    val knownPeriods: Int get() = periods.count { it.met != null }

    /** 0f…1f, clamped, so the progress bar can never overflow its track. */
    val fraction: Float
        get() = if (goal.targetCount <= 0) 0f
        else (count.toFloat() / goal.targetCount.toFloat()).coerceIn(0f, 1f)
}

/**
 * One judged period of a goal's history: the calendar window, the days inside
 * the window that logged the activity, and whether the target was reached.
 *
 * [start] and [end] are the WHOLE calendar period — a month runs to its last
 * day, a week to its Sunday — even for the running period, so the detail window
 * can name the range; scoring still only sees the days up to today, because
 * [dates] and the current `count` share the same clamped window. A window with
 * no `entries` rows at all has no [dates] and `met == null` ("no data").
 */
data class GoalPeriodResult(
    val start: String,
    val end: String,
    val dates: List<String>,
    /**
     * `true` when the window reached the target, `false` when it has entries but
     * not enough, `null` = no `entries` rows in the window at all ("no data").
     */
    val met: Boolean?,
) {
    /** Days in the window that logged the activity; 0 when there are no data. */
    val count: Int get() = dates.size
}

/**
 * One day's logged values (already unioned across that day's entries).
 *
 * [keys] holds the raw `entries.activities` values — historically display
 * LABELS like `Chůze` even though goals store slug KEYS like `chuze`, which is
 * why every comparison runs through [normalizeActivityValue]. [habitKeys] holds
 * the `entries.habits` keys whose JSON value is `true`: a goal bound to a habit
 * is fulfilled by that habit, which never appears in `activities`. The default
 * keeps every existing constructor call (and test) source-compatible.
 */
internal data class DayActivities(
    val date: String,
    val keys: List<String>,
    val habitKeys: List<String> = emptyList(),
)

// ── Pure progress maths (JVM-tested, no Android, no clock, no network) ───────

/**
 * The one normalisation every activity comparison runs through: a stored LABEL
 * (`Chůze`, `Trénink`, `CTENI`) and a goal's slug KEY (`chuze`, `trenink`,
 * `cteni`) both land on the same string, so counting can no longer miss.
 *
 * Mirrors `PickersRepository.slug` (which is `private`, so replicated here —
 * keep the two in step): NFD-decompose, drop combining marks, lower-case,
 * every run of non-`[a-z0-9]` → `_`, trim `_`, and the same blank fallback so
 * two punctuation-only values with different text never collapse into one.
 * Idempotent for values that are already slugs, which keeps the existing
 * key==key tests passing unchanged.
 */
internal fun normalizeActivityValue(v: String): String {
    val ascii = Normalizer.normalize(v, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return ascii.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "vlastni_" + v.hashCode().toString().replace("-", "n") }
}

/**
 * Number of days that logged [activityKey].
 *
 * One count per DAY: a day whose list repeats the key (several entries, or the
 * activity twice in one entry) still counts once, and a day without the key is
 * skipped entirely. Comparison goes through [normalizeActivityValue], so a
 * label stored in `entries.activities` matches a slug goal key.
 */
internal fun countActivity(activitiesPerDay: List<List<String>>, activityKey: String): Int {
    val target = normalizeActivityValue(activityKey)
    return activitiesPerDay.count { day -> day.any { normalizeActivityValue(it) == target } }
}

/**
 * The days in `[from, to]` that logged [activityKey], distinct and descending.
 *
 * A day counts when ANY of its `activities` values OR its fulfilled
 * `habitKeys` normalises to the goal key ([normalizeActivityValue]); a day
 * matching both still contributes a single date.
 */
internal fun activityDates(
    days: List<DayActivities>,
    activityKey: String,
    from: String,
    to: String,
): List<String> {
    val target = normalizeActivityValue(activityKey)
    return days.asSequence()
        .filter { day ->
            day.date in from..to &&
                (day.keys.any { normalizeActivityValue(it) == target } ||
                    day.habitKeys.any { normalizeActivityValue(it) == target })
        }
        .map { it.date }
        .distinct()
        .sortedDescending()
        .toList()
}

/** Days in the goal's period that logged [activityKey] — the goal's `count`. */
internal fun countActivityInPeriod(
    days: List<DayActivities>,
    activityKey: String,
    from: String,
    to: String,
): Int = activityDates(days, activityKey, from, to).size

/** `count >= target` — a goal hit exactly on target is met. */
internal fun isTargetMet(count: Int, targetCount: Int): Boolean = count >= targetCount

/** How many periods a card shows in history: 14 days, 8 weeks or 6 months. */
internal fun historyPeriods(frequency: String): Int = when (normalizeFrequency(frequency)) {
    "daily" -> 14
    "monthly" -> 6
    else -> 8
}

/**
 * The goal's recent periods, OLDEST first, the current period LAST: 14 days for
 * a daily goal, 8 weeks for a weekly one, 6 months for a monthly one. Each
 * period is judged over exactly the same window [countActivityInPeriod] uses,
 * moved back k whole periods ([periodEndBack], then [periodStart]), with the
 * repository's existing rules — one count per day, met when the window reaches
 * `targetCount`. A period with no `entries` rows in its window carries no dates
 * and `met == null` ("no data"), never a miss. Each item reports its whole
 * calendar window ([periodFullEnd] keeps the running period's real end instead
 * of today's clamp) and the logged days, so the UI can open the period's
 * detail. `periodsBack = 0` is the current period, so the LAST item always
 * measures the same window as [GoalProgress.count].
 */
internal fun computeHistory(
    days: List<DayActivities>,
    goal: Goal,
    todayIso: String,
): List<GoalPeriodResult> =
    (historyPeriods(goal.frequency) - 1 downTo 0).map { periodsBack ->
        val end = periodEndBack(todayIso, goal.frequency, periodsBack)
        val from = periodStart(end, goal.frequency)
        val fullEnd = if (periodsBack == 0) periodFullEnd(todayIso, goal.frequency) else end
        if (days.none { it.date in from..end }) {
            GoalPeriodResult(start = from, end = fullEnd, dates = emptyList(), met = null)
        } else {
            val logged = activityDates(days, goal.activityKey, from, end).sorted()
            GoalPeriodResult(
                start = from,
                end = fullEnd,
                dates = logged,
                met = isTargetMet(logged.size, goal.targetCount),
            )
        }
    }

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
 * daily → today, weekly → this week's Monday (Mon–Sun), monthly → the 1st of
 * this month. The windows are CALENDAR periods, not rolling ones.
 */
internal fun periodStart(todayIso: String, frequency: String): String = when (normalizeFrequency(frequency)) {
    "daily" -> todayIso
    "monthly" -> firstOfMonth(todayIso)
    else -> weekStart(todayIso)
}

/**
 * The END date of the period [periodsBack] whole CALENDAR periods back:
 * daily → that day; weekly → that week's Sunday (the current week clamps to
 * [todayIso]); monthly → that month's last day (the current month clamps to
 * [todayIso]). `periodStart` of the result reproduces the window exactly — the
 * current period is start…today, every earlier one its full calendar window —
 * which keeps history's last item on the same window as the current `count`.
 */
internal fun periodEndBack(todayIso: String, frequency: String, periodsBack: Int): String =
    when (normalizeFrequency(frequency)) {
        "daily" -> dateMinusDays(todayIso, periodsBack)
        "monthly" -> {
            if (periodsBack <= 0) todayIso
            else lastDayOfMonth(monthsBack(todayIso, periodsBack))
        }
        else -> {
            if (periodsBack <= 0) todayIso
            else dateMinusDays(weekStart(todayIso), 7 * periodsBack - 6)
        }
    }

/**
 * The whole calendar END of the period [todayIso] falls in: the month's last
 * day, the week's Sunday, or today itself for a daily goal. [periodEndBack]
 * clamps the running period to today for scoring; this keeps its real extent so
 * the detail window can say "Probíhá — zbývá…" instead of ending today.
 */
internal fun periodFullEnd(todayIso: String, frequency: String): String =
    when (normalizeFrequency(frequency)) {
        "daily" -> todayIso
        "monthly" -> lastDayOfMonth(todayIso)
        else -> dateMinusDays(weekStart(todayIso), -6)
    }

/** Czech period label shown on the card. */
internal fun periodLabelFor(frequency: String): String = when (normalizeFrequency(frequency)) {
    "daily" -> if (AppLanguage.isEnglish()) "day" else "den"
    "monthly" -> if (AppLanguage.isEnglish()) "month" else "měsíc"
    else -> if (AppLanguage.isEnglish()) "week" else "týden"
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

/** [iso] moved back [months] whole calendar months (clamped day, like [minusOneMonth]). */
internal fun monthsBack(iso: String, months: Int): String {
    var out = iso
    repeat(months) { out = minusOneMonth(out) }
    return out
}

/**
 * Monday of [iso]'s ISO week — Czech calendar weeks run Monday … Sunday. Epoch
 * day 0 (1970-01-01) was a Thursday, so the ISO weekday is `((day + 3) mod 7) + 1`
 * with Monday = 1; the modulo is kept positive for dates before 1970.
 */
internal fun weekStart(iso: String): String {
    val day = isoToEpochDay(iso) ?: return iso
    val dow = ((day + 3) % 7 + 7) % 7 + 1
    return epochDayToIso(day - (dow - 1))
}

/** First day of [iso]'s calendar month. */
internal fun firstOfMonth(iso: String): String {
    val parts = iso.trim().split("-")
    if (parts.size != 3) return iso
    val year = parts[0].toIntOrNull() ?: return iso
    val month = parts[1].toIntOrNull() ?: return iso
    if (month !in 1..12) return iso
    return formatIso(year, month, 1)
}

/** Last day of [iso]'s calendar month (leap-year aware through [daysInMonth]). */
internal fun lastDayOfMonth(iso: String): String {
    val parts = iso.trim().split("-")
    if (parts.size != 3) return iso
    val year = parts[0].toIntOrNull() ?: return iso
    val month = parts[1].toIntOrNull() ?: return iso
    if (month !in 1..12) return iso
    return formatIso(year, month, daysInMonth(year, month))
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

/**
 * `entries` rows → one [DayActivities] per date: activity values and fulfilled
 * habit keys unioned across the day's entries.
 *
 * `habits` is a JSON object key→Boolean; only keys whose value is `true` are
 * kept (an explicit `false` means the habit was not fulfilled that day).
 */
internal fun daysFrom(rows: List<JSONObject>): List<DayActivities> {
    val activitiesByDate = LinkedHashMap<String, LinkedHashSet<String>>()
    val habitsByDate = LinkedHashMap<String, LinkedHashSet<String>>()
    rows.forEach { row ->
        val date = row.plainString("date").takeIf { it.isNotBlank() } ?: return@forEach
        val keys = activitiesByDate.getOrPut(date) { LinkedHashSet<String>() }
        row.optJSONArray("activities")?.stringList()?.forEach { key ->
            if (key.isNotBlank()) keys.add(key)
        }
        val habits = habitsByDate.getOrPut(date) { LinkedHashSet<String>() }
        habitsObject(row)?.let { obj ->
            obj.keys().forEach { key ->
                if (key.isNotBlank() && obj.optBoolean(key, false)) habits.add(key)
            }
        }
    }
    return activitiesByDate.map { (date, keys) ->
        DayActivities(date, keys.toList(), habitsByDate[date]?.toList() ?: emptyList())
    }
}

/**
 * `entries.habits` as a JSON object; tolerates the column arriving as a
 * JSON-encoded string (a `jsonb` column normally arrives as an object).
 */
private fun habitsObject(row: JSONObject): JSONObject? {
    row.optJSONObject("habits")?.let { return it }
    val raw = row.optString("habits", "")
    if (raw.isBlank() || raw == "null") return null
    return try {
        JSONObject(raw)
    } catch (_: Exception) {
        null
    }
}

/** Today as the web writes it (`yyyy-MM-dd`, local time). */
internal fun currentIsoDate(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date())
