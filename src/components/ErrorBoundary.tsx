"use client";

import { Component, type ErrorInfo, type ReactNode } from "react";

import { useTranslation } from "@/lib/i18n";

function ErrorFallback({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) {
  const { t } = useTranslation();

  return (
    <div
      role="alert"
      className="mx-auto flex min-h-[70vh] w-full max-w-[620px] flex-col items-center justify-center gap-4 px-6 text-center"
    >
      <h2 className="text-lg font-semibold text-[color:var(--text-1)]">
        {t("error.title")}
      </h2>
      <p className="text-sm text-[color:var(--text-2)]">{t("error.hint")}</p>
      <pre className="max-h-40 w-full overflow-auto rounded-xl border border-white/10 bg-black/30 p-3 text-left text-xs text-red-300/80">
        {message}
      </pre>
      <div className="flex flex-wrap items-center justify-center gap-2">
        <button type="button" onClick={onRetry} className="btn-primary">
          {t("error.retry")}
        </button>
        <button type="button" onClick={() => window.location.reload()} className="btn-glass">
          {t("error.reload")}
        </button>
      </div>
      <p className="text-[11px] leading-4 text-[color:var(--text-3)]">
        {t("error.session_safe")}
      </p>
    </div>
  );
}

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  hasError: boolean;
  message: string;
}

/**
 * Render-error boundary for the app shell.
 *
 * Recovery must never sign the user out: a crash in one screen is not a reason
 * to wipe `localStorage` (the Supabase session lives there). "Retry" re-renders
 * the tree; "Reload" performs a normal page reload with tokens intact.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false, message: "" };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, message: error.message || String(error) };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("[ErrorBoundary]", error, info.componentStack);
  }

  private retry = () => {
    this.setState({ hasError: false, message: "" });
  };

  render() {
    if (this.state.hasError) {
      return <ErrorFallback message={this.state.message} onRetry={this.retry} />;
    }

    return this.props.children;
  }
}
