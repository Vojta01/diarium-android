/**
 * The check-in form model.
 *
 * `CheckInData` is the single shape the form edits. It contains only fields the
 * form owns plus read-only fields copied from the row (phone/screen time and the
 * AI reflection). `toPayload` in `src/lib/checkin/payload.ts` never writes the
 * read-only fields back, so a form save cannot wipe data owned by the sync
 * worker or the AI flow.
 */
export interface CheckInTopApp {
  app: string;
  /** Seconds of use. The phone worker stores seconds; legacy rows stored minutes. */
  time_sec: number;
}

export interface CheckInData {
  /** 0 = not answered, 1..5 = recorded mood. */
  mood: number;
  moodEmoji: string;
  /** 0 = not answered, 1..5 = recorded sleep quality. */
  sleepQuality: number;
  /** 0 = not answered, 1..5 = recorded stress. */
  stress: number;
  /** Stored activity labels (the historical contract, not catalogue keys). */
  activities: string[];
  /** Only keys the user touched; absence means "not answered", not false. */
  habits: Record<string, boolean>;
  /** At least three slots; extra legacy values are preserved. */
  gratitude: string[];
  note: string;
  /** Canonical weather keys, e.g. `dest`. Unknown legacy values are kept verbatim. */
  weather: string[];
  /** Public Storage URL only. A legacy `data:` value is never treated as a path. */
  photoPath: string | null;
  /** Persisted explicit removal; absence of a photo alone means preserve. */
  photoCleared: boolean;
  /** Includes 0 for a cleared visible scale so the route can delete its mirror row. */
  scaleValues: Record<string, number>;
  // ── Read-only: copied from the row, never written by the form ──
  phoneScreenTime: number | null;
  phoneUnlocks: number | null;
  phoneTopApps: CheckInTopApp[];
  aiReflection: string | null;
  /** True when the stored photo is a legacy base64 value that needs re-uploading. */
  photoLegacy: boolean;
}

/**
 * The wire contract for `/api/save-entry` and `saveEntry`.
 *
 * Every field is optional on purpose: the route is also used by the phone sync
 * worker and the AI flow for partial updates. `buildEntryRow` emits only the
 * fields that are present, so an AI patch cannot wipe mood, weather or photo.
 */
export interface CheckInPayload {
  date?: string;
  mood?: number | null;
  mood_emoji?: string | null;
  sleep_quality?: number | null;
  stress?: number | null;
  activities?: string[];
  habits?: Record<string, boolean>;
  gratitude?: string[];
  note?: string;
  weather?: string[];
  /** Public `https://` URL only. `data:` is rejected by the route. */
  photo_path?: string | null;
  /** Explicit removal; only valid when no new `photo_path` is supplied. */
  clear_photo?: boolean;
  /** V2 removes these scale keys and mirrors atomically; zero alone is not clear. */
  clear_scale_ids?: string[];
  scale_values?: Record<string, number>;
  phone_screen_time?: number | null;
  phone_unlocks?: number | null;
  phone_top_apps?: unknown;
  ai_reflection?: string | null;
}
