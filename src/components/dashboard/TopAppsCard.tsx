"use client";

import type { DashboardData } from "@/lib/dashboard";
import { formatTopAppDuration } from "@/lib/dashboard-format";
import {
  formatDashboardDate,
  topAppsCardState,
} from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";

import {
  ACCENT,
  Card,
  EmptyState,
  Hint,
  ProgressBar,
  SecondaryButton,
} from "./primitives";

/** The ranked list of the newest usable app snapshot. */
export function TopAppsCard({
  data,
  onRefresh,
}: {
  data: DashboardData;
  onRefresh: () => void;
}) {
  const { t, lang } = useTranslation();
  const state = topAppsCardState(data);

  if (state.kind === "hidden") return null;

  const date = formatDashboardDate(state.date, lang, "numeric");
  const total = state.apps.reduce((sum, app) => sum + app.time_sec, 0);

  return (
    <Card
      cardId="top_apps"
      title={t("dashboard.top_apps.title")}
      accent={ACCENT.violet}
      className="h-full"
    >
      {state.kind === "not_enough" ? (
        <EmptyState
          emoji="📵"
          title={t("dashboard.top_apps.not_enough_title")}
          message={t("dashboard.top_apps.not_enough")}
          action={
            <SecondaryButton onClick={onRefresh}>
              {t("dashboard.top_apps.refresh")}
            </SecondaryButton>
          }
        />
      ) : (
        <>
          <Hint>{t("dashboard.top_apps.caption", { date })}</Hint>
          <ol
            aria-label={t("dashboard.top_apps.list_label", { date })}
            className="mt-2 flex flex-col gap-2.5"
          >
            {state.apps.map((app, index) => {
              const share = total > 0 ? app.time_sec / total : 0;
              const percent = Math.round(share * 100);
              return (
                <li key={`${app.app}-${index}`} className="min-w-0">
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="flex min-w-0 items-baseline gap-2">
                      <span
                        aria-hidden="true"
                        className="w-4 shrink-0 text-[11px] font-semibold tabular-nums text-[color:var(--text-3)]"
                      >
                        {index + 1}.
                      </span>
                      <span className="min-w-0 truncate text-[13px] font-medium text-[color:var(--text-1)]">
                        {app.app}
                      </span>
                    </span>
                    <span className="shrink-0 text-[11px] tabular-nums text-[color:var(--text-2)]">
                      {formatTopAppDuration(app.time_sec)}
                    </span>
                  </div>
                  <div className="mt-1 flex items-center gap-2">
                    <ProgressBar
                      fraction={share}
                      color={ACCENT.violet}
                      height={5}
                      label={t("dashboard.top_apps.row", {
                        app: app.app,
                        duration: formatTopAppDuration(app.time_sec),
                      })}
                      valueNow={percent}
                    />
                    <span className="w-8 shrink-0 text-right text-[10px] tabular-nums text-[color:var(--text-3)]">
                      {percent} %
                    </span>
                  </div>
                </li>
              );
            })}
          </ol>
        </>
      )}
    </Card>
  );
}
