"use client";

import { useId, useState } from "react";
import { useTranslation } from "@/lib/i18n";
import { getAccessToken } from "@/lib/supabase/db";
import { pragueDateKey } from "@/lib/date";
import { csvFileName } from "@/lib/export-csv";
import { Modal } from "@/components/ui/Modal";

interface ExportDialogProps {
  open: boolean;
  onClose: () => void;
}

type Phase = "idle" | "loading" | "success" | "empty" | "error";

/**
 * Web export dialog.
 *
 * Deliberate web adaptation of the Android CSV screen: the account lives in
 * Supabase, so the file is generated server-side. The CSV columns, order,
 * quoting and line endings are Android's (`lib/export-csv.ts`). PDF is a
 * **web-only reduced summary** and says so — Android exports CSV only.
 */
export function ExportDialog({ open, onClose }: ExportDialogProps) {
  const { t } = useTranslation();
  const titleId = useId();
  const fromId = useId();
  const toId = useId();
  const rangeErrorId = useId();
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [format, setFormat] = useState<"csv" | "pdf">("csv");
  const [spreadsheetSafe, setSpreadsheetSafe] = useState(false);
  const [phase, setPhase] = useState<Phase>("idle");
  const [count, setCount] = useState(0);

  const rangeInvalid = Boolean(from && to && from > to);
  const loading = phase === "loading";

  function reset() {
    setPhase("idle");
    setCount(0);
  }

  function handleClose() {
    reset();
    onClose();
  }

  async function handleExport() {
    if (rangeInvalid) return;

    setPhase("loading");
    setCount(0);

    try {
      const params = new URLSearchParams();
      if (from) params.set("from", from);
      if (to) params.set("to", to);
      if (format === "csv" && spreadsheetSafe) {
        params.set("mode", "spreadsheet-safe");
      }

      // The export routes derive the user from the verified JWT (they no longer
      // accept a user_id query param), so the access token must be sent.
      const token = getAccessToken();
      const headers: Record<string, string> = {};
      if (token) headers["Authorization"] = `Bearer ${token}`;

      const response = await fetch(`/api/export/${format}?${params}`, { headers });

      if (response.status === 404) {
        setPhase("empty");
        return;
      }
      if (!response.ok) {
        setPhase("error");
        return;
      }

      const rows = Number(response.headers.get("X-Diarium-Entry-Count") || "0");
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download =
        format === "csv"
          ? csvFileName(pragueDateKey())
          : csvFileName(pragueDateKey()).replace(/\.csv$/, ".pdf");
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      // Safari needs the URL to outlive the click.
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);

      setCount(Number.isFinite(rows) ? rows : 0);
      setPhase("success");
    } catch {
      setPhase("error");
    }
  }

  if (!open) return null;

  return (
    <Modal
      onClose={handleClose}
      labelledBy={titleId}
      panelClassName="relative glass-card p-5 w-full max-w-sm mx-4 mb-4 sm:mb-0 max-h-[90dvh] overflow-y-auto"
    >
      <div className="mb-3 flex items-start justify-between gap-3">
        <h2 id={titleId} className="text-xl font-bold text-[color:var(--text-1)]">
          {t("export.title")}
        </h2>
        <button
          type="button"
          onClick={handleClose}
          aria-label={t("common.close")}
          className="flex min-h-11 min-w-11 items-center justify-center rounded-xl text-[color:var(--text-2)] transition-colors hover:bg-white/5 hover:text-[color:var(--text-1)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70"
        >
          <span aria-hidden="true" className="text-lg leading-none">
            ✕
          </span>
        </button>
      </div>

      {phase === "error" ? (
        <p
          role="alert"
          className="mb-3 rounded-lg bg-red-500/10 p-2 text-sm text-red-300"
        >
          {t("export.error")}
        </p>
      ) : null}

      {phase === "empty" ? (
        <div role="status" className="mb-3 rounded-lg bg-white/5 p-3 text-sm">
          <p className="font-medium text-[color:var(--text-1)]">{t("export.empty")}</p>
          <p className="mt-1 text-xs leading-5 text-[color:var(--text-2)]">
            {t("export.empty_hint")}
          </p>
        </div>
      ) : null}

      {phase === "success" ? (
        <p
          role="status"
          className="mb-3 rounded-lg bg-emerald-500/10 p-2 text-sm text-emerald-300"
        >
          {count > 0
            ? t("export.success_count", { count })
            : t("export.success")}
        </p>
      ) : null}

      <fieldset className="mb-3">
        <legend className="mb-2 text-sm text-[color:var(--text-2)]">
          {t("export.date_range")}
        </legend>
        <div className="flex gap-2">
          <div className="min-w-0 flex-1">
            <label
              htmlFor={fromId}
              className="mb-1 block text-xs text-[color:var(--text-2)]"
            >
              {t("export.from")}
            </label>
            <input
              id={fromId}
              type="date"
              value={from}
              onChange={(event) => {
                setFrom(event.target.value);
                if (phase === "success" || phase === "empty" || phase === "error") {
                  reset();
                }
              }}
              aria-invalid={rangeInvalid || undefined}
              aria-describedby={rangeInvalid ? rangeErrorId : undefined}
              className="min-h-11 w-full rounded-xl border border-white/15 bg-white/10 px-2 text-sm text-[color:var(--text-1)] [color-scheme:dark] focus:border-indigo-400 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70"
            />
          </div>
          <div className="min-w-0 flex-1">
            <label
              htmlFor={toId}
              className="mb-1 block text-xs text-[color:var(--text-2)]"
            >
              {t("export.to")}
            </label>
            <input
              id={toId}
              type="date"
              value={to}
              onChange={(event) => {
                setTo(event.target.value);
                if (phase === "success" || phase === "empty" || phase === "error") {
                  reset();
                }
              }}
              className="min-h-11 w-full rounded-xl border border-white/15 bg-white/10 px-2 text-sm text-[color:var(--text-1)] [color-scheme:dark] focus:border-indigo-400 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70"
            />
          </div>
        </div>
        {rangeInvalid ? (
          <p id={rangeErrorId} role="alert" className="mt-2 text-xs text-red-300">
            {t("export.error_range")}
          </p>
        ) : null}
      </fieldset>

      <div className="mb-4">
        <p id={`${titleId}-format`} className="mb-2 text-sm text-[color:var(--text-2)]">
          {t("export.format")}
        </p>
        <div
          role="group"
          aria-labelledby={`${titleId}-format`}
          className="flex gap-2"
        >
          {(["csv", "pdf"] as const).map((fmt) => (
            <button
              key={fmt}
              type="button"
              aria-pressed={format === fmt}
              onClick={() => {
                setFormat(fmt);
                reset();
              }}
              className={`min-h-11 flex-1 rounded-xl text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70 ${
                format === fmt
                  ? "bg-indigo-500 text-white"
                  : "border border-white/15 text-[color:var(--text-1)] hover:bg-white/10"
              }`}
            >
              {t(`export.${fmt}`)}
            </button>
          ))}
        </div>
        {format === "csv" ? (
          <fieldset className="mt-3 rounded-xl border border-white/10 p-3">
            <legend className="px-1 text-xs text-[color:var(--text-2)]">
              {t("export.csv_mode")}
            </legend>
            <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm text-[color:var(--text-1)]">
              <input
                type="radio"
                name={`${titleId}-csv-mode`}
                value="faithful"
                checked={!spreadsheetSafe}
                onChange={() => setSpreadsheetSafe(false)}
              />
              {t("export.csv_faithful")}
            </label>
            <label className="flex min-h-11 cursor-pointer items-center gap-2 text-sm text-[color:var(--text-1)]">
              <input
                type="radio"
                name={`${titleId}-csv-mode`}
                value="spreadsheet-safe"
                checked={spreadsheetSafe}
                onChange={() => setSpreadsheetSafe(true)}
              />
              {t("export.csv_spreadsheet_safe")}
            </label>
          </fieldset>
        ) : null}
        <p className="mt-2 text-xs leading-5 text-[color:var(--text-2)]">
          {format === "csv"
            ? t(spreadsheetSafe ? "export.csv_safe_hint" : "export.csv_hint")
            : t("export.pdf_web_only")}
        </p>
      </div>

      <button
        type="button"
        onClick={() => void handleExport()}
        disabled={loading || rangeInvalid}
        aria-busy={loading}
        className="min-h-11 w-full rounded-xl bg-indigo-500 font-medium text-white transition-colors hover:bg-indigo-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-300 disabled:opacity-50"
      >
        {loading
          ? t("export.exporting")
          : format === "csv"
            ? t("export.export_csv")
            : t("export.export_pdf")}
      </button>
    </Modal>
  );
}
