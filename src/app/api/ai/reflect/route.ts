import { NextRequest } from "next/server";
import { createClient } from "@supabase/supabase-js";
import { verifyAuth } from "@/lib/auth";
import { guardAIUser, guardDailyReflection } from "@/lib/ai-guard";
import { getAIModel } from "@/lib/ai-config";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";
const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || '';

const SYSTEM_PROMPT = `Jsi Diarium AI — osobní asistent pro denní reflexi. Dostaneš dnešní záznam + historii posledních 7 dní.

Tvůj úkol: napiš krátkou, osobní reflexi (3-5 vět v češtině):

1. Ocenění dneška — konkrétně zareaguj na poznámku nebo vděčnost. Použij jeho vlastní slova.
2. Vzorce a trendy — porovnej dnešek s historií. Všimni si:
   - Změn nálady: "už 3 dny po sobě skvělá nálada" nebo "dneska poprvé za týden dobře"
   - Návratu k aktivitám: "konečně jsi po 4 dnech zase cvičil a hned je nálada lepší"
   - Zhoršení: "všiml jsem si, že když špatně spíš, druhý den máš vyšší stres"
   - Splněných cílů: "tenhle týden jsi meditoval 5 ze 7 dní!"
   - Zvládnuté návyky: "návyk splněn 5 ze 7 dní — super!"
   - Screen time patternů: "screen time ti klesá — super trend!"
3. Návaznost na předchozí reflexe — pokud historie obsahuje 🤖 Včerejší reflexe, můžeš na ni navázat: "včera jsem ti psal, že... a dneska..."
4. Povzbuzení — krátké, osobní. Když je co chválit, chval. Když je zle, podpoř.

⚠️ DŮLEŽITÁ PRAVIDLA:
- Mluv přímo k uživateli. Pokud znáš jeho jméno, oslov ho jménem.
- **Respektuj pohlaví:** výchozí je mužský rod.
- **Odkazuj POUZE na konkrétní data.** Pokud data neukazují žádný trend, NEVYMÝŠLEJ si ho — prostě to přeskoč a soustřeď se na dnešek.
- **Nementoruj.** Jsi kamarád, ne terapeut. Když všechno klape, jen to ocen.
- **Nikdy neříkej "podle dat" nebo "záznam ukazuje"** — mluv přirozeně: "všiml jsem si, že..." nebo "tenhle týden..."
- Max 5 krátkých, smysluplných vět.
- Pokud historie neobsahuje dost dní pro trendy (míň než 3 dny), ber to v pohodě — soustřeď se hlavně na dnešek.`;

const SYSTEM_PROMPT_EN = `You are Diarium AI — a personal daily reflection assistant. You'll receive today's entry plus the history of the last 7 days.

Your task: write a short, personal reflection (3-5 sentences in English):

1. Appreciation of today — react specifically to their note or gratitude. Use their own words.
2. Patterns and trends — compare today with the history. Notice:
   - Mood changes: "3 days in a row of great mood!" or "first good day in a week"
   - Return to activities: "you finally worked out after 4 days and your mood is already better"
   - Deterioration: "I noticed that when you sleep poorly, the next day your stress is higher"
   - Goals achieved: "you meditated 5 out of 7 days this week!"
   - Habits kept: "you kept a habit 5 out of 7 days — nice!"
   - Screen time patterns: "your screen time is trending down — nice!"
3. Continuity with previous reflections — if the history contains 🤖 Previous reflection, you can build on it: "yesterday I mentioned that... and today..."
4. Encouragement — short, personal. When there's something to praise, praise. When things are rough, be supportive.

⚠️ IMPORTANT RULES:
- Speak directly to the user. If you know their name, address them by name.
- **Refer ONLY to the actual data.** If the data doesn't show any trend, DON'T make one up — just skip it and focus on today.
- **Don't lecture.** You're a friend, not a therapist. When everything is going well, just appreciate it.
- **Never say "according to the data" or "the record shows"** — speak naturally: "I noticed that..." or "this week..."
- Max 5 short, meaningful sentences.
- If the history doesn't have enough days for trends (fewer than 3 days), that's fine — just focus on today.`;

const moodLabels: Record<number, string> = {
  5: "skvěle 😄", 4: "dobře 🙂", 3: "neutrálně 😐", 2: "špatně 😟", 1: "hrozně 😡"
};
const stressLabels: Record<number, string> = {
  5: "extrémní", 4: "vysoký", 3: "střední", 2: "mírný", 1: "nízký"
};
const sleepLabels: Record<number, string> = {
  3: "skvělý", 2: "normální", 1: "špatný"
};

