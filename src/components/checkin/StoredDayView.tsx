"use client";

import { DayDetail } from "@/components/dashboard/DayDetail";
import type { Entry, HabitDef } from "@/lib/supabase/db";
import type { Scale } from "@/lib/scales";
import { mapDailyEntryRow } from "@/lib/stats";

/**
 * A recorded day opens passively, exactly like history: the shared `DayDetail`
 * renders the stored row and owns the single "Upravit" action. The check-in
 * adds no second edit button. Android's stored day renders screen time and the
 * AI reflection (both come from `DayDetail`) but no goals block, so this
 * wrapper adds neither.
 */
export function StoredDayView({
  date,
  entry,
  habits,
  scales,
  scalesError,
  onRetry,
  onEdit,
}: {
  date: string;
  entry: Entry;
  habits: readonly HabitDef[] | null;
  scales: readonly Scale[] | null;
  scalesError: string | null;
  onRetry: () => void;
  onEdit: () => void;
}) {
  const daily = mapDailyEntryRow(entry as unknown as Record<string, unknown>);

  return (
    <div className="pb-8">
      <div className="glass-card">
        <DayDetail
          date={date}
          entry={daily}
          habits={habits}
          scales={scales}
          scalesError={scalesError}
          onRetry={onRetry}
          onEdit={onEdit}
        />
      </div>
    </div>
  );
}
