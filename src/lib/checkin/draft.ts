/**
 * Local draft persistence.
 *
 * Draft writes and conditional clears require the browser Web Locks API. A
 * localStorage read/compare/write sequence is not atomic across tabs, so this
 * module fails closed when exclusive cross-tab locks are unavailable.
 */
import type { CheckInData } from "../types.ts";
import { fromDraft, hasContent, toDraft } from "./model.ts";

export const DRAFT_PREFIX = "diarium_draft_";
const DRAFT_LOCK_PREFIX = "diarium_draft_lock_";
const DRAFT_REVISION_PREFIX = "diarium_draft_revision_v1_";
export const DRAFT_CONFLICT_PREFIX = "diarium_draft_conflict_v1_";

export function draftKey(date: string, userId: string): string {
  return `${DRAFT_PREFIX}${date}_${encodeURIComponent(userId)}`;
}

export function draftLockKey(date: string, userId: string): string {
  return `${DRAFT_LOCK_PREFIX}${date}_${encodeURIComponent(userId)}`;
}

export function draftConflictKey(date: string, userId: string, id: string): string {
  return `${DRAFT_CONFLICT_PREFIX}${date}_${encodeURIComponent(userId)}_${encodeURIComponent(id)}`;
}

function draftRevisionKey(date: string, userId: string): string {
  return `${DRAFT_REVISION_PREFIX}${date}_${encodeURIComponent(userId)}`;
}

export interface DraftStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
  /** Web Storage enumeration discovers uniquely keyed conflict copies. */
  readonly length?: number;
  key?(index: number): string | null;
}

export interface DraftLockManager {
  request<T>(name: string, callback: () => T): Promise<T>;
}

export type DraftLockResolver = () => DraftLockManager | null;

/** Native Web Locks are the only supported cross-tab transaction primitive. */
export function resolveBrowserDraftLocks(): DraftLockManager | null {
  if (typeof navigator === "undefined" || !navigator.locks) return null;
  const locks = navigator.locks;
  return {
    request: <T>(name: string, callback: () => T) =>
      locks.request<T>(name, { mode: "exclusive" }, callback),
  };
}

export type DraftReadStatus =
  | { status: "missing"; revision?: number }
  | {
      status: "found";
      data: CheckInData;
      revision: number | null;
      updatedAt: number | null;
    }
  | { status: "failed" }
  | { status: "unavailable" };

export interface DraftConflictCandidate {
  id: string;
  ownerId: string;
  date: string;
  expectedRevision: number | null;
  observedRevision: number | null;
  createdAt: number;
  data: CheckInData;
}

export type DraftConflictSnapshotRead =
  | {
      status: "ready";
      ownerId: string;
      date: string;
      current: DraftReadStatus & { status: "found" | "missing" };
      candidates: DraftConflictCandidate[];
    }
  | { status: "failed" | "unavailable" };

export type DraftCandidateChoice = "candidate" | "current";

export type DraftCandidateResolution =
  | { status: "confirmation_required" }
  | { status: "stale"; snapshot?: DraftConflictSnapshotRead }
  | { status: "storage_failed" }
  | {
      status: "resolved";
      choice: DraftCandidateChoice;
      data: CheckInData | null;
      revision: number | null;
    };

export type DraftWriteResult =
  | { status: "written"; revision: number; updatedAt: number }
  | { status: "cleared"; revision: number | null }
  | {
      status: "conflict";
      current: DraftReadStatus;
      candidate: DraftConflictCandidate;
    }
  | { status: "failed" }
  | { status: "unavailable"; reason?: "storage" | "locks" };

export type DraftClearResult =
  | { status: "cleared"; revision: number }
  | { status: "missing"; revision: number | null }
  | { status: "preserved"; current: DraftReadStatus & { status: "found" } }
  | { status: "conflict"; current: DraftReadStatus }
  | { status: "failed" }
  | { status: "unavailable" };

