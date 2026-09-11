package cz.digitalnivedomi.diarium.notifications

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure JVM scheduling logic for the three notification jobs. Deliberately free
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
 * "is there a saved entry today?" answer (Supabase) and for the offline rule:
 * when the answer is unknown the caller passes `hasSavedEntry = false` so the
 * reminder is still shown and the marker is set — never dropped silently.
 */
object SmartReminder {

    /**
     * @param todayIso today in the device zone, `yyyy-MM-dd`
     * @param dayOfWeek 1 = Monday … 7 = Sunday
     * @param hasSavedEntry true only on a definitive "entry exists" answer
     * @return true when the check-in reminder should fire now
     */
    fun shouldNotify(
        prefs: NotificationPrefs,
        todayIso: String,
        dayOfWeek: Int,
        hasSavedEntry: Boolean,
    ): Boolean {
        if (!prefs.reminderEnabled) return false
        if (dayOfWeek !in prefs.reminderDays) return false
        if (prefs.lastReminderDate == todayIso) return false // never twice a day
        if (prefs.smartReminder && hasSavedEntry) return false // already checked in
        return true
    }
}
