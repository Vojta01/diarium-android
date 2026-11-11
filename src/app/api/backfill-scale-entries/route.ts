import { NextRequest, NextResponse } from 'next/server';
import { createClient } from '@supabase/supabase-js';
import { verifyAuth } from '@/lib/auth';
import { isCronAuthorized } from '@/lib/cron-auth';

const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || '';

/**
 * Backfill: copy historical scale values from entries.scale_values (JSONB)
 * into the scale_entries table, which the Scales widget reads.
 *
 * The Daylio expansion initially stored scale values ONLY inside entries.scale_values,
 * while ScalesWidget reads scale_entries — so the widget always rendered empty.
 * This endpoint migrates the existing data. New saves mirror automatically
 * via /api/save-entry.
 *
 * GET /api/backfill-scale-entries[?user_id=<uuid>]
 *   user_id: optional — when omitted and the call is cron-authorized, backfills ALL users.
 *
 * Auth: Bearer token of the owner (backfills own data), OR the CRON_SECRET
 * (Bearer or ?secret=) which may backfill all users. The `x-vercel-cron` header
 * is NOT accepted — see src/lib/cron-auth.ts.
 */
export async function GET(req: NextRequest) {
  try {
    const user = await verifyAuth(req);

    // Cron-style authorization (manual, shared secret only).
    // See src/lib/cron-auth.ts — the caller-supplied `x-vercel-cron` header is
    // no longer trusted; a forged one reached this handler on production.
    const isCron = isCronAuthorized(req);

    const targetUserId = req.nextUrl.searchParams.get('user_id');

    // Resolve effective user scope
    if (isCron) {
      // Cron may backfill a specific user or all users (user_id omitted → all)
      // fall through with targetUserId (may be null → all users)
    } else {
      // Regular user: can only backfill their own data
      if (!user) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
      if (targetUserId && targetUserId !== user.id) {
        return NextResponse.json({ error: 'Forbidden: can only backfill your own data' }, { status: 403 });
      }
    }

    const supabase = createClient(SUPABASE_URL, SERVICE_KEY);

    // Resolve user list: explicit user_id → that user; user auth → self; else all users
    let userIds: string[] = [];
    if (targetUserId) {
      userIds = [targetUserId];
    } else if (user) {
      userIds = [user.id];
    } else {
      const { data: allUsers } = await supabase.from('entries').select('user_id').limit(10000);
      userIds = Array.from(new Set((allUsers || []).map((r: any) => r.user_id)));
    }

    const results = [];
    let totalUpserted = 0;
    let totalEntries = 0;

    for (const uid of userIds) {
      // Fetch valid scales for this user (id → definition)
      const { data: scales } = await supabase
        .from('scales')
        .select('id, min_value, max_value')
        .eq('user_id', uid)
        .eq('is_active', true);
      const scaleDefs = new Map((scales || []).map((s: any) => [s.id, s]));

      // Fetch entries that have scale_values — filter in code (JSONB not-is-null
      // filters via PostgREST are unreliable), newest first, paginated.
      const { data: entries, error: entriesErr } = await supabase
        .from('entries')
        .select('date, scale_values')
        .eq('user_id', uid)
        .order('date', { ascending: false })
        .limit(10000);

      if (entriesErr) {
        results.push({ userId: uid, error: entriesErr.message });
        continue;
      }

      const rows: { user_id: string; scale_id: string; date: string; value: number }[] = [];
      let skippedUnknown = 0;
      let entriesWithScales = 0;

      for (const entry of entries || []) {
        const sv = (entry as any).scale_values;
        if (!sv || typeof sv !== 'object') continue;
        const pairs = Object.entries(sv);
        if (pairs.length === 0) continue;
        entriesWithScales++;
        for (const [scaleId, rawValue] of pairs) {
          const def = scaleDefs.get(scaleId);
          if (!def) {
            skippedUnknown++;
            continue;
          }
          const value = Number(rawValue);
          if (!Number.isFinite(value) || value <= 0) continue; // 0 = not selected
          rows.push({ user_id: uid, scale_id: scaleId, date: (entry as any).date, value });
        }
      }

      let upserted = 0;
      let upsertErrMsg: string | null = null;
      if (rows.length > 0) {
        const { error: upsertErr } = await supabase
          .from('scale_entries')
          .upsert(rows, { onConflict: 'user_id,scale_id,date' });
        if (upsertErr) upsertErrMsg = upsertErr.message;
        else upserted = rows.length;
      }

      totalUpserted += upserted;
      totalEntries += (entries || []).length;
      results.push({
        userId: uid,
        entriesProcessed: (entries || []).length,
        entriesWithScales,
        rowsUpserted: upserted,
        skippedUnknownScale: skippedUnknown,
        upsertError: upsertErrMsg,
      });
    }

    return NextResponse.json({
      users: results.length,
      totalEntries,
      totalRowsUpserted: totalUpserted,
      results,
    });
  } catch (e: any) {
    console.error('backfill-scale-entries exception:', e);
    return NextResponse.json({ error: e.message }, { status: 500 });
  }
}
