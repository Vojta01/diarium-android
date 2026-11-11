/**
 * Pure helpers for the three read-only check-in sections.
 *
 * Goals, screen time and the AI reflection are *reports*: the check-in shows
 * what the data layer already knows and never edits goals, localStorage or the
 * phone/AI columns. Keeping the decisions here (instead of inside JSX) makes
 * the read-only contract and the "null vs zero" rules unit-testable.
 */
import {
  computeGoalProgressList,
  type ActivityEntry,
  type GoalDefinition,
  type GoalProgressData,
} from "../goal-progress.ts";
import type { CheckInData, CheckInTopApp } from "../types.ts";
import { fromEntry } from "./model.ts";
import { rebaseCheckInData } from "./draft-recovery.ts";
import {
  baselineFromEntry,
  type EntrySaveBaseline,
} from "./save-v2.ts";

/** Goal progress for the calendar period that contains `date`. */
export function checkInGoalRows<G extends GoalDefinition>(
  goals: readonly G[],
  entries: readonly ActivityEntry[],
  date: string
): GoalProgressData<G>[] {
  return computeGoalProgressList(goals, entries, date);
}

export interface ScreenTimeSnapshot {
  /** Seconds, exactly as stored. `null` means "not synced", `0` is a real zero. */
  screenTime: number | null;
  /** Unlocks, exactly as stored. `null` means "not synced", `0` is a real zero. */
  unlocks: number | null;
  /** The stored top-apps snapshot, already capped to the five rows the UI shows. */
  topApps: CheckInTopApp[];
  hasAny: boolean;
}

export function screenTimeSnapshot(
  data: Pick<CheckInData, "phoneScreenTime" | "phoneUnlocks" | "phoneTopApps">
): ScreenTimeSnapshot {
  const topApps = data.phoneTopApps.slice(0, 5);
  return {
    screenTime: data.phoneScreenTime,
    unlocks: data.phoneUnlocks,
    topApps,
    hasAny:
      data.phoneScreenTime !== null ||
      data.phoneUnlocks !== null ||
      topApps.length > 0,
  };
}

/**
 * The identifiers-only body sent to `/api/ai/reflect`.
 *
 * The route reads the saved day (and its history) from Supabase; the check-in
 * must never send mood/note/activities or any other form field. `userName` is
 * session metadata used for the greeting, not a form field.
 */
export interface ReflectionRequest {
  user_id: string;
  date: string;
  lang: "cs" | "en";
  userName?: string;
}

export function buildReflectionRequest(input: {
  userId: string;
  date: string;
  lang: "cs" | "en";
  userName?: string | null;
}): ReflectionRequest {
  const request: ReflectionRequest = {
    user_id: input.userId,
    date: input.date,
    lang: input.lang,
  };
  const userName = input.userName?.trim();
  if (userName) request.userName = userName;
  return request;
}

/** The partial row written after generation: `date` + `ai_reflection`, nothing else. */
export interface ReflectionPatch {
  date: string;
  ai_reflection: string;
}

export function reflectionPatch(
  date: string,
  text: string
): ReflectionPatch | null {
  const trimmed = text.trim();
  return trimmed ? { date, ai_reflection: trimmed } : null;
}

/**
 * Reconcile the form against the full row returned by the reflection patch.
 * The reflection endpoint advances the row revision; adopting its current row
 * prevents our own write from looking like a stale CAS conflict. If another
 * writer changed form fields meanwhile, untouched values adopt that server row
 * while post-request local edits remain local.
 */
export function reconcileReflectionEntry(input: {
  ownerId: string;
  date: string;
  currentData: CheckInData;
  previousBaseline: CheckInData;
  updatedEntry: Record<string, unknown>;
}): {
  data: CheckInData;
  serverData: CheckInData;
  baseline: EntrySaveBaseline;
} {
  if (
    input.updatedEntry.user_id !== input.ownerId ||
    input.updatedEntry.date !== input.date
  ) {
    throw new TypeError("Reflection patch returned a row outside its owner/date scope");
  }
  const baseline = baselineFromEntry(input.updatedEntry);
  const serverData = fromEntry(input.updatedEntry);
  const data = rebaseCheckInData(
    input.currentData,
    input.previousBaseline,
    serverData
  );
  return { data, serverData, baseline };
}

export type ReflectionMode =
  | "save-first"
  | "unsaved"
  | "generate"
  | "regenerate";

/**
 * What the reflection section may offer right now.
 *
 * - `save-first`: the day has no stored row yet, so there is nothing to reflect
 *   on and no patch target.
 * - `unsaved`: the day is stored but the form has unsaved edits; generating now
 *   would use the older saved version, so the section warns instead of acting.
 * - `generate` / `regenerate`: the saved row is the source of truth and the
 *   request can run without touching any form field.
 */
export function reflectionMode(input: {
  canGenerate: boolean;
  dirty: boolean;
  hasReflection: boolean;
}): ReflectionMode {
  if (input.dirty && input.canGenerate) return "unsaved";
  if (!input.canGenerate) return "save-first";
  if (input.hasReflection) return "regenerate";
  return "generate";
}

export function reflectionCanRequest(mode: ReflectionMode): boolean {
  return mode === "generate" || mode === "regenerate";
}
