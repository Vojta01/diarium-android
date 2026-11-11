import { createClient } from '@supabase/supabase-js';

const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || '';

/**
 * Allowed user IDs that can use AI features.
 * Comma-separated in AI_GUARD_ALLOWED_USER_ID env var, or ALLOWED_AI_USERS env var for backward compat,
 * or default to the owner's UUID (from ALLOWED_AI_USERS).
 */
function getAllowedUsers(): string[] {
  const env = process.env.AI_GUARD_ALLOWED_USER_ID || process.env.ALLOWED_AI_USERS;
  if (env) return env.split(',').map(s => s.trim()).filter(Boolean);
  // Default: owner only — keep existing users working if env vars are unset
  return ['4a4de4ba-4733-4699-b2a8-2ad0db8ec700'];
}

/**
 * Check if a user is allowed to use AI features.
 * Returns null if allowed, or a Response error if blocked.
 */
export function guardAIUser(userId: string): Response | null {
  const allowed = getAllowedUsers();
  if (!allowed.includes(userId)) {
    return Response.json({ error: 'AI features are restricted to authorized users' }, { status: 403 });
  }
  return null;
}

/**
 * Check if today's entry already has an AI reflection.
 * If it does, return the cached reflection instead of generating a new one.
 * Returns the cached reflection Response, or null if no reflection exists yet.
 */
export async function guardDailyReflection(userId: string, date: string): Promise<Response | null> {
  try {
    const supabase = createClient(SUPABASE_URL, SERVICE_KEY, {
      auth: { persistSession: false },
    });
    const { data } = await supabase
      .from('entries')
      .select('ai_reflection')
      .eq('user_id', userId)
      .eq('date', date)
      .maybeSingle();

    if (data?.ai_reflection) {
      return Response.json({
        reflection: data.ai_reflection,
        cached: true,
      });
    }
  } catch (e) {
    console.error('guardDailyReflection: failed to check cache', e);
    // On error, allow through — don't block legitimate use
  }
  return null;
}

/**
 * Check if a periodic report already exists for this user + type + period.
 * Returns the cached report Response, or null if no report exists yet.
 */
export async function guardPeriodicReport(
  userId: string,
  type: string,
  periodStart: string,
  periodEnd: string
): Promise<Response | null> {
  try {
    const supabase = createClient(SUPABASE_URL, SERVICE_KEY, {
      auth: { persistSession: false },
    });
    const { data } = await supabase
      .from('ai_reports')
      .select('content, created_at')
      .eq('user_id', userId)
      .eq('type', type)
      .eq('period_start', periodStart)
      .eq('period_end', periodEnd)
      .order('created_at', { ascending: false })
      .limit(1);

    if (data && data.length > 0) {
      return Response.json({
        type,
        analysis: data[0].content,
        daysAnalyzed: null,
        cached: true,
        generatedAt: data[0].created_at,
      });
    }
  } catch (e) {
    console.error('guardPeriodicReport: failed to check cache', e);
  }
  return null;
}

/**
 * Check regeneration cooldown — prevent token draining.
 * Allows max 1 forced regeneration per hour per user+type+period.
 */
export async function guardRegenerationCooldown(
  userId: string,
  type: string,
  periodStart: string,
  periodEnd: string
): Promise<Response | null> {
  try {
    const supabase = createClient(SUPABASE_URL, SERVICE_KEY, {
      auth: { persistSession: false },
    });
    const { data } = await supabase
      .from('ai_reports')
      .select('created_at')
      .eq('user_id', userId)
      .eq('type', type)
      .eq('period_start', periodStart)
      .eq('period_end', periodEnd)
      .order('created_at', { ascending: false })
      .limit(1);

    if (data && data.length > 0) {
      const lastGen = new Date(data[0].created_at).getTime();
      const now = Date.now();
      const cooldownMs = 60 * 60 * 1000; // 1 hour
      if (now - lastGen < cooldownMs) {
        const minutesLeft = Math.ceil((cooldownMs - (now - lastGen)) / 60000);
        return Response.json({
          error: `Regeneration cooldown active. Try again in ${minutesLeft} minute(s).`,
          cooldown: true,
          minutesLeft,
        }, { status: 429 });
      }
    }
  } catch (e) {
    console.error('guardRegenerationCooldown: failed', e);
    // On error, allow through — don't block legitimate use
  }
  return null;
}
