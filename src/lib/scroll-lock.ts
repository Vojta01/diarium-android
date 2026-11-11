/**
 * Body scroll lock shared by every open modal.
 *
 * Dialogs can stack (a photo viewer over a check-in, a picker over a goal
 * editor), so a plain `body.style.overflow = "hidden"` on open would unlock the
 * page as soon as the first one closes. Counting owners keeps the page frozen
 * until the last dialog unmounts, then restores the exact inline value that was
 * there before.
 */

export interface ScrollLockTarget {
  style: { overflow: string };
}

let activeLocks = 0;
let previousOverflow = "";

/** Locks the target and returns an idempotent release function. */
export function lockBodyScroll(target: ScrollLockTarget): () => void {
  if (activeLocks === 0) {
    previousOverflow = target.style.overflow;
    target.style.overflow = "hidden";
  }
  activeLocks += 1;

  let released = false;
  return () => {
    if (released) return;
    released = true;
    activeLocks -= 1;
    if (activeLocks === 0) target.style.overflow = previousOverflow;
  };
}
