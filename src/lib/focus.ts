/**
 * Focus restoration helpers for the shell's sub-screen stack.
 *
 * The interesting part is deliberately pure: `restoreFocus` accepts any
 * object with `isConnected` + `focus`, so it can be unit-tested without a DOM
 * and still guards against an opener that was unmounted while the sub-screen
 * was open.
 */

export interface FocusRestoreTarget {
  isConnected: boolean;
  focus: () => void;
}

/**
 * Return focus to the element that opened the sub-screen.
 *
 * @returns `true` when focus was restored, `false` when the opener is gone
 * (or was never captured), in which case the browser's current focus stays.
 */
export function restoreFocus(
  target: FocusRestoreTarget | null | undefined
): boolean {
  if (!target || !target.isConnected) return false;
  target.focus();
  return true;
}
