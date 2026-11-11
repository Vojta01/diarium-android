/**
 * Cross-screen data invalidation.
 *
 * Main tab panels stay mounted (so switching tabs does not lose a half-written
 * check-in), which means a screen's mount-only fetch would otherwise show stale
 * data after a save on another tab. Mutations broadcast a window event with the
 * kinds of data they touched; screens subscribe to the kinds they read and
 * refetch. Fetches never broadcast, so this cannot loop.
 */

export const DATA_CHANGED_EVENT = "diarium:data-changed";

export const DATA_KINDS = ["entries", "goals", "habits", "templates", "scales", "activities"] as const;
export type DataKind = (typeof DATA_KINDS)[number];

export interface DataChangedDetail {
  kinds: readonly DataKind[];
}

/** True when a screen subscribed to `subscribed` must react to `changed`. */
export function matchesDataChange(
  subscribed: readonly DataKind[],
  changed: readonly DataKind[]
): boolean {
  return changed.some((kind) => subscribed.includes(kind));
}

/** Broadcast that one or more data kinds changed. */
export function notifyDataChanged(...kinds: DataKind[]): void {
  if (typeof window === "undefined") return;
  const detail: DataChangedDetail = { kinds };
  window.dispatchEvent(new CustomEvent<DataChangedDetail>(DATA_CHANGED_EVENT, { detail }));
}

/**
 * Subscribe a screen to data changes. Returns the unsubscribe function; safe to
 * call during SSR (no window) so components can use it unconditionally.
 */
export function subscribeDataChanged(
  subscribed: readonly DataKind[],
  refresh: () => void
): () => void {
  if (typeof window === "undefined") return () => {};

  const handler = (event: Event) => {
    const detail = (event as CustomEvent<DataChangedDetail>).detail;
    // A malformed/foreign event with no kinds is ignored rather than forcing
    // every screen to refetch.
    if (!detail || !Array.isArray(detail.kinds)) return;
    if (matchesDataChange(subscribed, detail.kinds)) refresh();
  };

  window.addEventListener(DATA_CHANGED_EVENT, handler);
  return () => window.removeEventListener(DATA_CHANGED_EVENT, handler);
}
