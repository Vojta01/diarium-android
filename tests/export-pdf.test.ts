import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { createPdfExportHandler, type PdfEntry } from "../src/lib/export-pdf.ts";

function parseAndCheckPdfXref(pdf: string): number {
  assert.ok(pdf.startsWith("%PDF-1.4\n"));
  assert.ok(pdf.endsWith("%%EOF\n"));
  const start = Number(pdf.match(/startxref\n(\d+)\n%%EOF/)?.[1]);
  assert.ok(Number.isInteger(start));
  assert.equal(pdf.slice(start, start + 5), "xref\n");
  const lines = pdf.slice(start).split("\n");
  const [, range] = lines;
  const objectCount = Number(range.split(" ")[1]);
  assert.ok(objectCount > 1);
  const xrefEntries = lines.slice(3, 3 + objectCount - 1);
  assert.equal(xrefEntries.length, objectCount - 1);
  for (let objectId = 1; objectId < objectCount; objectId += 1) {
    const offset = Number(xrefEntries[objectId - 1].slice(0, 10));
    assert.equal(pdf.slice(offset).startsWith(`${objectId} 0 obj\n`), true);
  }
  return objectCount;
}

test("authenticated PDF export paginates every row into a finite valid PDF", async () => {
  const entries: PdfEntry[] = Array.from({ length: 1001 }, (_, index) => ({
    date: `2026-01-${String((index % 28) + 1).padStart(2, "0")}`,
    mood: 4,
    mood_emoji: "🙂",
    activities: ["synthetic activity"],
    note: `synthetic note ${index}`,
  }));
  const ranges: Array<[number, number]> = [];
  const scopedUsers: string[] = [];
  const handler = createPdfExportHandler({
    authenticate: async (request) => request.headers.get("authorization") === "Bearer owner-token"
      ? { id: "owner-id" }
      : null,
    makeQuery: (userId, from, to) => {
      scopedUsers.push(`${userId}:${from}:${to}`);
      return {
        range: async (start, end) => {
          ranges.push([start, end]);
          return { data: entries.slice(start, end + 1), error: null };
        },
      };
    },
    today: () => "2026-11-08",
  });

  const response = await handler(new Request(
    "https://diarium.test/api/export/pdf?user_id=attacker-id&from=2026-01-01&to=2026-01-31",
    { headers: { authorization: "Bearer owner-token" } }
  ));
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-type"), "application/pdf");
  assert.equal(response.headers.get("x-diarium-entry-count"), "1001");
  assert.deepEqual(ranges, [[0, 999], [1000, 1999]]);
  assert.ok(scopedUsers.length === 2 && scopedUsers.every((value) =>
    value === "owner-id:2026-01-01:2026-01-31"));

  const pdf = Buffer.from(await response.arrayBuffer()).toString("ascii");
  const objectCount = parseAndCheckPdfXref(pdf);
  assert.equal((pdf.match(/\/Type \/Page\b/g) ?? []).length, 51);
  assert.match(pdf, /\/Count 51\b/);
  assert.match(pdf, /synthetic note 1000/);
  assert.ok(objectCount > 100, "all page/content objects were serialized");
});

test("PDF export requires the authenticated owner and ignores a supplied user_id", async () => {
  let queryCalls = 0;
  const handler = createPdfExportHandler({
    authenticate: async () => null,
    makeQuery: () => {
      queryCalls += 1;
      return { range: async () => ({ data: [], error: null }) };
    },
    today: () => "2026-11-08",
  });
  const response = await handler(new Request("https://diarium.test/api/export/pdf?user_id=other"));
  assert.equal(response.status, 401);
  assert.equal(queryCalls, 0);

  const route = readFileSync(new URL("../src/app/api/export/pdf/route.ts", import.meta.url), "utf8");
  assert.match(route, /authenticate:\s*verifyAuth/);
  assert.match(route, /\.eq\("user_id", userId\)/);
  assert.match(route, /\.gte\("date", from\)/);
  assert.match(route, /\.lte\("date", to\)/);
});
