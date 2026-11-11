/**
 * CSV writer shared by the export route and its tests.
 *
 * Faithful backup format: same header order, bare LF, no trailing newline, no
 * UTF-8 BOM, `null` → empty field, object columns always quoted compact JSON,
 * and text quoted for comma, quote, CR or LF. Quoting CR is stricter than the
 * former Android twin because leaving it bare can break CSV record boundaries.
 *
 * No BOM on purpose: keep the established Diarium CSV dialect; formula-safe
 * cell transformation is a separate, explicit export mode.
 */

export const CSV_HEADERS = [
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
] as const;

export type CsvHeader = (typeof CSV_HEADERS)[number];

/** Columns whose database value is a JSON array/object. */
export const CSV_JSON_COLUMNS: ReadonlySet<string> = new Set([
  "activities",
  "habits",
  "gratitude",
  "scale_values",
]);

export const CSV_LINE_SEPARATOR = "\n";

/** Quote and double inner quotes. */
export function quoteCsv(text: string): string {
  return `"${text.replace(/"/g, '""')}"`;
}

/**
 * Plain text field: empty for `null`/`undefined`, quoted only when it holds a
 * comma, a double quote, carriage return or newline (faithful backup).
 */
export function csvEscape(value: unknown): string {
  if (value === null || value === undefined) return "";
  const text = String(value);
  return text.includes(",") || text.includes('"') || text.includes("\n") || text.includes("\r")
    ? quoteCsv(text)
    : text;
}

/**
 * JSON field. `null`/`undefined` → empty; everything else is compact
 * `JSON.stringify` output, **always** quoted, with inner quotes doubled.
 */
export function csvJsonField(value: unknown): string {
  if (value === null || value === undefined) return "";
  return quoteCsv(JSON.stringify(value));
}

/** One column, using the rule for that header. */
export function csvField(header: string, value: unknown): string {
  return CSV_JSON_COLUMNS.has(header) ? csvJsonField(value) : csvEscape(value);
}

/** One data line, built by walking the header list so order cannot drift. */
export function csvRow(entry: Record<string, unknown>): string {
  return CSV_HEADERS.map((header) => csvField(header, entry[header])).join(",");
}

/** The whole document: header line, then one line per entry. */
export function buildCsv(entries: readonly Record<string, unknown>[]): string {
  return [CSV_HEADERS.join(","), ...entries.map(csvRow)].join(CSV_LINE_SEPARATOR);
}

/**
 * Spreadsheet-compatible export is deliberately opt-in: prefix only strings
 * whose first non-whitespace/control character is an Excel formula marker.
 * The faithful `buildCsv` remains byte-preserving for backup/re-import use.
 */
function spreadsheetSafeText(value: unknown): unknown {
  if (typeof value !== "string") return value;
  return /^[\s\u0000-\u001f\u007f-\u009f]*[=+\-@]/u.test(value)
    ? `'${value}`
    : value;
}

function spreadsheetSafeRow(entry: Record<string, unknown>): string {
  return CSV_HEADERS.map((header) => {
    const value = entry[header];
    if (CSV_JSON_COLUMNS.has(header)) return csvJsonField(value);
    return csvEscape(spreadsheetSafeText(value));
  }).join(",");
}

export function buildSpreadsheetSafeCsv(
  entries: readonly Record<string, unknown>[]
): string {
  return [CSV_HEADERS.join(","), ...entries.map(spreadsheetSafeRow)].join(CSV_LINE_SEPARATOR);
}

/**
 * Android `ExportState.fileName` shape: `diarium-export-YYYY-MM-DD.csv`.
 * Takes the already-local ISO day from the caller — never a UTC timestamp.
 */
export function csvFileName(todayISO: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(todayISO)) {
    throw new Error("csvFileName requires a YYYY-MM-DD date");
  }
  return `diarium-export-${todayISO}.csv`;
}
