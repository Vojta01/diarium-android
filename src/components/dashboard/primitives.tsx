"use client";

import { useId, type ReactNode } from "react";

import { trendCaption } from "@/lib/dashboard-format";
import type { MetricTrend, TrendTone } from "@/lib/dashboard";
import { useTranslation } from "@/lib/i18n";

/** Shared focus ring: visible on keyboard focus, quiet for pointer users. */
export const FOCUS_RING =
  "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 focus-visible:ring-offset-2 focus-visible:ring-offset-[#07070b]";

/** Beta5 accents used by the dashboard cards. */
export const ACCENT = {
  indigo: "#6366f1",
  indigoLight: "#818cf8",
  cyan: "#22d3ee",
  violet: "#8b5cf6",
  green: "#22c55e",
  amber: "#f59e0b",
  red: "#ef4444",
  muted: "#6e6e85",
} as const;

/** Hex + alpha as an 8-digit hex, so a mood colour can tint a border. */
export function withAlpha(hex: string, alpha: number): string {
  const value = Math.max(0, Math.min(255, Math.round(alpha * 255)));
  return `${hex}${value.toString(16).padStart(2, "0")}`;
}

/** A card in the dashboard grid: glass surface, accent hairline, h2 heading. */
export function Card({
  cardId,
  title,
  accent,
  trailing,
  children,
  className = "",
}: {
  cardId: string;
  title: string;
  accent?: string;
  trailing?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  const titleId = useId();
  return (
    <section
      data-card={cardId}
      aria-labelledby={titleId}
      className={`glass-card relative min-w-0 ${className}`}
      style={accent ? { borderColor: withAlpha(accent, 0.3) } : undefined}
    >
      <div className="flex items-start justify-between gap-3">
        <h2
          id={titleId}
          className="text-[11px] font-semibold uppercase leading-4 tracking-[1.2px] text-[color:var(--text-2)]"
        >
          {title}
        </h2>
        {trailing ? <div className="shrink-0">{trailing}</div> : null}
      </div>
      {children}
    </section>
  );
}

/** A secondary line inside a card. */
export function Hint({ children }: { children: ReactNode }) {
  return (
    <p className="mt-1 text-[12px] leading-[18px] text-[color:var(--text-2)]">
      {children}
    </p>
  );
}

/** The lead sentence under a card title (Android's `titleMedium`). */
export function Lead({ children }: { children: ReactNode }) {
  return (
    <p className="mt-1 text-[15px] font-semibold leading-5 text-[color:var(--text-1)]">
      {children}
    </p>
  );
}

/** The thin beta5 divider between a card's body and its footer. */
export function Divider() {
  return <div className="glass-divider my-3" />;
}

/** `label ————— value` row used by the cards and the day detail. */
export function MetricRow({
  label,
  value,
  muted = false,
}: {
  label: string;
  value: string;
  muted?: boolean;
}) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1">
      <span className="min-w-0 truncate text-[13px] leading-5 text-[color:var(--text-2)]">
        {label}
      </span>
      <span
        className={`shrink-0 text-right text-[13px] font-medium leading-5 ${
          muted ? "text-[color:var(--text-3)]" : "text-[color:var(--text-1)]"
        }`}
      >
        {value}
      </span>
    </div>
  );
}

/** A large hero figure with its unit kept small and secondary. */
export function StatValue({
  value,
  unit,
  size = "lg",
}: {
  value: string;
  unit?: string;
  size?: "lg" | "md";
}) {
  const sizeClass = size === "lg" ? "text-[34px] leading-[38px]" : "text-[26px] leading-[30px]";
  return (
    <div className="flex items-end gap-1.5">
      <span
        className={`${sizeClass} font-bold tracking-[-0.6px] text-[color:var(--text-1)] tabular-nums`}
      >
        {value}
      </span>
      {unit ? (
        <span className="pb-1 text-[12px] font-medium text-[color:var(--text-2)]">
          {unit}
        </span>
      ) : null}
    </div>
  );
}

/** The arrow colour: growth is good for mood, bad for usage. */
export function trendColor(trend: MetricTrend, tone: TrendTone): string {
  if (trend.direction === "flat" || trend.direction === "unknown") {
    return ACCENT.muted;
  }
  if (trend.direction === "up") {
    return tone === "mood" ? ACCENT.green : ACCENT.amber;
  }
  return tone === "mood" ? ACCENT.amber : ACCENT.green;
}

/** One translated trend caption with a coloured arrow. */
export function TrendLine({
  trend,
  tone,
}: {
  trend: MetricTrend;
  tone: TrendTone;
}) {
  const { t } = useTranslation();
  const caption = trendCaption(trend, tone);
  if (!caption) return null;

  const arrow =
    trend.direction === "up"
      ? "↑"
      : trend.direction === "down"
        ? "↓"
        : trend.direction === "flat"
          ? "≈"
          : null;

  return (
    <p className="mt-1.5 flex items-center gap-1 text-[11px] leading-4 text-[color:var(--text-2)]">
      {arrow ? (
        <span aria-hidden="true" style={{ color: trendColor(trend, tone) }}>
          {arrow}
        </span>
      ) : null}
      <span>
        {t(
          `dashboard.trends.${caption.key}`,
          caption.percent === null ? undefined : { percent: caption.percent }
        )}
      </span>
    </p>
  );
}

