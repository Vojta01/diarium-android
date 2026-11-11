"use client";

import { useTranslation } from "@/lib/i18n";
import { WEATHER_OPTIONS } from "@/lib/checkin/options";

import { CheckInSection } from "../CheckInSection";
import { Chip } from "../primitives/Chip";

/**
 * Počasí — a separate multi-select. Values are canonical catalogue keys, so a
 * legacy row storing Czech or English labels is normalised by `fromEntry`
 * before it reaches this component.
 */
export function WeatherSection({
  selected,
  onToggle,
}: {
  selected: readonly string[];
  onToggle: (key: string) => void;
}) {
  const { t } = useTranslation();

  return (
    <CheckInSection id="weather" title={t("checkin.weather_title")}>
      {WEATHER_OPTIONS.length === 0 ? (
        <p className="py-1 text-[12px] italic text-[color:var(--text-3)]">
          {t("checkin.weather_empty")}
        </p>
      ) : (
        <div className="flex flex-wrap gap-1.5">
          {WEATHER_OPTIONS.map((option) => (
            <Chip
              key={option.key}
              pressed={selected.includes(option.key)}
              onClick={() => onToggle(option.key)}
            >
              {t(`dashboard.weather.${option.key}`)}
            </Chip>
          ))}
        </div>
      )}
    </CheckInSection>
  );
}