export interface DraftTransaction {
  /** Read the exact localStorage value while the owner/date lock is held. */
  read(): DraftReadStatus;
  /** Read current plus conflict copies while this owner/date lock is held. */
  readConflicts(): DraftConflictSnapshotRead;
  /** Update only if the durable revision still equals the observed revision. */
  write(data: CheckInData, expectedRevision: number | null): DraftWriteResult;
  /** Clear only the exact durable revision, optionally requiring its snapshot. */
  clear(
    expectedRevision: number | null,
    expectedData?: CheckInData
  ): DraftClearResult;
}

export type DraftTransactionResult<T> =
  | { status: "completed"; value: T }
  | { status: "failed" }
  | { status: "unavailable"; reason?: "storage" | "locks" };

function parseRead(storage: DraftStorage, date: string, userId: string): DraftReadStatus {
  try {
    const raw = storage.getItem(draftKey(date, userId));
    const rawRevision = storage.getItem(draftRevisionKey(date, userId));
    let revisionHighWater: number | null = null;
    if (rawRevision !== null) {
      const parsedRevision = Number(rawRevision);
      if (!Number.isSafeInteger(parsedRevision) || parsedRevision < 1) {
        return { status: "failed" };
      }
      revisionHighWater = parsedRevision;
    }
    if (raw === null) {
      return revisionHighWater === null
        ? { status: "missing" }
        : { status: "missing", revision: revisionHighWater };
    }
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { status: "failed" };
    }
    const object = parsed as Record<string, unknown>;
    const storedRevision =
      object._revision === undefined
        ? null
        : Number.isSafeInteger(object._revision) &&
            (object._revision as number) >= 1
          ? (object._revision as number)
          : NaN;
    if (Number.isNaN(storedRevision)) return { status: "failed" };
    const revision =
      storedRevision === null
        ? revisionHighWater
        : Math.max(storedRevision, revisionHighWater ?? 0);
    const updatedAt =
      Number.isSafeInteger(object._updatedAt) && (object._updatedAt as number) >= 0
        ? (object._updatedAt as number)
        : null;
    return {
      status: "found",
      data: fromDraft(parsed),
      revision,
      updatedAt,
    };
  } catch {
    return { status: "failed" };
  }
}

function parseConflictCandidate(
  raw: string,
  key: string,
  date: string,
  userId: string
): DraftConflictCandidate | null {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return null;
    const value = parsed as Record<string, unknown>;
    const id = value.id;
    const validRevision = (revision: unknown) =>
      revision === null ||
      (Number.isSafeInteger(revision) && (revision as number) >= 1);
    if (
      value.version !== 1 ||
      value.ownerId !== userId ||
      value.date !== date ||
      typeof id !== "string" ||
      !id ||
      draftConflictKey(date, userId, id) !== key ||
      !validRevision(value.expectedRevision) ||
      !validRevision(value.observedRevision) ||
      !Number.isSafeInteger(value.createdAt) ||
      (value.createdAt as number) < 0 ||
      !value.data ||
      typeof value.data !== "object" ||
      Array.isArray(value.data)
    ) return null;
    return {
      id,
      ownerId: userId,
      date,
      expectedRevision: value.expectedRevision as number | null,
      observedRevision: value.observedRevision as number | null,
      createdAt: value.createdAt as number,
      data: fromDraft(value.data),
    };
  } catch {
    return null;
  }
}

