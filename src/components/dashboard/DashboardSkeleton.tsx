"use client";

import type { ReactNode } from "react";

import {
  DASHBOARD_CARD_ORDER,
  type DashboardCardId,
} from "@/lib/dashboard";
import {
  DASHBOARD_FULL_SPAN_CLASS,
  DASHBOARD_GRID_CLASS,
  dashboardCardSpan,
} from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";

const SKELETON_CARDS = DASHBOARD_CARD_ORDER;

function Block({
  id,
  span,
  height,
  children,
}: {
  id: DashboardCardId;
  span: 1 | 2;
  height: string;
  children?: ReactNode;
}) {
  return (
    <div
      data-skeleton-card={id}
      className={`glass-card animate-pulse ${height} ${
        span === 2 ? DASHBOARD_FULL_SPAN_CLASS : ""
      } motion-reduce:animate-none`}
    >
      <div className="flex flex-col gap-3">
        <div className="h-2.5 w-24 rounded-full bg-white/[0.09]" />
        {children ?? (
          <>
            <div className="h-8 w-32 rounded-lg bg-white/[0.07]" />
            <div className="h-2.5 w-full rounded-full bg-white/[0.05]" />
            <div className="h-2.5 w-2/3 rounded-full bg-white/[0.05]" />
          </>
        )}
      </div>
    </div>
  );
}

/**
 * The overview's loading state: the same cards in the same order and spans as
 * the loaded screen, so nothing jumps when the data arrives. Shapes only — no
 * invented numbers, titles or chart lines.
 */
export function DashboardSkeleton() {
  const { t } = useTranslation();
  const visible = SKELETON_CARDS;

  return (
    <div
      role="status"
      aria-live="polite"
      aria-busy="true"
      className={DASHBOARD_GRID_CLASS}
    >
      <span className="sr-only">{t("dashboard.loading")}</span>
      {visible.map((id) => {
        const span = dashboardCardSpan(id, visible);
        switch (id) {
          case "hero_stats":
            return (
              <Block key={id} id={id} span={2} height="min-h-[224px]">
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                  <div className="h-[88px] rounded-xl bg-white/[0.05] sm:col-span-1" />
                  <div className="h-[88px] rounded-xl bg-white/[0.05]" />
                  <div className="h-[88px] rounded-xl bg-white/[0.05]" />
                </div>
              </Block>
            );
          case "mood_chart":
            return (
              <Block key={id} id={id} span={span} height="min-h-[270px]">
                <div className="h-4 w-28 rounded-full bg-white/[0.05]" />
                <div className="h-[150px] rounded-xl bg-white/[0.04]" />
              </Block>
            );
          case "streak":
          case "week":
            return (
              <Block key={id} id={id} span={span} height="min-h-[190px]">
                <div className="h-7 w-20 rounded-lg bg-white/[0.07]" />
                <div className="flex gap-1">
                  {Array.from({ length: 7 }).map((_, index) => (
                    <div
                      key={index}
                      className="size-8 flex-1 rounded-full bg-white/[0.05]"
                    />
                  ))}
                </div>
              </Block>
            );
          case "screen_time":
          case "unlocks":
            return (
              <Block key={id} id={id} span={span} height="min-h-[240px]">
                <div className="h-7 w-24 rounded-lg bg-white/[0.07]" />
                <div className="flex h-[92px] items-end gap-1">
                  {Array.from({ length: 7 }).map((_, index) => (
                    <div
                      key={index}
                      className="flex-1 rounded-t-sm bg-white/[0.05]"
                      style={{ height: `${30 + ((index * 17) % 60)}%` }}
                    />
                  ))}
                </div>
              </Block>
            );
          case "top_apps":
          case "reflection":
            return (
              <Block key={id} id={id} span={span} height="min-h-[210px]">
                <div className="h-3 w-2/3 rounded-full bg-white/[0.05]" />
                <div className="h-3 w-full rounded-full bg-white/[0.04]" />
                <div className="h-3 w-5/6 rounded-full bg-white/[0.04]" />
                <div className="h-3 w-3/4 rounded-full bg-white/[0.04]" />
              </Block>
            );
          case "entries":
            return (
              <Block key={id} id={id} span={span} height="min-h-[330px]">
                <div className="flex flex-col gap-2">
                  {Array.from({ length: 5 }).map((_, index) => (
                    <div
                      key={index}
                      className="flex items-center gap-3 rounded-xl border border-white/[0.04] px-2 py-2"
                    >
                      <div className="size-9 rounded-xl bg-white/[0.05]" />
                      <div className="flex flex-1 flex-col gap-1.5">
                        <div className="h-2.5 w-1/3 rounded-full bg-white/[0.07]" />
                        <div className="h-2 w-2/3 rounded-full bg-white/[0.04]" />
                      </div>
                      <div className="h-4 w-2 rounded bg-white/[0.05]" />
                    </div>
                  ))}
                </div>
              </Block>
            );
          default:
            return (
              <Block key={id} id={id} span={span} height="min-h-[200px]" />
            );
        }
      })}
    </div>
  );
}
