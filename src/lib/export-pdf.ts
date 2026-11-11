import { fetchAllExportPages, type ExportQuery } from "./export-query.ts";
import { csvFileName } from "./export-csv.ts";

export interface PdfEntry {
  date?: string | null;
  mood?: number | null;
  mood_emoji?: string | null;
  sleep_quality?: number | null;
  stress?: number | null;
  activities?: string[] | null;
  note?: string | null;
}

export interface PdfExportDependencies {
  authenticate(request: Request): Promise<{ id: string } | null>;
  makeQuery(userId: string, from: string | null, to: string | null): ExportQuery<PdfEntry>;
  today(): string;
}

const ENTRIES_PER_PAGE = 20;

function pdfString(value: string): string {
  return value
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[\r\n\t]+/g, " ")
    .replace(/[^\x20-\x7e]/g, "?")
    .replace(/\\/g, "\\\\")
    .replace(/\(/g, "\\(")
    .replace(/\)/g, "\\)");
}

function pageStream(entries: readonly PdfEntry[], generatedOn: string): string {
  let content = "BT\n/F1 12 Tf\n50 750 Td\n(Diarium Export) Tj\n0 -20 Td\n/F1 8 Tf\n";
  content += `(Generated: ${pdfString(generatedOn)}) Tj\n0 -20 Td\n`;

  for (const entry of entries) {
    const date = entry.date || "N/A";
    const mood = entry.mood ? `${entry.mood_emoji || ""} ${entry.mood}/5` : "N/A";
    const activities = entry.activities?.length ? entry.activities.join(", ") : "none";
    const sleep = entry.sleep_quality ? `Sleep: ${entry.sleep_quality}/3` : "";
    const stress = entry.stress ? `Stress: ${entry.stress}/5` : "";

    content += `(${pdfString(`${date} | ${mood} | ${sleep} ${stress} | ${activities}`)}) Tj\n0 -14 Td\n`;
    if (entry.note) {
      const note = entry.note.length > 100 ? `${entry.note.slice(0, 97)}...` : entry.note;
      content += `(${pdfString(`  ${note}`)}) Tj\n0 -12 Td\n`;
    }
  }

  return `${content}ET\n`;
}

/** Build a finite PDF with indexed page/content objects and a byte-accurate xref. */
export function buildPdf(entries: readonly PdfEntry[], generatedOn: string): string {
  const pages: PdfEntry[][] = [];
  for (let index = 0; index < entries.length; index += ENTRIES_PER_PAGE) {
    pages.push(entries.slice(index, index + ENTRIES_PER_PAGE));
  }
  if (pages.length === 0) pages.push([]);

  const objectCount = 3 + pages.length * 2;
  const objects: string[] = new Array(objectCount + 1);
  objects[1] = "<< /Type /Catalog /Pages 2 0 R >>";
  const kids = pages.map((_, index) => `${4 + index * 2} 0 R`).join(" ");
  objects[2] = `<< /Type /Pages /Kids [${kids}] /Count ${pages.length} >>`;
  objects[3] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>";

  pages.forEach((pageEntries, index) => {
    const pageObject = 4 + index * 2;
    const contentObject = pageObject + 1;
    const stream = pageStream(pageEntries, generatedOn);
    objects[pageObject] =
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] ` +
      `/Resources << /Font << /F1 3 0 R >> >> /Contents ${contentObject} 0 R >>`;
    objects[contentObject] =
      `<< /Length ${Buffer.byteLength(stream, "ascii")} >>\nstream\n${stream}endstream`;
  });

  let pdf = "%PDF-1.4\n";
  const offsets = new Array<number>(objectCount + 1).fill(0);
  for (let objectId = 1; objectId <= objectCount; objectId += 1) {
    offsets[objectId] = Buffer.byteLength(pdf, "ascii");
    pdf += `${objectId} 0 obj\n${objects[objectId]}\nendobj\n`;
  }

  const xrefOffset = Buffer.byteLength(pdf, "ascii");
  pdf += `xref\n0 ${objectCount + 1}\n0000000000 65535 f \n`;
  for (let objectId = 1; objectId <= objectCount; objectId += 1) {
    pdf += `${String(offsets[objectId]).padStart(10, "0")} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${objectCount + 1} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF\n`;
  return pdf;
}

export function createPdfExportHandler(dependencies: PdfExportDependencies) {
  return async function GET(request: Request): Promise<Response> {
    try {
      const user = await dependencies.authenticate(request);
      if (!user) return Response.json({ error: "Unauthorized" }, { status: 401 });

      const url = new URL(request.url);
      const from = url.searchParams.get("from");
      const to = url.searchParams.get("to");
      const { data: entries, error } = await fetchAllExportPages(
        () => dependencies.makeQuery(user.id, from, to)
      );
      if (error) return Response.json({ error }, { status: 500 });
      if (entries.length === 0) {
        return Response.json({ error: "No entries found", code: "no_entries" }, { status: 404 });
      }

      const today = dependencies.today();
      const pdf = buildPdf(entries, today);
      return new Response(pdf, {
        headers: {
          "Content-Type": "application/pdf",
          "Content-Disposition": `attachment; filename="${csvFileName(today).replace(/\.csv$/, ".pdf")}"`,
          "X-Diarium-Entry-Count": String(entries.length),
          "Cache-Control": "no-store",
        },
      });
    } catch {
      return Response.json({ error: "Internal error" }, { status: 500 });
    }
  };
}
