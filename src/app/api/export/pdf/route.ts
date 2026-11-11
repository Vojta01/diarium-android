import { createClient } from "@supabase/supabase-js";
import { verifyAuth } from "@/lib/auth";
import { pragueDateKey } from "@/lib/date";
import { createPdfExportHandler } from "@/lib/export-pdf";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || "";

export const GET = createPdfExportHandler({
  authenticate: verifyAuth,
  makeQuery: (userId, from, to) => {
    const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
    let query = supabase
      .from("entries")
      .select("date, mood, mood_emoji, sleep_quality, stress, activities, note")
      .eq("user_id", userId)
      .order("date", { ascending: true });

    if (from) query = query.gte("date", from);
    if (to) query = query.lte("date", to);
    return query;
  },
  today: pragueDateKey,
});
