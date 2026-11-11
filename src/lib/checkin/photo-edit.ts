/**
 * Photo replacement is a two-phase edit.
 *
 * Picking a photo uploads a new object; the entry row is untouched until Save.
 * Abandoning an edit removes only local selection/tracking. Because this browser
 * cannot prove global references or ownership, no client path deletes objects.
 */
import {
  isStoredPhotoUrl,
  stagedPhotoStoragePathFromUrl,
} from "./photo.ts";

export interface PhotoEditState {
  /** The URL currently stored on the row, or null. */
  stored: string | null;
  /** The URL staged by the picker, or null. */
  staged: string | null;
  /** The user removed the photo in this edit. */
  cleared: boolean;
}

export interface PhotoSaveIntent {
  photo_path?: string;
  clear_photo?: boolean;
}

export function beginPhotoEdit(stored: string | null): PhotoEditState {
  return {
    stored: isStoredPhotoUrl(stored) ? stored.trim() : null,
    staged: null,
    cleared: false,
  };
}

export function stagePhoto(state: PhotoEditState, url: string): PhotoEditState {
  if (!isStoredPhotoUrl(url)) return state;
  return { ...state, staged: url.trim(), cleared: false };
}

export function clearPhoto(state: PhotoEditState): PhotoEditState {
  return { ...state, staged: null, cleared: true };
}

/**
 * What Save should send. Physical object cleanup is deliberately not part of
 * this client intent: removing a row reference does not prove the blob is unused.
 */
export function photoSaveIntent(state: PhotoEditState): PhotoSaveIntent {
  const stored = state.stored;
  const staged = state.staged;

  if (staged) {
    return { photo_path: staged };
  }

  if (state.cleared && stored) {
    return { clear_photo: true };
  }

  return {};
}

/**
 * Abandoning an edit changes only local selection. Both remote objects are
 * retained; the staged URL is exposed so callers can explain the orphan tradeoff.
 */
export function photoAbandonIntent(state: PhotoEditState): {
  retainedStaged: string | null;
  retainedStored: string | null;
} {
  return { retainedStaged: state.staged, retainedStored: state.stored };
}

interface StagedUpload {
  ownerId: string;
  date: string;
  url: string;
  /** A durable local draft or pending request references this staged object. */
  persisted: boolean;
  committed: boolean;
  /** The server may still commit after the client lost the response. */
  uncertain: boolean;
  saves: number;
  /** A local selection was discarded while a save lease was still active. */
  discardRequested: boolean;
}

export interface PhotoSaveLease {
  /** Call exactly once when the save settles; unknown keeps it tracked. */
  finish(outcome: boolean | "unknown"): Promise<void>;
}

/**
 * Tracks staged uploads in one mounted check-in. This browser has no globally
 * authoritative reference/ownership view, so discard and abandonment remove
 * only in-memory tracking; remote storage objects are never deleted here.
 */
