import { buildEntryRow, type EntryRowInput } from "../entry-row.ts";
import { isValidDateKey } from "./dates.ts";
import type { EntrySaveBaseline } from "./save-v2.ts";
import type { CheckInPayload, CheckInData } from "../types.ts";
import { fromDraft, toDraft } from "./model.ts";
import {
  resolveBrowserDraftLocks,
  withDraftTransaction,
  type DraftLockResolver,
  type DraftReadStatus,
  type DraftConflictSnapshotRead,
  type DraftTransaction,
} from "./draft.ts";

export const PENDING_SAVE_PREFIX = "diarium_pending_save_v1_";

export interface PendingSaveRequest {
  readonly version: 1;
  readonly ownerId: string;
  readonly date: string;
  readonly baseline: EntrySaveBaseline;
  readonly requestId: string;
  /** Local ordering token used only to distinguish later drafts on remount. */
  readonly createdAt?: number;
  /** Exact durable draft revision flushed immediately before this request. */
  readonly draftRevision?: number | null;
  /** Exact route payload, normalized once before it is persisted. */
  readonly payload: Record<string, unknown>;
  /** Form snapshot lets the screen restore an ambiguous request without guessing. */
  readonly draftSnapshot: Record<string, unknown>;
  /** Exact JSON bytes passed as `fetch(..., { body })` on every attempt. */
  readonly body: string;
}

export interface PendingSaveStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export type PendingSaveRead =
  | { status: "missing" }
  | { status: "found"; request: PendingSaveRequest }
  | { status: "invalid" }
  | { status: "failed" }
  | { status: "unavailable" };

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PAYLOAD_FIELDS = new Set([
  "user_id",
  "date",
  "mood",
  "mood_emoji",
  "sleep_quality",
  "stress",
  "activities",
  "habits",
  "gratitude",
  "note",
  "weather",
  "photo_path",
  "clear_photo",
  "clear_scale_ids",
  "scale_values",
]);
const CREDENTIAL_FIELDS = new Set([
  "authorization",
  "access_token",
  "refresh_token",
  "password",
  "secret",
  "session",
  "api_key",
  "apikey",
]);