function readConflictCandidates(
  storage: DraftStorage,
  date: string,
  userId: string
): { status: "ready"; candidates: DraftConflictCandidate[] } | { status: "failed" } {
  if (
    typeof storage.length !== "number" ||
    !Number.isSafeInteger(storage.length) ||
    typeof storage.key !== "function"
  ) return { status: "failed" };
  const prefix = `${DRAFT_CONFLICT_PREFIX}${date}_${encodeURIComponent(userId)}_`;
  try {
    const candidates: DraftConflictCandidate[] = [];
    for (let index = 0; index < storage.length; index += 1) {
      const key = storage.key(index);
      if (!key?.startsWith(prefix)) continue;
      const raw = storage.getItem(key);
      if (raw === null) return { status: "failed" };
      const candidate = parseConflictCandidate(raw, key, date, userId);
      if (!candidate) return { status: "failed" };
      candidates.push(candidate);
    }
    candidates.sort((left, right) => left.createdAt - right.createdAt || left.id.localeCompare(right.id));
    return { status: "ready", candidates };
  } catch {
    return { status: "failed" };
  }
}

let candidateSequence = 0;
function newConflictCandidateId(): string {
  const randomUUID = globalThis.crypto?.randomUUID;
  if (typeof randomUUID === "function") return randomUUID.call(globalThis.crypto);
  candidateSequence += 1;
  return `${Date.now().toString(36)}-${candidateSequence.toString(36)}-${Math.random().toString(36).slice(2)}`;
}

function persistConflictCandidate(
  storage: DraftStorage,
  date: string,
  userId: string,
  data: CheckInData,
  expectedRevision: number | null,
  current: Extract<DraftReadStatus, { status: "found" | "missing" }>
): { status: "stored"; candidate: DraftConflictCandidate; current: DraftReadStatus } | { status: "failed" } {
  try {
    let id = "";
    let key = "";
    for (let attempt = 0; attempt < 16; attempt += 1) {
      id = newConflictCandidateId();
      key = draftConflictKey(date, userId, id);
      if (storage.getItem(key) === null) break;
      id = "";
    }
    if (!id || !key) return { status: "failed" };
    const raw = JSON.stringify({
      version: 1,
      id,
      ownerId: userId,
      date,
      expectedRevision,
      observedRevision: current.revision ?? null,
      createdAt: Date.now(),
      data: toDraft(data),
    });
    storage.setItem(key, raw);
    const confirmed = storage.getItem(key);
    if (confirmed !== raw) return { status: "failed" };
    const candidate = parseConflictCandidate(confirmed, key, date, userId);
    const currentAfterWrite = parseRead(storage, date, userId);
    if (
      !candidate ||
      currentAfterWrite.status === "failed" ||
      currentAfterWrite.status === "unavailable"
    ) return { status: "failed" };
    return { status: "stored", candidate, current: currentAfterWrite };
  } catch {
    return { status: "failed" };
  }
}

/** Distinguish a real missing draft from a storage/security/parse failure. */
export function readDraftWithStatus(
  storage: DraftStorage | null,
  date: string,
  userId: string
): DraftReadStatus {
  if (!storage) return { status: "unavailable" };
  return parseRead(storage, date, userId);
}

/** Read under the same cross-tab lock used for every draft mutation. */
export async function readDraftWithLock(
  storage: DraftStorage | null,
  date: string,
  userId: string,
  resolveLocks: DraftLockResolver = resolveBrowserDraftLocks
): Promise<DraftReadStatus> {
  const transaction = await withDraftTransaction(
    storage,
    date,
    userId,
    (draftTransaction) => draftTransaction.read(),
    resolveLocks
  );
  return transaction.status === "completed"
    ? transaction.value
    : { status: transaction.status };
}

export async function readDraftConflictSnapshot(
  storage: DraftStorage | null,
  date: string,
  userId: string,
  resolveLocks: DraftLockResolver = resolveBrowserDraftLocks
): Promise<DraftConflictSnapshotRead> {
  const result = await withDraftTransaction(
    storage,
    date,
    userId,
    (transaction) => transaction.readConflicts(),
    resolveLocks
  );
  if (result.status !== "completed") return { status: result.status };
  return result.value;
}

function sameCandidate(left: DraftConflictCandidate, right: DraftConflictCandidate): boolean {
  return (
    left.id === right.id &&
    left.ownerId === right.ownerId &&
    left.date === right.date &&
    left.expectedRevision === right.expectedRevision &&
    left.observedRevision === right.observedRevision &&
    left.createdAt === right.createdAt &&
    sameDraft(left.data, right.data)
  );
}

