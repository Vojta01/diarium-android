import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const dialog = readFileSync(
  new URL("../src/components/ExportDialog.tsx", import.meta.url),
  "utf8"
);

test("date inputs have programmatic labels", () => {
  assert.match(dialog, /htmlFor=\{fromId\}/);
  assert.match(dialog, /htmlFor=\{toId\}/);
  assert.match(dialog, /id=\{fromId\}/);
  assert.match(dialog, /id=\{toId\}/);
});

test("format choice is a labelled group with pressed state", () => {
  assert.match(dialog, /role="group"/);
  assert.match(dialog, /aria-labelledby=\{`\$\{titleId\}-format`\}/);
  assert.match(dialog, /aria-pressed=\{format === fmt\}/);
  assert.match(dialog, /t\("export\.pdf_web_only"\)/);
});

test("CSV mode explicitly distinguishes the faithful backup from spreadsheet-safe output", () => {
  assert.match(dialog, /value="faithful"/);
  assert.match(dialog, /value="spreadsheet-safe"/);
  assert.match(dialog, /params\.set\("mode", "spreadsheet-safe"\)/);
  assert.match(dialog, /csv_safe_hint/);
  assert.match(dialog, /csv_hint/);
});

test("states are announced and never surface a raw server message", () => {
  assert.match(dialog, /role="alert"/);
  assert.match(dialog, /role="status"/);
  assert.match(dialog, /setPhase\("empty"\)/);
  assert.match(dialog, /setPhase\("error"\)/);
  assert.match(dialog, /t\("export\.empty_hint"\)/);
  assert.doesNotMatch(dialog, /e: any/);
  assert.doesNotMatch(dialog, /err\.error/);
});

test("range is validated client-side and the button reports busy state", () => {
  assert.match(dialog, /from > to/);
  assert.match(dialog, /aria-invalid=\{rangeInvalid/);
  assert.match(dialog, /aria-busy=\{loading\}/);
  assert.match(dialog, /disabled=\{loading \|\| rangeInvalid\}/);
});

test("dialog has a visible 44 px close control", () => {
  assert.match(dialog, /aria-label=\{t\("common\.close"\)\}/);
  assert.match(dialog, /min-h-11 min-w-11/);
  assert.match(dialog, /type="button"/);
});

test("download uses the Android filename, not a timestamp", () => {
  assert.match(dialog, /csvFileName\(pragueDateKey\(\)\)/);
  assert.doesNotMatch(dialog, /diarium-export-\$\{Date\.now\(\)\}/);
  assert.doesNotMatch(dialog, /URL\.revokeObjectURL\(url\);/);
  assert.match(dialog, /document\.body\.appendChild\(anchor\)/);
});

test("export routes page through all entries instead of a 10k cap", () => {
  const csv = readFileSync(
    new URL("../src/app/api/export/csv/route.ts", import.meta.url),
    "utf8"
  );
  const pdf = readFileSync(
    new URL("../src/lib/export-pdf.ts", import.meta.url),
    "utf8"
  );
  const pdfRoute = readFileSync(
    new URL("../src/app/api/export/pdf/route.ts", import.meta.url),
    "utf8"
  );

  for (const source of [csv, pdf]) {
    assert.match(source, /fetchAllExportPages/);
    assert.doesNotMatch(source, /\.limit\(10000\)/);
    assert.match(source, /X-Diarium-Entry-Count/);
  }
  assert.match(csv, /url\.searchParams\.get\("mode"\) === "spreadsheet-safe"/);
  assert.match(csv, /"X-Diarium-CSV-Mode": mode/);
  assert.match(pdfRoute, /createPdfExportHandler/);
  assert.match(csv, /buildCsv/);
  assert.match(csv, /buildSpreadsheetSafeCsv/);
  assert.match(csv, /csvFileName/);
});
