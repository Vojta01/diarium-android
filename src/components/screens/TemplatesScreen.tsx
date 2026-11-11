"use client";

import { useCallback, useEffect, useState } from "react";

import {
  CardError,
  EmptyState,
  FOCUS_RING,
} from "@/components/dashboard/primitives";
import {
  DASHBOARD_ERROR_CODES,
  toDashboardError,
  type DashboardErrorCode,
} from "@/lib/dashboard-errors";
import { useTranslation } from "@/lib/i18n";
import { getTemplatesOrThrow, type Template } from "@/lib/templates";

type LoadState =
  | { kind: "loading" }
  | { kind: "ready"; templates: Template[] }
  | { kind: "error"; code: DashboardErrorCode };

/**
 * The read-only Templates destination behind the Overview's Entries card.
 *
 * Templates are edited where they are used — inside the check-in. This screen
 * only proves they exist and shows their real content; it adds no CRUD and no
 * separate storage. A failed read is an error with retry, never "no templates".
 */
export function TemplatesScreen() {
  const { t } = useTranslation();
  const [state, setState] = useState<LoadState>({ kind: "loading" });

  const load = useCallback(async () => {
    setState({ kind: "loading" });
    try {
      const templates = await getTemplatesOrThrow();
      setState({ kind: "ready", templates });
    } catch (error) {
      setState({
        kind: "error",
        code: toDashboardError(error, DASHBOARD_ERROR_CODES.templates).code,
      });
    }
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- async fetch resolves after the effect body
    void load();
  }, [load]);

  return (
    <div className="space-y-3" data-screen="templates">
      <p className="text-[13px] leading-[19px] text-[color:var(--text-2)]">
        {t("screens.templates.read_only")}
      </p>

      {state.kind === "loading" ? (
        <div className="glass-card p-4" role="status" aria-live="polite">
          <p className="text-[13px] text-[color:var(--text-2)]">
            {t("screens.templates.loading")}
          </p>
        </div>
      ) : null}

      {state.kind === "error" ? (
        <div className="glass-card p-4">
          <CardError
            title={t("screens.templates.error_title")}
            message={t(state.code)}
            retryLabel={t("screens.templates.retry")}
            onRetry={() => void load()}
          />
        </div>
      ) : null}

      {state.kind === "ready" && state.templates.length === 0 ? (
        <div className="glass-card p-4">
          <EmptyState
            emoji="📝"
            title={t("screens.templates.empty_title")}
            message={t("screens.templates.empty_hint")}
          />
        </div>
      ) : null}

      {state.kind === "ready" && state.templates.length > 0 ? (
        <ul className="space-y-3">
          {state.templates.map((template) => (
            <li key={template.id} className="glass-card p-4" data-template={template.id}>
              <h2 className="text-[15px] font-semibold leading-5 text-[color:var(--text-1)]">
                {template.name}
              </h2>
              <pre className="mt-2 whitespace-pre-wrap break-words font-sans text-[13px] leading-[20px] text-[color:var(--text-2)]">
                {template.content}
              </pre>
            </li>
          ))}
        </ul>
      ) : null}

      <button
        type="button"
        onClick={() => void load()}
        className={`min-h-11 rounded-[12px] border border-white/10 bg-white/[0.04] px-4 text-[12px] font-medium text-[color:var(--text-2)] transition-colors hover:bg-white/[0.08] ${FOCUS_RING}`}
      >
        {t("screens.templates.refresh")}
      </button>
    </div>
  );
}
