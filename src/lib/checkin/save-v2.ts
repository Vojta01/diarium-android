export type EntrySaveBaseline =
  | { exists: true; id: string; revision: number }
  | { exists: false; id: null; revision: null };

export {
  clearPendingSave,
  createPendingSaveRequest,
  pendingSaveKey,
  readPendingSave,
  readPendingSaveAndDraft,
  releaseRejectedPendingSave,
  validatePendingSaveRequest,
  writePendingSave,
  type PendingSaveAndDraftRead,
  type PendingSaveRead,
  type PendingSaveRequest,
  type PendingSaveStorage,
} from "./pending-save.ts";

export interface SaveV2RpcArgs {
  p_payload: Record<string, unknown>;
  p_expected_exists: boolean;
  p_expected_id: string | null;
  p_expected_revision: number | null;
  p_request_id: string;
}

export interface EntrySaveIntent {
  readonly fingerprint: string;
  readonly requestId: string;
}

export type ConditionalSaveResult<T> =
  | { status: "saved"; entry: T; replayed: boolean }
  | {
      status: "conflict";
      latest: T | null;
      reason: string;
      latestReadError?: unknown;
    }
  | { status: "failed"; error: unknown };

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function stableValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(stableValue);
  if (value && typeof value === "object") {
    const result: Record<string, unknown> = {};
    for (const key of Object.keys(value as Record<string, unknown>).sort()) {
      result[key] = stableValue((value as Record<string, unknown>)[key]);
    }
    return result;
  }
  return value;
}

function validateBaseline(baseline: EntrySaveBaseline): void {
  if (baseline.exists) {
    if (!UUID_PATTERN.test(baseline.id)) {
      throw new TypeError("An existing entry baseline requires a UUID row ID");
    }
    if (!Number.isSafeInteger(baseline.revision) || baseline.revision < 1) {
      throw new TypeError("An existing entry baseline requires a positive safe revision");
    }
    return;
  }
  if (baseline.id !== null || baseline.revision !== null) {
    throw new TypeError("An absent entry baseline requires explicit null ID and revision");
  }
}

function validateRequestId(requestId: string): void {
  if (!UUID_PATTERN.test(requestId)) {
    throw new TypeError("A conditional save requires a UUID request ID");
  }
}

/** Create an explicit CAS token from the strict server read for this date. */
export function baselineFromEntry(entry: {
  id?: unknown;
  revision?: unknown;
} | null): EntrySaveBaseline {
  if (entry === null) return { exists: false, id: null, revision: null };
  if (
    typeof entry.id !== "string" ||
    !UUID_PATTERN.test(entry.id) ||
    typeof entry.revision !== "number" ||
    !Number.isSafeInteger(entry.revision) ||
    entry.revision < 1
  ) {
    throw new TypeError("Server entry is missing its CAS revision");
  }
  return { exists: true, id: entry.id, revision: entry.revision };
}

/** Build exactly the named-argument contract of save_daily_entry_v2. */
export function buildSaveV2RpcArgs(
  payload: Record<string, unknown>,
  baseline: EntrySaveBaseline,
  requestId: string
): SaveV2RpcArgs {
  validateBaseline(baseline);
  validateRequestId(requestId);
  return {
    p_payload: payload,
    p_expected_exists: baseline.exists,
    // Explicit null is part of the SQL absent-row contract, not an omission.
    p_expected_id: baseline.exists ? baseline.id : null,
    p_expected_revision: baseline.exists ? baseline.revision : null,
    p_request_id: requestId,
  };
}

/**
 * Reuse an idempotency key only for the exact semantic request. The database
 * fingerprints this same payload + snapshot; edits or a new baseline require a
 * fresh immutable request ID.
 */
export function createOrReuseSaveIntent(
  payload: Record<string, unknown>,
  baseline: EntrySaveBaseline,
  previous: EntrySaveIntent | null,
  createRequestId: () => string
): EntrySaveIntent {
  validateBaseline(baseline);
  const fingerprint = JSON.stringify(
    stableValue({ payload, baseline })
  );
  if (previous?.fingerprint === fingerprint) return previous;
  const requestId = createRequestId();
  validateRequestId(requestId);
  return Object.freeze({ fingerprint, requestId });
}

export function newSaveRequestId(): string {
  const randomUUID = globalThis.crypto?.randomUUID;
  if (typeof randomUUID !== "function") {
    throw new Error("Secure UUID generation is unavailable");
  }
  return randomUUID.call(globalThis.crypto);
}

export async function performConditionalSave<T extends { id?: unknown; revision?: unknown }>(
  options: {
    payload: Record<string, unknown>;
    baseline: EntrySaveBaseline;
    requestId: string;
    rpc: (args: SaveV2RpcArgs) => Promise<{ data: unknown; error: unknown }>;
    /** Display-only read, invoked only after the atomic RPC reports a conflict. */
    readLatest: () => Promise<T | null>;
  }
): Promise<ConditionalSaveResult<T>> {
  let args: SaveV2RpcArgs;
  try {
    args = buildSaveV2RpcArgs(
      options.payload,
      options.baseline,
      options.requestId
    );
  } catch (error) {
    return { status: "failed", error };
  }

  let response: { data: unknown; error: unknown };
  try {
    response = await options.rpc(args);
  } catch (error) {
    return { status: "failed", error };
  }
  if (response.error) return { status: "failed", error: response.error };
  if (!isRecord(response.data)) {
    return { status: "failed", error: new Error("Conditional save returned an invalid outcome") };
  }

  const outcome = response.data;
  if (outcome.status === "conflict") {
    let latest: T | null = null;
    let latestReadError: unknown;
    try {
      latest = await options.readLatest();
    } catch (error) {
      latestReadError = error;
    }
    return {
      status: "conflict",
      latest,
      reason: typeof outcome.reason === "string" ? outcome.reason : "stale_revision",
      ...(latestReadError ? { latestReadError } : {}),
    };
  }

  if (outcome.status === "saved") {
    const replayed = outcome.replayed === true;
    if (!isRecord(outcome.entry)) {
      // SQL persists an immutable saved receipt without journal contents or the
      // original row. Resolve that exact token from the current row; never treat
      // the receipt as a fresh write result or submit a stale payload again.
      if (
        !replayed ||
        typeof outcome.entry_id !== "string" ||
        !Number.isSafeInteger(outcome.revision) ||
        (outcome.revision as number) < 1
      ) {
        return {
          status: "failed",
          error: new Error("Conditional save returned an invalid replay receipt"),
        };
      }
      let latest: T | null;
      try {
        latest = await options.readLatest();
      } catch (error) {
        return { status: "failed", error };
      }
      if (
        !latest ||
        latest.id !== outcome.entry_id ||
        latest.revision !== outcome.revision
      ) {
        return { status: "conflict", latest, reason: "replay_superseded" };
      }
      return { status: "saved", entry: latest, replayed: true };
    }

    const entry = outcome.entry as T;
    if (replayed) {
      // A receipt is immutable. If the row advanced after its original commit,
      // surface the current row rather than presenting the receipt's old row as
      // latest. This GET is informational; the RPC already decided the write.
      try {
        const latest = await options.readLatest();
        if (
          !latest ||
          latest.id !== entry.id ||
          latest.revision !== entry.revision
        ) {
          return {
            status: "conflict",
            latest,
            reason: "replay_superseded",
          };
        }
        return { status: "saved", entry: latest, replayed: true };
      } catch (error) {
        return { status: "failed", error };
      }
    }
    return { status: "saved", entry, replayed: false };
  }

  return {
    status: "failed",
    error: new Error("Conditional save returned an unknown outcome"),
  };
}
