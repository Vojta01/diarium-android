import { NextRequest, NextResponse } from 'next/server';
import { createClient } from '@supabase/supabase-js';
import { verifyAuth } from '@/lib/auth';
import { isCronAuthorized } from '@/lib/cron-auth';
import { buildEntryRow } from '@/lib/entry-row';
import { isCheckInError } from '@/lib/checkin/errors';
import { isFutureDateKey, isValidDateKey } from '@/lib/checkin/dates';
import { performConditionalSave, type EntrySaveBaseline } from '@/lib/checkin/save-v2';
import {
  isConditionalSaveValidationError,
  isMissingConditionalSaveRpc as isMissingRpc,
} from '@/lib/checkin/rpc-errors';

const SUPABASE_URL = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY || '';
const ANON_KEY = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY || '';
const CAS_FIELDS = ['expected_exists', 'expected_id', 'expected_revision', 'request_id'] as const;
const BEARER_SCHEME = 'Bearer';
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function getBearerToken(req: NextRequest): string | null {
  const authorization = req.headers.get('Authorization');
  if (!authorization?.startsWith('Bearer ')) return null;
  const token = authorization.slice(7).trim();
  return token || null;
}

function createAuthenticatedClient(token: string) {
  if (!ANON_KEY) throw new Error('Authenticated Supabase key is not configured');
  return createClient(SUPABASE_URL, ANON_KEY, {
    auth: {
      persistSession: false,
      autoRefreshToken: false,
      detectSessionInUrl: false,
    },
    global: {
      headers: { Authorization: `${BEARER_SCHEME} ${token}` },
    },
  });
}

function isAiReflectionPatch(payload: Record<string, unknown>): boolean {
  const keys = Object.keys(payload);
  return (
    typeof payload.ai_reflection === 'string' &&
    keys.every((key) => key === 'user_id' || key === 'date' || key === 'ai_reflection')
  );
}

function hasAnyCasField(payload: Record<string, unknown>): boolean {
  return CAS_FIELDS.some((key) => Object.prototype.hasOwnProperty.call(payload, key));
}

function buildValidatedRow(payload: Record<string, unknown>): Record<string, unknown> {
  try {
    return buildEntryRow(payload as unknown as Parameters<typeof buildEntryRow>[0]);
  } catch (error) {
    if (isCheckInError(error)) throw error;
    throw error;
  }
}

