package cz.digitalnivedomi.diarium.ui.reports

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * One piece of a rendered report body.
 *
 * The server sends markdown-ish Czech prose from DeepSeek (paragraphs split by a blank
 * line, `**bold**` spans, `-`/`*` bullets, `##`/`###` headings). No markdown library is
 * pulled in for that: [ReportsState.blocks] cuts it into these three shapes and
 * [ReportsState.inlineBold] handles the emphasis inside one line, so the screen only has
 * to lay out what it is told and every rule stays testable on the JVM.
 */
sealed interface Block {

    /** A `##`/`###` line, with the hashes and the leading space already stripped. */
    data class Heading(val text: String) : Block

    /** Consecutive plain lines joined into one paragraph. */
    data class Paragraph(val text: String) : Block

    /** One `-`/`*` line; every bullet is a block of its own. */
    data class Bullet(val text: String) : Block
}

/** A run of one line: plain text, or text that came wrapped in `**…**`. */
data class InlineChunk(val text: String, val bold: Boolean)

/**
 * Every Czech sentence the AI reports screen shows, plus the pure helpers behind it.
 *
 * Copy lives here rather than in the composable for the same reason as in `ExportState`:
 * the sentences, the date formatting (Czech ranges read `1. 9. – 8. 9. 2026`) and the tiny
 * markdown splitter are the parts that break quietly, so they are pinned by JVM tests
 * instead of by looking at the screen.
 */
object ReportsState {

    const val TITLE = "AI Přehledy"
    const val SUBTITLE = "Týdenní a měsíční souhrn deníku, který pro tebe napíše AI."

    const val LOADING_LABEL = "Načítám přehledy…"
    const val LOAD_FAILED_TITLE = "Načtení se nezdařilo"
    const val RETRY = "Zkusit znovu"

    const val EMPTY_TITLE = "Zatím žádný přehled"
    const val EMPTY_MESSAGE =
        "Vygeneruj si první souhrn — AI shrne, co se v deníku za to období objevilo."

    const val GENERATE_WEEKLY = "Vygenerovat týdenní"
    const val GENERATE_MONTHLY = "Vygenerovat měsíční"
    const val GENERATING_LABEL = "AI píše přehled…"

    /** The two kinds the server stores in `ai_reports.type`, in display order. */
    const val TYPE_WEEKLY = "weekly"
    const val TYPE_MONTHLY = "monthly"

    private const val GEN_FAILED_BARE = "Přehled se nepodařilo vygenerovat"

    private const val LOAD_FAILED_BARE = "Přehled se nepodařilo načíst"

    private const val BOLD = "**"

    /** The generation failure: the timeout is the common case, so the sentence names it. */
    const val GEN_FAILED_MESSAGE = "$GEN_FAILED_BARE. Zkus to za chvíli znovu."

    /** Shown instead of any loading state when the screen has no repository at all. */
    const val OFFLINE_MESSAGE = "Přehledy teď nejsou dostupné. Zkus to později."

    val TYPES: List<String> = listOf(TYPE_WEEKLY, TYPE_MONTHLY)

    /** Prague time, not the JVM default: `SELČ`/`SEST` is what the user's watch shows. */
    private val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")

    /** "weekly" -> "Týdenní přehled"; an unknown type falls back to the raw type name. */
    fun kindLabel(type: String): String = when (type) {
        TYPE_WEEKLY -> "Týdenní přehled"
        TYPE_MONTHLY -> "Měsíční přehled"
        else -> type
    }

    /** The button copy for a kind, so the screen never picks the action itself. */
    fun generateLabel(type: String): String = when (type) {
        TYPE_MONTHLY -> GENERATE_MONTHLY
        else -> GENERATE_WEEKLY
    }

    /**
     * The covered period as a Czech range: the year is printed once when both ends share
     * it ("1. 9. – 8. 9. 2026") and on both sides when the range crosses a new year
     * ("29. 12. 2025 – 4. 1. 2026"). A date that does not parse is shown as it arrived
     * instead of being dropped, and nothing ever prints "null".
     */
    fun periodLabel(start: String, end: String): String {
        val from = parseDate(start)
        val to = parseDate(end)
        return when {
            from != null && to != null ->
                if (from.year == to.year) "${dayMonth(from)} – ${full(to)}"
                else "${full(from)} – ${full(to)}"

            // One end (or both) unreadable: fall back to the raw text, minus blanks.
            else -> listOf(
                from?.let { full(it) } ?: start.trim(),
                to?.let { full(it) } ?: end.trim(),
            ).filter { it.isNotEmpty() }.joinToString(" – ")
        }
    }

    /**
     * When the report was minted, in Prague time: "Vygenerováno 8. 9. 2026 v 19:40".
     * An empty string for anything that does not parse — the caption is simply left out.
     */
    fun createdLabel(createdAt: String): String {
        val moment = pragueDateTime(createdAt) ?: return ""
        val time = String.format(Locale.ROOT, "%02d:%02d", moment.hour, moment.minute)
        return "Vygenerováno ${full(moment)} v $time"
    }

    /**
     * The report failure sentence, with the data layer's own Czech reason when it has one.
     * Only the first line of the reason survives, so a stack trace cannot reach the screen.
     */
    fun loadFailedMessage(detail: String?): String = sentence(LOAD_FAILED_BARE, detail)

