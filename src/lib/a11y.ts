import type { ReactNode } from "react";

/**
 * Flattens the text a user actually sees inside a link.
 *
 * An accessible name must contain the visible label (WCAG 2.5.3 "Label in
 * Name"), so callers derive the name from this text instead of replacing it
 * with an aria-label that only describes the destination.
 */
export function visibleLinkText(children: ReactNode): string {
  if (typeof children === "string" || typeof children === "number") {
    return String(children);
  }
  if (Array.isArray(children)) {
    return children.map(visibleLinkText).join("");
  }
  return "";
}

/**
 * Accessible-name label for an external link: the visible text when there is
 * any, otherwise the human-readable destination name.
 */
export function externalLinkNameLabel(
  children: ReactNode,
  destinationLabel: string
): string {
  const visible = visibleLinkText(children).replace(/\s+/g, " ").trim();
  return visible || destinationLabel;
}
