/**
 * Pure mapping between the `entries` row / draft JSON and `CheckInData`.
 *
 * Rules that matter for safety:
 * - A failed or missing value becomes an explicit "not answered" (0/null/[]),
 *   never a neutral 3 or a default object.
 * - Fields the form does not own (phone*, ai_reflection) are copied for display
 *   but never written back by `buildCheckInPayload`.
 * - A legacy `data:` photo is never treated as a public path.
 */
import type { CheckInData, CheckInTopApp } from "../types.ts";
import { isRecordedMood } from "../mood.ts";
import { weatherKey } from "../dashboard-ui.ts";
import { isLegacyPhotoValue, isStoredPhotoUrl } from "./photo.ts";

/**
 * The Android beta5 section order. Goals, screen time and the AI reflection are
 * reserved here on purpose: phase 2A renders through `note`, and the later
 * phases only need to add their components to the map — the order cannot drift.
 */
export const CHECKIN_SECTION_ORDER = [
  "mood",
  "sleep",
  "stress",
  "activities",
  "habits",
  "gratitude",
  "weather",
  "photo",
  "scales",
  "note",
  "goals",
  "screenTime",
  "reflection",
] as const;

export type CheckInSectionId = (typeof CHECKIN_SECTION_ORDER)[number];

/** Sections this phase renders; the full Android beta5 order. */
export const CHECKIN_RENDERED_SECTIONS: readonly CheckInSectionId[] =
  CHECKIN_SECTION_ORDER;

/**
 * The rendered order for the active feature flags. The canonical 13-section
 * order never changes; a disabled feature is simply omitted (screen time is
 * personal-mode data, not a core check-in field).
 */
export function visibleCheckInSections(flags: {
  screenTime: boolean;
}): readonly CheckInSectionId[] {
  return CHECKIN_SECTION_ORDER.filter(
    (id) => id !== "screenTime" || flags.screenTime
  );
}

/** Goals are a read-only report; a failed read must not render an empty block. */
export function shouldRenderGoalsSection(error: string | null): boolean {
  return !error;
}


export const EMPTY_CHECKIN: CheckInData = {
  mood: 0,
  moodEmoji: "",
  sleepQuality: 0,
  stress: 0,
  activities: [],
  habits: {},
  gratitude: ["", "", ""],
  note: "",
  weather: [],
  photoPath: null,
  photoCleared: false,
  scaleValues: {},
  phoneScreenTime: null,
  phoneUnlocks: null,
  phoneTopApps: [],
  aiReflection: null,
  photoLegacy: false,
};

export function emptyCheckIn(): CheckInData {
  return {
    ...EMPTY_CHECKIN,
    activities: [],
    habits: {},
    gratitude: ["", "", ""],
    weather: [],
    photoCleared: false,
    scaleValues: {},
    phoneTopApps: [],
  };
}

function integerInRange(value: unknown, min: number, max: number): number {
  if (typeof value !== "number" || !Number.isFinite(value)) return 0;
  const rounded = Math.trunc(value);
  return rounded >= min && rounded <= max ? rounded : 0;
}

function optionalNumber(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) return null;
  return Math.max(0, Math.trunc(value));
}

function stringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is string => typeof item === "string");
}

function nonEmptyStringArray(value: unknown): string[] {
  return stringArray(value).filter((item) => item.trim().length > 0);
}

/** Exactly three editable slots, while keeping any extra legacy values. */
function gratitudeSlots(value: unknown): string[] {
  const values = stringArray(value).slice();
  while (values.length < 3) values.push("");
  return values;
}

function booleanMap(value: unknown): Record<string, boolean> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const result: Record<string, boolean> = {};
  for (const [key, enabled] of Object.entries(value as Record<string, unknown>)) {
    if (!key.trim() || typeof enabled !== "boolean") continue;
    result[key] = enabled;
  }
  return result;
}

function numericMap(value: unknown): Record<string, number> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const result: Record<string, number> = {};
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (!key.trim() || typeof raw !== "number" || !Number.isFinite(raw)) continue;
    // Keep 0: it is the explicit "clear this scale" instruction.
    result[key] = Math.trunc(raw);
  }
  return result;
}

function topApps(value: unknown): CheckInTopApp[] {
  if (!Array.isArray(value)) return [];
  const result: CheckInTopApp[] = [];
  for (const raw of value) {
    if (!raw || typeof raw !== "object") continue;
    const app = (raw as { app?: unknown }).app;
    if (typeof app !== "string" || !app.trim()) continue;
    const seconds = (raw as { time_sec?: unknown }).time_sec;
    const minutes = (raw as { minutes?: unknown }).minutes;
    const timeSec =
      typeof seconds === "number" && Number.isFinite(seconds)
        ? Math.max(0, Math.trunc(seconds))
        : typeof minutes === "number" && Number.isFinite(minutes)
          ? Math.max(0, Math.round(minutes * 60))
          : null;
    if (timeSec == null) continue;
    result.push({ app: app.trim(), time_sec: timeSec });
  }
  return result;
}

/**
 * Normalise stored weather to catalogue keys. Known Czech/English labels are
 * mapped through `weatherKey`; an unknown legacy value is kept verbatim so a
 * save does not silently delete it.
 */
