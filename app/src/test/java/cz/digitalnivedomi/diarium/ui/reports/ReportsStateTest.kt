package cz.digitalnivedomi.diarium.ui.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI reports screen's copy and its pure helpers.
 *
 * Two traps live here. The date labels are Czech — "1. 9. – 8. 9. 2026" prints the year
 * once, a range across a new year prints it twice, and a row whose dates arrive empty must
 * not put "null" on the screen. And the report body is markdown-ish prose with no markdown
 * library behind it: every split and every join is pinned below, because a parser that is
 * merely "probably right" quietly reshapes what the AI wrote.
 */
class ReportsStateTest {

    // --- dates ---------------------------------------------------------------------

    @Test
    fun `a period inside one year prints the year once`() {
        assertEquals(
            "1. 9. – 8. 9. 2026",
            ReportsState.periodLabel(start = "2026-09-01", end = "2026-09-08"),
        )
        assertEquals(
            "1. 9. – 30. 9. 2026",
            ReportsState.periodLabel(start = "2026-09-01", end = "2026-09-30"),
        )
    }

    @Test
    fun `a period across a new year prints both years`() {
        assertEquals(
            "29. 12. 2025 – 4. 1. 2026",
            ReportsState.periodLabel(start = "2025-12-29", end = "2026-01-04"),
        )
    }

    @Test
    fun `a blank or broken period degrades to no range, never to the word null`() {
        assertEquals("", ReportsState.periodLabel(start = "", end = ""))
        assertEquals("", ReportsState.periodLabel(start = "   ", end = " "))
        // One readable end is still worth showing, and the unreadable one is not invented.
        assertEquals("1. 9. 2026", ReportsState.periodLabel(start = "2026-09-01", end = ""))
        assertEquals(
            "1. 9. 2026 – konec září",
            ReportsState.periodLabel(start = "2026-09-01", end = "konec září"),
        )
        assertEquals(
            "od začátku",
            ReportsState.periodLabel(start = "od začátku", end = ""),
        )
        listOf(
            ReportsState.periodLabel(start = "", end = ""),
            ReportsState.periodLabel(start = "nope", end = "nope"),
            ReportsState.periodLabel(start = "2026-09-01", end = "nope"),
        ).forEach { label ->
            assertFalse("null in \"$label\"", label.contains("null"))
        }
    }

    @Test
    fun `the created caption is Prague time, not the device's zone`() {
        assertEquals(
            "Vygenerováno 8. 9. 2026 v 19:40",
            ReportsState.createdLabel("2026-09-08T19:40:00+02:00"),
        )
        // The same instant written as UTC: summer time is CEST (+2), so still 19:40.
        assertEquals(
            "Vygenerováno 8. 9. 2026 v 19:40",
            ReportsState.createdLabel("2026-09-08T17:40:00Z"),
        )
        // Winter time is CET (+1) and the clock is zero-padded.
        assertEquals(
            "Vygenerováno 5. 1. 2026 v 10:05",
            ReportsState.createdLabel("2026-01-05T09:05:00+00:00"),
        )
    }

    @Test
    fun `an unparsable created timestamp leaves the caption out`() {
        assertEquals("", ReportsState.createdLabel(""))
        assertEquals("", ReportsState.createdLabel("   "))
        assertEquals("", ReportsState.createdLabel("včera"))
    }

    // --- failure sentences ---------------------------------------------------------

    @Test
    fun `a failed read keeps the data layer's own reason on one line`() {
        assertEquals("Přehled se nepodařilo načíst.", ReportsState.loadFailedMessage(null))
        assertEquals("Přehled se nepodařilo načíst.", ReportsState.loadFailedMessage("   "))
        assertEquals(
            "Přehled se nepodařilo načíst — Přihlášení vypršelo, přihlas se znovu.",
            ReportsState.loadFailedMessage("Přihlášení vypršelo, přihlas se znovu."),
        )
        val withTrace = ReportsState.loadFailedMessage(
            "java.io.IOException: read failed\n\tat okhttp3.RealCall.x",
        )
        assertFalse("no newline in \"$withTrace\"", withTrace.contains('\n'))
        assertFalse("no tab in \"$withTrace\"", withTrace.contains('\t'))
    }

    @Test
    fun `a failed generation falls back to the timeout sentence`() {
        assertEquals(ReportsState.GEN_FAILED_MESSAGE, ReportsState.generateFailedMessage(null))
        assertEquals(ReportsState.GEN_FAILED_MESSAGE, ReportsState.generateFailedMessage(""))
        assertEquals(
            "Přehled se nepodařilo vygenerovat (Server neodpověděl). Zkus to za chvíli znovu.",
            ReportsState.generateFailedMessage("Server neodpověděl"),
        )
    }

    // --- the markdown-ish splitter -------------------------------------------------

    @Test
    fun `blank content has no blocks at all`() {
        assertEquals(emptyList<Block>(), ReportsState.blocks(""))
        assertEquals(emptyList<Block>(), ReportsState.blocks("   \n\n \t \n"))
    }

    @Test
    fun `wrapped prose becomes a single paragraph`() {
        assertEquals(
            listOf<Block>(Block.Paragraph("První věta. Druhá věta.")),
            ReportsState.blocks("První věta.\nDruhá věta.   "),
        )
    }

