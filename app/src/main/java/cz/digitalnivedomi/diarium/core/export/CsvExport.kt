package cz.digitalnivedomi.diarium.core.export

/**
 * One `entries` row flattened onto exactly the columns the web export writes.
 *
 * Deliberately *not* [cz.digitalnivedomi.diarium.core.data.DiaryEntry]: the web
 * endpoint exports the raw database columns
 * (`src/app/api/export/csv/route.ts` → `supabase.from("entries").select("date, mood,
 * mood_emoji, sleep_quality, stress, activities, habits, gratitude, note, scale_values")`),
 * while the form model pads `gratitude` to three slots and normalises everything
 * else for the UI. Exporting the form model would silently rewrite the user's file
 * (an empty gratitude slot would become a `""` entry inside the JSON array), so the
 * export reads the row as it is.
 *
 * Every field is nullable because PostgREST returns JSON `null` for an empty
 * column, and the web maps `null`/`undefined` to an *empty* CSV field — which is a
 * different thing from an empty array (`[]`, written as the quoted `"[]"`).
 */
data class ExportEntry(
    /** `entries.date` — the ISO `YYYY-MM-DD` string the database stores, passed through verbatim. */
    val date: String = "",
    val mood: Int? = null,
    val moodEmoji: String? = null,
    val sleepQuality: Int? = null,
    val stress: Int? = null,
    /**
     * `activities`/`habits`/`gratitude`/`scale_values` hold the column **as the row
     * holds it**: JSON arrays of raw values and JSON objects of raw values, in the
     * order PostgREST returned them. Keeping the wire shape (rather than a
     * `List<String>`/`Map<String, Int>`) is what makes the export byte-identical to
     * the web's `JSON.stringify(val)` — including a `null` inside an array, a number
     * where a string was expected, or a nested value.
     */
    val activities: List<Any?>? = null,
    val habits: Map<String, Any?>? = null,
    val gratitude: List<Any?>? = null,
    val note: String? = null,
    val scaleValues: Map<String, Any?>? = null,
)

/**
 * The client-side twin of the web's `/api/export/csv` CSV writer.
 *
 * Pure Kotlin (no Android, no `org.json`, no network) so the byte-level format —
 * header order, escaping, date pass-through — is unit-testable on the JVM and
 * cannot drift from the web endpoint without a test failing. The rules, taken
 * line by line from the route handler:
 *
 *  - column order and header names: `date, mood, mood_emoji, sleep_quality, stress,
 *    activities, habits, gratitude, note, scale_values`;
 *  - rows are joined with a bare LF and there is **no** trailing newline and **no**
 *    UTF-8 BOM (the web returns the string as-is);
 *  - `null`/missing → empty field, for every column;
 *  - object columns (`activities`, `habits`, `gratitude`, `scale_values`) are
 *    serialised as compact JSON (`JSON.stringify` semantics) and are **always**
 *    wrapped in double quotes, even when the JSON carries no comma or quote;
 *  - plain text fields are quoted only when they contain a comma, a double quote
 *    or a newline, with inner double quotes doubled;
 *  - `date` is the raw ISO date the database returned: no reformatting, no locale,
 *    no timezone (`DATE` arrives as `2026-09-12`).
 *
 * One deliberate fidelity wart, kept because the requirement is to mirror the web
 * exactly: a field containing a lone carriage return (`\r`) but no `\n`, comma or
 * quote is written **unquoted**, because the web endpoint only tests for `,`, `"`
 * and `\n`.
 */
object CsvExport {

    /** Header names, in the web's order. */
    val HEADERS: List<String> = listOf(
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
    )

    /** Row separator — bare LF, exactly what `csvRows.join("\n")` produces. */
    const val LINE_SEPARATOR = "\n"

    /** The web's `Content-Type`; the SAF picker needs a concrete MIME type. */
    const val MIME_TYPE = "text/csv"

    /** Full document: header line, then one line per entry. */
    fun build(entries: List<ExportEntry>): String =
        (listOf(headerLine()) + entries.map { row(it) }).joinToString(LINE_SEPARATOR)

    /** The header line on its own (`date,mood,…`) — also the whole document when there are no entries. */
    fun headerLine(): String = HEADERS.joinToString(",")

    /**
     * One data line. The fields are produced *by walking [HEADERS]*, so a header
     * reorder can never produce a line whose columns sit under the wrong names.
     */
    fun row(entry: ExportEntry): String = HEADERS.joinToString(",") { header -> field(entry, header) }

    /** The value for one column, using the web's per-column rule. */
    private fun field(entry: ExportEntry, header: String): String = when (header) {
        "date" -> textField(entry.date)
        "mood" -> numberField(entry.mood)
        "mood_emoji" -> textField(entry.moodEmoji)
        "sleep_quality" -> numberField(entry.sleepQuality)
        "stress" -> numberField(entry.stress)
        "activities" -> jsonField(entry.activities)
        "habits" -> jsonField(entry.habits)
        "gratitude" -> jsonField(entry.gratitude)
        "note" -> textField(entry.note)
        "scale_values" -> jsonField(entry.scaleValues)
        else -> ""
    }

    /** `null` → empty field; otherwise the string escaped exactly like the web. */
    fun textField(value: String?): String = value?.let(::escape) ?: ""

    /** `null` → empty field; otherwise the plain number (`5`). */
    fun numberField(value: Int?): String = value?.toString() ?: ""

    /** `null` → empty field; otherwise compact JSON, wrapped in quotes with `"` doubled. */
    fun jsonField(value: Any?): String = when (value) {
        null -> ""
        else -> quote(encodeJson(value))
    }

    /**
     * Wraps [text] in double quotes and doubles any inner quote — the web's
     * `` `"${JSON.stringify(val).replace(/"/g, '""')}"` `` (always quoted).
     */
    fun quote(text: String): String = "\"" + text.replace("\"", "\"\"") + "\""

    /**
     * Quotes [value] only when it contains a comma, a double quote or a newline —
     * the web's `str.includes(",") || str.includes('"') || str.includes("\n")`.
     */
    fun escape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n')) quote(value) else value

    /**
     * Compact JSON with `JSON.stringify` semantics: no spaces, no forward-slash
     * escaping, `\n`/`\r`/`\t`/`\b`/`\f` as short escapes, other control characters
     * as `\uXXXX`, unpaired surrogates escaped (well-formed JSON.stringify), and
     * everything else — including Czech diacritics — passed through as-is.
     *
     * Maps keep their iteration order, which for the Android `org.json` parse of a
     * PostgREST response is the order the server sent.
     */
    fun encodeJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> jsonString(value)
        is Boolean -> value.toString()
        is Int, is Long, is Short, is Byte -> value.toString()
        is Double -> if (value.isFinite() && value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
        is Number -> value.toString()
        is List<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { encodeJson(it) }
        is Map<*, *> -> value.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, item) ->
            jsonString(key?.toString() ?: "null") + ":" + encodeJson(item)
        }
        else -> jsonString(value.toString())
    }

    /** A JSON string literal, escaped like JavaScript's `JSON.stringify`. */
    fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2).append('"')
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '\b' -> out.append("\\b")
                // U+000C FORM FEED — the one short escape left.
                ch == '\u000C' -> out.append("\\f")
                ch < ' ' -> out.append("\\u").append(String.format("%04x", ch.code))
                ch.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                    // A valid pair is one character: keep it verbatim.
                    out.append(ch).append(value[index + 1])
                    index++
                }
                ch.isSurrogate() -> out.append("\\u").append(String.format("%04x", ch.code))
                else -> out.append(ch)
            }
            index++
        }
        return out.append('"').toString()
    }
}