function sameRead(left: DraftReadStatus, right: DraftReadStatus): boolean {
  if (left.status !== right.status) return false;
  if (left.status === "found" && right.status === "found") {
    return left.revision === right.revision && sameDraft(left.data, right.data);
  }
  if (left.status === "missing" && right.status === "missing") {
    return (left.revision ?? null) === (right.revision ?? null);
  }
  return false;
}

export async function resolveDraftConflictCandidate(options: {
  storage: DraftStorage;
  ownerId: string;
  date: string;
  conflict: DraftConflictCandidate;
  expectedCurrentRevision: number | null;
  choice: DraftCandidateChoice;
  confirmed: boolean;
  resolveLocks?: DraftLockResolver;
}): Promise<DraftCandidateResolution> {
  if (!options.confirmed) return { status: "confirmation_required" };
  if (options.conflict.ownerId !== options.ownerId || options.conflict.date !== options.date) {
    return { status: "stale" };
  }

  const result = await withDraftTransaction(
    options.storage,
    options.date,
    options.ownerId,
    (transaction): DraftCandidateResolution => {
      const snapshot = transaction.readConflicts();
      if (snapshot.status !== "ready") return { status: "storage_failed" };
      const candidate = snapshot.candidates.find((item) => item.id === options.conflict.id);
      if (
        !candidate ||
        !sameCandidate(candidate, options.conflict) ||
        (snapshot.current.revision ?? null) !== options.expectedCurrentRevision
      ) return { status: "stale", snapshot };

      const key = draftConflictKey(options.date, options.ownerId, candidate.id);
      if (options.choice === "current") {
        try {
          const checked = transaction.readConflicts();
          if (
            checked.status !== "ready" ||
            !sameRead(checked.current, snapshot.current) ||
            !checked.candidates.some((item) => sameCandidate(item, candidate))
          ) return { status: "stale", snapshot: checked };
          options.storage.removeItem(key);
          if (options.storage.getItem(key) !== null) {
            return { status: "stale", snapshot: transaction.readConflicts() };
          }
          return {
            status: "resolved",
            choice: "current",
            data: snapshot.current.status === "found" ? snapshot.current.data : null,
            revision: snapshot.current.revision ?? null,
          };
        } catch {
          return { status: "storage_failed" };
        }
      }

      const written = transaction.write(candidate.data, options.expectedCurrentRevision);
      if (written.status === "failed" || written.status === "unavailable") {
        return { status: "storage_failed" };
      }
      if (written.status === "conflict") {
        return { status: "stale", snapshot: transaction.readConflicts() };
      }
      const revision = written.status === "written" ? written.revision : written.revision;
      const verified = transaction.readConflicts();
      if (
        verified.status !== "ready" ||
        (verified.current.revision ?? null) !== revision ||
        (hasContent(candidate.data)
          ? verified.current.status !== "found" || !sameDraft(verified.current.data, candidate.data)
          : verified.current.status !== "missing")
      ) return { status: "stale", snapshot: verified };

      try {
        options.storage.removeItem(key);
        if (options.storage.getItem(key) !== null) {
          return { status: "stale", snapshot: transaction.readConflicts() };
        }
      } catch {
        return { status: "storage_failed" };
      }
      return {
        status: "resolved",
        choice: "candidate",
        data: candidate.data,
        revision,
      };
    },
    options.resolveLocks
  );

  if (result.status !== "completed") {
    return result.status === "unavailable"
      ? { status: "storage_failed" }
      : { status: "storage_failed" };
  }
  return result.value;
}

export function readDraft(
  storage: DraftStorage,
  date: string,
  userId: string
): CheckInData | null {
  const result = readDraftWithStatus(storage, date, userId);
  return result.status === "found" ? result.data : null;
}

