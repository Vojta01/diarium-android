/**
 * Form model → `/api/save-entry` payload.
 *
 * The form owns mood/sleep/stress/activities/habits/gratitude/note/weather/
 * photo/scales. It never sends `phone_*` or `ai_reflection`: those columns belong
 * to the sync worker and the AI flow, and a form save must not wipe them.
 */
import type { CheckInData, CheckInPayload } from "../types.ts";
import { filteredGratitude, positiveScaleValues } from "./model.ts";
import { isStoredPhotoUrl } from "./photo.ts";

export interface BuildCheckInPayloadOptions {
  /** The user removed the photo and did not upload a replacement. */
  clearPhoto?: boolean;
}

export function buildCheckInPayload(
  data: CheckInData,
  date: string,
  options: BuildCheckInPayloadOptions = {}
): CheckInPayload {
  const photoPath = isStoredPhotoUrl(data.photoPath)
    ? data.photoPath.trim()
    : null;

  const payload: CheckInPayload = {
    date,
    mood: data.mood,
    mood_emoji: data.moodEmoji,
    sleep_quality: data.sleepQuality,
    stress: data.stress,
    activities: [...data.activities],
    habits: { ...data.habits },
    gratitude: filteredGratitude(data.gratitude),
    note: data.note,
    weather: [...data.weather],
    scale_values: { ...data.scaleValues },
  };

  const clearScaleIds = Object.entries(data.scaleValues)
    .filter(([, value]) => value === 0)
    .map(([scaleId]) => scaleId)
    .sort();
  if (clearScaleIds.length > 0) {
    // SQL v2 does not infer deletion from a zero. It uses this explicit list to
    // remove exactly these JSON keys and mirror rows in the same transaction.
    payload.clear_scale_ids = clearScaleIds;
  }

  if (photoPath) {
    payload.photo_path = photoPath;
  } else if (options.clearPhoto) {
    // Only an explicit removal may null the column. A legacy base64 photo
    // stays untouched until the user re-uploads it.
    payload.clear_photo = true;
  }

  return payload;
}

/** Values sent to the AI prompt: positive scales only, no zero clears. */
export function reflectScaleValues(data: CheckInData): Record<string, number> {
  return positiveScaleValues(data.scaleValues);
}
