/**
 * One guarded save path for the check-in screen.
 *
 * The guard exists because the old component had three save paths (header,
 * fixed footer, `beforeunload`) and none of them prevented a second POST while
 * the first was in flight. This module is pure and dependency-injected so the
 * failure/no-entry/double-save rules are unit-tested without a DOM.
 */
import type { CheckInPayload } from "../types.ts";
import { toCheckInError, type CheckInError } from "./errors.ts";

export interface SaveGate {
  /** A failed load must block saving: an empty form would overwrite the day. */
  loadFailed: boolean;
  photoUploading: boolean;
  hasContent: boolean;
}

export interface InFlightRef {
  current: boolean;
}

export type SaveResult<T> =
  | { status: "blocked" }
  | { status: "busy" }
  | { status: "saved"; entry: T }
  | { status: "failed"; error: CheckInError };

export function canSave(gate: SaveGate): boolean {
  return !gate.loadFailed && !gate.photoUploading && gate.hasContent;
}

export function canGenerateReflection(gate: SaveGate): boolean {
  return !gate.loadFailed && gate.hasContent;
}

export async function saveCheckIn<T>(options: {
  gate: SaveGate;
  inFlight: InFlightRef;
  payload: CheckInPayload;
  post: (payload: CheckInPayload) => Promise<T>;
}): Promise<SaveResult<T>> {
  if (!canSave(options.gate)) return { status: "blocked" };
  // The ref, not React state, is the race guard: two clicks in the same tick
  // both see `saving === false`, but only the first sees `inFlight === false`.
  if (options.inFlight.current) return { status: "busy" };

  options.inFlight.current = true;
  try {
    const entry = await options.post(options.payload);
    return { status: "saved", entry };
  } catch (error) {
    return { status: "failed", error: toCheckInError(error) };
  } finally {
    options.inFlight.current = false;
  }
}
