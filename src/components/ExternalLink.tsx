"use client";

import type { ReactNode } from "react";

import { useTranslation } from "@/lib/i18n";
import { externalLinkNameLabel } from "@/lib/a11y";

interface ExternalLinkProps {
  href: string;
  /** Human-readable destination name, announced as part of the new-tab hint. */
  label: string;
  children?: ReactNode;
  className?: string;
  showIcon?: boolean;
  /** Drop horizontal padding for icon-only header actions (44px target kept). */
  compact?: boolean;
}

/**
 * Accessible external link: always opens in a new tab, never leaks a referrer
 * and keeps a minimum 44px touch target on mobile.
 */
export function ExternalLink({
  href,
  label,
  children,
  className = "",
  showIcon = true,
  compact = false,
}: ExternalLinkProps) {
  const { t } = useTranslation();
  // Keep the visible text in the accessible name; only append the new-tab
  // hint, never replace the label with destination-only wording.
  const nameLabel = externalLinkNameLabel(children, label);

  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      referrerPolicy="no-referrer"
      aria-label={t("links.opens_in_new_tab", { label: nameLabel })}
      className={`inline-flex min-h-11 min-w-11 items-center justify-center gap-2 rounded-xl ${
        compact ? "px-0" : "px-3"
      } text-sm font-medium text-white/70 transition-colors hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/70 ${className}`}
    >
      {children ?? label}
      {showIcon && (
        <span aria-hidden="true" className="text-xs leading-none">
          ↗
        </span>
      )}
    </a>
  );
}