/** 7 / 30 day switch, 44px targets, pressed state exposed to AT. */
export function RangeToggle({
  value,
  onChange,
  label,
}: {
  value: "week" | "month";
  onChange: (range: "week" | "month") => void;
  label?: string;
}) {
  const { t } = useTranslation();
  return (
    <div
      role="group"
      aria-label={label ?? t("dashboard.usage.range")}
      className="flex items-center gap-0.5 rounded-xl border border-white/10 bg-white/[0.04] p-0.5"
    >
      {(["week", "month"] as const).map((range) => {
        const selected = value === range;
        return (
          <button
            key={range}
            type="button"
            aria-pressed={selected}
            onClick={() => onChange(range)}
            className={`min-h-11 min-w-11 rounded-[10px] px-2.5 text-[11px] font-semibold transition-colors ${FOCUS_RING} ${
              selected
                ? "bg-indigo-500/30 text-[color:var(--text-1)] shadow-[inset_0_1px_0_rgba(255,255,255,0.1)]"
                : "text-[color:var(--text-2)] hover:text-[color:var(--text-1)]"
            }`}
          >
            {t(
              range === "week"
                ? "dashboard.usage.range_week"
                : "dashboard.usage.range_month"
            )}
          </button>
        );
      })}
    </div>
  );
}

/** A full-width glass action in the brand indigo (Android's GlassActionButton). */
export function ActionButton({
  children,
  onClick,
  disabled = false,
  busy = false,
  type = "button",
}: {
  children: ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  busy?: boolean;
  type?: "button" | "submit";
}) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      aria-busy={busy || undefined}
      className={`flex min-h-11 w-full items-center justify-center gap-2 rounded-[14px] border px-4 py-2.5 text-[14px] font-semibold transition-colors ${FOCUS_RING} ${
        disabled
          ? "cursor-not-allowed border-indigo-500/20 bg-indigo-500/[0.07] text-[color:var(--text-3)]"
          : "border-indigo-500/50 bg-indigo-500/[0.18] text-[color:var(--text-1)] hover:bg-indigo-500/25"
      }`}
    >
      {busy ? <Spinner /> : null}
      {children}
    </button>
  );
}

/** Secondary glass action for "open day" and "refresh". */
export function SecondaryButton({
  children,
  onClick,
  className = "",
}: {
  children: ReactNode;
  onClick?: () => void;
  className?: string;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`flex min-h-11 w-full items-center justify-center gap-2 rounded-[14px] border border-white/10 bg-white/[0.05] px-4 py-2.5 text-[14px] font-medium text-[color:var(--text-1)] transition-colors hover:bg-white/[0.09] ${FOCUS_RING} ${className}`}
    >
      {children}
    </button>
  );
}

/** The shared "nothing here yet" treatment. */
export function EmptyState({
  emoji,
  title,
  message,
  action,
}: {
  emoji: string;
  title: string;
  message: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center px-1 py-3 text-center">
      <span aria-hidden="true" className="text-[30px] leading-none">
        {emoji}
      </span>
      <p className="mt-2 text-[15px] font-semibold text-[color:var(--text-1)]">
        {title}
      </p>
      <p className="mt-1 max-w-[42ch] text-[12px] leading-[18px] text-[color:var(--text-2)]">
        {message}
      </p>
      {action ? <div className="mt-3 w-full">{action}</div> : null}
    </div>
  );
}

/** A card-scoped failure with its own retry, never an empty state. */
export function CardError({
  title,
  message,
  retryLabel,
  onRetry,
}: {
  title: string;
  message: string;
  retryLabel: string;
  onRetry: () => void;
}) {
  return (
    <div role="alert" className="flex flex-col items-center px-1 py-3 text-center">
      <span aria-hidden="true" className="text-[28px] leading-none">
        ⚠️
      </span>
      <p className="mt-2 text-[14px] font-semibold text-[color:var(--text-1)]">
        {title}
      </p>
      <p className="mt-1 text-[12px] leading-[18px] text-[color:var(--text-2)]">
        {message}
      </p>
      <button
        type="button"
        onClick={onRetry}
        className={`mt-3 min-h-11 rounded-[12px] border border-white/12 bg-white/[0.06] px-4 text-[13px] font-medium text-[color:var(--text-1)] hover:bg-white/[0.1] ${FOCUS_RING}`}
      >
        {retryLabel}
      </button>
    </div>
  );
}

/** Small inline spinner used by the reflection action. */
export function Spinner() {
  return (
    <span
      aria-hidden="true"
      className="inline-block size-4 animate-spin rounded-full border-2 border-indigo-300/40 border-t-indigo-200 motion-reduce:animate-none"
    />
  );
}

/** A thin proportional bar (top apps, goals). */
export function ProgressBar({
  fraction,
  color,
  height = 6,
  label,
  valueNow,
  valueMax = 100,
}: {
  fraction: number;
  color: string;
  height?: number;
  label?: string;
  valueNow?: number;
  valueMax?: number;
}) {
  const width = `${Math.max(0, Math.min(1, fraction)) * 100}%`;
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuenow={valueNow}
      aria-valuemin={0}
      aria-valuemax={valueMax}
      className="w-full overflow-hidden rounded-full bg-white/[0.07]"
      style={{ height }}
    >
      <div
        className="h-full rounded-full transition-[width] duration-500 ease-out motion-reduce:transition-none"
        style={{ width, backgroundColor: color }}
      />
    </div>
  );
}

/** A round emoji badge in a tinted circle. */
export function IconBadge({
  emoji,
  accent,
  size = 44,
}: {
  emoji: string;
  accent: string;
  size?: number;
}) {
  return (
    <span
      aria-hidden="true"
      className="flex shrink-0 items-center justify-center rounded-full border"
      style={{
        width: size,
        height: size,
        borderColor: withAlpha(accent, 0.45),
        backgroundColor: withAlpha(accent, 0.14),
      }}
    >
      <span style={{ fontSize: Math.round(size * 0.48) }}>{emoji}</span>
    </span>
  );
}
