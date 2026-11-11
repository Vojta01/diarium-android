import type { HabitDef } from "../supabase/db.ts";

export type HabitCatalogRow = HabitDef & { is_active?: boolean };
export type UserHabitCatalogRow = Partial<HabitDef> & {
  key: string;
  is_active?: boolean;
};

/**
 * Resolve the habits that are currently visible to this account. A user row
 * with `is_active: false` is a soft-hide override for a default, never a delete;
 * active user rows customize defaults or add custom habits. Historical entry
 * JSON is deliberately not changed by this catalogue projection.
 */
export function mergeActiveHabitCatalog(
  defaults: readonly HabitCatalogRow[],
  userRows: readonly UserHabitCatalogRow[]
): HabitDef[] {
  const byKey = new Map(userRows.map((row) => [row.key, row]));
  const defaultKeys = new Set(defaults.map((habit) => habit.key));

  const visibleDefaults: HabitDef[] = defaults
    .filter((habit) => habit.is_active !== false)
    .flatMap((habit) => {
      const override = byKey.get(habit.key);
      if (override?.is_active === false) return [];

      return [{
        ...habit,
        label: override?.is_active === true && override.label ? override.label : habit.label,
        icon: override?.is_active === true && override.icon ? override.icon : habit.icon,
        is_negative:
          override?.is_active === true && typeof override.is_negative === "boolean"
            ? override.is_negative
            : habit.is_negative,
        source: "default" as const,
      }];
    });

  const visibleCustoms: HabitDef[] = userRows
    .filter((habit) => habit.is_active === true && !defaultKeys.has(habit.key))
    .map((habit) => ({
      key: habit.key,
      label: habit.label || habit.key,
      labelEn: habit.labelEn,
      icon: habit.icon || "✅",
      category: habit.category || "vlastní",
      color: habit.color || "#6366f1",
      is_negative: typeof habit.is_negative === "boolean" ? habit.is_negative : false,
      source: "custom" as const,
    }));

  return [...visibleDefaults, ...visibleCustoms];
}

export interface AnsweredHabitEntry {
  key: string;
  label: string;
  value: boolean;
}

/**
 * Project one historical entry through the currently visible catalogue. Missing
 * definitions/keys are excluded, while explicit false remains an answer. This
 * is read-only and leaves the historical `habits` map intact.
 */
export function answeredHabitEntries(
  values: Readonly<Record<string, boolean>> | null | undefined,
  definitions: readonly HabitDef[] | null
): AnsweredHabitEntry[] {
  if (!values || !definitions) return [];
  const visible = new Map(definitions.map((habit) => [habit.key, habit]));
  return Object.entries(values)
    .filter(([key, value]) => visible.has(key) && typeof value === "boolean")
    .map(([key, value]) => ({ key, label: visible.get(key)!.label, value }));
}
