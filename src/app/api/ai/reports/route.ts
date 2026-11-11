import { NextRequest } from "next/server";
import { createClient } from "@supabase/supabase-js";
import { verifyAuth } from "@/lib/auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || '';

export async function GET(request: NextRequest) {
  // Authenticate the request
  const user = await verifyAuth(request);
  if (!user) {
    return Response.json({ error: "Unauthorized" }, { status: 401 });
  }

  const url = new URL(request.url);
  const type = url.searchParams.get("type") || "weekly";
  const userId = url.searchParams.get("user_id");

  if (!userId) {
    return Response.json({ error: "user_id required" }, { status: 400 });
  }

  // Enforce that users can only access their own reports
  if (user.id !== userId) {
    return Response.json({ error: "Forbidden" }, { status: 403 });
  }

  const supabase = createClient(SUPABASE_URL, SERVICE_KEY);

  const { data, error } = await supabase
    .from("ai_reports")
    .select("*")
    .eq("user_id", userId)
    .eq("type", type)
    .order("created_at", { ascending: false })
    .limit(1);

  if (error) {
    return Response.json({ error: error.message }, { status: 500 });
  }

  if (!data || data.length === 0) {
    return Response.json({ report: null });
  }

  return Response.json({
    report: {
      content: data[0].content,
      period_start: data[0].period_start,
      period_end: data[0].period_end,
      created_at: data[0].created_at,
    },
  });
}
