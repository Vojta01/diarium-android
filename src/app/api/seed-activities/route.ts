import { NextRequest, NextResponse } from "next/server";
import { createClient } from "@supabase/supabase-js";
import { verifyAuth } from "@/lib/auth";

// All original activities from the app
const ORIGINAL_ACTIVITIES = [
  // ── Společenské ──
  { key: "rodina", label: "Rodina", icon: "👨‍👩‍👧", category: "sociální", sort_order: 1 },
  { key: "pratele", label: "Přátelé", icon: "👥", category: "sociální", sort_order: 2 },
  { key: "rande", label: "Rande", icon: "💑", category: "sociální", sort_order: 3 },
  { key: "party", label: "Párty", icon: "🎉", category: "sociální", sort_order: 4 },
  { key: "office", label: "Office", icon: "🏢", category: "sociální", sort_order: 5 },

  // ── Záliby ──
  { key: "filmy_a_tv", label: "Filmy a TV", icon: "🎬", category: "volný čas", sort_order: 10 },
  { key: "cteni", label: "Čtení", icon: "📖", category: "volný čas", sort_order: 11 },
  { key: "hrani_her", label: "Hraní her", icon: "🎮", category: "volný čas", sort_order: 12 },
  { key: "sport", label: "Sport", icon: "🏃", category: "volný čas", sort_order: 13 },
  { key: "relax", label: "Relax", icon: "😌", category: "volný čas", sort_order: 14 },
  { key: "hudba", label: "Hudba", icon: "🎵", category: "volný čas", sort_order: 15 },

  // ── Jídlo ──
  { key: "jist_zdrave", label: "Jíst zdravě", icon: "🥗", category: "jídlo", sort_order: 20 },
  { key: "rychle_obcerstveni", label: "Rychlé občerstvení", icon: "🍔", category: "jídlo", sort_order: 21 },
  { key: "domaci_vyroba", label: "Domácí výroba", icon: "🍳", category: "jídlo", sort_order: 22 },
  { key: "restaurace", label: "Restaurace", icon: "🍽️", category: "jídlo", sort_order: 23 },
  { key: "donaska", label: "Donáška", icon: "📦", category: "jídlo", sort_order: 24 },
  { key: "den_bez_masa", label: "Den bez masa", icon: "🥬", category: "jídlo", sort_order: 25 },
  { key: "zadne_sladkosti", label: "Žádné sladkosti", icon: "🚫🍰", category: "jídlo", sort_order: 26 },
  { key: "zadne_limonady", label: "Žádné limonády", icon: "🚫🥤", category: "jídlo", sort_order: 27 },

  // ── Zdraví ──
  { key: "trenink", label: "Trénink", icon: "🏋️", category: "sport", sort_order: 30 },
  { key: "pit_vody", label: "Pít vodu", icon: "💧", category: "sport", sort_order: 31 },
  { key: "chuze", label: "Chůze", icon: "🚶", category: "sport", sort_order: 32 },
  { key: "kolo", label: "Kolo", icon: "🚴", category: "sport", sort_order: 33 },
  { key: "plavani", label: "Plavání", icon: "🏊", category: "sport", sort_order: 34 },
  { key: "paddleboard", label: "Paddleboard", icon: "🏄", category: "sport", sort_order: 35 },
  { key: "snooker", label: "Snooker", icon: "🎱", category: "sport", sort_order: 36 },

  // ── Mé lepší já ──
  { key: "meditovat", label: "Meditovat", icon: "🧘", category: "wellness", sort_order: 40 },
  { key: "laskavost", label: "Laskavost", icon: "💝", category: "wellness", sort_order: 41 },
  { key: "naslouchani", label: "Naslouchání", icon: "👂", category: "wellness", sort_order: 42 },
  { key: "darcovstvi", label: "Dárcovství", icon: "💰", category: "wellness", sort_order: 43 },
  { key: "dej_darek", label: "Dej dárek", icon: "🎁", category: "wellness", sort_order: 44 },
  { key: "terapie", label: "Terapie", icon: "🛋️", category: "wellness", sort_order: 45 },
  { key: "integrita", label: "Integrita", icon: "⚖️", category: "wellness", sort_order: 46 },

  // ── Domácí práce ──
  { key: "nakupovani", label: "Nakupování", icon: "🛒", category: "domácí práce", sort_order: 50 },
  { key: "uklizeni", label: "Uklízení", icon: "🧹", category: "domácí práce", sort_order: 51 },
  { key: "vareni", label: "Vaření", icon: "🍲", category: "domácí práce", sort_order: 52 },
  { key: "prani", label: "Praní", icon: "🧺", category: "domácí práce", sort_order: 53 },
  { key: "zehleni", label: "Žehlení", icon: "👕", category: "domácí práce", sort_order: 54 },

  // Weather is deliberately NOT an activity (owner decision, 2026-10-13): it renders
  // in the check-in's own weather section, never among the pickable activities.
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

  const results: { key: string; status: string }[] = [];

  for (const activity of ORIGINAL_ACTIVITIES) {
    const { error } = await sb.from("activity_catalog").upsert(
      {
        key: activity.key,
        label: activity.label,
        icon: activity.icon,
        category: activity.category,
        is_default: true,
        sort_order: activity.sort_order,
      },
      { onConflict: "key" }
    );

    results.push({
      key: activity.key,
      status: error ? `❌ ${error.message}` : "✓",
    });
  }

  return NextResponse.json({
    seeded: results.length,
    results,
  });
}
