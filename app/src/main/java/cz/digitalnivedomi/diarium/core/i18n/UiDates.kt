package cz.digitalnivedomi.diarium.core.i18n

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Display patterns for dates, in the selected UI language.
 *
 * Dates are stored as ISO `yyyy-MM-dd` everywhere; only the rendered form belongs to a
 * language. The formatters used to be top-level `val`s pinned to `Locale("cs", "CZ")`, which
 * meant an English UI still printed "10. září 2026" — a Czech month in the middle of an
 * English screen. They now come from here instead.
 *
 * The Czech patterns are byte-identical to what the screens printed before, so nothing
 * changes in Czech. English keeps the day-first order (`10 September 2026`) that matches the
 * numeric `10. 9. 2026` the app already shows, instead of flipping to the American
 * month-first form. `Locale.ENGLISH` rather than `Locale.UK` on purpose: en-GB abbreviates
 * September as "Sept" in recent CLDR data, which would make the same date render differently
 * across Android versions.
 *
 * The getters read [AppLanguage] on every access on purpose: the formatters are cached, but
 * which one applies must follow a language switch made at runtime.
 */
object UiDates {

    private val CS: Locale = Locale("cs", "CZ")
    private val EN: Locale = Locale.ENGLISH

    private val longDateCs = DateTimeFormatter.ofPattern("d. MMMM yyyy", CS)
    private val longDateEn = DateTimeFormatter.ofPattern("d MMMM yyyy", EN)

    private val numericDateCs = DateTimeFormatter.ofPattern("d. M. yyyy", CS)
    private val numericDateEn = DateTimeFormatter.ofPattern("d MMM yyyy", EN)

    private val dayMonthCs = DateTimeFormatter.ofPattern("d. M.", CS)
    private val dayMonthEn = DateTimeFormatter.ofPattern("d MMM", EN)

    /** "10. září 2026" / "10 September 2026". */
    fun longDate(): DateTimeFormatter = if (AppLanguage.isEnglish()) longDateEn else longDateCs

    /** "10. 9. 2026" / "10 Sep 2026". */
    fun numericDate(): DateTimeFormatter =
        if (AppLanguage.isEnglish()) numericDateEn else numericDateCs

    /** "10. 9." / "10 Sep" — the short caption under a chart bar. */
    fun dayMonth(): DateTimeFormatter = if (AppLanguage.isEnglish()) dayMonthEn else dayMonthCs
}