export async function POST(req: NextRequest) {
  try {
    const user = await verifyAuth(req);

    // Server-to-server fallback (CRON_SECRET). Only this authorized cron path and
    // the isolated AI-reflection patch retain the legacy service-role upsert.
    const isCron = isCronAuthorized(req);

    if (!user && !isCron) {
      return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
    }

    const payload = await req.json();
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
      return NextResponse.json({ error: 'Invalid payload' }, { status: 400 });
    }
    const body = payload as Record<string, unknown>;
    const { user_id, date, clear_photo, scale_values } = body;

    if (typeof user_id !== 'string' || !user_id || typeof date !== 'string' || !date) {
      return NextResponse.json({ error: 'Missing user_id or date' }, { status: 400 });
    }

    if (!isValidDateKey(date)) {
      return NextResponse.json({ error: 'Invalid date' }, { status: 400 });
    }

    // Prague "today", not UTC: the client clamps too, but a direct caller must
    // never be able to create a future entry.
    if (isFutureDateKey(date)) {
      return NextResponse.json({ error: 'Future date not allowed' }, { status: 400 });
    }

    // Verify the caller owns this user_id (skip for cron). The database RPC also
    // binds p_payload.user_id to auth.uid(); this route check is not its substitute.
    if (user && user_id !== user.id) {
      return NextResponse.json({ error: 'Forbidden: user_id mismatch' }, { status: 403 });
    }

    const hasCasFields = hasAnyCasField(body);
    if (user && hasCasFields) {
      if (!CAS_FIELDS.every((key) => Object.prototype.hasOwnProperty.call(body, key))) {
        return NextResponse.json(
          { error: 'Conditional save requires the complete snapshot token' },
          { status: 400 }
        );
      }

      const expectedExists = body.expected_exists;
      if (typeof expectedExists !== 'boolean') {
        return NextResponse.json({ error: 'Invalid expected_exists' }, { status: 400 });
      }
      const baseline: EntrySaveBaseline | null = expectedExists
        ? typeof body.expected_id === 'string' &&
          typeof body.expected_revision === 'number' &&
          Number.isSafeInteger(body.expected_revision) &&
          body.expected_revision >= 1
          ? {
              exists: true,
              id: body.expected_id,
              revision: body.expected_revision,
            }
          : null
        : body.expected_id === null && body.expected_revision === null
          ? { exists: false, id: null, revision: null }
          : null;
      if (
        !baseline ||
        typeof body.request_id !== 'string' ||
        !UUID_PATTERN.test(body.request_id) ||
        (expectedExists &&
          typeof body.expected_id === 'string' &&
          !UUID_PATTERN.test(body.expected_id))
      ) {
        return NextResponse.json(
          { error: 'Invalid conditional save token' },
          { status: 400 }
        );
      }

      let row: Record<string, unknown>;
      try {
        row = buildValidatedRow(body);
      } catch (error) {
        if (isCheckInError(error)) {
          return NextResponse.json({ error: error.message }, { status: error.status || 400 });
        }
        throw error;
      }

      // v2's SQL contract owns the scale mirror in the same transaction. Keep
      // zero values as UI intent and pass the exact IDs explicitly for clearing.
      const rpcPayload: Record<string, unknown> = {
        ...row,
        ...(body.clear_photo === true ? { clear_photo: true } : {}),
        ...(Object.prototype.hasOwnProperty.call(body, 'clear_scale_ids')
          ? { clear_scale_ids: body.clear_scale_ids }
          : {}),
      };

      const token = getBearerToken(req);
      if (!token) {
        return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });
      }
      const supabase = createAuthenticatedClient(token);
      const outcome = await performConditionalSave({
        payload: rpcPayload,
        baseline,
        requestId: body.request_id,
        rpc: async (args) => await supabase.rpc('save_daily_entry_v2', args),
        // This read is only for the conflict panel, after the atomic RPC has
        // already returned a conflict. It is never used as the write guard.
        readLatest: async () => {
          const { data, error } = await supabase
            .from('entries')
            .select('*')
            .eq('user_id', user.id)
            .eq('date', date)
            .maybeSingle();
          if (error) throw error;
          return (data as Record<string, unknown> | null) ?? null;
        },
      });

      if (outcome.status === 'conflict') {
        return NextResponse.json(
          {
            error: 'Entry changed since it was loaded',
            reason: outcome.reason,
            latest: outcome.latest,
            latest_read_failed: Boolean(outcome.latestReadError),
          },
          { status: 409 }
        );
      }
      if (outcome.status === 'failed') {
        const error = outcome.error as { code?: unknown; message?: unknown } | null;
        console.error('conditional save-entry error:', error?.code ?? 'unknown');
        const missingRpc = isMissingRpc(error);
        const validationFailure = isConditionalSaveValidationError(error);
        return NextResponse.json(
          {
            error: missingRpc
              ? 'Conditional save is not enabled on the server'
              : validationFailure
                ? 'Conditional save payload failed validation'
                : 'Conditional save failed',
            code: missingRpc
              ? 'conditional_save_unavailable'
              : validationFailure
                ? 'conditional_save_validation'
                : 'conditional_save_failed',
          },
          { status: missingRpc ? 503 : validationFailure ? 400 : 500 }
        );
      }

      return NextResponse.json(outcome.entry, {
        headers: { "X-Diarium-Replayed": String(outcome.replayed) },
      });
    }

    // Keep the separate partial AI-reflection contract. It only sends
    // user_id/date/ai_reflection and does not write foreground form fields.
    if (user && !isAiReflectionPatch(body)) {
      return NextResponse.json(
        { error: 'A loaded conditional-save snapshot is required' },
        { status: 428 }
      );
    }

    // Authorized cron and the partial AI-reflection patch retain the legacy
    // behavior. The foreground check-in never reaches this service-role upsert.
    let row: Record<string, unknown>;
    try {
      row = buildValidatedRow(body);
    } catch (error) {
      if (isCheckInError(error)) {
        return NextResponse.json({ error: error.message }, { status: error.status || 400 });
      }
      throw error;
    }

    const supabase = createClient(SUPABASE_URL, SERVICE_KEY);
    const { data, error } = await supabase
      .from('entries')
      .upsert(row, { onConflict: 'user_id,date' })
      .select()
      .single();

    if (error) {
      console.error('save-entry legacy error:', error);
      return NextResponse.json({ error: error.message, details: error }, { status: 400 });
    }

    // Legacy cron/AI callers do not use the v2 scale transaction. The foreground
    // CAS branch above intentionally performs no post-save scale mirror writes.
    if (scale_values && typeof scale_values === 'object' && user) {
      try {
        const entries = Object.entries(scale_values as Record<string, unknown>)
          .filter(([, value]) => typeof value === 'number' && Number.isFinite(value));
        const clearedIds = entries
          .filter(([, value]) => value === 0)
          .map(([scaleId]) => scaleId);
        if (clearedIds.length > 0) {
          const { error: deleteErr } = await supabase
            .from('scale_entries')
            .delete()
            .eq('user_id', user.id)
            .eq('date', date)
            .in('scale_id', clearedIds);
          if (deleteErr) console.error('scale_entries delete error:', deleteErr);
        }

        const positivePairs = entries.filter(([, value]) => (value as number) > 0);
        if (positivePairs.length > 0) {
          const { data: userScales } = await supabase
            .from('scales')
            .select('id')
            .eq('user_id', user.id);
          const validScaleIds = new Set((userScales || []).map((scale: { id: string }) => scale.id));
          const scaleRows = positivePairs
            .filter(([scaleId]) => validScaleIds.has(scaleId))
            .map(([scaleId, value]) => ({ user_id: user.id, scale_id: scaleId, date, value }));
          if (scaleRows.length > 0) {
            const { error: scaleErr } = await supabase
              .from('scale_entries')
              .upsert(scaleRows, { onConflict: 'user_id,scale_id,date' });
            if (scaleErr) console.error('scale_entries upsert error:', scaleErr);
          }
        }
      } catch (e) {
        console.error('scale_entries legacy mirror error:', e);
      }
    }

    return NextResponse.json(data);
  } catch (e) {
    console.error('save-entry exception:', e instanceof Error ? e.message : 'unknown');
    return NextResponse.json(
      { error: e instanceof Error ? e.message : 'Unknown error' },
      { status: 500 }
    );
  }
}