/**
 * Execute a synchronous localStorage transaction under the owner/date Web Lock.
 * Do not await network work from `operation`; the lock protects only local
 * storage checks and updates.
 */
export async function withDraftTransaction<T>(
  storage: DraftStorage | null,
  date: string,
  userId: string,
  operation: (transaction: DraftTransaction) => T,
  resolveLocks: DraftLockResolver = resolveBrowserDraftLocks
): Promise<DraftTransactionResult<T>> {
  if (!storage) return { status: "unavailable", reason: "storage" };
  let locks: DraftLockManager | null;
  try {
    locks = resolveLocks();
  } catch {
    return { status: "failed" };
  }
  if (!locks) return { status: "unavailable", reason: "locks" };

  const transaction: DraftTransaction = {
    read: () => parseRead(storage, date, userId),
    readConflicts() {
      const current = parseRead(storage, date, userId);
      if (current.status === "failed" || current.status === "unavailable") {
        return { status: current.status };
      }
      const result = readConflictCandidates(storage, date, userId);
      if (result.status !== "ready") return { status: "failed" };
      return {
        status: "ready",
        ownerId: userId,
        date,
        current,
        candidates: result.candidates,
      };
    },
    write(data, expectedRevision) {
      const current = parseRead(storage, date, userId);
      if (current.status === "failed" || current.status === "unavailable") {
        return { status: current.status };
      }
      const currentRevision = current.revision ?? null;
      if (currentRevision !== expectedRevision) {
        const preserved = persistConflictCandidate(
          storage,
          date,
          userId,
          data,
          expectedRevision,
          current
        );
        return preserved.status === "stored"
          ? { status: "conflict", current: preserved.current, candidate: preserved.candidate }
          : { status: "failed" };
      }
      if (!hasContent(data)) {
        if (current.status === "found") {
          const revision = nextRevision(currentRevision);
          if (revision === null) return { status: "failed" };
          storage.setItem(draftRevisionKey(date, userId), String(revision));
          storage.removeItem(draftKey(date, userId));
          return { status: "cleared", revision };
        }
        return { status: "cleared", revision: currentRevision };
      }
      const previousUpdatedAt =
        current.status === "found" ? current.updatedAt ?? 0 : 0;
      const revision = (currentRevision ?? 0) + 1;
      if (!Number.isSafeInteger(revision)) return { status: "failed" };
      const updatedAt = Math.max(Date.now(), previousUpdatedAt + 1);
      storage.setItem(
        draftKey(date, userId),
        JSON.stringify({ ...toDraft(data), _revision: revision, _updatedAt: updatedAt })
      );
      return { status: "written", revision, updatedAt };
    },
    clear(expectedRevision, expectedData) {
      const current = parseRead(storage, date, userId);
      if (current.status === "failed" || current.status === "unavailable") {
        return { status: current.status };
      }
      const currentRevision = current.revision ?? null;
      if (currentRevision !== expectedRevision) {
        return current.status === "found"
          ? { status: "preserved", current }
          : { status: "conflict", current };
      }
      if (current.status === "missing") {
        return { status: "missing", revision: currentRevision };
      }
      if (
        (expectedData !== undefined && !sameDraft(current.data, expectedData))
      ) {
        return { status: "preserved", current };
      }
      const revision = nextRevision(currentRevision);
      if (revision === null) return { status: "failed" };
      storage.setItem(draftRevisionKey(date, userId), String(revision));
      storage.removeItem(draftKey(date, userId));
      return { status: "cleared", revision };
    },
  };

  try {
    const value = await locks.request(draftLockKey(date, userId), () =>
      operation(transaction)
    );
    return { status: "completed", value };
  } catch {
    return { status: "failed" };
  }
}

function sameDraft(left: CheckInData, right: CheckInData): boolean {
  return JSON.stringify(toDraft(left)) === JSON.stringify(toDraft(right));
}

