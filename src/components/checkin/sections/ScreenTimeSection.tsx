"use client";

import {
  formatScreenTimeDuration,
  formatTopAppDuration,
} from "@/lib/dashboard-format";
import { screenTimeSnapshot } from "@/lib/checkin/readonly";
import { useTranslation } from "@/lib/i18n";
import type { CheckInData } from "@/lib/types";

import { CheckInSection } from "../CheckInSection";

/**
 * 📱 Screen time — the day's phone snapshot, read-only.
 *
 * The background worker owns these columns. `null` is "not synced" and renders
 * an em dash; `0` is a real zero and renders "0m" / "0". The section has no
 * controls and no sync button because the web app has no screen-time sync UI —
 * the Android app is the only writer.
 */
export function ScreenTimeSection({
  data,
}: {
  data: Pick<CheckInData, "phoneScreenTime" | "phoneUnlocks" | "phoneTopApps">;
}) {
  const { t } = useTranslation();
  const snapshot = screenTimeSnapshot(data);

  return (
    <CheckInSection id="screenTime" title={t("checkin.screen_time_title")}>
      {!snapshot.hasAny ? (
        <>
          <p className="py-1 text-[12px] leading-[18px] text-[color:var(--text-2)]">
            {t("checkin.screen_time_empty")}
          </p>
          <p className="mt-1 text-[10px] leading-4 text-[color:var(--text-3)]">
            {t("checkin.screen_time_source")}
          </p>
        </>
      ) : (
        <>
          <dl className="grid grid-cols-2 gap-3">
            <div className="min-w-0">
              <dt className="text-[10px] font-medium uppercase leading-4 tracking-[0.8px] text-[color:var(--text-3)]">
                {t("checkin.screen_time_total")}
              </dt>
              <dd className="mt-0.5 text-[22px] font-bold leading-7 tabular-nums text-[color:var(--text-1)]">
                {formatScreenTimeDuration(snapshot.screenTime)}
              </dd>
              {snapshot.screenTime === null ? (
                <p className="mt-0.5 text-[10px] leading-4 text-[color:var(--text-3)]">
                  {t("checkin.screen_time_not_synced")}
                </p>
              ) : null}
            </div>
            <div className="min-w-0">
              <dt className="text-[10px] font-medium uppercase leading-4 tracking-[0.8px] text-[color:var(--text-3)]">
                {t("checkin.screen_time_unlocks")}
              </dt>
              <dd className="mt-0.5 text-[22px] font-bold leading-7 tabular-nums text-[color:var(--text-1)]">
                {snapshot.unlocks === null ? "—" : snapshot.unlocks}
              </dd>
              {snapshot.unlocks === null ? (
                <p className="mt-0.5 text-[10px] leading-4 text-[color:var(--text-3)]">
                  {t("checkin.screen_time_not_synced")}
                </p>
              ) : null}
            </div>
          </dl>

          {snapshot.topApps.length > 0 ? (
            <div className="mt-3">
              <p className="text-[10px] font-medium uppercase leading-4 tracking-[0.8px] text-[color:var(--text-3)]">
                {t("checkin.screen_time_top_apps")}
              </p>
              <ul className="mt-1 space-y-1">
                {snapshot.topApps.map((app, index) => (
                  <li
                    key={`${app.app}-${index}`}
                    className="flex items-baseline justify-between gap-2 text-[12px] leading-5"
                  >
                    <span className="min-w-0 truncate text-[color:var(--text-1)]">
                      {app.app}
                    </span>
                    <span className="shrink-0 tabular-nums text-[color:var(--text-2)]">
                      {formatTopAppDuration(app.time_sec)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          <p className="mt-2 text-[10px] leading-4 text-[color:var(--text-3)]">
            {t("checkin.screen_time_source")}
          </p>
        </>
      )}
    </CheckInSection>
  );
}
