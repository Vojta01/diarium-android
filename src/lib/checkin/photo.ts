/**
 * Check-in photo rules.
 *
 * The database stores a public Storage URL, never image bytes. These helpers are
 * pure where they can be (type/size/path/URL) and browser-only in exactly one
 * place (`compressPhoto`), so the data contract stays unit-testable in Node.
 */
import { photoError } from "./errors.ts";

export const PHOTO_BUCKET = "diary-photos";
export const PHOTO_MAX_INPUT_BYTES = 12 * 1024 * 1024;
export const PHOTO_MAX_OUTPUT_BYTES = 4.5 * 1024 * 1024;
export const PHOTO_MAX_EDGE = 1600;
export const PHOTO_JPEG_QUALITIES = [0.85, 0.7, 0.55, 0.4] as const;
export const PHOTO_ALLOWED_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
] as const;

export type PhotoValidationCode =
  | "checkin.photo_error_type"
  | "checkin.photo_error_size"
  | "checkin.photo_error_read";

/** `null` means the file may be processed. */
export function validatePhotoFile(file: {
  type: string;
  size: number;
}): PhotoValidationCode | null {
  if (!(PHOTO_ALLOWED_TYPES as readonly string[]).includes(file.type)) {
    return "checkin.photo_error_type";
  }
  if (file.size <= 0) return "checkin.photo_error_read";
  if (file.size > PHOTO_MAX_INPUT_BYTES) return "checkin.photo_error_size";
  return null;
}

/**
 * A stored photo is a bounded public URL. `data:` is deliberately rejected:
 * legacy base64 must be re-uploaded, not written back to the row.
 */
export function isStoredPhotoUrl(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const trimmed = value.trim();
  if (!trimmed || trimmed.startsWith("data:")) return false;
  return /^https:\/\//i.test(trimmed);
}

/** True for a legacy base64 value that needs the explicit re-upload warning. */
export function isLegacyPhotoValue(value: unknown): boolean {
  return typeof value === "string" && value.trim().startsWith("data:");
}

const DATE_KEY = /^\d{4}-\d{2}-\d{2}$/;

/** The legacy deterministic storage object for a user's day. */
export function photoStoragePath(userId: string, date: string): string {
  if (!userId.trim()) throw new Error("photoStoragePath: missing userId");
  if (!DATE_KEY.test(date)) throw new Error("photoStoragePath: invalid date");
  return `${userId}/${date}.jpg`;
}

/**
 * A replacement upload goes to a fresh object. Reusing `{date}.jpg` with
 * `upsert` would destroy the stored photo before the user saves, so an
 * abandoned edit could never restore it.
 */
export function photoUploadStoragePath(
  userId: string,
  date: string,
  nonce: string
): string {
  if (!userId.trim()) throw new Error("photoUploadStoragePath: missing userId");
  if (!DATE_KEY.test(date)) throw new Error("photoUploadStoragePath: invalid date");
  const token = nonce.trim().replace(/[^a-zA-Z0-9_-]/g, "");
  if (!token) throw new Error("photoUploadStoragePath: missing nonce");
  return `${userId}/${date}-${token}.jpg`;
}

/**
 * Extract the Storage object path from a public bucket URL, or null when the
 * URL does not belong to this bucket. Used for best-effort cleanup after a
 * successful replacement/clear.
 */
export function photoStoragePathFromUrl(
  url: string | null | undefined
): string | null {
  if (!isStoredPhotoUrl(url)) return null;
  try {
    const parsed = new URL(url.trim());
    const marker = `/storage/v1/object/public/${PHOTO_BUCKET}/`;
    const index = parsed.pathname.indexOf(marker);
    if (index < 0) return null;
    const path = decodeURIComponent(parsed.pathname.slice(index + marker.length));
    return path.trim() ? path : null;
  } catch {
    return null;
  }
}

/**
 * A path is eligible for abandoned-upload cleanup only when it is a unique
 * staging object owned by this user and (when supplied) this date. The legacy
 * canonical `{user}/{date}.jpg` object is deliberately excluded.
 */
export function stagedPhotoStoragePathFromUrl(
  url: string | null | undefined,
  userId: string,
  date?: string,
  expectedOrigin?: string
): string | null {
  if (!isStoredPhotoUrl(url) || !userId.trim()) return null;
  if (date !== undefined && !isCalendarDateKey(date)) return null;

  try {
    const parsed = new URL(url.trim());
    if (expectedOrigin !== undefined) {
      if (!expectedOrigin.trim()) return null;
      if (parsed.origin !== new URL(expectedOrigin).origin) return null;
    }

    const path = photoStoragePathFromUrl(url);
    if (!path) return null;
    const segments = path.split("/");
    if (segments.length !== 2 || segments[0] !== userId) return null;

    const match = /^(\d{4}-\d{2}-\d{2})-([a-zA-Z0-9_-]+)\.jpg$/.exec(
      segments[1]
    );
    if (!match || !isCalendarDateKey(match[1])) return null;
    if (date !== undefined && match[1] !== date) return null;
    return path;
  } catch {
    return null;
  }
}

function isCalendarDateKey(value: string): boolean {
  if (!DATE_KEY.test(value)) return false;
  const [year, month, day] = value.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
  );
}


function loadImage(file: Blob): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(url);
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(photoError("checkin.photo_error_read"));
    };
    image.src = url;
  });
}

function canvasToBlob(
  canvas: HTMLCanvasElement,
  quality: number
): Promise<Blob | null> {
  return new Promise((resolve) => {
    canvas.toBlob((blob) => resolve(blob), "image/jpeg", quality);
  });
}

/**
 * Compress to JPEG with the Android contract: longest edge 1600, quality 0.85,
 * then progressively lower quality until the 4.5 MB ceiling is met.
 */
export async function compressPhoto(file: File): Promise<Blob> {
  const validation = validatePhotoFile(file);
  if (validation) throw photoError(validation);

  const image = await loadImage(file);
  const scale = Math.min(
    1,
    PHOTO_MAX_EDGE / Math.max(image.width, image.height)
  );
  const width = Math.max(1, Math.round(image.width * scale));
  const height = Math.max(1, Math.round(image.height * scale));

  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d");
  if (!context) throw photoError("checkin.photo_error_read");
  context.drawImage(image, 0, 0, width, height);

  for (const quality of PHOTO_JPEG_QUALITIES) {
    const blob = await canvasToBlob(canvas, quality);
    if (blob && blob.size > 0 && blob.size <= PHOTO_MAX_OUTPUT_BYTES) {
      return blob;
    }
  }

  throw photoError("checkin.photo_error_size");
}
