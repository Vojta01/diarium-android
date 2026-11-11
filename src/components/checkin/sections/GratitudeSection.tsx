"use client";

import { useTranslation } from "@/lib/i18n";
import {
  GRATITUDE_SLOTS,
  setGratitudeSlot,
} from "@/lib/checkin/gratitude";

import { CheckInSection } from "../CheckInSection";

/**
 * Za co jsi vděčný? — exactly three slots, matching the Android beta5 form.
 *
 * Legacy rows with more than three values keep the extras in storage (the
 * payload still carries them); the form only edits the canonical three.
 */
export function GratitudeSection({
  values,
  onChange,
}: {
  values: readonly string[];
  onChange: (values: string[]) => void;
}) {
  const { t } = useTranslation();

  return (
    <CheckInSection id="gratitude" title={t("checkin.gratitude_title")}>
      <ul className="space-y-2">
        {Array.from({ length: GRATITUDE_SLOTS }, (_, index) => {
          const inputId = `checkin-gratitude-${index}`;
          return (
            <li key={index} className="flex items-center gap-2">
              <label
                htmlFor={inputId}
                className="w-5 shrink-0 text-right text-[13px] text-[color:var(--text-3)]"
              >
                {index + 1}.
              </label>
              <input
                id={inputId}
                value={values[index] ?? ""}
                maxLength={200}
                onChange={(event) =>
                  onChange(setGratitudeSlot(values, index, event.target.value))
                }
                placeholder={t("checkin.gratitude_placeholder", { n: index + 1 })}
                aria-label={t("checkin.gratitude_slot", { n: index + 1 })}
                className="min-h-11 min-w-0 flex-1 rounded-lg border border-white/10 bg-white/5 px-3 text-sm text-[color:var(--text-1)] placeholder:text-white/25 focus:border-indigo-400/60 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/50"
              />
            </li>
          );
        })}
      </ul>
    </CheckInSection>
  );
}
