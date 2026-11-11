import assert from "node:assert/strict";
import test from "node:test";

import {
  CSV_HEADERS,
  CSV_LINE_SEPARATOR,
  buildCsv,
  csvEscape,
  csvFileName,
  csvJsonField,
  csvRow,
} from "../src/lib/export-csv.ts";
import { fetchAllExportPages } from "../src/lib/export-query.ts";

test("CSV header matches Android CsvExport exactly", () => {
  assert.deepEqual(
    [...CSV_HEADERS],
    [
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
    ]
  );
  assert.equal(
    CSV_HEADERS.join(","),
    "date,mood,mood_emoji,sleep_quality,stress,activities,habits,gratitude,note,scale_values"
  );
});

test("plain text is quoted for comma, quote, carriage return or newline", () => {
  assert.equal(csvEscape("plain"), "plain");
  assert.equal(csvEscape("a,b"), '"a,b"');
  assert.equal(csvEscape('say "hi"'), '"say ""hi"""');
  assert.equal(csvEscape("line\nbreak"), '"line\nbreak"');
  assert.equal(csvEscape("carriage\rreturn"), '"carriage\rreturn"');
  assert.equal(csvEscape(null), "");
  assert.equal(csvEscape(undefined), "");
  assert.equal(csvEscape(0), "0");
});

test("object columns are always quoted compact JSON", () => {
  assert.equal(csvJsonField(["run", "read"]), '"[""run"",""read""]"');
  assert.equal(csvJsonField({ mood: 4 }), '"{""mood"":4}"');
  assert.equal(csvJsonField([]), '"[]"');
  assert.equal(csvJsonField(null), "");
  assert.equal(csvJsonField(undefined), "");
});

test("a full row keeps Android column order and null handling", () => {
  const row = csvRow({
    date: "2026-11-06",
    mood: 4,
    mood_emoji: "🙂",
    sleep_quality: null,
    stress: 2,
    activities: ["run"],
    habits: { walk: true },
    gratitude: ["family"],
    note: "hello, world",
    scale_values: { energy: 7 },
  });

  assert.equal(
    row,
    '2026-11-06,4,🙂,,2,"[""run""]","{""walk"":true}","[""family""]","hello, world","{""energy"":7}"'
  );
});

test("the document is LF-only, BOM-free and has no trailing newline", () => {
  const csv = buildCsv([
    { date: "2026-11-05", mood: 3 },
    { date: "2026-11-06", mood: 5, note: "two\nlines" },
  ]);

  assert.equal(csv.charCodeAt(0), "d".charCodeAt(0));
  assert.ok(!csv.includes("\r\n"));
  assert.ok(!csv.endsWith("\n"));
  assert.ok(csv.includes(CSV_LINE_SEPARATOR));
  // Two rows, one of which contains a literal newline: four physical lines.
  assert.equal(csv.split("\n").length, 4);
});

test("export filename uses the Android local-day shape", () => {
  assert.equal(csvFileName("2026-11-06"), "diarium-export-2026-11-06.csv");
  assert.throws(() => csvFileName("2026-11-06T10:00:00Z"));
});

test("10,001 entries are paged without truncation", async () => {
  const all = Array.from({ length: 10_001 }, (_, index) => ({
    date: "2026-11-06",
    n: index,
  }));
  let calls = 0;

  const result = await fetchAllExportPages(() => ({
    range: (from: number, to: number) => {
      calls += 1;
      return Promise.resolve({
        data: all.slice(from, to + 1),
        error: null,
      });
    },
  }));

  assert.equal(result.error, null);
  assert.equal(result.data.length, 10_001);
  assert.equal(result.data[10_000]?.n, 10_000);
  assert.equal(calls, 11);
});

test("a failed page is reported, not silently dropped", async () => {
  const result = await fetchAllExportPages(() => ({
    range: () => Promise.resolve({ data: null, error: { message: "boom" } }),
  }));

  assert.equal(result.data.length, 0);
  assert.equal(result.error, "boom");
});
