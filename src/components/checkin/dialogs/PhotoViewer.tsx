"use client";

import { useState } from "react";

import { Modal } from "@/components/ui/Modal";
import { useTranslation } from "@/lib/i18n";

/** Full-screen photo viewer with tap-to-zoom, ported from the old check-in. */
export function PhotoViewer({
  src,
  alt,
  onClose,
}: {
  src: string;
  alt: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [zoomed, setZoomed] = useState(false);

  return (
    <Modal
      onClose={onClose}
      ariaLabel={alt}
      fullScreen
      panelClassName="relative flex h-full w-full flex-col bg-black/95"
    >
      <div className="flex h-full flex-col" onClick={() => setZoomed(!zoomed)}>
        <div
          className="absolute left-0 right-0 top-0 z-10 p-4"
          onClick={(event) => event.stopPropagation()}
        >
          <button
            type="button"
            onClick={onClose}
            aria-label={t("common.close")}
            className="flex h-11 w-11 items-center justify-center rounded-full bg-black/50 text-xl text-white backdrop-blur-sm transition-colors hover:bg-black/70"
          >
            ←
          </button>
        </div>

        <div
          className="flex flex-1 items-start justify-center overflow-auto"
          onClick={(event) => event.stopPropagation()}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={src}
            alt={alt}
            className={`transition-all duration-200 ${
              zoomed
                ? "h-auto w-auto max-h-none max-w-none"
                : "max-h-full max-w-full object-contain"
            }`}
            style={zoomed ? { minWidth: "100%", minHeight: "100dvh" } : {}}
          />
        </div>

        {!zoomed ? (
          <div
            className="absolute bottom-8 left-0 right-0 text-center"
            onClick={(event) => event.stopPropagation()}
          >
            <span className="inline-block rounded-full bg-black/40 px-3 py-1 text-xs text-white/40 backdrop-blur-sm">
              {t("photo.zoom_hint")}
            </span>
          </div>
        ) : null}
      </div>
    </Modal>
  );
}
