package cz.digitalnivedomi.diarium.ui.i18n

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.core.i18n.AppLanguage
import cz.digitalnivedomi.diarium.core.i18n.Lang
import cz.digitalnivedomi.diarium.core.i18n.UiDates
import cz.digitalnivedomi.diarium.core.stats.StatsMath
import cz.digitalnivedomi.diarium.ui.history.HistoryCalendar
import cz.digitalnivedomi.diarium.ui.nav.TopLevelDestination
import cz.digitalnivedomi.diarium.ui.reports.ReportsState
import cz.digitalnivedomi.diarium.ui.settings.NotificationSettingsState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Pins the English UI the documentation screenshots are taken in.
 *
 * The translation layer falls back to Czech on a missing key — by design, so a forgotten
 * string never blanks a screen. The cost is that a regression is invisible: a tab silently
 * reads "Dnes" again and nothing fails. These assertions are the tripwire for the labels
 * that end up in the screenshots (tab bar, dashboard, stats, history, day detail, settings)
 * plus the calendar and date formatting that used to be hardcoded Czech.
 *
 * Robolectric rather than plain JUnit because [AppLanguage.set] persists through
 * `SharedPreferences`; the test therefore exercises the same switch the Settings screen does.
 * The language is a process-wide singleton, so every test starts in English and [tearDown]
 * puts Czech back — otherwise a later class pinning Czech copy would fail depending on order.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EnglishUiTest {

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

    // ── Bottom navigation ─────────────────────────────────────────────────────

    /**
     * The enum still carries the Czech literal as its dictionary key — that is what the
     * `Text` wrapper and `tr()` look up. What must change is the rendered label.
     */
    @Test
    fun `tab labels stay Czech keys but render in English`() {
        assertEquals(
            listOf("Přehled", "Dnes", "Historie", "Statistiky", "Nastavení"),
            TopLevelDestination.entries.map { it.label },
        )
        assertEquals(
            listOf("Overview", "Today", "History", "Statistics", "Settings"),
            TopLevelDestination.entries.map { translate(it.label) },
        )
    }

    // ── Labels the documentation scenes show ──────────────────────────────────

    @Test
    fun `dashboard and day-detail chrome is English`() {
        assertEquals("Badges", translate("Odznaky"))
        assertEquals("Notifications", translate("Notifikace"))
        assertEquals("Stress", translate("Stres"))
        assertEquals("Refresh", translate("Aktualizovat"))
        assertEquals("Try again", translate("Zkusit znovu"))
        assertEquals("Generating reflection…", translate("Generuji reflexi…"))
        assertEquals("📅 Today", translate("📅 Dnes"))
        assertEquals("🕰️ On this day in history", translate("🕰️ Tenhle den v historii"))
        assertEquals("🤖 AI Reflection", translate("🤖 AI Reflexe"))
        assertEquals(
            "This reflection isn't saved on this phone yet. It remains in memory during this session when switching screens or accounts, but may be lost if you close or restart the app. Try saving again or copy it.",
            translate(
                "Reflexe zatím není uložená v telefonu. Při přepnutí obrazovky nebo účtu zůstane v paměti této relace, ale po zavření či restartu aplikace se může ztratit. Zkus opětovné uložení nebo ji zkopíruj.",
            ),
        )
        assertEquals("Photo", translate("Fotka"))
        assertEquals("📷 Photo", translate("📷 Fotka"))
        assertEquals("🏃 Activities", translate("🏃 Aktivity"))
        assertEquals("Day detail", translate("Detail dne"))
        assertEquals("📅 Day detail", translate("📅 Detail dne"))
        assertEquals("Photo of the day", translate("Fotka dne"))
    }

    @Test
    fun `profile privacy disclosure says profile details are sent to DeepSeek`() {
        assertEquals(
            "Your name and birth year are stored on this phone. If provided, they are sent with journal data in the prompt to DeepSeek when generating an AI reflection or report. Reflections go through our server; reports may be generated directly on this phone or through our server.",
            translate(
                "Jméno a rok narození jsou uložené v tomto telefonu. Pokud je vyplníš, při žádosti o AI reflexi nebo přehled se odešlou spolu s údaji z deníku v promptu pro DeepSeek. Reflexe procházejí naším serverem; přehledy mohou být vygenerované přímo telefonem nebo přes náš server.",
            ),
        )
    }

    @Test
    fun `recovery copy review asks the user to review without promising a write`() {
        assertEquals(
            "The server version has changed since you started editing.",
            translate("Serverová verze se od zahájení úpravy změnila"),
        )
        assertEquals(
            "There are two versions of this day. The local draft and server copy are both preserved; review both.",
            translate("Na tento den jsou dvě verze. Místní návrh i kopie na serveru zůstávají zachované; prohlédni si obě."),
        )
        assertEquals(
            "The local draft is still saved. Retry loading the server copy to review it too.",
            translate("Místní návrh zůstává uložený. Nejdřív zkus znovu načíst serverovou kopii."),
        )
        assertEquals(
            "Not synced — saved only on this phone. It will not be sent automatically.",
            translate("Nesynchronizováno — uloženo jen v tomto telefonu. Automaticky se neodešle."),
        )
        assertEquals(
            "Saving remains blocked. Review both copies; choosing either won't overwrite anything.",
            translate("Ukládání zůstává zablokované. Prohlédni si obě kopie; výběr nic nepřepíše."),
        )
    }

    @Test
    fun `statistics and history chrome is English`() {
        assertEquals("Legend", translate("Legenda"))
        assertEquals("🗓️ Year in pixels", translate("🗓️ Rok v pixelech"))
        assertEquals("Total", translate("Celkem"))
        assertEquals("By day", translate("Po dnech"))
        assertEquals("This year", translate("Tento rok"))
        assertEquals("Days with activity", translate("Dny s aktivitou"))
        assertEquals("1 day with activity", translate("1 den s aktivitou"))
    }

    @Test
    fun `settings and export chrome is English`() {
        assertEquals("Export to CSV", translate("Export do CSV"))
        assertEquals("What the file contains", translate("Co soubor obsahuje"))
        assertEquals("Sound", translate("Zvuk"))
        assertEquals("Reports", translate("Reporty"))
        assertEquals("Reflections", translate("Reflexe"))
        assertEquals("Features", translate("Funkce"))
        assertEquals("Delete", translate("Smazat"))
        assertEquals("Key status couldn't be verified", translate("Stav klíče nelze ověřit"))
        assertEquals(
            "Couldn't safely verify whether an older key is stored. No data was changed, and an unverified key won't be used.",
            translate("Nepodařilo se bezpečně ověřit, zda je uložený starší klíč. Data nebyla změněna a neověřený klíč se nepoužije."),
        )
    }

    /** A key that does not exist must fall back to the Czech source, never to blank. */
    @Test
    fun `an unknown string falls back to Czech`() {
        val unknown = "Tohle v slovníku není."
        assertEquals(unknown, translate(unknown))
    }

    // ── Calendar ──────────────────────────────────────────────────────────────

    @Test
    fun `calendar weekday headers follow the language`() {
        assertEquals(
            listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"),
            HistoryCalendar.weekdayHeaders(),
        )
        // 2026-09-10 is a Thursday.
        assertEquals("Th", HistoryCalendar.weekdayName(LocalDate.of(2026, 9, 10)))
    }

    @Test
    fun `calendar month header follows the language`() {
        assertEquals("September 2026", HistoryCalendar.title(2026, 9))
        assertEquals("January 2026", HistoryCalendar.title(2026, 1))
        assertEquals("December 2025", HistoryCalendar.title(2025, 12))
    }

    /** The Czech calendar is unchanged — English must not replace it, only run beside it. */
    @Test
    fun `czech calendar names are untouched`() {
        AppLanguage.set(context, Lang.CS)
        assertEquals(
            listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne"),
            HistoryCalendar.weekdayHeaders(),
        )
        assertEquals("Září 2026", HistoryCalendar.title(2026, 9))
    }

    // ── Dates ─────────────────────────────────────────────────────────────────

    @Test
    fun `dates are formatted in the selected language`() {
        val tenthOfSeptember = LocalDate.of(2026, 9, 10)
        assertEquals("10 September 2026", tenthOfSeptember.format(UiDates.longDate()))
        assertEquals("10 Sep 2026", tenthOfSeptember.format(UiDates.numericDate()))
        assertEquals("10 Sep", tenthOfSeptember.format(UiDates.dayMonth()))

        AppLanguage.set(context, Lang.CS)
        assertEquals("10. září 2026", tenthOfSeptember.format(UiDates.longDate()))
        assertEquals("10. 9. 2026", tenthOfSeptember.format(UiDates.numericDate()))
        assertEquals("10. 9.", tenthOfSeptember.format(UiDates.dayMonth()))
    }

    /**
     * The AI report captions used to hardcode the Czech `d. M. yyyy` pattern, so an
     * English screen printed "1. 11. – 7. 11. 2026" under English labels. The period
     * and created captions now go through [UiDates] like every other date.
     */
    @Test
    fun `report period and created captions follow the UI language`() {
        assertEquals(
            "1 Nov – 7 Nov 2026",
            ReportsState.periodLabel(start = "2026-11-01", end = "2026-11-07"),
        )
        assertEquals(
            "29 Dec 2025 – 4 Jan 2026",
            ReportsState.periodLabel(start = "2025-12-29", end = "2026-01-04"),
        )
        assertEquals(
            "Generated 7 Nov 2026 at 08:00",
            translate(ReportsState.createdLabel("2026-11-07T07:00:00+00:00")),
        )

        AppLanguage.set(context, Lang.CS)
        assertEquals(
            "1. 11. – 7. 11. 2026",
            ReportsState.periodLabel(start = "2026-11-01", end = "2026-11-07"),
        )
        assertEquals(
            "Vygenerováno 7. 11. 2026 v 08:00",
            translate(ReportsState.createdLabel("2026-11-07T07:00:00+00:00")),
        )
    }

    // ── Shared name tables ────────────────────────────────────────────────────

    @Test
    fun `weekday names come from one language-aware table`() {
        assertEquals("Friday", StatsMath.weekdayFullName(DayOfWeek.FRIDAY))
        assertEquals("Fr", StatsMath.weekdayShortName(DayOfWeek.FRIDAY))
        assertEquals(
            listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"),
            NotificationSettingsState.dayShortNames,
        )
        assertEquals("Sunday", NotificationSettingsState.weeklyDayName(7))

        AppLanguage.set(context, Lang.CS)
        assertEquals("Pátek", StatsMath.weekdayFullName(DayOfWeek.FRIDAY))
        assertEquals("Pá", StatsMath.weekdayShortName(DayOfWeek.FRIDAY))
    }
}
