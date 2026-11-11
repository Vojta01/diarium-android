import assert from "node:assert/strict";
import test from "node:test";
import { buildCsv, buildSpreadsheetSafeCsv, csvEscape } from "../src/lib/export-csv.ts";

const dangerous = ["=1+1", "+SUM(A1:A2)", "-2+3", "@SUM(A1)", " \t=2+2", "\r+1", "\n@SUM(A1)"];

function parseCsv(text: string): string[][] {
  const records: string[][] = [[]];
  let field = "";
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (quoted) {
      if (char === '"' && text[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (char === '"') {
        quoted = false;
      } else {
        field += char;
      }
    } else if (char === '"' && field === "") {
      quoted = true;
    } else if (char === ",") {
      records.at(-1)!.push(field);
      field = "";
    } else if (char === "\r" || char === "\n") {
      records.at(-1)!.push(field);
      field = "";
      if (char === "\r" && text[index + 1] === "\n") index += 1;
      records.push([]);
    } else {
      field += char;
    }
  }
  records.at(-1)!.push(field);
  return records;
}

test("faithful CSV quotes carriage returns and preserves formula-like text exactly", () => {
  assert.equal(csvEscape("before\r\nafter"), '"before\r\nafter"');
  assert.equal(csvEscape('quote " and \r'), '"quote "" and \r"');

  const csv = buildCsv([{
    date: "2026-01-01",
    note: `faithful ${dangerous.join(" | ")}\r\nquoted, "text"`,
  }]);
  assert.ok(csv.includes(`faithful ${dangerous.join(" | ")}\r\nquoted, ""text""`));
});

test("spreadsheet-safe CSV neutralizes formula prefixes after whitespace and controls", () => {
  const csv = buildSpreadsheetSafeCsv(dangerous.map((note, index) => ({
    date: `2026-01-${String(index + 1).padStart(2, "0")}`,
    note,
  })));
  const rows = parseCsv(csv).slice(1);
  for (let index = 0; index < dangerous.length; index += 1) {
    assert.equal(rows[index][8], `'${dangerous[index]}`);
  }
});

test("spreadsheet-safe CSV still applies normal CSV quoting without losing record boundaries", () => {
  const value = "=SUM(1,2)\r\nnext, \"quoted\"";
  const csv = buildSpreadsheetSafeCsv([{ date: "2026-01-01", note: value }]);
  const expected = `"'${value.replace(/"/g, '""')}"`;
  assert.ok(csv.includes(`,${expected},`));
  assert.equal(parseCsv(csv).length, 2, "embedded quoted newline stays inside one CSV record");
  assert.equal(parseCsv(csv)[1][8], `'${value}`);
});
