import { NextRequest, NextResponse } from "next/server";
import { createClient } from "@supabase/supabase-js";
import { verifyAuth } from "@/lib/auth";
import {
  buildCsv,
  buildSpreadsheetSafeCsv,
  CSV_HEADERS,
  csvFileName,
} from "@/lib/export-csv";
import { fetchAllExportPages } from "@/lib/export-query";
import { pragueDateKey } from "@/lib/date";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || "";

export async function GET(request: NextRequest) {
  try {
    const url = new URL(request.url);
    // SECURITY: this route reads through the service_role key, so the user MUST come
    // from a verified JWT. It previously took `user_id` from the query string and never
    // checked the Authorization header, letting any caller export any user's diary.
    const user = await verifyAuth(request);
    if (!user) {
      return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }
    const userId = user.id;
    const from = url.searchParams.get("from");
    const to = url.searchParams.get("to");

    const supabase = createClient(SUPABASE_URL, SERVICE_KEY);

    // PostgREST answers with at most one page. Android reads until the account
    // is exhausted, so page through instead of truncating at an arbitrary cap.
    const makeQuery = () => {
      let query = supabase
        .from("entries")
        .select(CSV_HEADERS.join(", "))
        .eq("user_id", userId)
        .order("date", { ascending: true });

      if (from) query = query.gte("date", from);
      if (to) query = query.lte("date", to);
      return query;
    };

    const { data: entries, error } = await fetchAllExportPages(makeQuery);

    if (error) {
      return NextResponse.json({ error }, { status: 500 });
    }

    if (entries.length === 0) {
      return NextResponse.json(
        { error: "No entries found", code: "no_entries" },
        { status: 404 }
      );
    }

    const mode = url.searchParams.get("mode") === "spreadsheet-safe"
      ? "spreadsheet-safe"
      : "faithful";
    const rows = entries as unknown as Record<string, unknown>[];
    const csv = mode === "spreadsheet-safe"
      ? buildSpreadsheetSafeCsv(rows)
      : buildCsv(rows);

    return new NextResponse(csv, {
      headers: {
        "Content-Type": "text/csv; charset=utf-8",
        "Content-Disposition": `attachment; filename="${csvFileName(
          pragueDateKey()
        )}"`,
        // The dialog reads this so its success state can name the real row count.
        "X-Diarium-Entry-Count": String(entries.length),
        "X-Diarium-CSV-Mode": mode,
        "Cache-Control": "no-store",
      },
    });
  } catch (err) {
    const message = err instanceof Error ? err.message : "Internal error";
    return NextResponse.json({ error: message }, { status: 500 });
  }
}
