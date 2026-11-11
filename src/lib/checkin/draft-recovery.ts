import type { CheckInData } from "../types.ts";
import {
  clearDraft,
  readDraftWithLock,
  resolveBrowserDraftLocks,
  type DraftLockResolver,
  type DraftStorage,
} from "./draft.ts";
import { emptyCheckIn, hasContent } from "./model.ts";
import { CheckInError } from "./errors.ts";
import type { LoadResult } from "./load.ts";

export interface DraftConflict<T> {
  ownerId: string;
  date: string;
  draftRevision: number | null;
  localDraft: CheckInData;
  cloudEntry: T | null;
  cloudData: CheckInData;
  /** True after CAS so local means this full, re-based form snapshot. */
  localIsFullSnapshot?: boolean;
  /** The response may have been lost; local retries the same immutable request. */
  ambiguous?: boolean;
}

export type OnlineLoadRecovery<T> =
  | { status: "failed"; error: CheckInError }
  | { status: "empty"; data: CheckInData; draftRevision: number | null }
  | { status: "draft"; data: CheckInData; draftRevision: number | null }
  | { status: "entry"; entry: T; data: CheckInData; draftRevision: number | null }
  | { status: "conflict"; conflict: DraftConflict<T> };

/**
 * Decide how a verified cloud read and its owner/date local draft coexist.
 * Reading a cloud row must never imply that a separate local draft was saved.
 */
export async function recoverOnlineLoad<T>(
  outcome: LoadResult<T>,
  storage: DraftStorage | null,
  date: string,
  ownerId: string,
  mapEntry: (entry: T) => CheckInData,
  resolveLocks: DraftLockResolver = resolveBrowserDraftLocks
): Promise<OnlineLoadRecovery<T>> {
  if (outcome.status === "failed") {
    return { status: "failed", error: outcome.error };
  }

  // An empty or unverified owner must not read/reassign an ownerless legacy key.
  // A storage failure is not equivalent to "no draft": never render a writable
  // cloud form over local data we could not inspect.
  const draftRead = ownerId
    ? await readDraftWithLock(storage, date, ownerId, resolveLocks)
    : { status: "missing" as const };
  if (draftRead.status === "failed" || draftRead.status === "unavailable") {
    return {
      status: "failed",
      error: new CheckInError("load_failed", "checkin.error_draft_storage", 507),
    };
  }
  const localDraft = draftRead.status === "found" ? draftRead.data : null;
  const draftRevision =
    draftRead.status === "found" || draftRead.status === "missing"
      ? draftRead.revision ?? null
      : null;
  const validDraft = localDraft && hasContent(localDraft) ? localDraft : null;

  if (outcome.status === "entry") {
    const cloudData = mapEntry(outcome.entry);
    if (validDraft) {
      return {
        status: "conflict",
        conflict: {
          ownerId,
          date,
          draftRevision,
          localDraft: validDraft,
          cloudEntry: outcome.entry,
          cloudData,
        },
      };
    }
    return { status: "entry", entry: outcome.entry, data: cloudData, draftRevision };
  }

  return validDraft
    ? { status: "draft", data: validDraft, draftRevision }
    : { status: "empty", data: emptyCheckIn(), draftRevision };
}

export type DraftConflictChoice = "local" | "cloud";

export type DraftConflictResolution<T> =
  | { status: "confirmation_required" }
  | { status: "stale" }
  | { status: "storage_failed" }
  | { status: "local"; entry: T | null; data: CheckInData }
  | { status: "cloud"; entry: T | null; data: CheckInData; revision: number | null };