export function pendingSaveKey(date: string, ownerId: string): string {
  return `${PENDING_SAVE_PREFIX}${date}_${encodeURIComponent(ownerId)}`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function cloneRecord(value: unknown, label: string): Record<string, unknown> {
  if (!isRecord(value)) throw new TypeError(`${label} must be an object`);
  const cloned: unknown = JSON.parse(JSON.stringify(value));
  if (!isRecord(cloned)) throw new TypeError(`${label} could not be copied`);
  return cloned;
}

function validateBaseline(baseline: EntrySaveBaseline): void {
  if (baseline.exists) {
    if (!UUID_PATTERN.test(baseline.id)) {
      throw new TypeError("An existing entry baseline requires a UUID row ID");
    }
    if (!Number.isSafeInteger(baseline.revision) || baseline.revision < 1) {
      throw new TypeError("An existing entry baseline requires a positive revision");
    }
  } else if (baseline.id !== null || baseline.revision !== null) {
    throw new TypeError("An absent entry baseline requires explicit null token fields");
  }
}

function validatePayloadKeys(payload: Record<string, unknown>): void {
  for (const key of Object.keys(payload)) {
    if (!PAYLOAD_FIELDS.has(key) || CREDENTIAL_FIELDS.has(key.toLowerCase())) {
      throw new TypeError("Pending save payload contains an unsupported field");
    }
  }
}

function normalizeDraftSnapshot(value: unknown): Record<string, unknown> {
  // Round-trip through the form's strict field mapper. Unknown keys (including
  // tokens/session data) can never become part of the durable pending record.
  return toDraft(fromDraft(value));
}

function expectedBody(request: Pick<
  PendingSaveRequest,
  "payload" | "baseline" | "requestId"
>): string {
  return JSON.stringify({
    ...request.payload,
    expected_exists: request.baseline.exists,
    expected_id: request.baseline.exists ? request.baseline.id : null,
    expected_revision: request.baseline.exists ? request.baseline.revision : null,
    request_id: request.requestId,
  });
}

/**
 * Normalize and freeze the request once. The bearer token is deliberately not
 * part of this record; it is obtained from the current verified session only
 * when the request is sent.
 */
export function createPendingSaveRequest(options: {
  ownerId: string;
  date: string;
  baseline: EntrySaveBaseline;
  requestId: string;
  createdAt?: number;
  draftRevision?: number | null;
  payload: CheckInPayload;
  draftSnapshot: unknown;
}): PendingSaveRequest {
  if (!UUID_PATTERN.test(options.ownerId)) {
    throw new TypeError("A pending save requires a UUID owner ID");
  }
  if (!isValidDateKey(options.date)) {
    throw new TypeError("A pending save requires a valid date");
  }
  if (!UUID_PATTERN.test(options.requestId)) {
    throw new TypeError("A pending save requires a UUID request ID");
  }
  validateBaseline(options.baseline);
  if (options.payload.date !== options.date) {
    throw new TypeError("Pending save payload date does not match its scope");
  }

  const row = buildEntryRow({
    ...options.payload,
    user_id: options.ownerId,
    date: options.date,
  } as EntryRowInput);
  const payload: Record<string, unknown> = {
    ...row,
    ...(options.payload.clear_photo === true ? { clear_photo: true } : {}),
    ...(options.payload.clear_scale_ids !== undefined
      ? { clear_scale_ids: [...options.payload.clear_scale_ids] }
      : {}),
  };
  validatePayloadKeys(payload);
  const draftSnapshot = normalizeDraftSnapshot(
    cloneRecord(options.draftSnapshot, "Draft snapshot")
  );
  const baseline = Object.freeze({ ...options.baseline }) as EntrySaveBaseline;
  const immutablePayload = Object.freeze(payload);
  const request = {
    version: 1 as const,
    ownerId: options.ownerId,
    date: options.date,
    baseline,
    requestId: options.requestId,
    createdAt: options.createdAt ?? Date.now(),
    ...(options.draftRevision !== undefined
      ? { draftRevision: options.draftRevision }
      : {}),
    payload: immutablePayload,
    draftSnapshot: Object.freeze(draftSnapshot),
    body: "",
  };
  const complete = Object.freeze({
    ...request,
    body: expectedBody(request),
  });
  validatePendingSaveRequest(complete);
  return complete;
}

/** Validate stored/in-memory data before replaying its exact request body. */
export function validatePendingSaveRequest(value: unknown): asserts value is PendingSaveRequest {
  if (!isRecord(value) || value.version !== 1) {
    throw new TypeError("Unsupported pending save record");
  }
  const request = value as unknown as PendingSaveRequest;
  if (
    !UUID_PATTERN.test(request.ownerId) ||
    !isValidDateKey(request.date) ||
    !UUID_PATTERN.test(request.requestId) ||
    !isRecord(request.payload) ||
    !isRecord(request.draftSnapshot) ||
    typeof request.body !== "string"
  ) {
    throw new TypeError("Invalid pending save record");
  }
  if (
    request.createdAt !== undefined &&
    (!Number.isSafeInteger(request.createdAt) || request.createdAt < 0)
  ) {
    throw new TypeError("Pending save creation time is invalid");
  }
  if (
    request.draftRevision !== undefined &&
    request.draftRevision !== null &&
    (!Number.isSafeInteger(request.draftRevision) || request.draftRevision < 1)
  ) {
    throw new TypeError("Pending save draft revision is invalid");
  }
  validateBaseline(request.baseline);
  validatePayloadKeys(request.payload);
  if (
    JSON.stringify(normalizeDraftSnapshot(request.draftSnapshot)) !==
    JSON.stringify(request.draftSnapshot)
  ) {
    throw new TypeError("Pending save snapshot contains unsupported form data");
  }
  if (
    request.payload.user_id !== request.ownerId ||
    request.payload.date !== request.date ||
    request.body !== expectedBody(request)
  ) {
    throw new TypeError("Pending save bytes do not match their owner/date/token");
  }
}

function sameRequest(left: PendingSaveRequest, right: PendingSaveRequest): boolean {
  return left.requestId === right.requestId && left.body === right.body;
}

/** Write-ahead storage: never replace a different unresolved request. */
export function writePendingSave(
  storage: PendingSaveStorage | null,
  request: PendingSaveRequest,
  resolveLocks?: DraftLockResolver
): Promise<boolean> {
  if (!storage) return Promise.resolve(false);
  try {
    validatePendingSaveRequest(request);
    const key = pendingSaveKey(request.date, request.ownerId);
    return withDraftTransaction(
      storage,
      request.date,
      request.ownerId,
      () => {
        const existing = storage.getItem(key);
        if (existing !== null) {
          const parsed: unknown = JSON.parse(existing);
          validatePendingSaveRequest(parsed);
          if (!sameRequest(parsed, request)) return false;
        }
        storage.setItem(key, JSON.stringify(request));
        return true;
      },
      resolveLocks
    ).then((result) => result.status === "completed" && result.value);
  } catch {
    return Promise.resolve(false);
  }
}

export function readPendingSave(
  storage: PendingSaveStorage | null,
  date: string,
  ownerId: string
): PendingSaveRead {
  if (!storage) return { status: "unavailable" };
  if (!UUID_PATTERN.test(ownerId) || !isValidDateKey(date)) {
    return { status: "invalid" };
  }
  try {
    const raw = storage.getItem(pendingSaveKey(date, ownerId));
    if (raw === null) return { status: "missing" };
    const parsed: unknown = JSON.parse(raw);
    validatePendingSaveRequest(parsed);
    const request = parsed as PendingSaveRequest;
    if (request.ownerId !== ownerId || request.date !== date) {
      return { status: "invalid" };
    }
    return { status: "found", request };
  } catch {
    return { status: "invalid" };
  }
}

export type PendingSaveAndDraftRead =
  | {
      status: "ready";
      pending: PendingSaveRead;
      draft: DraftReadStatus;
      conflicts: DraftConflictSnapshotRead;
    }
  | { status: "failed" | "unavailable" };

/** Read pending intent and its draft as one owner/date lock snapshot. */
export async function readPendingSaveAndDraft(
  storage: PendingSaveStorage | null,
  date: string,
  ownerId: string,
  resolveLocks: DraftLockResolver = resolveBrowserDraftLocks
): Promise<PendingSaveAndDraftRead> {
  if (!storage) return { status: "unavailable" };
  if (!UUID_PATTERN.test(ownerId) || !isValidDateKey(date)) {
    return { status: "failed" };
  }
  const result = await withDraftTransaction(
    storage,
    date,
    ownerId,
    (draftTransaction) => ({
      pending: readPendingSave(storage, date, ownerId),
      draft: draftTransaction.read(),
      conflicts: draftTransaction.readConflicts(),
    }),
    resolveLocks
  );
  return result.status === "completed"
    ? { status: "ready", ...result.value }
    : { status: result.status };
}

/** Remove only the exact request whose saved receipt was freshly confirmed. */
export function clearPendingSave(
  storage: PendingSaveStorage | null,
  date: string,
  ownerId: string,
  requestId: string,
  resolveLocks?: DraftLockResolver
): Promise<boolean> {
  if (!storage) return Promise.resolve(false);
  try {
    const key = pendingSaveKey(date, ownerId);
    return withDraftTransaction(
      storage,
      date,
      ownerId,
      () => {
        const raw = storage.getItem(key);
        if (raw === null) return true;
        const parsed: unknown = JSON.parse(raw);
        validatePendingSaveRequest(parsed);
        const request = parsed as PendingSaveRequest;
        if (
          request.ownerId !== ownerId ||
          request.date !== date ||
          request.requestId !== requestId
        ) {
          return false;
        }
        storage.removeItem(key);
        return true;
      },
      resolveLocks
    ).then((result) => result.status === "completed" && result.value);
  } catch {
    return Promise.resolve(false);
  }
}

/**
 * Release a request only after the server definitively rejected it and its
 * complete form snapshot has been copied to the ordinary owner-scoped draft.
 */
export type RejectedPendingSaveRelease =
  | {
      status: "released";
      data: CheckInData;
      revision: number | null;
      draftStatus: "written" | "cleared" | "preserved";
    }
  | { status: "stale" | "failed" | "unavailable" };

export async function releaseRejectedPendingSave(
  storage: PendingSaveStorage | null,
  request: PendingSaveRequest,
  data: CheckInData,
  resolveLocks?: DraftLockResolver
): Promise<RejectedPendingSaveRelease> {
  if (!storage) return { status: "unavailable" };
  try {
    validatePendingSaveRequest(request);
    const expectedDraftRevision = request.draftRevision;
    if (expectedDraftRevision === undefined) return { status: "failed" };
    const result = await withDraftTransaction(
      storage,
      request.date,
      request.ownerId,
      (draftTransaction: DraftTransaction): RejectedPendingSaveRelease => {
        const existing = readPendingSave(storage, request.date, request.ownerId);
        if (
          existing.status !== "found" ||
          !sameRequest(existing.request, request)
        ) {
          return { status: "stale" };
        }
        const current = draftTransaction.read();
        if (current.status === "failed" || current.status === "unavailable") {
          return { status: current.status };
        }

        if (current.status === "found") {
          const isNewerRevision =
            current.revision !== null &&
            (expectedDraftRevision === null ||
              current.revision > expectedDraftRevision);
          if (isNewerRevision) {
            storage.removeItem(pendingSaveKey(request.date, request.ownerId));
            return {
              status: "released",
              data: current.data,
              revision: current.revision,
              draftStatus: "preserved",
            };
          }
          if (
            current.revision !== expectedDraftRevision ||
            JSON.stringify(toDraft(current.data)) !==
              JSON.stringify(request.draftSnapshot)
          ) {
            return { status: "failed" };
          }
        }

        const write = draftTransaction.write(
          data,
          current.revision ?? null
        );
        if (write.status === "conflict") return { status: "failed" };
        if (write.status === "failed" || write.status === "unavailable") {
          return { status: write.status };
        }
        const pendingNow = readPendingSave(storage, request.date, request.ownerId);
        if (
          pendingNow.status !== "found" ||
          !sameRequest(pendingNow.request, request)
        ) {
          return { status: "stale" };
        }
        storage.removeItem(pendingSaveKey(request.date, request.ownerId));
        return {
          status: "released",
          data,
          revision:
            write.status === "written" || write.status === "cleared"
              ? write.revision
              : null,
          draftStatus: write.status,
        };
      },
      resolveLocks
    );
    if (result.status !== "completed") return { status: result.status };
    return result.value;
  } catch {
    return { status: "failed" };
  }
}
