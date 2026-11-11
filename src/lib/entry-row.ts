/**
 * The single row builder shared by the browser data layer and the route.
 *
 * Semantics are intentionally explicit:
 * - a field that is absent from the payload is omitted, so a partial AI patch
 *   cannot wipe mood/weather/photo;
 * - `weather: []` is a real clear (the old client sent `[]` unconditionally);
 * - `photo_path: null` is ignored unless `clear_photo: true` accompanies it;
 * - `sleep_quality: 0` is "not answered" and never overwrites a stored value;
 * - `data:` photo values are rejected, because the row stores a bounded URL.
 */
import type { CheckInPayload } from "./types.ts";
import { CheckInError } from "./checkin/errors.ts";
import { isStoredPhotoUrl } from "./checkin/photo.ts";
import { isValidDateKey } from "./checkin/dates.ts";

export interface EntryRowInput extends CheckInPayload {
  user_id: string;
  date: string;
}

function invalid(message: string): CheckInError {
  return new CheckInError(
    "save_failed",
    "checkin.error_save_validation",
    400,
    { cause: new Error(message) }
  );
}

function stringList(value: unknown, field: string): string[] {
  if (!Array.isArray(value)) throw invalid(`${field} must be an array`);
  return value.filter((item): item is string => typeof item === "string");
}

function booleanMap(value: unknown, field: string): Record<string, boolean> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw invalid(`${field} must be an object`);
  }
  const result: Record<string, boolean> = {};
  for (const [key, enabled] of Object.entries(value as Record<string, unknown>)) {
    if (!key.trim()) continue;
    if (typeof enabled !== "boolean") {
      throw invalid(`${field}.${key} must be a boolean`);
    }
    result[key] = enabled;
  }
  return result;
}

function numberMap(value: unknown, field: string): Record<string, number> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw invalid(`${field} must be an object`);
  }
  const result: Record<string, number> = {};
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (!key.trim()) continue;
    if (typeof raw !== "number" || !Number.isFinite(raw)) {
      throw invalid(`${field}.${key} must be a finite number`);
    }
    // Zero is preserved: the route uses it to delete the mirrored scale row.
    result[key] = Math.trunc(raw);
  }
  return result;
}

/** Accept the worker's array shape and the legacy "App:minutes, ..." string. */
function topApps(value: unknown): { app: string; time_sec: number }[] {
  if (Array.isArray(value)) {
    return value
      .filter((item): item is Record<string, unknown> =>
        Boolean(item) && typeof item === "object"
      )
      .map((item) => ({
        app: typeof item.app === "string" ? item.app : "",
        time_sec:
          typeof item.time_sec === "number"
            ? Math.max(0, Math.trunc(item.time_sec))
            : typeof item.minutes === "number"
              ? Math.max(0, Math.round(item.minutes * 60))
              : 0,
      }))
      .filter((item) => item.app.trim().length > 0);
  }

  if (typeof value === "string" && value.trim()) {
    return value
      .split(",")
      .map((part) => {
        const [app, minutes] = part.trim().split(":");
        return {
          app: (app || "").trim(),
          time_sec: Math.max(0, parseInt(minutes || "0", 10) * 60),
        };
      })
      .filter((item) => item.app.length > 0 && item.time_sec > 0);
  }

  throw invalid("phone_top_apps must be an array or a legacy string");
}

/**
 * Build the `entries` row. Throws a `CheckInError` with the validation code for
 * malformed input; the route turns that into HTTP 400.
 */
export function buildEntryRow(payload: EntryRowInput): Record<string, unknown> {
  const userId = typeof payload.user_id === "string" ? payload.user_id.trim() : "";
  if (!userId) throw invalid("user_id is required");
  if (!isValidDateKey(payload.date)) throw invalid("date must be YYYY-MM-DD");

  const row: Record<string, unknown> = {
    user_id: userId,
    date: payload.date,
  };

  if (payload.mood !== undefined) {
    row.mood =
      typeof payload.mood === "number" && payload.mood > 0
        ? Math.min(5, Math.trunc(payload.mood))
        : null;
  }
  if (payload.mood_emoji !== undefined) {
    row.mood_emoji =
      typeof payload.mood_emoji === "string" ? payload.mood_emoji : "";
  }
  if (payload.sleep_quality !== undefined) {
    // Documented server rule: 0/null means unanswered and must not clear a
    // value written earlier (Android behaves the same way).
    if (
      typeof payload.sleep_quality === "number" &&
      payload.sleep_quality > 0
    ) {
      row.sleep_quality = Math.min(5, Math.trunc(payload.sleep_quality));
    }
  }
  if (payload.stress !== undefined) {
    row.stress =
      typeof payload.stress === "number" && payload.stress > 0
        ? Math.min(5, Math.trunc(payload.stress))
        : null;
  }
  if (payload.activities !== undefined) {
    row.activities = stringList(payload.activities, "activities").filter(
      (value) => value.trim().length > 0
    );
  }
  if (payload.habits !== undefined) {
    row.habits = booleanMap(payload.habits, "habits");
  }
  if (payload.gratitude !== undefined) {
    row.gratitude = stringList(payload.gratitude, "gratitude");
  }
  if (payload.note !== undefined) {
    if (typeof payload.note !== "string") throw invalid("note must be a string");
    row.note = payload.note;
  }
  if (payload.weather !== undefined) {
    // An explicit empty array is a real clear; do not turn it into "omitted".
    row.weather = stringList(payload.weather, "weather").filter(
      (value) => value.trim().length > 0
    );
  }
  if (payload.scale_values !== undefined) {
    row.scale_values = numberMap(payload.scale_values, "scale_values");
  }

  const hasPhoto = payload.photo_path !== undefined && payload.photo_path !== null;
  if (payload.clear_photo === true) {
    if (hasPhoto) throw invalid("clear_photo cannot be combined with photo_path");
    row.photo_path = null;
  } else if (hasPhoto) {
    if (!isStoredPhotoUrl(payload.photo_path)) {
      throw invalid("photo_path must be a public https URL, not base64");
    }
    row.photo_path = payload.photo_path.trim();
  }
  // photo_path: null without clear_photo is deliberately ignored: a partial
  // payload must not delete the stored photo.

  if (payload.phone_screen_time !== undefined) {
    row.phone_screen_time = payload.phone_screen_time;
  }
  if (payload.phone_unlocks !== undefined) {
    row.phone_unlocks = payload.phone_unlocks;
  }
  if (payload.phone_top_apps !== undefined) {
    row.phone_top_apps = topApps(payload.phone_top_apps);
  }
  if (payload.ai_reflection !== undefined) {
    // The AI flow persists prose only. An empty value must not erase a stored
    // reflection, so it is omitted rather than written as "".
    if (typeof payload.ai_reflection === "string" && payload.ai_reflection.trim()) {
      row.ai_reflection = payload.ai_reflection;
    }
  }

  return row;
}
