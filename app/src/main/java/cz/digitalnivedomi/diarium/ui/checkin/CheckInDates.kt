package cz.digitalnivedomi.diarium.ui.checkin

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date helpers for the check-in header.
 *
 * Dates are ISO `yyyy-MM-dd` everywhere (the `entries.date` column and the RPC
 * key), and only rendered in Czech for display.
 */
object CheckInDates {

    private val czech = Locale("cs", "CZ")
    private val displayFormat = DateTimeFormatter.ofPattern("d. MMMM yyyy", czech)

    fun today(): String = LocalDate.now().toString()

    fun yesterday(): String = LocalDate.now().minusDays(1).toString()

    /**
     * True between midnight and 04:00 — the window in which the day being written up is
     * still the one that just ended. The check-in form uses it to open on [yesterday]
     * instead of today, which is what the owner means when he fills the day in after
     * midnight (the app used to file that whole entry under the new date).
     *
     * Four in the morning is the boundary because a diary day ends when the owner sleeps,
     * not at 00:00 — and by 04:00 a night owl has long finished the entry.
     */
    fun isAfterMidnight(): Boolean = LocalTime.now().hour < LATE_NIGHT_UNTIL_HOUR

    /** The hour (exclusive) up to which [isAfterMidnight] reports true. */
    const val LATE_NIGHT_UNTIL_HOUR = 4

    fun parse(date: String): LocalDate? = runCatching { LocalDate.parse(date) }.getOrNull()

    /** "Dnes" / "Včera" / "Zítra" for the near days, otherwise a Czech date. */
    fun display(date: String): String {
        val parsed = parse(date) ?: return date
        val today = LocalDate.now()
        return when (parsed) {
            today -> "Dnes"
            today.minusDays(1) -> "Včera"
            today.plusDays(1) -> "Zítra"
            else -> parsed.format(displayFormat)
        }
    }

    fun shift(date: String, days: Long): String =
        (parse(date) ?: LocalDate.now()).plusDays(days).toString()

    /** Material3 pickers work in UTC epoch millis. */
    fun toEpochMillis(date: String): Long =
        (parse(date) ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun fromEpochMillis(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
}