function nextRevision(current: number | null): number | null {
  const next = (current ?? 0) + 1;
  return Number.isSafeInteger(next) ? next : null;
}

/**
 * Persist only against an observed durable revision. A missing Web Locks API,
 * unreadable draft, revision conflict, or storage error never overwrites data.
 */
export async function writeDraft(
  storage: DraftStorage | null,
  date: string,
  userId: string,
  data: CheckInData,
  expectedRevision: number | null,
  resolveLocks: DraftLockResolver = resolveBrowserDraftLocks
): Promise<DraftWriteResult> {
  const transaction = await withDraftTransaction(
    storage,
    date,
    userId,
    (draftTransaction) => draftTransaction.write(data, expectedRevision),
    resolveLocks
  );
  return transaction.status === "completed"
    ? transaction.value
    : transaction.status === "unavailable"
      ? { status: "unavailable", reason: transaction.reason }
      : { status: transaction.status };
}

/** Clear a draft only if both its revision and (when supplied) contents match. */
export async function clearDraft(
  storage: DraftStorage | null,
  date: string,
  userId: string,
  expectedRevision: number | null,
  expectedData?: CheckInData,
  resolveLocks: DraftLockResolver = resolveBrowserDraftLocks
): Promise<DraftClearResult> {
  const transaction = await withDraftTransaction(
    storage,
    date,
    userId,
    (draftTransaction) => draftTransaction.clear(expectedRevision, expectedData),
    resolveLocks
  );
  return transaction.status === "completed"
    ? transaction.value
    : { status: transaction.status };
}

/**
 * The draft/save arbitration for one mounted screen. The writer remembers the
 * revision observed when its form was loaded; a tab that sees a different
 * revision cannot silently replace or clear it.
 */
export type DraftFlushStatus =
  | "written"
  | "cleared"
  | "skipped"
  | "conflict"
  | "failed"
  | "unavailable";

export type DraftFlushResult =
  | { status: "written"; revision: number; updatedAt: number }
  | { status: "cleared"; revision: number | null }
  | { status: "skipped" }
  | {
      status: "conflict";
      current: DraftReadStatus;
      candidate: DraftConflictCandidate;
    }
  | { status: "failed" }
  | { status: "unavailable"; reason?: "storage" | "locks" };

/**
 * Page-level navigation may proceed only after a durable local result. A
 * conflict counts only when the candidate is verified for this owner/date and
 * the concurrently current slot was successfully read under the same lock.
 */
export function isDraftFlushDurableForNavigation(
  result: DraftFlushResult,
  scope: { ownerId: string; date: string }
): boolean {
  if (
    result.status === "written" ||
    result.status === "cleared" ||
    result.status === "skipped"
  ) return true;
  if (result.status !== "conflict" || !result.candidate) return false;
  return (
    result.candidate.ownerId === scope.ownerId &&
    result.candidate.date === scope.date &&
    (result.current?.status === "found" || result.current?.status === "missing")
  );
}

export type DraftSaveResolution =
  | { status: "cleared"; revision: number }
  | { status: "missing"; revision: number | null }
  | { status: "preserved"; data: CheckInData; revision: number | null }
  | { status: "conflict"; current: DraftReadStatus }
  | { status: "failed" }
  | { status: "unavailable" };

export interface DraftWriter {
  observe(date: string, read: DraftReadStatus): void;
  observeRevision(date: string, revision: number | null | undefined): void;
  revision(date: string): number | null | undefined;
  markUnedited(): void;
  markEdited(): void;
  markSaved(
    date: string,
    expectedRevision: number | null | undefined,
    savedData: CheckInData
  ): Promise<DraftSaveResolution>;
  flush(date: string, data: CheckInData): Promise<boolean>;
  flushWithStatus(date: string, data: CheckInData): Promise<DraftFlushResult>;
}

