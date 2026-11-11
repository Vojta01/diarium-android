/**
 * Additive default-scale seeding.
 *
 * The old `seedDefaultScales` deleted every scale whose name or range did not
 * match the two built-in defaults. That destroyed custom scales and their
 * `scale_entries` history. Seeding is now purely additive: existing rows are
 * never updated or deleted, and a failed read rejects so the caller can show a
 * retry instead of creating defaults into an unknown account state.
 */
import type { Scale } from "../scales.ts";

export interface DefaultScaleDef {
  name: string;
  emoji: string;
  min_value: number;
  max_value: number;
  color: string;
  sort_order: number;
}

/** The Daylio-inspired defaults created only for an account without scales. */
export const DEFAULT_SCALE_DEFS: readonly DefaultScaleDef[] = [
  {
    name: "Energie",
    emoji: "⚡",
    min_value: 1,
    max_value: 5,
    color: "#eab308",
    sort_order: 0,
  },
  {
    name: "Produktivita",
    emoji: "💪",
    min_value: 1,
    max_value: 5,
    color: "#22c55e",
    sort_order: 1,
  },
];

/**
 * Defaults the account is missing. Matching is by name only: a custom scale
 * with a drifted range keeps its range and is never "reset".
 */
export function missingDefaultScales(
  existing: readonly Pick<Scale, "name">[]
): DefaultScaleDef[] {
  const names = new Set(existing.map((scale) => scale.name.trim()));
  return DEFAULT_SCALE_DEFS.filter((def) => !names.has(def.name));
}

/** Stable ids for the reserved UI order; kept out of the React layer. */
export function sortScales(scales: readonly Scale[]): Scale[] {
  return [...scales].sort(
    (a, b) => a.sort_order - b.sort_order || a.name.localeCompare(b.name)
  );
}
