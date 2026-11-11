import { NextRequest, NextResponse } from "next/server";
import { createClient } from "@supabase/supabase-js";
import { verifyAuth } from "@/lib/auth";

// Standard habits shown to all users (owner decision 2026-10-13). Personal
// sensitive habits (e.g. porno, masturbace) stay in the user_habits table only.
const DEFAULT_HABITS = [
  { key: "alkohol", label: "Alkohol", icon: "🍺", category: "zdraví", is_negative: true, sort_order: 1 },
  { key: "koureni", label: "Kouření", icon: "🚬", category: "zdraví", is_negative: true, sort_order: 2 },
  { key: "sladkosti", label: "Sladkosti", icon: "🍬", category: "zdraví", is_negative: true, sort_order: 3 },
  { key: "bez_kafe", label: "Bez kafe", icon: "☕", category: "zdraví", is_negative: true, sort_order: 4 },
  { key: "cviceni", label: "Cvičení", icon: "🏋️", category: "pohyb", is_negative: false, sort_order: 5 },
  { key: "pit_vodu", label: "Pít vodu", icon: "💧", category: "zdraví", is_negative: false, sort_order: 6 },
  { key: "cteni", label: "Čtení", icon: "📖", category: "mysl", is_negative: false, sort_order: 7 },
  { key: "meditace", label: "Meditace", icon: "🧘", category: "mysl", is_negative: false, sort_order: 8 },
];

// Legacy keys that must never be catalogue defaults. The standard set above is
// NOT on this list any more (it used to remove koureni/cviceni/meditace).
const HABITS_TO_REMOVE = [
  "cist", "zdrave_jidlo", "studena_sprcha", "piti_vody",
];

export async function POST(request: NextRequest) {
  const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL;
  const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY;

  if (!supabaseUrl || !serviceKey) {
    return NextResponse.json({ error: "Missing env vars" }, { status: 500 });
  }

  // Require valid auth (any authenticated user can seed the catalog)
  const user = await verifyAuth(request);
  if (!user) {
    return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
  }

  const sb = createClient(supabaseUrl, serviceKey);

  // 1. Remove habits that shouldn't be default
  if (HABITS_TO_REMOVE.length > 0) {
    await sb.from("habit_catalog").delete().in("key", HABITS_TO_REMOVE);
  }

  // 2. Upsert alkohol (only default)
  const results: { key: string; status: string }[] = [];
  for (const h of DEFAULT_HABITS) {
    const { error } = await sb.from("habit_catalog").upsert(
      {
        key: h.key,
        label: h.label,
        icon: h.icon,
        category: h.category,
        is_negative: h.is_negative,
        is_default: true,
        sort_order: h.sort_order,
      },
      { onConflict: "key" }
    );
    results.push({ key: h.key, status: error ? `❌ ${error.message}` : "✓" });
  }

  // 3. Count remaining
  const { count } = await sb.from("habit_catalog").select("*", { count: "exact", head: true });

  return NextResponse.json({
    default_habits_remaining: count,
    upserted: results,
    removed: HABITS_TO_REMOVE,
  });
}