    @Test
    fun `a blank line starts a new paragraph`() {
        assertEquals(
            listOf<Block>(
                Block.Paragraph("První odstavec."),
                Block.Paragraph("Druhý odstavec."),
            ),
            ReportsState.blocks("První odstavec.\n\nDruhý odstavec.\n"),
        )
    }

    @Test
    fun `hashes turn a line into a heading without the hashes`() {
        assertEquals(
            listOf<Block>(Block.Heading("Shrnutí týdne")),
            ReportsState.blocks("## Shrnutí týdne"),
        )
        assertEquals(
            listOf<Block>(Block.Heading("Detaily")),
            ReportsState.blocks("### Detaily"),
        )
    }

    @Test
    fun `each bullet is a block of its own`() {
        assertEquals(
            listOf<Block>(
                Block.Bullet("Spánek 7 h"),
                Block.Bullet("Pohyb 3x"),
            ),
            ReportsState.blocks("- Spánek 7 h\n* Pohyb 3x"),
        )
    }

    @Test
    fun `a real report keeps its headings, paragraphs and bullets in order`() {
        val content = """
            ## Shrnutí

            Byl to **dobrý** týden.
            Držel jsi se plánu.
            - Spánek 7 h
            - Pohyb 3x

            ## Detaily
            Zbytek týdne byl klidný.
        """.trimIndent()

        assertEquals(
            listOf<Block>(
                Block.Heading("Shrnutí"),
                Block.Paragraph("Byl to **dobrý** týden. Držel jsi se plánu."),
                Block.Bullet("Spánek 7 h"),
                Block.Bullet("Pohyb 3x"),
                Block.Heading("Detaily"),
                Block.Paragraph("Zbytek týdne byl klidný."),
            ),
            ReportsState.blocks(content),
        )
    }

    // --- bold runs -----------------------------------------------------------------

    @Test
    fun `plain text is one unbold chunk`() {
        assertEquals(
            listOf(InlineChunk("Bez důrazu.", bold = false)),
            ReportsState.inlineBold("Bez důrazu."),
        )
        assertEquals(emptyList<InlineChunk>(), ReportsState.inlineBold(""))
    }

    @Test
    fun `a bold run keeps the text around it`() {
        assertEquals(
            listOf(
                InlineChunk("Bylo to ", bold = false),
                InlineChunk("skvělé", bold = true),
                InlineChunk(" dnes.", bold = false),
            ),
            ReportsState.inlineBold("Bylo to **skvělé** dnes."),
        )
    }

    @Test
    fun `bold at the start or the end adds no empty chunk`() {
        assertEquals(
            listOf(
                InlineChunk("Dobrý", bold = true),
                InlineChunk(" den", bold = false),
            ),
            ReportsState.inlineBold("**Dobrý** den"),
        )
        assertEquals(
            listOf(
                InlineChunk("Den byl dobrý ", bold = false),
                InlineChunk("celý", bold = true),
            ),
            ReportsState.inlineBold("Den byl dobrý **celý**"),
        )
        assertEquals(
            listOf(
                InlineChunk("Celý", bold = true),
                InlineChunk(" den ", bold = false),
                InlineChunk("dobrý", bold = true),
                InlineChunk(".", bold = false),
            ),
            ReportsState.inlineBold("**Celý** den **dobrý**."),
        )
    }

    @Test
    fun `an unclosed marker stays literal text instead of eating the line`() {
        assertEquals(
            listOf(InlineChunk("Text **nedokončený", bold = false)),
            ReportsState.inlineBold("Text **nedokončený"),
        )
    }

    // --- labels and copy -----------------------------------------------------------

    @Test
    fun `the kind label names the period, the button names the action`() {
        assertEquals("Týdenní přehled", ReportsState.kindLabel("weekly"))
        assertEquals("Měsíční přehled", ReportsState.kindLabel("monthly"))
        assertEquals(ReportsState.GENERATE_WEEKLY, ReportsState.generateLabel("weekly"))
        assertEquals(ReportsState.GENERATE_MONTHLY, ReportsState.generateLabel("monthly"))
        assertEquals(listOf("weekly", "monthly"), ReportsState.TYPES)
    }

    @Test
    fun `every sentence the screen shows is Czech`() {
        val shown = listOf(
            ReportsState.TITLE,
            ReportsState.SUBTITLE,
            ReportsState.LOADING_LABEL,
            ReportsState.EMPTY_TITLE,
            ReportsState.EMPTY_MESSAGE,
            ReportsState.LOAD_FAILED_TITLE,
            ReportsState.RETRY,
            ReportsState.GENERATE_WEEKLY,
            ReportsState.GENERATE_MONTHLY,
            ReportsState.GENERATING_LABEL,
            ReportsState.GEN_FAILED_MESSAGE,
            ReportsState.OFFLINE_MESSAGE,
            ReportsState.loadFailedMessage(null),
        )

        shown.forEach { text ->
            assertTrue("blank copy: \"$text\"", text.isNotBlank())
        }
        // A handful of English UI words that must not appear in user-facing copy.
        val forbidden = listOf("Loading", "Retry", "Generate", "Report", "Error", "Empty")
        shown.forEach { text ->
            forbidden.forEach { word ->
                assertFalse("\"$word\" in \"$text\"", text.contains(word, ignoreCase = false))
            }
        }
    }
}