/** Apply only a confirmed choice, and never mutate a different owner/date draft. */
export async function resolveDraftConflict<T>(options: {
  storage: DraftStorage;
  conflict: DraftConflict<T>;
  choice: DraftConflictChoice;
  confirmed: boolean;
  activeOwnerId: string;
  activeDate: string;
  resolveLocks?: import("./draft.ts").DraftLockResolver;
}): Promise<DraftConflictResolution<T>> {
  if (!options.confirmed) return { status: "confirmation_required" };
  if (
    options.conflict.ownerId !== options.activeOwnerId ||
    options.conflict.date !== options.activeDate
  ) {
    return { status: "stale" };
  }

  if (options.choice === "local") {
    return {
      status: "local",
      entry: options.conflict.cloudEntry,
      data: options.conflict.localIsFullSnapshot
        ? copyCheckInData(options.conflict.localDraft)
        : mergeOfflineDraftOntoCloud(
            options.conflict.localDraft,
            options.conflict.cloudData
          ),
    };
  }

  const cleared = await clearDraft(
    options.storage,
    options.activeDate,
    options.activeOwnerId,
    options.conflict.draftRevision,
    options.conflict.localDraft,
    options.resolveLocks
  );
  if (cleared.status === "failed" || cleared.status === "unavailable") {
    return { status: "storage_failed" };
  }
  if (cleared.status === "preserved" || cleared.status === "conflict") {
    return { status: "stale" };
  }
  return {
    status: "cloud",
    entry: options.conflict.cloudEntry,
    data: options.conflict.cloudData,
    revision: cleared.revision,
  };
}

/**
 * Rebase a locally selected form from its old server baseline onto the latest
 * row after an atomic CAS conflict. Unchanged fields take the newest cloud value;
 * edits made locally (including explicit scale zero/photo removal) stay local.
 */
export function rebaseCheckInData(
  local: CheckInData,
  base: CheckInData,
  remote: CheckInData
): CheckInData {
  const choose = <T>(localValue: T, baseValue: T, remoteValue: T): T =>
    sameValue(localValue, baseValue) ? remoteValue : localValue;

  return {
    ...copyCheckInData(remote),
    mood: choose(local.mood, base.mood, remote.mood),
    moodEmoji: choose(local.moodEmoji, base.moodEmoji, remote.moodEmoji),
    sleepQuality: choose(local.sleepQuality, base.sleepQuality, remote.sleepQuality),
    stress: choose(local.stress, base.stress, remote.stress),
    activities: [...choose(local.activities, base.activities, remote.activities)],
    habits: rebaseMap(local.habits, base.habits, remote.habits),
    gratitude: [...choose(local.gratitude, base.gratitude, remote.gratitude)],
    note: choose(local.note, base.note, remote.note),
    weather: [...choose(local.weather, base.weather, remote.weather)],
    photoPath: choose(local.photoPath, base.photoPath, remote.photoPath),
    photoCleared: choose(local.photoCleared, base.photoCleared, remote.photoCleared),
    scaleValues: rebaseMap(local.scaleValues, base.scaleValues, remote.scaleValues),
  };
}

function sameValue(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function rebaseMap<T extends boolean | number>(
  local: Record<string, T>,
  base: Record<string, T>,
  remote: Record<string, T>
): Record<string, T> {
  const result: Record<string, T> = { ...remote };
  const keys = new Set([...Object.keys(local), ...Object.keys(base)]);
  for (const key of keys) {
    const localHas = Object.prototype.hasOwnProperty.call(local, key);
    const baseHas = Object.prototype.hasOwnProperty.call(base, key);
    const unchanged = localHas === baseHas && (!localHas || local[key] === base[key]);
    if (unchanged) continue;
    if (localHas) result[key] = local[key];
    else delete result[key];
  }
  return result;
}

function copyCheckInData(data: CheckInData): CheckInData {
  return {
    ...data,
    activities: [...data.activities],
    habits: { ...data.habits },
    gratitude: [...data.gratitude],
    weather: [...data.weather],
    scaleValues: { ...data.scaleValues },
    phoneTopApps: data.phoneTopApps.map((app) => ({ ...app })),
  };
}

/** Local-only editing owns these fields; retain cloud-only values from the row. */
function mergeOfflineDraftOntoCloud(
  localDraft: CheckInData,
  cloudData: CheckInData
): CheckInData {
  return {
    ...cloudData,
    mood: localDraft.mood,
    moodEmoji: localDraft.moodEmoji,
    sleepQuality: localDraft.sleepQuality,
    stress: localDraft.stress,
    gratitude: [...localDraft.gratitude],
    note: localDraft.note,
    weather: [...localDraft.weather],
    // Missing draft photo data means preserve cloud state; only a durable staged
    // URL or explicit clear replaces it. Missing scale keys likewise preserve
    // cloud values while zero remains an explicit deletion intent.
    photoPath: localDraft.photoPath ?? (localDraft.photoCleared ? null : cloudData.photoPath),
    photoCleared: localDraft.photoCleared,
    scaleValues: { ...cloudData.scaleValues, ...localDraft.scaleValues },
  };
}
