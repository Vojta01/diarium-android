/**
 * Pure accessibility helpers for the shared modal primitive.
 *
 * The DOM-facing parts (querying focusable nodes, restoring focus) live in
 * `components/ui/Modal.tsx`; the decisions that are easy to get wrong are kept
 * here so they can be unit-tested without a browser.
 */

/** Selector for elements that can receive keyboard focus inside a dialog. */
export const FOCUSABLE_SELECTOR = [
  "a[href]",
  "area[href]",
  "button:not([disabled])",
  "input:not([disabled]):not([type='hidden'])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "iframe",
  "[contenteditable='true']",
  "[tabindex]:not([tabindex='-1'])",
].join(",");

/**
 * Decide where Tab / Shift+Tab should move focus inside a dialog.
 *
 * Returns the index to focus when the browser's default would leave the
 * dialog, or `null` when the default behaviour is already correct. A dialog
 * with no focusable children keeps focus on the panel itself.
 */
export function resolveTabStop(
  count: number,
  activeIndex: number,
  shiftKey: boolean
): number | null {
  if (count <= 0) return null;
  if (activeIndex < 0) return shiftKey ? count - 1 : 0;
  if (shiftKey && activeIndex === 0) return count - 1;
  if (!shiftKey && activeIndex === count - 1) return 0;
  return null;
}

/**
 * Whether Escape belongs to a dialog rather than the sub-screen behind it.
 *
 * A sub-screen may only close on Escape when the event did not originate in a
 * dialog and no modal dialog is currently open. Checking the real
 * `role="dialog"` node (not a lookalike class name) keeps the two layers from
 * closing at once.
 */
export function escapeBelongsToDialog(
  targetInsideDialog: boolean,
  openModalCount: number
): boolean {
  return targetInsideDialog || openModalCount > 0;
}
