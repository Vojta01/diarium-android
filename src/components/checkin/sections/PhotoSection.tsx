"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { useTranslation } from "@/lib/i18n";
import { uploadCheckInPhoto } from "@/lib/supabase/db";
import { isCheckInError } from "@/lib/checkin/errors";
import { compressPhoto, validatePhotoFile } from "@/lib/checkin/photo";
import { finishPhotoUpload } from "@/lib/checkin/photo-edit";

import { CheckInSection } from "../CheckInSection";

/**
 * Fotka — camera/gallery only (no Google Photos, matching Android), upload on
 * pick, and a public URL in the row. The local object URL is only a preview; it
 * is revoked as soon as the upload settles so no blob URL leaks into state.
 */
export function PhotoSection({
  currentPhoto,
  date,
  legacyPhoto = false,
  onPhotoSelected,
  onUploadingChange,
  onView,
}: {
  /** Public Storage URL, or null when there is no photo. */
  currentPhoto: string | null;
  date: string;
  legacyPhoto?: boolean;
  onPhotoSelected: (url: string | null, uploadDate?: string) => void;
  onUploadingChange?: (uploading: boolean) => void;
  onView: (url: string) => void;
}) {
  const { t } = useTranslation();
  const cameraRef = useRef<HTMLInputElement>(null);
  const galleryRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [preview, setPreview] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const dateRef = useRef(date);
  const previewRef = useRef<string | null>(null);
  const uploadGeneration = useRef(0);
  const mounted = useRef(false);

  const releasePreview = useCallback((updateState = true) => {
    if (previewRef.current) {
      URL.revokeObjectURL(previewRef.current);
      previewRef.current = null;
    }
    if (updateState && mounted.current) setPreview(null);
  }, []);

  useEffect(() => {
    dateRef.current = date;
    uploadGeneration.current += 1;
    releasePreview();
  }, [date, releasePreview]);

  // A date change or unmount must not leave a blob URL or live upload behind.
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      uploadGeneration.current += 1;
      releasePreview(false);
    };
  }, [releasePreview]);

  const setUploadingState = useCallback(
    (value: boolean) => {
      setUploading(value);
      onUploadingChange?.(value);
    },
    [onUploadingChange]
  );

  const handleFileChange = useCallback(
    async (event: React.ChangeEvent<HTMLInputElement>) => {
      const file = event.target.files?.[0];
      // Reset immediately so picking the same file twice still fires change.
      event.target.value = "";
      if (!file) return;

      const validation = validatePhotoFile(file);
      if (validation) {
        setError(validation);
        return;
      }

      const uploadDate = dateRef.current;
      const generation = ++uploadGeneration.current;
      const isCurrent = () =>
        mounted.current &&
        generation === uploadGeneration.current &&
        uploadDate === dateRef.current;
      setError(null);
      setUploadingState(true);
      try {
        const blob = await compressPhoto(file);
        if (!isCurrent()) return;
        const localUrl = URL.createObjectURL(blob);
        previewRef.current = localUrl;
        setPreview(localUrl);

        await finishPhotoUpload({
          upload: () => uploadCheckInPhoto(blob, uploadDate),
          isCurrent,
          onStage: (url) => onPhotoSelected(url, uploadDate),
        });
        releasePreview();
      } catch (uploadError) {
        releasePreview();
        if (isCurrent()) {
          setError(
            isCheckInError(uploadError)
              ? uploadError.code
              : "checkin.photo_error_upload"
          );
        }
      } finally {
        if (mounted.current && generation === uploadGeneration.current) {
          setUploadingState(false);
        }
      }
    },
    [onPhotoSelected, releasePreview, setUploadingState]
  );

  const removePhoto = () => {
    setError(null);
    releasePreview();
    onPhotoSelected(null, dateRef.current);
  };

  const cancelUpload = () => {
    uploadGeneration.current += 1;
    releasePreview();
    setUploadingState(false);
  };

  const shown = preview ?? currentPhoto;
  const alt = t("checkin.photo_alt", { date });

  return (
    <CheckInSection id="photo" title={t("checkin.photo_title")}>
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          onClick={() => cameraRef.current?.click()}
          disabled={uploading}
          className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-white/10 bg-white/[0.05] px-3 text-[13px] text-[color:var(--text-1)] transition-colors hover:bg-white/[0.09] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 disabled:opacity-50"
        >
          <span aria-hidden="true" className="text-[16px]">
            📷
          </span>
          {t("checkin.photo_camera")}
        </button>
        <button
          type="button"
          onClick={() => galleryRef.current?.click()}
          disabled={uploading}
          className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-white/10 bg-white/[0.05] px-3 text-[13px] text-[color:var(--text-1)] transition-colors hover:bg-white/[0.09] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80 disabled:opacity-50"
        >
          <span aria-hidden="true" className="text-[16px]">
            🖼️
          </span>
          {t("checkin.photo_gallery")}
        </button>
        {shown && !uploading ? (
          <button
            type="button"
            onClick={removePhoto}
            className="inline-flex min-h-11 items-center gap-1.5 rounded-xl border border-red-500/20 bg-red-500/[0.06] px-3 text-[13px] text-red-300 transition-colors hover:bg-red-500/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
          >
            <span aria-hidden="true">🗑</span>
            {t("checkin.photo_remove")}
          </button>
        ) : null}
      </div>

      <p role="note" className="mt-2 text-[12px] leading-5 text-[color:var(--text-3)]">
        {t("checkin.photo_storage_notice")}
      </p>

      {uploading ? (
        <div className="mt-2 space-y-1">
          <div className="flex items-center gap-3">
            <p role="status" className="text-[12px] text-[color:var(--text-2)]">
              {t("checkin.photo_uploading")}
            </p>
            <button
              type="button"
              onClick={cancelUpload}
              className="min-h-11 text-[12px] text-[color:var(--text-2)] underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
            >
              {t("checkin.photo_cancel_upload")}
            </button>
          </div>
          <p role="note" className="text-[12px] leading-5 text-[color:var(--text-3)]">
            {t("checkin.photo_cancel_upload_notice")}
          </p>
        </div>
      ) : null}

      {error ? (
        <p role="alert" className="mt-2 text-[12px] text-red-300">
          {t(error)}
        </p>
      ) : null}

      {legacyPhoto && !shown ? (
        <p role="status" className="mt-2 text-[12px] text-amber-300/85">
          {t("checkin.photo_legacy")}
        </p>
      ) : null}

      {shown ? (
        <button
          type="button"
          onClick={() => onView(shown)}
          className="mt-3 block w-full overflow-hidden rounded-[14px] border border-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-400/80"
          aria-label={`${t("checkin.photo_view")}: ${alt}`}
        >
          {/* A plain img keeps the upload pipeline dependency-free. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={shown}
            alt={alt}
            className="h-[190px] w-full object-cover"
          />
        </button>
      ) : !uploading ? (
        <p className="mt-2 text-[12px] italic text-[color:var(--text-3)]">
          {t("checkin.photo_empty")}
        </p>
      ) : null}

      <input
        ref={cameraRef}
        type="file"
        accept="image/*"
        capture="environment"
        aria-label={t("checkin.photo_camera")}
        onChange={handleFileChange}
        className="sr-only"
      />
      <input
        ref={galleryRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        aria-label={t("checkin.photo_gallery")}
        onChange={handleFileChange}
        className="sr-only"
      />
    </CheckInSection>
  );
}