function formatDay(entry: any, isToday: boolean, scaleNameMap: Record<string, string>): string {
  const parts: string[] = [];
  const prefix = isToday ? "📅 DNEŠEK" : `📅 ${entry.date?.slice(5)}`;

  parts.push(`${prefix}:`);
  parts.push(`  Nálada: ${moodLabels[entry.mood] || "?"}`);
  
  if (entry.note?.trim()) {
    parts.push(`  Poznámka: "${entry.note}"`);
  }
  
  const gratitude = entry.gratitude?.filter((g: string) => g.trim());
  if (gratitude?.length) {
    parts.push(`  Vděčnost: ${gratitude.join("; ")}`);
  }
  
  if (entry.activities?.length) {
    parts.push(`  Aktivity: ${entry.activities.join(", ")}`);
  }
  
  if (entry.sleep_quality > 0) {
    parts.push(`  Spánek: ${sleepLabels[entry.sleep_quality] || "?"}`);
  }
  
  if (entry.stress > 0) {
    parts.push(`  Stres: ${stressLabels[entry.stress] || "?"}`);
  }

  // Scales (Energie, Produktivita)
  if (entry.scale_values && typeof entry.scale_values === "object") {
    const scaleParts: string[] = [];
    for (const [scaleId, val] of Object.entries(entry.scale_values)) {
      const name = scaleNameMap[scaleId];
      if (name && typeof val === "number") {
        scaleParts.push(`${name} ${val}/5`);
      }
    }
    if (scaleParts.length > 0) {
      parts.push(`  Škály: ${scaleParts.join(", ")}`);
    }
  }

  // Habits — 2026-10-13 owner decision: the value means "fulfilled" for every
  // habit (a negative habit fulfilled = the user avoided it that day).
  if (entry.habits) {
    const kept: string[] = [];
    const missed: string[] = [];
    for (const [k, v] of Object.entries(entry.habits)) {
      (v ? kept : missed).push(k);
    }
    if (kept.length > 0) parts.push(`  Splněné návyky: ${kept.join(", ")}`);
    if (missed.length > 0) parts.push(`  Nesplněné návyky: ${missed.join(", ")}`);
  }

  if (entry.phone_screen_time) {
    const mins = Math.round(entry.phone_screen_time / 60);
    parts.push(`  Screen time: ${mins} min`);
  }

  // Previous AI reflection — for continuity
  if (!isToday && entry.ai_reflection?.trim()) {
    parts.push(`  🤖 Včerejší reflexe: "${entry.ai_reflection}"`);
  }

  return parts.join("\n");
}

export async function POST(request: NextRequest) {
  try {
    const user = await verifyAuth(request);
    if (!user) {
      return Response.json({ error: "Unauthorized" }, { status: 401 });
    }

    // AI user whitelist
    const userBlock = guardAIUser(user.id);
    if (userBlock) return userBlock;

    const apiKey = process.env.DEEPSEEK_API_KEY;
    if (!apiKey) {
      return Response.json({ error: "API key not configured" }, { status: 500 });
    }

    if (!SUPABASE_URL || !SERVICE_KEY) {
      return Response.json({ error: "Supabase not configured" }, { status: 500 });
    }

    const body = await request.json();
    const { userName, birthYear, user_id, date, lang, ...todayData } = body;

    // Verify the caller owns this user_id
    if (user_id && user_id !== user.id) {
      return Response.json({ error: "Forbidden" }, { status: 403 });
    }

    // Rate limiting: check if today's reflection already exists in DB
    if (user_id && date) {
      const cached = await guardDailyReflection(user_id, date);
      if (cached) return cached;
    }

    // Fetch last 7 days from Supabase
    let history: any[] = [];
    if (user_id && date) {
      try {
        const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
        const sevenDaysAgo = new Date(date);
        sevenDaysAgo.setDate(sevenDaysAgo.getDate() - 7);
        const fromDate = sevenDaysAgo.toISOString().split("T")[0];

        const { data: rows } = await supabase
          .from("entries")
          .select("*")
          .eq("user_id", user_id)
          .gte("date", fromDate)
          .lte("date", date)
          .order("date", { ascending: true });

        history = rows || [];
      } catch (e) {
        console.error("Failed to fetch history:", e);
        // Continue with just today's data
      }
    }

    // Fetch scales to map scale IDs → names (Energie, Produktivita)
    const scaleNameMap: Record<string, string> = {};
    try {
      const supabaseForScales = createClient(SUPABASE_URL, SERVICE_KEY);
      const { data: scales } = await supabaseForScales
        .from("scales")
        .select("id, name")
        .eq("user_id", user_id);
      (scales || []).forEach((s: any) => { scaleNameMap[s.id] = s.name; });
    } catch (e) {
      console.error("Failed to fetch scales:", e);
    }

    // Build the prompt
    const promptParts: string[] = [];
    
    if (userName) {
      promptParts.push(`👤 Uživatel: ${userName}${birthYear ? ` (narozen ${birthYear})` : ""}`);
    }
    promptParts.push(`Historický kontext (posledních ${history.length} dní včetně dneška):`);
    promptParts.push("");

    for (const entry of history) {
      const isToday = entry.date === date;
      promptParts.push(formatDay(entry, isToday, scaleNameMap));
      promptParts.push("");
    }

    // If no history, fall back to just today
    if (history.length === 0) {
      promptParts.push(formatDay({ ...todayData, date }, true, scaleNameMap));
    }

    const userPrompt = promptParts.join("\n");

    const resp = await fetch(DEEPSEEK_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${apiKey}`,
      },
      body: JSON.stringify({
        model: await getAIModel(),
        messages: [
          { role: "system", content: lang === "en" ? SYSTEM_PROMPT_EN : SYSTEM_PROMPT },
          { role: "user", content: userPrompt },
        ],
        max_tokens: 1600,
        temperature: 0.7,
      }),
    });

    if (!resp.ok) {
      const err = await resp.text();
      console.error("DeepSeek error:", err);
      return Response.json({ error: "AI failed" }, { status: 502 });
    }

    const json = await resp.json();
    const reflection = json.choices?.[0]?.message?.content?.trim() || "";

    return Response.json({ reflection });
  } catch (err) {
    console.error("AI reflect error:", err);
    return Response.json({ error: "Internal error" }, { status: 500 });
  }
}
