package cz.digitalnivedomi.diarium.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export contract, pinned byte for byte.
 *
 * The reference is the web endpoint (`/root/diarium/src/app/api/export/csv/route.ts`):
 * header `date,mood,mood_emoji,sleep_quality,stress,activities,habits,gratitude,note,scale_values`,
 * rows joined with a bare LF, `null`/`undefined` → empty field, object columns written as
 * `JSON.stringify(val)` **always** wrapped in quotes, other fields quoted only when they
 * contain a comma, a quote or a newline. Every expectation below is written out by hand
 * rather than re-derived from the implementation, so a change in behaviour fails the test.
 *
 * These are plain JVM tests: [CsvExport] touches no Android class, which is the point of
 * keeping the file format in `core/export`.
 */
class CsvExportTest {

    @Test
    fun `the header is the web's ten columns in the web's order`() {
        assertEquals(
            listOf(
                "date",
                "mood",
                "mood_emoji",
                "sleep_quality",
                "stress",
                "activities",
                "habits",
                "gratitude",
                "note",
                "scale_values",
            ),
            CsvExport.HEADERS,
        )
        assertEquals(
            "date,mood,mood_emoji,sleep_quality,stress,activities,habits,gratitude,note,scale_values",
            CsvExport.headerLine(),
        )
    }

    @Test
    fun `a filled row lines its values up under the header names`() {
        val entry = ExportEntry(
            date = "2026-09-12",
            mood = 4,
            moodEmoji = "🙂",
            sleepQuality = 3,
            stress = 2,
            activities = listOf<Any?>("práce", "sport"),
            habits = linkedMapOf<String, Any?>("reading" to true, "run" to false),
            gratitude = listOf<Any?>("káva", "rodina"),
            note = "Dobrý den, klid",
            scaleValues = linkedMapOf<String, Any?>("energy" to 4, "focus" to 3),
        )

        val expected = listOf(
            "2026-09-12", // date — ISO, never quoted
            "4", // mood — plain number
            "🙂", // mood_emoji — no comma/quote/newline, so not quoted
            "3", // sleep_quality
            "2", // stress
            "\"[\"\"práce\"\",\"\"sport\"\"]\"", // activities — JSON, always quoted, quotes doubled
            "\"{\"\"reading\"\":true,\"\"run\"\":false}\"", // habits — JSON, insertion order
            "\"[\"\"káva\"\",\"\"rodina\"\"]\"", // gratitude — JSON
            "\"Dobrý den, klid\"", // note — comma forces quotes, contents untouched
            "\"{\"\"energy\"\":4,\"\"focus\"\":3}\"", // scale_values — JSON
        )

        assertEquals(expected.joinToString(","), CsvExport.row(entry))
        // Ten fields, no more and no fewer — parsed back out of the finished line.
        assertEquals(10, splitCsv(CsvExport.row(entry)).size)
    }

    @Test
    fun `a row of nulls writes the date and nine empty fields`() {
        // The web's `val ? ... : ""` for every nullable column.
        assertEquals("2026-09-12,,,,,,,,,", CsvExport.row(ExportEntry(date = "2026-09-12")))
    }

    @Test
    fun `a missing column and an explicit null write the same empty field`() {
        val bare = ExportEntry(date = "2026-01-01")
        val explicit = ExportEntry(
            date = "2026-01-01",
            mood = null,
            moodEmoji = null,
            sleepQuality = null,
            stress = null,
            activities = null,
            habits = null,
            gratitude = null,
            note = null,
            scaleValues = null,
        )

        assertEquals(CsvExport.row(explicit), CsvExport.row(bare))
    }

    @Test
    fun `an empty array and an empty object are JSON, not empty fields`() {
        // `[]`/`{}` are falsy in JS but not nullish, so the endpoint writes them as JSON —
        // this is the value the app must not mistake for "no data".
        assertEquals("\"[]\"", CsvExport.jsonField(emptyList<Any?>()))
        assertEquals("\"{}\"", CsvExport.jsonField(emptyMap<String, Any?>()))
        assertEquals("", CsvExport.jsonField(null))
    }

    @Test
    fun `a comma, a quote or a newline each force quotes on a text field`() {
        assertEquals("plain", CsvExport.escape("plain"))
        assertEquals("\"a,b\"", CsvExport.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.escape("say \"hi\""))
        assertEquals("\"two\nlines\"", CsvExport.escape("two\nlines"))
        // Diacritics are not a reason to quote: UTF-8 comes out as-is.
        assertEquals("Příliš žluťoučký kůň", CsvExport.escape("Příliš žluťoučký kůň"))
    }

    @Test
    fun `a bare carriage return is not quoted, exactly like the web`() {
        // Deliberate parity wart: the endpoint tests `,`, `"` and `\n` only, so a lone CR
        // (an old-Mac line ending) leaves the field unquoted. Kept because the requirement
        // is to mirror the web output, and documented in CsvExport's KDoc.
        assertEquals("a\rb", CsvExport.escape("a\rb"))
        // CRLF does contain \n, so that one *is* quoted and the CR survives inside.
        assertEquals("\"a\r\nb\"", CsvExport.escape("a\r\nb"))
    }

