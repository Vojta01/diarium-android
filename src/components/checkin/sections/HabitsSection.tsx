"use client";

import { useState } from "react";

import { HabitEditDialog } from "@/components/HabitEditDialog";
import type { HabitDef } from "@/lib/supabase/db";
import { useTranslation } from "@/lib/i18n";
import { definitionLabel } from "@/lib/checkin/options";

import { CheckInSection } from "../CheckInSection";
import { Toggle } from "../primitives/Chip";

/**
 * Návyky — one switch per habit, plus add/edit/remove through the shared
 * dialog. Only keys the user has touched live in `values`; an untouched habit
 * is "not answered", not `false` (Android's toggleHabit contract).
 */
export function HabitsSection({
  habits,
  values,
  error,
  onToggle,
  onAdd,
  onUpdate,
  onRemove,
}: {
  habits: readonly HabitDef[];
  values: Record<string, boolean>;
  error: string | null;
  onToggle: (key: string, checked: boolean) => void;
  onAdd: (input: {
    name: string;
    icon: string;
    isNegative: boolean;
  }) => Promise<string | null>;
  onUpdate: (
    key: string,
    input: { name: string; icon: string; isNegative: boolean }
  ) => Promise<string | null>;
  onRemove: (key: string) => string | null | Promise<string | null>;
}) {
  const { t, lang } = useTranslation();
  const [dialog, setDialog] = useState<{ open: boolean; habit: HabitDef | null }>(
    { open: false, habit: null }
  );

  const duplicateName = (name: string, exceptKey?: string) => {
    const normalized = name.trim().toLowerCase();
    return habits.some(
      (habit) =>
        habit.key !== exceptKey && habit.label.trim().toLowerCase() === normalized
    );
  };

  return (
    <CheckInSection
      id="habits"
      title={t("checkin.habits_title")}
      trailing={
        <button
          type="button"
          onClick={() => setDialog({ open: true, habit: null })}
          aria-label={t("checkin.add_habit")}
          title={t("checkin.add_habit")}
          className="flex h-11 w-11 items-center justify-center rounded-lg text-[16px] text-[color:var(--text-2)] transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
        >
          ➕
        </button>
      }
    >
      {habits.length === 0 ? (
        <p className="py-1 text-[12px] italic text-[color:var(--text-3)]">
          {t("habits.no_habits_yet")}
        </p>
      ) : (
        <ul className="flex flex-col gap-1">
          {habits.map((habit) => {
            const checked = values[habit.key] === true;
            const label = definitionLabel(habit, lang);
            return (
              <li
                key={habit.key}
                className="flex min-h-12 items-center gap-2 rounded-xl border border-white/[0.05] bg-white/[0.025] px-2.5 py-1"
              >
                <span aria-hidden="true" className="text-[18px] leading-6">
                  {habit.icon}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] text-[color:var(--text-1)]">
                    {label}
                  </span>
                  <span className="flex items-center gap-1.5">
                    {habit.is_negative ? (
                      <span className="text-[10px] text-red-300/80">
                        {t("habits.negative_badge")}
                      </span>
                    ) : null}
                    <span
                      className={`text-[10px] ${
                        checked ? "text-emerald-300" : "text-[color:var(--text-3)]"
                      }`}
                    >
                      {checked ? t("habits.done_badge") : t("habits.not_today")}
                    </span>
                  </span>
                </span>
                <button
                  type="button"
                  onClick={() => setDialog({ open: true, habit })}
                  aria-label={`${t("habits.edit_tooltip")}: ${label}`}
                  className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg text-[15px] text-[color:var(--text-3)] transition-colors hover:bg-white/10 hover:text-indigo-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
                >
                  ✏️
                </button>
                <Toggle
                  checked={checked}
                  onChange={() => onToggle(habit.key, !checked)}
                  label={label}
                />
              </li>
            );
          })}
        </ul>
      )}

      {error ? (
        <p role="alert" className="mt-2 text-[12px] text-red-300">
          {t(error)}
        </p>
      ) : null}

      {dialog.open ? (
        <HabitEditDialog
          habit={dialog.habit}
          onClose={() => setDialog({ open: false, habit: null })}
          onDelete={
            dialog.habit ? () => onRemove(dialog.habit!.key) : undefined
          }
          onSave={async (input) => {
            if (duplicateName(input.name, dialog.habit?.key)) {
              return "habits.error_duplicate";
            }
            return dialog.habit
              ? onUpdate(dialog.habit.key, input)
              : onAdd(input);
          }}
        />
      ) : null}
    </CheckInSection>
  );
}
