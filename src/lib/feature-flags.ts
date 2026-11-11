/**
 * Feature flags for Diarium
 *
 * Reads from NEXT_PUBLIC_FEATURES env var (comma-separated).
 * Defaults to 'core' when not set — portfolio-ready, no personal/sensitive features.
 * Set to 'personal' to enable the full version (screen time, sensitive habits, HA).
 *
 * Usage:
 *   import { getFeatureFlags } from "@/lib/feature-flags";
 *   const flags = getFeatureFlags();
 *   if (flags.screenTime) { ... }
 */

export type FeatureMode = "core" | "personal";

export interface FeatureFlags {
  /** Screen time tracking display (phone_screen_time stats, charts) */
  screenTime: boolean;
  /** Sensitive habit tracking (kouření, studená sprcha) — core mode hides these */
  habitTracking: boolean;
  /** Home Assistant integration (screen time data from HA, HA-specific widgets) */
  homeAssistant: boolean;
  /** Phone unlock display */
  phoneUnlocks: boolean;
}

/** Sensitive habit keys that are hidden in core mode */
export const SENSITIVE_HABIT_KEYS = ["koureni", "studena_sprcha"];

/**
 * Determine the current feature mode from NEXT_PUBLIC_FEATURES.
 * 'personal' in the comma-separated list enables personal mode.
 * Anything else (including empty/unset) defaults to 'core'.
 */
function getFeatureMode(): FeatureMode {
  if (typeof process === "undefined" || !process.env) return "core";
  const raw = process.env.NEXT_PUBLIC_FEATURES || "";
  const modes = raw
    .split(",")
    .map((m) => m.trim().toLowerCase());
  if (modes.includes("personal")) return "personal";
  return "core";
}

/**
 * Returns the full set of resolved feature flags based on the current mode.
 */
export function getFeatureFlags(): FeatureFlags {
  const isPersonal = getFeatureMode() === "personal";

  return {
    screenTime: isPersonal,
    habitTracking: isPersonal,
    homeAssistant: isPersonal,
    phoneUnlocks: isPersonal,
  };
}

/**
 * Convenience check — returns true when personal mode is active.
 */
export function isPersonalMode(): boolean {
  return getFeatureMode() === "personal";
}
