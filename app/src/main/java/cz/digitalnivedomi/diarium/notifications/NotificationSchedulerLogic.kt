package cz.digitalnivedomi.diarium.notifications

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Pure JVM scheduling logic for the notification jobs. Deliberately free
 * of every Android import so it can be unit tested on the JVM with a fixed
 * instant — the Android glue ([NotificationScheduler]) only converts the
 * returned epoch millis into an AlarmManager call.
 *
 * Day-of-week convention matches [NotificationPrefs]: **1 = Monday … 7 = Sunday**
 * (java.time's [java.time.DayOfWeek.value] uses the same numbering).
 *
 * DST is handled by java.time: a wall-clock time inside a spring-forward gap is
 * shifted forward by the gap length, an ambiguous fall-back time uses the earlier
 * offset. Neither case throws.
 */
object NotificationSchedulerLogic {

    private const val MINUTES_PER_DAY = 24 * 60

    /**
     * Next occurrence at [timeMinutes] minutes past midnight that falls on one of
     * [days] (1=Mo…7=Su). Returns `null` when [days] is empty. If today matches and
     * today's time has not passed yet, today's instant is returned; otherwise the
     * next matching weekday (max 7 days ahead).
     */
    fun nextDailyMillis(now: Long, timeMinutes: Int, days: Set<Int>, zone: ZoneId): Long? {
        if (days.isEmpty()) return null
        val minutes = normalizeMinutes(timeMinutes)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        // 0..7 so that "today already passed, only today selected" lands 7 days out.
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            if (date.dayOfWeek.value !in days) continue
            val candidate = atTime(date, minutes, zone).toInstant().toEpochMilli()
            if (candidate > now) return candidate
        }
        return null
    }

    /**
     * Next occurrence on [day] (1=Mo…7=Su) at [timeMinutes]. When [day] is today and
     * the time has already passed, returns next week's occurrence.
     */
    fun nextWeeklyMillis(now: Long, timeMinutes: Int, day: Int, zone: ZoneId): Long {
        val minutes = normalizeMinutes(timeMinutes)
        val target = normalizeDay(day)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            if (date.dayOfWeek.value != target) continue
            val candidate = atTime(date, minutes, zone).toInstant().toEpochMilli()
            if (candidate > now) return candidate
        }
        // Unreachable (offset 7 always matches), kept for exhaustiveness.
        return atTime(today.plusDays(7L), minutes, zone).toInstant().toEpochMilli()
    }

    /**
     * Next occurrence on [dayOfMonth] at [timeMinutes]. A day larger than the target
     * month (e.g. 31 in February) is clamped to that month's last day, so the job
     * never silently disappears.
     */
    fun nextMonthlyMillis(now: Long, timeMinutes: Int, dayOfMonth: Int, zone: ZoneId): Long {
        val minutes = normalizeMinutes(timeMinutes)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var year = today.year
        var month = today.monthValue
        for (attempt in 0..13) {
            val length = YearMonth.of(year, month).lengthOfMonth()
            val dom = dayOfMonth.coerceIn(1, length)
            val candidate = atTime(LocalDate.of(year, month, dom), minutes, zone)
                .toInstant().toEpochMilli()
            if (candidate > now) return candidate
            if (month == 12) {
                month = 1
                year++
            } else {
                month++
            }
        }
        error("nextMonthlyMillis could not find a candidate")
    }

    private fun atTime(date: LocalDate, minutes: Int, zone: ZoneId): ZonedDateTime =
        date.atTime(minutes / 60, minutes % 60).atZone(zone)

    private fun normalizeMinutes(minutes: Int): Int =
        ((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY

    private fun normalizeDay(day: Int): Int = ((day - 1) % 7 + 7) % 7 + 1
}

/**
 * Pure smart-reminder decision. The caller is responsible for taking the real
 * "is today's mood filled in?" answer (Supabase) and for the offline rule:
 * when the answer is unknown the caller passes `hasRecordedMood = false` so the
 * reminder is still shown and the marker is set — never dropped silently.
 */
object SmartReminder {

    /**
     * @param todayIso today in the device zone, `yyyy-MM-dd`
     * @param dayOfWeek 1 = Monday … 7 = Sunday
     * @param hasRecordedMood true only when today's mood is actually filled in
     * @return true when the check-in reminder should fire now
     */
    fun shouldNotify(
        prefs: NotificationPrefs,
        todayIso: String,
        dayOfWeek: Int,
        hasRecordedMood: Boolean,
    ): Boolean {
        if (!prefs.reminderEnabled) return false
        if (dayOfWeek !in prefs.reminderDays) return false
        if (prefs.lastReminderDate == todayIso) return false // never twice a day
        if (prefs.smartReminder && hasRecordedMood) return false // already checked in
        return true
    }
}

/**
 * Pure morning-reminder decision — "včerejšek není vyplněn" (owner request
 * 2026-09-25): fires the morning after a day whose mood was never filled in.
 *
 * The day-of-week convention is irrelevant here: unlike the evening reminder
 * ([NotificationPrefs.reminderDays]), the morning reminder runs **every day**.
 *
 * Unlike [SmartReminder], which fires on an unknown answer so the evening
 * check-in is never silently dropped, this reminder stays silent on unknown
 * (offline): it asserts a fact about yesterday, and crying wolf when the day
 * may be filled is worse than staying silent.
 */
object MorningReminder {

    /**
     * @param todayIso today in the device zone, `yyyy-MM-dd`
     * @param hasYesterdayMood true = mood recorded, false = definitively missing,
     *   null = unknown (offline / query failure)
     * @return true when the morning reminder should fire now
     */
    fun shouldNotify(prefs: NotificationPrefs, todayIso: String, hasYesterdayMood: Boolean?): Boolean {
        if (!prefs.morningReminderEnabled) return false
        if (prefs.lastMorningReminderDate == todayIso) return false // never twice a day
        // null (offline / unknown) → do NOT notify: the notification asserts a fact
        // about yesterday, and crying wolf when the day may be filled is worse than
        // staying silent. Unlike the evening reminder, which fires on unknown.
        if (hasYesterdayMood == null) return false
        return !hasYesterdayMood
    }
}

/**
 * Decides whether an already generated report is recent enough to notify about
 * without asking the server for a fresh one. Reports are app-driven: the exact
 * alarm asks the server to generate shortly before the user's chosen time, and
 * a row created within the freshness window is "this run's" report — the worker
 * must not trigger a second generation.
 */
object ReportFreshness {

    /**
     * @param createdAtIso `created_at` from `ai_reports`, e.g.
     *   `2026-10-11T20:57:34.740885+00:00`
     * @param now the caller's clock
     * @param windowHours how far back a report still counts as fresh
     * @return true only for a parseable timestamp newer than `now - windowHours`;
     *   an unparseable timestamp is treated as NOT fresh (the caller then asks
     *   the server to generate instead of silently notifying about something old).
     */
    fun isFresh(createdAtIso: String, now: Instant, windowHours: Long): Boolean {
        val created = try {
            OffsetDateTime.parse(createdAtIso).toInstant()
        } catch (_: DateTimeParseException) {
            return false
        }
        return created.isAfter(now.minus(windowHours, ChronoUnit.HOURS))
    }
}

/**
 * When the periodic safety net ([ReportSafetyNetWorker]) should act. The exact
 * alarm at the user's chosen time is the primary trigger; the safety window
 * opens only AFTER that time has passed (so the net never preempts the alarm)
 * and stays quiet at night — a rescued report then notifies the next morning
 * instead of at 03:00. All checks are pure local wall-clock math.
 */
object ReportSafetyWindow {

    /** The same-day window ends before this hour; a rescue never notifies at night. */
    private const val QUIET_FROM_HOUR = 23

    /** The next-day window covers daytime hours only. */
    private const val DAY_START_HOUR = 8
    private const val DAY_END_HOUR = 20

    /**
     * The local date of the most recent scheduled weekly run at or before [now].
     * A Monday safety-net run therefore returns the Sunday the report belongs to,
     * not the Monday that just started.
     */
    fun lastWeeklyOccurrence(now: ZonedDateTime, weeklyDay: Int, weeklyTimeMinutes: Int): LocalDate {
        val target = weeklyDay.coerceIn(1, 7)
        val minutes = now.hour * 60 + now.minute
        var date = now.toLocalDate()
        if (date.dayOfWeek.value == target && minutes >= weeklyTimeMinutes.coerceIn(0, 1439)) return date
        repeat(7) {
            date = date.minusDays(1)
            if (date.dayOfWeek.value == target) return date
        }
        return date
    }

    /**
     * @param weeklyDay the user's chosen day (1=Mo…7=Su)
     * @param weeklyTimeMinutes the user's chosen time (minutes past midnight)
     */
    fun isWeeklyWindow(now: ZonedDateTime, weeklyDay: Int, weeklyTimeMinutes: Int): Boolean {
        val day = now.dayOfWeek.value // 1=Mo…7=Su
        val minutes = now.hour * 60 + now.minute
        val nextDay = if (weeklyDay == 7) 1 else weeklyDay + 1
        return when (day) {
            weeklyDay -> minutes >= weeklyTimeMinutes + 30 && now.hour < QUIET_FROM_HOUR
            nextDay -> now.hour in DAY_START_HOUR until DAY_END_HOUR
            else -> false
        }
    }

    /** The monthly alarm always fires on the 1st; the rescue covers the 1st (late) and the 2nd (daytime). */
    fun isMonthlyWindow(now: ZonedDateTime, monthlyTimeMinutes: Int): Boolean {
        val minutes = now.hour * 60 + now.minute
        return when (now.dayOfMonth) {
            1 -> minutes >= monthlyTimeMinutes + 30 && now.hour < QUIET_FROM_HOUR
            2 -> now.hour in DAY_START_HOUR until DAY_END_HOUR
            else -> false
        }
    }
}
