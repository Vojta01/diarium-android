"use client";

import {
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
  type RefObject,
} from "react";
import { createPortal } from "react-dom";

import { FOCUSABLE_SELECTOR, resolveTabStop } from "@/lib/modal-a11y";
import { SHELL_NAVIGATION_EVENT } from "@/lib/navigation";
import { lockBodyScroll } from "@/lib/scroll-lock";

const subscribeToNothing = () => () => {};
const getClientSnapshot = () => true;
const getServerSnapshot = () => false;

/** True only after hydration; portals need a real `document.body`. */
function useIsClient(): boolean {
  return useSyncExternalStore(subscribeToNothing, getClientSnapshot, getServerSnapshot);
}

function focusableElements(panel: HTMLElement): HTMLElement[] {
  return Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
    (element) =>
      element.getAttribute("aria-hidden") !== "true" &&
      element.tabIndex !== -1 &&
      element.offsetParent !== null
  );
}

function isRestorable(element: HTMLElement | null): element is HTMLElement {
  return Boolean(
    element &&
      element.isConnected &&
      !element.closest("[hidden], [inert], [aria-hidden='true']")
  );
}

export interface ModalProps {
  /** Called for Escape, backdrop click and shell navigation. */
  onClose: () => void;
  /** id of the visible heading that names the dialog. */
  labelledBy?: string;
  /** Fallback accessible name when the dialog has no visible heading. */
  ariaLabel?: string;
  describedBy?: string;
  children: ReactNode;
  /** Panel classes. The caller owns the visual design. */
  panelClassName?: string;
  backdropClassName?: string;
  /** Bottom sheet on phones (default) or centred on every viewport. */
  align?: "sheet" | "center";
  /** Full-viewport surface, used by the photo viewer. */
  fullScreen?: boolean;
  /** Element that should receive focus on open; defaults to the first focusable. */
  initialFocusRef?: RefObject<HTMLElement | null>;
}

/**
 * Shared accessible modal primitive.
 *
 * Rendered through a portal on `document.body` so it can never be trapped in
 * the shell's `z-index: 1` content stacking context (sticky header `z-20`,
 * bottom navigation `z-40`). Every dialog in the app mounts this component only
 * while open, which also makes the initial-focus capture reliable.
 *
 * Responsibilities: `role="dialog"` + `aria-modal`, labelled heading, Escape
 * close, focus trap, focus restoration, backdrop dismissal, and closing when
 * the shell navigates to another destination so a dialog never survives a tab
 * switch in a hidden panel.
 */
export function Modal({
  onClose,
  labelledBy,
  ariaLabel,
  describedBy,
  children,
  panelClassName = "",
  backdropClassName = "",
  align = "sheet",
  fullScreen = false,
  initialFocusRef,
}: ModalProps) {
  const isClient = useIsClient();
  const panelRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  // Set while the dialog is unmounting so the focus-in guard does not fight
  // the focus restoration to the opener.
  const closingRef = useRef(false);

  // Capture the opener during the first render, before the dialog's children
  // (and their autoFocus inputs) are committed to the DOM.
  const [opener] = useState<HTMLElement | null>(() => {
    if (typeof document === "undefined") return null;
    return document.activeElement instanceof HTMLElement ? document.activeElement : null;
  });

  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  // Initial focus: an explicit target, the first focusable child, or the panel.
  useEffect(() => {
    const panel = panelRef.current;
    if (!panel) return;

    const target = initialFocusRef?.current ?? focusableElements(panel)[0] ?? panel;
    target.focus({ preventScroll: true });
  }, [initialFocusRef]);

  // Escape closes the dialog and must not also close the sub-screen behind it.
  // The listener lives on `document` so it works even if focus is temporarily
  // outside the panel.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      event.stopPropagation();
      onCloseRef.current();
    };

    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, []);

  // A tab / sub-screen switch closes any dialog left open in a hidden panel.
  useEffect(() => {
    const onNavigate = () => onCloseRef.current();
    window.addEventListener(SHELL_NAVIGATION_EVENT, onNavigate);
    return () => window.removeEventListener(SHELL_NAVIGATION_EVENT, onNavigate);
  }, []);

  // Freeze background scrolling while the dialog is open. The lock is
  // reference-counted so stacked dialogs keep the page frozen until the last
  // one closes, and the previous inline overflow is restored exactly.
  useEffect(() => {
    if (typeof document === "undefined") return;
    return lockBodyScroll(document.body);
  }, []);

  // Focus containment: Tab is handled inside the panel, but programmatic focus
  // or browser UI can still move focus behind the dialog. Pull it back to the
  // first focusable control (or the panel itself).
  useEffect(() => {
    const onFocusIn = (event: FocusEvent) => {
      if (closingRef.current) return;
      const panel = panelRef.current;
      if (!panel) return;
      const target = event.target;
      if (target instanceof Node && panel.contains(target)) return;
      // A stacked dialog owns focus while it is open; never yank focus out of
      // the top-most layer back into its parent dialog.
      const owner =
        target instanceof Element ? target.closest('[role="dialog"]') : null;
      if (owner && !owner.closest("[hidden]")) return;
      const fallback = focusableElements(panel)[0] ?? panel;
      fallback.focus({ preventScroll: true });
    };

    document.addEventListener("focusin", onFocusIn);
    return () => document.removeEventListener("focusin", onFocusIn);
  }, []);

  // Restore focus to the control that opened the dialog, unless that control
  // disappeared or sits in a panel that is now hidden.
  useEffect(() => {
    return () => {
      closingRef.current = true;
      if (isRestorable(opener)) opener.focus({ preventScroll: true });
    };
  }, [opener]);

  const handleKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key !== "Tab") return;

    const panel = panelRef.current;
    if (!panel) return;

    const items = focusableElements(panel);
    if (items.length === 0) {
      event.preventDefault();
      panel.focus();
      return;
    }

    const activeIndex = items.indexOf(document.activeElement as HTMLElement);
    const nextIndex = resolveTabStop(items.length, activeIndex, event.shiftKey);
    if (nextIndex === null) return;

    event.preventDefault();
    items[nextIndex]?.focus();
  };

  if (!isClient) return null;

  const wrapperAlign = fullScreen
    ? "items-stretch"
    : align === "center"
      ? "items-center"
      : "items-end sm:items-center";

  return createPortal(
    <div
      data-diarium-modal=""
      className={`fixed inset-0 z-[100] flex justify-center ${wrapperAlign}`}
    >
      <div
        aria-hidden="true"
        className={`absolute inset-0 bg-black/60 backdrop-blur-sm ${backdropClassName}`}
        onClick={() => onCloseRef.current()}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        aria-label={labelledBy ? undefined : ariaLabel}
        aria-describedby={describedBy}
        tabIndex={-1}
        onKeyDown={handleKeyDown}
        className={panelClassName}
      >
        {children}
      </div>
    </div>,
    document.body
  );
}
