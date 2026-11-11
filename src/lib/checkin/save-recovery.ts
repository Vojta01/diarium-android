import type { CheckInData } from "../types.ts";
import { fromDraft, toDraft } from "./model.ts";
import type { DraftReadStatus } from "./draft.ts";
import type { PendingSaveRequest } from "./pending-save.ts";

export type PendingSaveRecovery =
  | { status: "ready"; data: CheckInData }
  | { status: "blocked" };

/**
 * Restore the immutable request's form snapshot unless a later durable draft
 * exists. An unreadable draft is never treated as missing: replay is blocked so
 * an unknown newer snapshot cannot be overwritten or hidden.
 */
export function restorePendingSaveData(
  request: PendingSaveRequest,
  draftRead: DraftReadStatus
): PendingSaveRecovery {
  if (draftRead.status === "failed" || draftRead.status === "unavailable") {
    return { status: "blocked" };
  }
  const pendingData = fromDraft(request.draftSnapshot);
  if (draftRead.status === "missing") return { status: "ready", data: pendingData };

  const pendingSnapshot = JSON.stringify(toDraft(pendingData));
  const localSnapshot = JSON.stringify(toDraft(draftRead.data));
  if (pendingSnapshot === localSnapshot) {
    return { status: "ready", data: pendingData };
  }

  if (request.draftRevision !== undefined) {
    const expectedRevision = request.draftRevision ?? 0;
    if (draftRead.revision !== null && draftRead.revision > expectedRevision) {
      return { status: "ready", data: draftRead.data };
    }
    if (draftRead.revision !== request.draftRevision) {
      return { status: "blocked" };
    }
    // The revision is exactly the one the pending request observed but its
    // contents differ. This indicates a broken/legacy writer; do not replay.
    return { status: "blocked" };
  }

  // Older pending records have no revision. Preserve a differing durable draft
  // when it is at/after request creation; if ordering is ambiguous, fail closed.
  if (request.createdAt === undefined) {
    return { status: "ready", data: draftRead.data };
  }
  if (draftRead.updatedAt !== null && draftRead.updatedAt >= request.createdAt) {
    return { status: "ready", data: draftRead.data };
  }
  if (draftRead.updatedAt !== null && draftRead.updatedAt < request.createdAt) {
    return { status: "ready", data: pendingData };
  }
  return { status: "blocked" };
}

/** True only when the visible form still exactly matches the immutable request. */
export function pendingSaveMatchesForm(
  data: CheckInData,
  request: PendingSaveRequest
): boolean {
  return JSON.stringify(toDraft(data)) === JSON.stringify(request.draftSnapshot);
}
