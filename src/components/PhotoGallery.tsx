"use client";

/* eslint-disable @next/next/no-img-element */

import type { DailyEntry } from "@/lib/stats";
import { dayForm, formatDashboardDate } from "@/lib/dashboard-ui";
import { useTranslation } from "@/lib/i18n";

interface PhotoGalleryProps {
  entries: DailyEntry[];
  onNavigateToDate?: (date: string) => void;
}

export function PhotoGallery({ entries, onNavigateToDate }: PhotoGalleryProps) {
  const { t, lang } = useTranslation();

  const photos = entries
    .filter((e) => !!e.photo_path)
    .sort((a, b) => b.date.localeCompare(a.date)); // newest first

  if (photos.length === 0) {
    return (
      <div className="glass-card p-8 text-center">
        <div className="text-4xl mb-2">📷</div>
        <p className="text-white/40 text-sm">{t("gallery.no_photos")}</p>
        <p className="text-white/25 text-xs mt-1">{t("gallery.no_photos_hint")}</p>
      </div>
    );
  }

  return (
    <div>
      <p className="text-white/30 text-xs mb-3 text-center">
        {t(`gallery.count_${dayForm(photos.length, lang)}`, {
          count: photos.length,
        })}
      </p>
      <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
        {photos.map((e) => (
          <button
            key={e.date}
            onClick={() => onNavigateToDate?.(e.date)}
            className="relative aspect-square rounded-xl overflow-hidden border border-white/10 group focus:outline-none focus:ring-2 focus:ring-indigo-400/50"
          >
            <img
              src={e.photo_path!}
              alt={e.date}
              loading="lazy"
              className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
            />
            <div className="absolute bottom-0 inset-x-0 bg-gradient-to-t from-black/80 to-transparent px-2 py-1.5 text-left">
              <span className="text-[10px] text-white/90 font-medium">
                {formatDashboardDate(e.date, lang, "numeric")}
              </span>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
}