    /** The generation failure sentence; without a reason it is [GEN_FAILED_MESSAGE]. */
    fun generateFailedMessage(detail: String?): String {
        val shown = reason(detail) ?: return GEN_FAILED_MESSAGE
        return "$GEN_FAILED_BARE ($shown). Zkus to za chvíli znovu."
    }

    /**
     * Splits a report body into headings, paragraphs and bullets.
     *
     * Trailing whitespace is trimmed line by line, a blank line closes the running
     * paragraph (or stands alone where the server left a gap after a bullet), `##`/`###`
     * lines become headings with the hashes stripped, `-`/`*` lines become one bullet
     * each, and every other line is appended to the current paragraph separated by a
     * single space — the server wraps its prose, a reader should not see the wraps.
     * Blank content yields no blocks at all.
     */
    fun blocks(content: String): List<Block> {
        val out = mutableListOf<Block>()
        val paragraph = StringBuilder()

        fun flush() {
            if (paragraph.isNotEmpty()) {
                out += Block.Paragraph(paragraph.toString())
                paragraph.setLength(0)
            }
        }

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val heading = headingOf(line)
            val bullet = bulletOf(line)
            when {
                line.isBlank() -> flush()

                heading != null -> {
                    flush()
                    out += Block.Heading(heading)
                }

                bullet != null -> {
                    flush()
                    out += Block.Bullet(bullet)
                }

                else -> {
                    val text = line.trim()
                    if (text.isNotEmpty()) {
                        if (paragraph.isNotEmpty()) paragraph.append(' ')
                        paragraph.append(text)
                    }
                }
            }
        }
        flush()
        return out
    }

    /**
     * The `**bold**` runs of one line, in order, so the screen can style them without a
     * markdown renderer. Bold at the start or the end produces no empty plain chunk, and
     * an unclosed `**` stays literal text instead of swallowing the rest of the line.
     */
    fun inlineBold(text: String): List<InlineChunk> {
        if (text.isEmpty()) return emptyList()

        val chunks = mutableListOf<InlineChunk>()
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                chunks += InlineChunk(plain.toString(), bold = false)
                plain.setLength(0)
            }
        }

        var index = 0
        while (index < text.length) {
            val open = text.indexOf(BOLD, index)
            val close = if (open < 0) -1 else text.indexOf(BOLD, open + BOLD.length)
            if (open < 0 || close < 0) {
                // No closing marker: the rest of the line is plain text, markers included.
                plain.append(text, index, text.length)
                break
            }
            plain.append(text, index, open)
            val bold = text.substring(open + BOLD.length, close)
            if (bold.isEmpty()) {
                plain.append(text, open, close + BOLD.length)
            } else {
                flushPlain()
                chunks += InlineChunk(bold, bold = true)
            }
            index = close + BOLD.length
        }
        flushPlain()
        return chunks
    }

    /** `- ` and `* ` are the two bullet markers the server uses; anything else is prose. */
    private fun bulletOf(line: String): String? {
        val marker = line.trimStart()
        if (!marker.startsWith("- ") && !marker.startsWith("* ")) return null
        return marker.drop(2).trim().takeIf { it.isNotEmpty() }
    }

    /** A line of one to six `#` followed by a space and some text is a heading. */
    private fun headingOf(line: String): String? {
        val marker = line.trimStart()
        val hashes = marker.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return null
        if (marker.length <= hashes || marker[hashes] != ' ') return null
        return marker.drop(hashes).trim().takeIf { it.isNotEmpty() }
    }

    private fun parseDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.trim()) }.getOrNull()

    /** The day and month with a trailing dot ("1. 9."), the shape Czech ranges use. */
    private fun dayMonth(date: LocalDate): String = "${date.dayOfMonth}. ${date.monthValue}."

    private fun full(date: LocalDate): String =
        "${date.dayOfMonth}. ${date.monthValue}. ${date.year}"

    private fun full(date: ZonedDateTime): String =
        "${date.dayOfMonth}. ${date.monthValue}. ${date.year}"

    /**
     * A timestamp from the server, in Prague time — offset or `Z` both work, and a naive
     * local timestamp is read as Prague (that is how the rows are written). Null when the
     * string is not a timestamp at all.
     */
    private fun pragueDateTime(raw: String): ZonedDateTime? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(text).atZoneSameInstant(PRAGUE) }
            .recoverCatching { Instant.parse(text).atZone(PRAGUE) }
            .recoverCatching { LocalDateTime.parse(text).atZone(PRAGUE) }
            .getOrNull()
    }

    /**
     * The one-line reason worth showing, or null when there is none: only the first line
     * of [detail] survives (a stack trace's `\tat okhttp3.x` never reaches the screen),
     * control characters are dropped, and a blank result counts as no reason.
     */
    private fun reason(detail: String?): String? = detail
        ?.lineSequence()
        ?.firstOrNull()
        ?.filterNot { it.isISOControl() }
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    /** `bare.`, or `bare — reason.` when the data layer supplied a reason. */
    private fun sentence(bare: String, detail: String?): String {
        val shown = reason(detail) ?: return "$bare."
        return "$bare — $shown"
    }
}
