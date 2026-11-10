package cz.digitalnivedomi.diarium.ui.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.data.DashboardDay
import cz.digitalnivedomi.diarium.core.data.MetricTrend
import cz.digitalnivedomi.diarium.core.data.StatsRepository
import cz.digitalnivedomi.diarium.core.data.TrendDirection
import cz.digitalnivedomi.diarium.core.i18n.AppLanguage
import cz.digitalnivedomi.diarium.core.i18n.Lang
import cz.digitalnivedomi.diarium.core.i18n.UiDates
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import cz.digitalnivedomi.diarium.ui.checkin.components.formatMinutes
import cz.digitalnivedomi.diarium.ui.checkin.moodChoiceText
import cz.digitalnivedomi.diarium.ui.history.HistoryCalendar
import cz.digitalnivedomi.diarium.ui.history.monthEmptyText
import cz.digitalnivedomi.diarium.ui.history.monthSyncedOnlyText
import cz.digitalnivedomi.diarium.ui.home.TrendLineText
import cz.digitalnivedomi.diarium.ui.home.TrendTone
import cz.digitalnivedomi.diarium.ui.home.formatMetricMinutes
import cz.digitalnivedomi.diarium.ui.home.moodTextOf
import cz.digitalnivedomi.diarium.ui.home.trendLineText
import cz.digitalnivedomi.diarium.ui.stats.StatsRange
import cz.digitalnivedomi.diarium.ui.stats.answeredDaysText
import cz.digitalnivedomi.diarium.ui.stats.cellLabel
import cz.digitalnivedomi.diarium.ui.stats.emptyWindowTitle
import cz.digitalnivedomi.diarium.ui.stats.goalSummaryText
import cz.digitalnivedomi.diarium.ui.stats.moodShareText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * The strings the planned documentation scenes build at runtime, asserted as whole
 * sentences through [translate] — not as dictionary keys.
 *
 * [EnglishUiTest] pins static labels; this class pins the composed ones: a hero caption
 * with a formatted average, a standalone day count, a range suffix, the year's
 * best/worst month line, the year card's header and limit hint, a goal's activity line,
 * the best/worst day facts, the unlock chart's caption with its appended legend, the
 * empty stats window, the trend line's translated arrow caption, the history month
 * sentences, the goal summary, the selected mood/date labels, and the dashboard hints
 * the screen joins from two fragments.
 *
 * Each of these used to fall through to Czech because the rule matched only a prefix
 * (or a piece the screen never renders on its own). A prefix rule looks right in the
 * dictionary and fails on screen, so every case here goes through the same entry point
 * the `Text` wrapper uses.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DynamicEnglishStringsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun speakEnglish() {
        AppLanguage.set(context, Lang.EN)
    }

    @After
    fun speakCzechAgain() {
        AppLanguage.set(context, Lang.CS)
    }

    // ── Dashboard hero ────────────────────────────────────────────────────────

    /** "Ø 3 h 12 na den" — the average under the screen-time hero number. */
    @Test
    fun `dashboard hero average per day reads as one English sentence`() {
        assertEquals(
            "Ø 3 h 12 per day",
            translate("Ø ${formatMetricMinutes(192)} na den"),
        )
        assertEquals(
            "Ø 45 min per day",
            translate("Ø ${formatMetricMinutes(45)} na den"),
        )
        assertEquals(
            "Ø 2 h per day",
            translate("Ø ${formatMetricMinutes(120)} na den"),
        )
        // The unlocks hero keeps its own, more specific rule.
        assertEquals("Ø 12 unlocks per day", translate("Ø 12 odemknutí na den"))
    }

    // ── Streak ────────────────────────────────────────────────────────────────

    /** "17 dní" — the longest-streak value stands alone, without "v řadě". */
    @Test
    fun `a standalone streak day count is pluralised in English`() {
        assertEquals("17 days", translate("17 dní"))
        assertEquals("1 day", translate("1 den"))
        assertEquals("3 days", translate("3 dny"))
        assertEquals("0 days", translate("0 dní"))
        // The subtitle next to the hero number: "5 dní v řadě" → "days in a row".
        assertEquals("days in a row", translate("dní v řadě"))
        assertEquals("day in a row", translate("den v řadě"))
        assertEquals("days in a row", translate("dny v řadě"))
    }

    // ── Stats range ───────────────────────────────────────────────────────────

    /** "· Tento rok" — the trailing range label on the mood distribution header. */
    @Test
    fun `the stats range suffix follows the selected window`() {
        assertEquals("· This year", translate("· Tento rok"))
        assertEquals("· 30 days", translate("· 30 dní"))
        assertEquals("· 7 days", translate("· 7 dní"))
    }

    // ── Year summary ──────────────────────────────────────────────────────────

    /** "Nejlepší: Srpen 4.1 / 5 · nejslabší: Únor 2.9 / 5" as one rendered line. */
    @Test
    fun `the year best and worst month line translates as a whole`() {
        val best = StatsMath.monthFullName(8) // "August" in English mode.
        val worst = StatsMath.monthFullName(2) // "February".
        val line = "Nejlepší: $best ${average(4.1)} / 5 · nejslabší: $worst ${average(2.9)} / 5"

        assertEquals(
            "Best: August 4.1 / 5 · weakest: February 2.9 / 5",
            translate(line),
        )
    }

    // ── Year in pixels ────────────────────────────────────────────────────────

    /** "Rok 2026" plus the full 400-day limit hint under it. */
    @Test
    fun `the year card header and its full limit hint are English`() {
        assertEquals("Year 2026", translate("Rok 2026"))

        val hint = "Zobrazuju posledních ${StatsRepository.LOAD_DAYS} dní, takže starší dny roku " +
            "zůstávají prázdné, i když je máš v deníku zapsané."
        assertEquals(
            "Showing the last ${StatsRepository.LOAD_DAYS} days, so older days of the year stay empty " +
                "even though you have them written in your journal.",
            translate(hint),
        )
    }

    // ── Goals ─────────────────────────────────────────────────────────────────

    /** "aktivita: běhání" — the goal card's activity line. */
    @Test
    fun `a goal activity line translates its prefix and a known label`() {
        assertEquals("activity: Running", translate("aktivita: běhání"))
    }

    // ── Best and worst day ────────────────────────────────────────────────────

    /** "3 h 12 min · 45 odemknutí · 2 aktivity" — the card's single facts line. */
    @Test
    fun `the best and worst day facts line translates as a whole`() {
        assertEquals(
            "3 h 12 min · 45 unlocks · 2 activities",
            translate("${formatMinutes(192)} · 45 odemknutí · 2 aktivity"),
        )
        assertEquals(
            "45 min · 1 unlock · 1 activity",
            translate("${formatMinutes(45)} · 1 odemknutí · 1 aktivita"),
        )
        // A day the sync worker never reported keeps the screen's own dash.
        assertEquals("— · — unlocks · 0 activities", translate("— · — odemknutí · 0 aktivit"))
    }

    // ── Screen time charts ────────────────────────────────────────────────────

    /** The unlock caption keeps the legend sentence appended to the same window. */
    @Test
    fun `the unlock chart caption keeps its appended legend in English`() {
        val from = LocalDate.of(2026, 9, 4).format(UiDates.dayMonth())
        val to = LocalDate.of(2026, 9, 10).format(UiDates.dayMonth())
        val caption = "Posledních 7 dní ($from – $to), stejné období jako čas na obrazovce. " +
            "Sloupec = jeden den, přerušovaná čára = průměr."

        assertEquals(
            "Last 7 days ($from – $to), the same period as screen time. " +
                "One bar = one day, dashed line = average.",
            translate(caption),
        )
    }

    // ── Empty states ──────────────────────────────────────────────────────────

    /** "Za posledních 30 dní žádný zápis" — the empty mood window names its range. */
    @Test
    fun `the empty stats window translates the range it names`() {
        assertEquals("No entry over the last 30 days", translate(emptyWindowTitle(StatsRange.MONTH)))
        assertEquals("No entry over the last 7 days", translate(emptyWindowTitle(StatsRange.WEEK)))
    }

    /** The dashboard joins these hints from two fragments before the wrapper sees them. */
    @Test
    fun `the dashboard empty hints translate as whole sentences`() {
        assertEquals(
            "Screen time data hasn't synced yet. Once your phone sends them, you'll see the columns here.",
            translate(
                "Data o screen timu se ještě nenasynchronizovala. " +
                    "Až je telefon odešle, uvidíš tady sloupce.",
            ),
        )
        assertEquals(
            "Unlock count hasn't synced yet. Once your phone sends them, you'll see the columns here.",
            translate(
                "Počet odemknutí se ještě nenasynchronizoval. " +
                    "Až je telefon odešle, uvidíš tady sloupce.",
            ),
        )
    }

    // ── Trend line ────────────────────────────────────────────────────────────

    /**
     * The arrow is split off only *after* the sentence is translated. Splitting first
     * asked the dictionary for the caption without its arrow, which is why "same as last
     * week" and the percentage forms used to fall back to Czech.
     */
    @Test
    fun `the trend line translates the whole caption before splitting the arrow`() {
        val up = MetricTrend(current = 120, previous = 100, direction = TrendDirection.UP, percentChange = 20)
        assertEquals(
            TrendLineText(arrow = "↑", caption = "20 % more than last week"),
            trendLineText(up, TrendTone.USAGE),
        )
        assertEquals(
            TrendLineText(arrow = "↑", caption = "20 % higher than last week"),
            trendLineText(up, TrendTone.MOOD),
        )

        val down = MetricTrend(current = 80, previous = 100, direction = TrendDirection.DOWN, percentChange = -20)
        assertEquals(
            TrendLineText(arrow = "↓", caption = "20 % less than last week"),
            trendLineText(down, TrendTone.USAGE),
        )
        assertEquals(
            TrendLineText(arrow = "↓", caption = "20 % lower than last week"),
            trendLineText(down, TrendTone.MOOD),
        )

        val flat = MetricTrend(current = 100, previous = 100, direction = TrendDirection.FLAT, percentChange = 0)
        assertEquals(
            TrendLineText(arrow = "≈", caption = "same as last week"),
            trendLineText(flat, TrendTone.USAGE),
        )

        val new = MetricTrend(current = 100, previous = 0, direction = TrendDirection.UP, percentChange = null)
        assertEquals(
            TrendLineText(arrow = null, caption = "New – no data last week"),
            trendLineText(new, TrendTone.USAGE),
        )

        val unknown = MetricTrend(
            current = 100,
            previous = null,
            direction = TrendDirection.UNKNOWN,
            percentChange = null,
        )
        assertEquals(
            TrendLineText(arrow = null, caption = "No comparison – last week missing"),
            trendLineText(unknown, TrendTone.USAGE),
        )

        assertNull(
            trendLineText(
                MetricTrend(current = null, previous = 5, direction = TrendDirection.UNKNOWN, percentChange = null),
                TrendTone.USAGE,
            ),
        )
    }

    /** The arrow-prefixed sentences the caption builder produces, as whole rules. */
    @Test
    fun `the arrow-prefixed trend sentences are dictionary rules`() {
        assertEquals("↑ 12 % more than last week", translate("↑ o 12 % více než minulý týden"))
        assertEquals("↓ 12 % less than last week", translate("↓ o 12 % méně než minulý týden"))
        assertEquals("↑ 12 % higher than last week", translate("↑ o 12 % vyšší než minulý týden"))
        assertEquals("↓ 12 % lower than last week", translate("↓ o 12 % nižší než minulý týden"))
    }

    /** "😄 výborná nálada" / "🙁 slabá nálada" — the mood word inside the caption. */
    @Test
    fun `the dashboard mood word covers excellent and weak`() {
        assertEquals("😄 excellent mood", translate("😄 výborná nálada"))
        assertEquals("🙁 weak mood", translate("🙁 slabá nálada"))
        assertEquals("🙂 good mood", translate("🙂 dobrá nálada"))
    }

    // ── History month sentences ───────────────────────────────────────────────

    /**
     * The specific month sentence must win over the generic "V měsíci …" rule; with the
     * generic one first it swallowed the whole tail and left the English UI half Czech.
     */
    @Test
    fun `the history month empty sentence translates as one full sentence`() {
        val title = HistoryCalendar.title(2026, 9)
        assertEquals("September 2026", title)
        assertEquals(
            "You have no entry in September 2026. Tap a day in the calendar and write down how it went.",
            translate(monthEmptyText(title)),
        )
    }

    @Test
    fun `the history synced-only month sentence translates as one full sentence`() {
        val title = HistoryCalendar.title(2026, 9)
        assertEquals(
            "In September 2026 you only have data synced from the phone (screen time, unlocks). " +
                "Without a mood the day does not count as an entry — tap a day and fill in the check-in.",
            translate(monthSyncedOnlyText(title)),
        )
    }

    // ── Goals summary ─────────────────────────────────────────────────────────

    /** "3/8 období ✓ (38 %) · série 🔥5" — the parts are translated before joining. */
    @Test
    fun `the goal summary translates its parts before joining them`() {
        assertEquals("3/8 periods ✓ (38 %) · 🔥5 streak", goalSummaryText(3, 8, 5))
        assertEquals("3/8 periods ✓ (38 %)", goalSummaryText(3, 8, 0))
        assertEquals("🔥5 streak", goalSummaryText(0, 0, 5))
        assertNull(goalSummaryText(0, 0, 0))
    }

    // ── Stats selection and empty window ──────────────────────────────────────

    /** The distribution card's tap detail: "🙂 Dobře" and "3 dny · 38 %". */
    @Test
    fun `the selected mood row translates its label and share`() {
        assertEquals("🙂 Good", moodChoiceText(4))
        assertEquals("Unanswered", moodChoiceText(0))
        assertEquals("3 days · 38 %", translate("3 dny · 38 %"))
        assertEquals("3 days · 38 %", translate(moodShareText(3, 38)))
        assertEquals("1 day · 25 %", translate(moodShareText(1, 25)))
        assertEquals("17 days · 5 %", translate(moodShareText(17, 5)))
    }

    /** "17 dní" — the summary tile's count and unit are one label, so it prints once. */
    @Test
    fun `the answered days tile prints one count and translates it`() {
        assertEquals("17 dní", answeredDaysText(17))
        assertEquals("1 den", answeredDaysText(1))
        assertEquals("17 days", translate(answeredDaysText(17)))
        assertEquals("1 day", translate(answeredDaysText(1)))
        assertEquals("0 days", translate(answeredDaysText(0)))
        assertEquals(1, Regex("\\b17\\b").findAll(answeredDaysText(17)).count())
        assertEquals(1, Regex("\\b17\\b").findAll(translate(answeredDaysText(17))).count())
    }

    /**
     * The year tab is a calendar year, not "the last N days": its Czech sentence is
     * built at the source instead of dropping "Tento rok" into the day-count template.
     */
    @Test
    fun `the year empty window is grammatical Czech and natural English`() {
        assertEquals("Tento rok žádný zápis", emptyWindowTitle(StatsRange.YEAR))
        assertFalse(emptyWindowTitle(StatsRange.YEAR).contains("Za posledních"))
        assertEquals("No entry this year", translate(emptyWindowTitle(StatsRange.YEAR)))

        assertEquals("Za posledních 7 dní žádný zápis", emptyWindowTitle(StatsRange.WEEK))
        assertEquals("No entry over the last 7 days", translate(emptyWindowTitle(StatsRange.WEEK)))
        assertEquals("Za posledních 30 dní žádný zápis", emptyWindowTitle(StatsRange.MONTH))
        assertEquals("No entry over the last 30 days", translate(emptyWindowTitle(StatsRange.MONTH)))
    }

    // ── Year in pixels ────────────────────────────────────────────────────────

    /** "10 Sep 2026 — 🙂 Good": the date is already English, the mood word must be too. */
    @Test
    fun `the selected year pixel translates its date and mood word`() {
        assertEquals("10 Sep 2026 — 🙂 Good", cellLabel("2026-09-10", 4))
        assertEquals("10 Sep 2026 — no answer", cellLabel("2026-09-10", 0))
    }

    // ── Dashboard mood chart ──────────────────────────────────────────────────

    /** The dashboard chart's tap detail joins emoji and label — both must read English. */
    @Test
    fun `the dashboard chart's selected mood label translates fully`() {
        assertEquals("🙂 Good", translate(moodTextOf(day(mood = 4))))
        assertEquals("Unanswered", translate(moodTextOf(day(mood = 0))))
    }

    // ── Activity days ─────────────────────────────────────────────────────────

    /** "3 dny s aktivitou" — the 2–4 plural the goal period window prints. */
    @Test
    fun `two to four activity days translate with the Czech plural`() {
        assertEquals("1 day with activity", translate("1 den s aktivitou"))
        assertEquals("3 days with activity", translate("3 dny s aktivitou"))
        assertEquals("4 days with activity", translate("4 dny s aktivitou"))
        assertEquals("17 days with activity", translate("17 dní s aktivitou"))
    }

    // ── Czech stays Czech ─────────────────────────────────────────────────────

    /** The composed interactive strings must not leak English into the Czech UI. */
    @Test
    fun `composed interactive strings stay Czech in Czech mode`() {
        AppLanguage.set(context, Lang.CS)
        assertEquals("3/8 období ✓ (38 %) · série 🔥5", goalSummaryText(3, 8, 5))
        assertEquals("🙂 Dobře", moodChoiceText(4))
        assertEquals("10. 9. 2026 — 🙂 Dobře", cellLabel("2026-09-10", 4))
        assertEquals(
            TrendLineText(arrow = "↑", caption = "o 20 % více než minulý týden"),
            trendLineText(
                MetricTrend(current = 120, previous = 100, direction = TrendDirection.UP, percentChange = 20),
                TrendTone.USAGE,
            ),
        )
        AppLanguage.set(context, Lang.EN)
    }

    /**
     * The inline weekday is lowercase in Czech ("pátek, 12. 9.") while the notifications
     * picker keeps its capitalised label ("Pátek") — the split b8e5597 collapsed into
     * one capitalised table, which leaked "Pátek, 12. 9." into the metric dialog.
     */
    @Test
    fun `inline weekdays stay lowercase in Czech and capitalised in English`() {
        AppLanguage.set(context, Lang.CS)
        assertEquals("pátek", StatsMath.weekdayNameInline(DayOfWeek.FRIDAY))
        // The picker vocabulary is unchanged.
        assertEquals("Pátek", StatsMath.weekdayFullName(DayOfWeek.FRIDAY))
        // And no new rule touches the Czech runtime string.
        assertEquals("17 dní", translate("17 dní"))
        assertEquals("· Tento rok", translate("· Tento rok"))

        AppLanguage.set(context, Lang.EN)
        assertEquals("Friday", StatsMath.weekdayNameInline(DayOfWeek.FRIDAY))
        assertEquals("Friday", StatsMath.weekdayFullName(DayOfWeek.FRIDAY))
    }

    /** A dashboard day carrying only the mood — enough for the chart's tap detail. */
    private fun day(mood: Int): DashboardDay = DashboardDay(
        date = "2026-09-10",
        mood = mood,
        hasEntry = mood in 1..5,
        screenTimeSeconds = null,
        unlocks = null,
    )

    /** The web prints `toFixed(1)`, so averages keep a dot rather than a Czech comma. */
    private fun average(value: Double): String = String.format(Locale.US, "%.1f", value)
}
