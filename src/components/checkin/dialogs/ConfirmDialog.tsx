"use client";

import { useId } from "react";

import { Modal } from "@/components/ui/Modal";

/** A small styled confirmation dialog — never `window.confirm`. */
export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  body?: string;
  confirmLabel: string;
  cancelLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const titleId = useId();
  const bodyId = useId();
  if (!open) return null;

  return (
    <Modal
      onClose={onCancel}
      labelledBy={titleId}
      describedBy={body ? bodyId : undefined}
      align="center"
      panelClassName="mx-4 w-full max-w-sm rounded-2xl border border-white/10 bg-gray-900/95 shadow-2xl backdrop-blur-xl"
    >
      <div className="px-5 pb-1 pt-5">
        <h3 id={titleId} className="text-base font-semibold text-white">
          {title}
        </h3>
        {body ? (
          <p
            id={bodyId}
            className="mt-2 text-[13px] leading-5 text-[color:var(--text-2)]"
          >
            {body}
          </p>
        ) : null}
      </div>
      <div className="flex gap-2 px-5 pb-5 pt-3">
        <button
          type="button"
          onClick={onCancel}
          className="min-h-11 flex-1 rounded-xl border border-white/10 bg-white/5 text-sm font-medium text-[color:var(--text-2)] transition-colors hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
        >
          {cancelLabel}
        </button>
        <button
          type="button"
          onClick={onConfirm}
          className="min-h-11 flex-1 rounded-xl bg-indigo-500 text-sm font-medium text-white transition-colors hover:bg-indigo-400 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
        >
          {confirmLabel}
        </button>
      </div>
    </Modal>
  );
}
