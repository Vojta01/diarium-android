import { createClient } from "@supabase/supabase-js";

/**
 * Runtime AI configuration.
 *
 * The DeepSeek model ID lives in the database (public.app_settings), so it can
 * be changed from the backend without redeploying the app.
 *
 * Resolution order:
 *   1. public.app_settings  row  key = 'deepseek_model'   <- change it here
 *   2. DEEPSEEK_MODEL       env var (deploy-time override / local dev)
 *   3. DEFAULT_AI_MODEL     hardcoded fallback below
 *
 * To switch model in production, run in the Supabase SQL editor:
 *   update public.app_settings
 *      set value = 'deepseek-v4-pro', updated_at = now()
 *    where key = 'deepseek_model';
 *
 * Canonical DeepSeek model IDs (verified against GET https://api.deepseek.com/models):
 *   deepseek-flash   = V4.1 Flash  (current default)
 *   deepseek-v4-pro  = V4 Pro
 *   NOTE: 'deepseek-v4-flash' is a legacy alias DeepSeek maps to 'deepseek-flash'.
 */

export const DEFAULT_AI_MODEL = "deepseek-flash";

/** How long a resolved value is reused inside a warm serverless instance. */
const CACHE_TTL_MS = 60_000;

let cached: { value: string; at: number } | null = null;

export async function getAIModel(): Promise<string> {
  const now = Date.now();
  if (cached && now - cached.at < CACHE_TTL_MS) return cached.value;

  let value = process.env.DEEPSEEK_MODEL || DEFAULT_AI_MODEL;

  try {
    const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL;
    const serviceKey = process.env.SUPABASE_SERVICE_ROLE_KEY || "";
    if (supabaseUrl && serviceKey) {
      const sb = createClient(supabaseUrl, serviceKey);
      const { data } = await sb
        .from("app_settings")
        .select("value")
        .eq("key", "deepseek_model")
        .maybeSingle();
      if (data?.value) value = data.value;
    }
  } catch {
    // A config lookup must never break AI generation — fall back to env/default.
  }

  cached = { value, at: now };
  return value;
}
