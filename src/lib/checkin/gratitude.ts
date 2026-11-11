/**
 * Gratitude is a three-slot contract.
 *
 * Older rows (and drafts) can contain more than three values. The form edits
 * the first three and keeps any legacy extras untouched on save instead of
 * silently deleting them.
 */
export const GRATITUDE_SLOTS = 3;

/** Number of editable rows; legacy extras stay stored but are not rendered. */
export function gratitudeRowCount(): number {
  return GRATITUDE_SLOTS;
}

/** Update one of the three canonical slots, preserving legacy extras. */
export function setGratitudeSlot(
  values: readonly string[],
  index: number,
  value: string
): string[] {
  if (index < 0 || index >= GRATITUDE_SLOTS) return [...values];
  const next = [...values];
  while (next.length < GRATITUDE_SLOTS) next.push("");
  next[index] = value;
  return next;
}
