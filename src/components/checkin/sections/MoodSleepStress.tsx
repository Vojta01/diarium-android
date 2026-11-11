"use client";

import { useTranslation } from "@/lib/i18n";
import {
  MOOD_OPTIONS,
  SLEEP_OPTIONS,
  STRESS_OPTIONS,
} from "@/lib/checkin/options";

import { CheckInSection, checkInSectionTitleId } from "../CheckInSection";
import { EmojiChoiceGroup } from "../primitives/EmojiChoice";

/** Nálada — five emoji, one required selection, no deselect (Android parity). */
export function MoodSection({
  value,
  onChange,
}: {
  value: number;
  onChange: (value: number, emoji: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <CheckInSection id="mood" title={t("checkin.mood_label")}>
      <EmojiChoiceGroup
        labelId={checkInSectionTitleId("mood")}
        options={MOOD_OPTIONS.map((option) => ({
          ...option,
          label: t(option.labelKey),
        }))}
        value={value}
        onChange={onChange}
        columns={5}
      />
    </CheckInSection>
  );
}

/** Kvalita spánku — 3 emoji. */
export function SleepSection({
  value,
  onChange,
}: {
  value: number;
  onChange: (value: number, emoji: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <CheckInSection id="sleep" title={t("checkin.sleep_label")}>
      <EmojiChoiceGroup
        labelId={checkInSectionTitleId("sleep")}
        options={SLEEP_OPTIONS.map((option) => ({
          ...option,
          label: t(option.labelKey),
        }))}
        value={value}
        onChange={onChange}
        columns={3}
      />
    </CheckInSection>
  );
}

/** Stres — 5 emoji, 1 = calm. */
export function StressSection({
  value,
  onChange,
}: {
  value: number;
  onChange: (value: number, emoji: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <CheckInSection id="stress" title={t("checkin.stress_label")}>
      <EmojiChoiceGroup
        labelId={checkInSectionTitleId("stress")}
        options={STRESS_OPTIONS.map((option) => ({
          ...option,
          label: t(option.labelKey),
        }))}
        value={value}
        onChange={onChange}
        columns={5}
        tone="orange"
      />
    </CheckInSection>
  );
}