    @Test
    fun `dates pass through exactly as the database returned them`() {
        assertEquals("2026-09-12", CsvExport.textField("2026-09-12"))
        assertEquals("", CsvExport.textField(null))
        assertTrue(CsvExport.row(ExportEntry(date = "2026-09-12")).startsWith("2026-09-12,"))
    }

    @Test
    fun `JSON strings are escaped like JavaScript, not like Kotlin`() {
        assertEquals("\"a\\\"b\"", CsvExport.jsonString("a\"b"))
        assertEquals("\"a\\\\b\"", CsvExport.jsonString("a\\b"))
        assertEquals("\"a\\nb\"", CsvExport.jsonString("a\nb"))
        assertEquals("\"a\\rb\"", CsvExport.jsonString("a\rb"))
        assertEquals("\"a\\tb\"", CsvExport.jsonString("a\tb"))
        assertEquals("\"\\b\"", CsvExport.jsonString("\b"))
        assertEquals("\"\\f\"", CsvExport.jsonString("\u000C"))
        assertEquals("\"\\u0001\"", CsvExport.jsonString("\u0001"))
        // JSON.stringify does not escape forward slashes…
        assertEquals("\"a/b\"", CsvExport.jsonString("a/b"))
        // …and leaves every non-ASCII character alone.
        assertEquals("\"Příliš žluťoučký kůň\"", CsvExport.jsonString("Příliš žluťoučký kůň"))
    }

    @Test
    fun `JSON numbers and booleans follow JavaScript, not Java`() {
        assertEquals("4", CsvExport.encodeJson(4))
        assertEquals("true", CsvExport.encodeJson(true))
        assertEquals("false", CsvExport.encodeJson(false))
        assertEquals("null", CsvExport.encodeJson(null))
        // Kotlin prints 4.0 as "4.0"; JSON.stringify(4.0) is "4".
        assertEquals("4", CsvExport.encodeJson(4.0))
        assertEquals("4.5", CsvExport.encodeJson(4.5))
    }

    @Test
    fun `nested JSON keeps its shape and a map keeps its order`() {
        assertEquals(
            "[\"a\",1,true,null]",
            CsvExport.encodeJson(listOf<Any?>("a", 1, true, null)),
        )
        assertEquals(
            "[{\"a\":1}]",
            CsvExport.encodeJson(listOf<Any?>(linkedMapOf<String, Any?>("a" to 1))),
        )
        assertEquals(
            "{\"b\":1,\"a\":2,\"nested\":{\"x\":false}}",
            CsvExport.encodeJson(
                linkedMapOf<String, Any?>(
                    "b" to 1,
                    "a" to 2,
                    "nested" to linkedMapOf<String, Any?>("x" to false),
                ),
            ),
        )
    }

    @Test
    fun `a null slot inside a JSON array survives as JSON null`() {
        // The row holds `["a", null, "b"]`; the file must say exactly that.
        assertEquals(
            "\"[\"\"a\"\",null,\"\"b\"\"]\"",
            CsvExport.jsonField(listOf<Any?>("a", null, "b")),
        )
    }

    @Test
    fun `rows are joined with LF and the document has no trailing newline or BOM`() {
        val first = ExportEntry(date = "2026-09-11", mood = 3)
        val second = ExportEntry(date = "2026-09-12", mood = 5, note = "poznámka")

        val csv = CsvExport.build(listOf(first, second))

        assertEquals(
            listOf(
                CsvExport.headerLine(),
                CsvExport.row(first),
                CsvExport.row(second),
            ).joinToString("\n"),
            csv,
        )
        assertFalse("no trailing newline", csv.endsWith("\n"))
        assertFalse("LF only, never CRLF", csv.contains("\r\n"))
        assertFalse("no UTF-8 BOM", csv.startsWith("\uFEFF"))
        assertEquals(2, csv.count { it == '\n' })
    }

    @Test
    fun `an empty account still produces the header line`() {
        // The web's `csvRows.join("\n")` with no rows is just the header.
        assertEquals(CsvExport.headerLine(), CsvExport.build(emptyList()))
    }

    @Test
    fun `a pathological row still has exactly ten fields`() {
        val entry = ExportEntry(
            date = "2026-01-05",
            mood = 1,
            moodEmoji = "\"quoted\"",
            sleepQuality = 5,
            stress = 5,
            activities = listOf<Any?>("a,b", "c\"d", "e\nf"),
            habits = linkedMapOf<String, Any?>("a,b" to true),
            gratitude = listOf<Any?>(),
            note = "comma, \"quote\" and\nnewline",
            scaleValues = emptyMap<String, Any?>(),
        )

        val fields = splitCsv(CsvExport.row(entry))

        assertEquals(10, fields.size)
        assertEquals("2026-01-05", fields[0])
        assertEquals("\"quoted\"", fields[2])
        // The JSON text, recovered from the CSV layer: `\"` and `\n` are still JSON escapes.
        assertEquals("[\"a,b\",\"c\\\"d\",\"e\\nf\"]", fields[5])
        assertEquals("[]", fields[7])
        assertEquals("{}", fields[9])
    }

    /**
     * RFC 4180 reader, used only to count columns: `""` inside a quoted field is one
     * quote, commas inside quotes are data, and the quotes themselves are dropped.
     */
    private fun splitCsv(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                inQuotes && ch == '"' && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            index++
        }
        fields.add(current.toString())
        return fields
    }
}
