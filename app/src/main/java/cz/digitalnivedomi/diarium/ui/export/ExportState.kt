package cz.digitalnivedomi.diarium.ui.export

import java.time.LocalDate
import java.util.Locale

/** What the export screen is doing right now — the only thing the UI switches on. */
enum class ExportPhase {
    /** Reading the entries from Supabase. */
    LOADING,

    /** Entries are in memory; the user can pick a file. */
    READY,

    /** The account has no entries yet, so there is nothing to write. */
    EMPTY,

    /** The read failed; the screen offers a retry. */
    ERROR,
}

/**
 * Every Czech sentence and every derived label the export screen shows, kept out of
 * the composable so the copy (and the Czech pluralisation, which is easy to get
 * wrong) is testable on the JVM — the same split as `NotificationSettingsState`.
 */
object ExportState {

    const val TITLE = "Export do CSV"
    const val SUBTITLE = "Všechny zápisy v jednom souboru, který si uložíš, kam chceš."

    const val LOADING_LABEL = "Načítám zápisy…"
    const val LOAD_FAILED_TITLE = "Načtení se nezdařilo"
    const val RETRY = "Zkusit znovu"
    const val LOAD_AGAIN = "Načíst znovu"
    const val SAVE_ACTION = "Uložit CSV…"
    const val SAVE_CANCELLED = "Ukládání zrušeno."
    const val EMPTY_TITLE = "Zatím žádné zápisy"
    const val EMPTY_MESSAGE = "Až si vyplníš první den, bude co exportovat."
    const val OFFLINE_MESSAGE = "Export teď není dostupný."

    const val SOURCE_HINT =
        "Data se načtou přímo z tvého účtu v Supabase. Aplikace nikam neposílá klíč ani cizí server."

    const val SAVER_HINT =
        "Soubor uložíš přes systémové okno — aplikace k tomu nepotřebuje žádné nové oprávnění."

    const val PARITY_HINT =
        "Stejné sloupce a stejné pořadí jako u exportu na webu, včetně kódování diakritiky v UTF-8."

    /**
     * Suggested file name in the system picker. The date is the device's local day —
     * the file name is not part of the web export, so a plain local date keeps two
     * exports taken on different days apart.
     */
    fun fileName(today: LocalDate): String = "diarium-export-$today.csv"

    /** Czech count: 1 záznam, 2–4 záznamy, 0 a 5+ záznamů (12–14 are záznamů again). */
    fun entryCountLabel(count: Int): String {
        val mod100 = count % 100
        val mod10 = count % 10
        return when {
            count == 1 -> "$count záznam"
            mod10 in 2..4 && mod100 !in 12..14 -> "$count záznamy"
            else -> "$count záznamů"
        }
    }

    /** Bytes below a kilobyte, one decimal of kB above it — Locale-independent, so the test is too. */
    fun binarySizeLabel(bytes: Int): String =
        if (bytes < 1024) "$bytes B" else String.format(Locale.ROOT, "%.1f kB", bytes / 1024.0)

    /** The confirmation after a successful write to the picked file. */
    fun savedMessage(entries: Int, bytes: Int): String =
        "Uloženo: ${entryCountLabel(entries)} (${binarySizeLabel(bytes)})."

    /** A failed read, with the data layer's own Czech sentence when it has one. */
    fun loadFailedMessage(detail: String?): String =
        sentence("Zápisy se nepodařilo načíst", detail, open = " — ", close = "")

    /** A failed write; the reason is a class name or a system message, never a stack trace. */
    fun saveFailedMessage(reason: String?): String =
        sentence("Uložení se nezdařilo", reason, open = " (", close = ")")

    /**
     * The one-line reason worth showing, or null when there is none: only the first
     * line of [detail] survives (a stack trace's `\tat okhttp3.x` never reaches the
     * screen), control characters are dropped, and a blank result counts as no reason.
     */
    private fun reason(detail: String?): String? = detail
        ?.lineSequence()
        ?.firstOrNull()
        ?.filterNot { it.isISOControl() }
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    /** `bare` alone, or `bare` with the reason wrapped in [open]/[close] — always a full stop. */
    private fun sentence(bare: String, detail: String?, open: String, close: String): String {
        val shown = reason(detail) ?: return "$bare."
        val body = "$bare$open$shown$close"
        return if (body.endsWith('.')) body else "$body."
    }

    /** The phase that follows a successful read of [count] entries. */
    fun phaseFor(count: Int): ExportPhase = if (count > 0) ExportPhase.READY else ExportPhase.EMPTY
}