export function normalizeWeather(value: unknown): string[] {
  const result: string[] = [];
  for (const raw of stringArray(value)) {
    const trimmed = raw.trim();
    if (!trimmed) continue;
    const key = weatherKey(trimmed) ?? trimmed;
    if (!result.includes(key)) result.push(key);
  }
  return result;
}

/** Map a snake_case `entries` row to the form model. */
export function fromEntry(row: Record<string, unknown>): CheckInData {
  const rawPhoto = row.photo_path;
  const mood = isRecordedMood(row.mood) ? row.mood : 0;

  return {
    mood,
    moodEmoji: typeof row.mood_emoji === "string" ? row.mood_emoji : "",
    sleepQuality: integerInRange(row.sleep_quality, 1, 5),
    stress: integerInRange(row.stress, 1, 5),
    activities: nonEmptyStringArray(row.activities),
    habits: booleanMap(row.habits),
    gratitude: gratitudeSlots(row.gratitude),
    note: typeof row.note === "string" ? row.note : "",
    weather: normalizeWeather(row.weather),
    photoPath: isStoredPhotoUrl(rawPhoto) ? rawPhoto.trim() : null,
    photoCleared: false,
    scaleValues: numericMap(row.scale_values),
    phoneScreenTime: optionalNumber(row.phone_screen_time),
    phoneUnlocks: optionalNumber(row.phone_unlocks),
    phoneTopApps: topApps(row.phone_top_apps),
    aiReflection:
      typeof row.ai_reflection === "string" && row.ai_reflection.trim()
        ? row.ai_reflection
        : null,
    photoLegacy: isLegacyPhotoValue(rawPhoto),
  };
}

/**
 * Restore a draft. Drafts are camelCase, may be from an older web build, and
 * must never smuggle base64 photo bytes or stale read-only phone/AI fields into
 * the form. Unknown keys are ignored.
 */
export function fromDraft(raw: unknown): CheckInData {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    return emptyCheckIn();
  }
  const draft = raw as Record<string, unknown>;

  // Old web drafts used `photoDataUrl`; only a URL is safe to keep.
  const rawPhoto =
    draft.photoPath !== undefined ? draft.photoPath : draft.photoDataUrl;
  const photoPath = isStoredPhotoUrl(rawPhoto) ? rawPhoto.trim() : null;

  return {
    mood: integerInRange(draft.mood, 1, 5),
    moodEmoji: typeof draft.moodEmoji === "string" ? draft.moodEmoji : "",
    sleepQuality: integerInRange(draft.sleepQuality, 1, 5),
    stress: integerInRange(draft.stress, 1, 5),
    activities: nonEmptyStringArray(draft.activities),
    habits: booleanMap(draft.habits),
    gratitude: gratitudeSlots(draft.gratitude),
    note: typeof draft.note === "string" ? draft.note : "",
    weather: normalizeWeather(draft.weather),
    photoPath,
    photoCleared: draft.photoCleared === true && photoPath === null,
    scaleValues: numericMap(draft.scaleValues),
    // Drafts deliberately exclude worker/AI fields; restoring them from
    // localStorage would show stale data from a previous sync.
    phoneScreenTime: null,
    phoneUnlocks: null,
    phoneTopApps: [],
    aiReflection: null,
    photoLegacy: isLegacyPhotoValue(rawPhoto),
  };
}

/** Draft JSON: form-owned fields only, no phone/AI, no base64 photo bytes. */
export function toDraft(data: CheckInData): Record<string, unknown> {
  return {
    mood: data.mood,
    moodEmoji: data.moodEmoji,
    sleepQuality: data.sleepQuality,
    stress: data.stress,
    activities: data.activities,
    habits: data.habits,
    gratitude: data.gratitude,
    note: data.note,
    weather: data.weather,
    // Staged uploads survive a reload as owner/date-scoped draft intent. The
    // lifecycle keeps a persisted staged object until it is saved or discarded.
    photoPath: isStoredPhotoUrl(data.photoPath) ? data.photoPath : null,
    photoCleared: data.photoCleared,
    scaleValues: data.scaleValues,
  };
}

/** Gratitude values that will be persisted (blank slots dropped, extras kept). */
export function filteredGratitude(values: readonly string[]): string[] {
  return values.map((value) => value.trim()).filter((value) => value.length > 0);
}

/** Scale values above zero, for display and the AI prompt. */
export function positiveScaleValues(
  values: Record<string, number>
): Record<string, number> {
  const result: Record<string, number> = {};
  for (const [key, value] of Object.entries(values)) {
    if (typeof value === "number" && Number.isFinite(value) && value > 0) {
      result[key] = value;
    }
  }
  return result;
}

/**
 * Android's `DiaryEntry.hasContent()`: only fields the form owns count. Phone
 * data and an AI reflection do not make an otherwise empty day saveable.
 */
export function hasContent(data: CheckInData): boolean {
  return (
    data.mood > 0 ||
    data.sleepQuality > 0 ||
    data.stress > 0 ||
    data.activities.length > 0 ||
    Object.values(data.habits).some(Boolean) ||
    data.note.trim().length > 0 ||
    data.gratitude.some((value) => value.trim().length > 0) ||
    Object.values(data.scaleValues).some((value) => value >= 0) ||
    data.photoCleared ||
    isStoredPhotoUrl(data.photoPath) ||
    data.weather.length > 0
  );
}
