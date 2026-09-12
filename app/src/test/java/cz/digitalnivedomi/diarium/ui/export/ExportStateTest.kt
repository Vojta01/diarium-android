package cz.digitalnivedomi.diarium.ui.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The export screen's copy and derived labels.
 *
 * Czech pluralisation is the trap here — "2 záznamy" but "5 záznamů", while 12–14 go
 * back to "záznamů" — so the rule is pinned on both sides of each boundary rather than
 * left to whoever reads the screen next.
 */
class ExportStateTest {

    @Test
    fun `the suggested file name carries the day, so two exports do not collide`() {
        assertEquals(
            "diarium-export-2026-09-12.csv",
            ExportState.fileName(LocalDate.of(2026, 9, 12)),
        )
        assertEquals(
            "diarium-export-2026-01-01.csv",
            ExportState.fileName(LocalDate.of(2026, 1, 1)),
        )
    }

    @Test
    fun `the count label follows Czech plural rules`() {
        assertEquals("0 záznamů", ExportState.entryCountLabel(0))
        assertEquals("1 záznam", ExportState.entryCountLabel(1))
        assertEquals("2 záznamy", ExportState.entryCountLabel(2))
        assertEquals("3 záznamy", ExportState.entryCountLabel(3))
        assertEquals("4 záznamy", ExportState.entryCountLabel(4))
        assertEquals("5 záznamů", ExportState.entryCountLabel(5))
        assertEquals("11 záznamů", ExportState.entryCountLabel(11))
        assertEquals("12 záznamů", ExportState.entryCountLabel(12))
        assertEquals("14 záznamů", ExportState.entryCountLabel(14))
        assertEquals("22 záznamy", ExportState.entryCountLabel(22))
        assertEquals("101 záznamů", ExportState.entryCountLabel(101))
    }

    @Test
    fun `the size label is kilobytes with one decimal, bytes below that`() {
        assertEquals("0 B", ExportState.binarySizeLabel(0))
        assertEquals("512 B", ExportState.binarySizeLabel(512))
        assertEquals("1023 B", ExportState.binarySizeLabel(1023))
        assertEquals("1.0 kB", ExportState.binarySizeLabel(1024))
        assertEquals("1.5 kB", ExportState.binarySizeLabel(1536))
        assertEquals("2.0 kB", ExportState.binarySizeLabel(2048))
    }

    @Test
    fun `the confirmation names the count and the file size`() {
        assertEquals(
            "Uloženo: 1 záznam (512 B).",
            ExportState.savedMessage(entries = 1, bytes = 512),
        )
        assertEquals(
            "Uloženo: 2 záznamy (2.0 kB).",
            ExportState.savedMessage(entries = 2, bytes = 2048),
        )
        assertEquals(
            "Uloženo: 120 záznamů (48.0 kB).",
            ExportState.savedMessage(entries = 120, bytes = 48 * 1024),
        )
    }

    @Test
    fun `a failed read keeps the data layer's own reason`() {
        assertEquals("Zápisy se nepodařilo načíst.", ExportState.loadFailedMessage(null))
        assertEquals("Zápisy se nepodařilo načíst.", ExportState.loadFailedMessage(""))
        assertEquals("Zápisy se nepodařilo načíst.", ExportState.loadFailedMessage("   "))
        assertEquals(
            "Zápisy se nepodařilo načíst — Přihlášení vypršelo, přihlas se znovu.",
            ExportState.loadFailedMessage("Přihlášení vypršelo, přihlas se znovu."),
        )
    }

    @Test
    fun `a failed write names the reason in brackets`() {
        assertEquals("Uložení se nezdařilo.", ExportState.saveFailedMessage(null))
        assertEquals(
            "Uložení se nezdařilo (IOException).",
            ExportState.saveFailedMessage("IOException"),
        )
    }

    @Test
    fun `failure sentences are one line and never leak a stack trace`() {
        listOf(
            ExportState.loadFailedMessage("java.io.IOException: read failed\n\tat okhttp3.x"),
            ExportState.saveFailedMessage("SecurityException\n\tat android.x"),
        ).forEach { message ->
            assertFalse("no newline in \"$message\"", message.contains('\n'))
            assertFalse("no tab in \"$message\"", message.contains('\t'))
        }
    }

    @Test
    fun `an empty account is EMPTY and any entry at all is READY`() {
        assertEquals(ExportPhase.EMPTY, ExportState.phaseFor(0))
        assertEquals(ExportPhase.READY, ExportState.phaseFor(1))
        assertEquals(ExportPhase.READY, ExportState.phaseFor(5000))
    }

    @Test
    fun `every sentence the screen shows is Czech`() {
        val shown = listOf(
            ExportState.TITLE,
            ExportState.SUBTITLE,
            ExportState.LOADING_LABEL,
            ExportState.LOAD_FAILED_TITLE,
            ExportState.RETRY,
            ExportState.LOAD_AGAIN,
            ExportState.SAVE_ACTION,
            ExportState.SAVE_CANCELLED,
            ExportState.EMPTY_TITLE,
            ExportState.EMPTY_MESSAGE,
            ExportState.OFFLINE_MESSAGE,
            ExportState.SOURCE_HINT,
            ExportState.SAVER_HINT,
            ExportState.PARITY_HINT,
        )

        shown.forEach { text ->
            assertTrue("blank copy: \"$text\"", text.isNotBlank())
        }
        // A handful of English UI words that must not appear in user-facing copy.
        val forbidden = listOf("Save", "Cancel", "Export to", "File", "Retry", "Export is")
        shown.forEach { text ->
            forbidden.forEach { word ->
                assertFalse("\"$word\" in \"$text\"", text.contains(word, ignoreCase = false))
            }
        }
    }
}