export function createPhotoEditLifecycle(
  expectedOrigin?: string
) {
  const staged = new Map<string, StagedUpload>();
  const keyFor = (ownerId: string, url: string) => `${ownerId}\u0000${url}`;

  const forgetTrackingAfterDiscard = (item: StagedUpload): Promise<void> => {
    if (
      !item.discardRequested ||
      item.committed ||
      item.uncertain ||
      item.saves > 0
    ) {
      return Promise.resolve();
    }

    // This is a local bookkeeping operation only. Do not call Storage.remove:
    // drafts or pending requests on other dates, accounts, tabs, or devices
    // may still reference this URL, including from an older client bundle.
    staged.delete(keyFor(item.ownerId, item.url));
    return Promise.resolve();
  };

  return {
    /** Register only a well-formed unique upload under the current owner/day. */
    track(ownerId: string, date: string, url: string): boolean {
      if (!ownerId.trim()) return false;
      if (
        !stagedPhotoStoragePathFromUrl(url, ownerId, date, expectedOrigin)
      ) {
        return false;
      }
      const key = keyFor(ownerId, url);
      const existing = staged.get(key);
      if (existing) {
        if (existing.persisted) {
          existing.discardRequested = false;
        }
        return !existing.discardRequested;
      }
      staged.set(key, {
        ownerId,
        date,
        url,
        persisted: false,
        committed: false,
        uncertain: false,
        saves: 0,
        discardRequested: false,
      });
      return true;
    },

    /** Protect a staged object while a durable local snapshot references it. */
    preserve(ownerId: string, date: string, url: string): boolean {
      const item = staged.get(keyFor(ownerId, url));
      if (!item || item.date !== date || item.committed) {
        return false;
      }
      item.persisted = true;
      item.discardRequested = false;
      return true;
    },

    /** Drop local tracking for one superseded/cancelled upload. */
    discard(ownerId: string, date: string, url: string): Promise<void> {
      const item = staged.get(keyFor(ownerId, url));
      if (!item || item.date !== date || item.committed) {
        return Promise.resolve();
      }
      item.discardRequested = true;
      return forgetTrackingAfterDiscard(item);
    },

    /** Hold tracking during POST so its result remains attributable. */
    beginSave(
      ownerId: string,
      date: string,
      url: string | null
    ): PhotoSaveLease | null {
      if (!url) return null;
      const key = keyFor(ownerId, url);
      const item = staged.get(key);
      if (
        !item ||
        item.date !== date ||
        item.discardRequested ||
        item.committed
      ) {
        return null;
      }

      item.saves += 1;
      let settled = false;
      return {
        finish: (outcome) => {
          if (settled) return Promise.resolve();
          settled = true;
          item.saves -= 1;
          if (outcome === "unknown") {
            item.uncertain = true;
            item.discardRequested = false;
          }
          if (outcome === true) {
            item.committed = true;
            staged.delete(key);
          }
          return forgetTrackingAfterDiscard(item);
        },
      };
    },

    /** Abandon every staged upload associated with a calendar date. */
    abandonDate(date: string): Promise<void> {
      const pending: Promise<void>[] = [];
      for (const item of staged.values()) {
        if (
          item.date !== date ||
          item.committed ||
          item.uncertain ||
          (item.persisted && !item.discardRequested)
        ) continue;
        item.discardRequested = true;
        pending.push(forgetTrackingAfterDiscard(item));
      }
      return Promise.all(pending).then(() => undefined);
    },

    /** Abandon all outstanding uploads when the check-in is removed. */
    abandonAll(): Promise<void> {
      const pending: Promise<void>[] = [];
      for (const item of staged.values()) {
        if (
          item.committed ||
          item.uncertain ||
          (item.persisted && !item.discardRequested)
        ) continue;
        item.discardRequested = true;
        pending.push(forgetTrackingAfterDiscard(item));
      }
      return Promise.all(pending).then(() => undefined);
    },
  };
}

export async function persistDraftBeforePhotoTrackingReset(options: {
  persist: () => unknown | Promise<unknown>;
  ownerId: string;
  date: string;
  photoUrl: string | null;
  preserve: (ownerId: string, date: string, url: string) => unknown;
  abandon: () => Promise<void>;
}): Promise<void> {
  if (options.photoUrl) {
    try {
      options.preserve(options.ownerId, options.date, options.photoUrl);
    } catch {
      // Local persistence can fail; remote cleanup is still unavailable.
    }
  }
  try {
    // Wait for the locked revision write before dropping local tracking.
    await options.persist();
  } catch {
    // Even if storage fails, keep the staged object rather than lose its URL.
  }
  await options.abandon();
}

/**
 * Resolve an upload after the async request completes. If the picker has been
 * cancelled, its date changed, or its component unmounted, drop the local
 * selection instead of attaching it. The remote upload is retained.
 */
export async function finishPhotoUpload(options: {
  upload: () => Promise<string>;
  isCurrent: () => boolean;
  onStage: (url: string) => void;
}): Promise<"staged" | "discarded"> {
  const url = await options.upload();
  if (!isStoredPhotoUrl(url)) throw new Error("Photo upload returned an invalid URL");

  if (!options.isCurrent()) {
    return "discarded";
  }

  options.onStage(url);
  return "staged";
}