export function createDraftWriter(
  /** Resolved lazily so the writer is safe to construct during SSR. */
  resolveStorage: () => DraftStorage | null,
  userId: string,
  resolveLocks: DraftLockResolver = resolveBrowserDraftLocks
): DraftWriter {
  let savedDate: string | null = null;
  let writesEnabled = true;
  const knownDates = new Set<string>();
  const revisions = new Map<string, number | null>();

  const flushWithStatus = async (date: string, data: CheckInData): Promise<DraftFlushResult> => {
    if (!writesEnabled || savedDate === date) return { status: "skipped" };
    if (!knownDates.has(date)) return { status: "failed" };
    let storage: DraftStorage | null;
    try {
      storage = resolveStorage();
    } catch {
      return { status: "failed" };
    }
    if (!storage) return { status: "unavailable", reason: "storage" };
    const expectedRevision = revisions.get(date) ?? null;
    const result = await writeDraft(
      storage,
      date,
      userId,
      data,
      expectedRevision,
      resolveLocks
    );
    if (result.status === "written") {
      if (revisions.get(date) === expectedRevision) revisions.set(date, result.revision);
      return { status: "written", revision: result.revision, updatedAt: result.updatedAt };
    }
    if (result.status === "cleared") {
      if (revisions.get(date) === expectedRevision) {
        revisions.set(date, result.revision);
      }
      return { status: "cleared", revision: result.revision };
    }
    if (result.status === "conflict") {
      return {
        status: "conflict",
        current: result.current,
        candidate: result.candidate,
      };
    }
    return result.status === "unavailable"
      ? { status: "unavailable", reason: result.reason }
      : { status: result.status };
  };

  return {
    observe(date, read) {
      if (read.status === "found") {
        knownDates.add(date);
        revisions.set(date, read.revision);
      } else if (read.status === "missing") {
        knownDates.add(date);
        revisions.set(date, read.revision ?? null);
      } else {
        knownDates.delete(date);
        revisions.delete(date);
      }
    },
    observeRevision(date, revision) {
      if (revision === undefined) {
        knownDates.delete(date);
        revisions.delete(date);
      } else {
        knownDates.add(date);
        revisions.set(date, revision);
      }
    },
    revision(date) {
      return knownDates.has(date) ? revisions.get(date) ?? null : undefined;
    },
    markUnedited() {
      writesEnabled = false;
    },
    markEdited() {
      writesEnabled = true;
      savedDate = null;
    },
    async markSaved(date, expectedRevision, savedData) {
      // This synchronous guard also suppresses stale React effect cleanups while
      // the cross-tab lock request is pending.
      savedDate = date;
      if (expectedRevision === undefined) return { status: "failed" };
      let storage: DraftStorage | null;
      try {
        storage = resolveStorage();
      } catch {
        return { status: "failed" };
      }
      if (!storage) return { status: "unavailable" };
      const transaction = await withDraftTransaction(
        storage,
        date,
        userId,
        (draftTransaction) => {
          const current = draftTransaction.read();
          if (current.status === "failed" || current.status === "unavailable") {
            return { status: current.status } as const;
          }
          if (current.status === "missing") {
            return {
              status: "missing",
              revision: current.revision ?? null,
            } as const;
          }
          const clear = draftTransaction.clear(expectedRevision, savedData);
          if (clear.status === "preserved") {
            return {
              status: "preserved",
              data: clear.current.data,
              revision: clear.current.revision,
            } as const;
          }
          return clear;
        },
        resolveLocks
      );
      if (transaction.status !== "completed") {
        return { status: transaction.status };
      }
      const resolution = transaction.value;
      if (
        (resolution.status === "cleared" || resolution.status === "missing") &&
        revisions.get(date) === expectedRevision
      ) {
        revisions.set(date, resolution.revision);
      }
      return resolution;
    },
    async flush(date: string, data: CheckInData) {
      const result = await flushWithStatus(date, data);
      return result.status === "written" || result.status === "cleared";
    },
    flushWithStatus,
  };
}
