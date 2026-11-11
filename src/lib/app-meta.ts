/**
 * Web build identity shown on the About screen.
 *
 * `tests/app-meta.test.ts` keeps these values in sync with package.json, so a
 * version bump cannot silently leave the About screen stale.
 */
export const WEB_PACKAGE_NAME = "diarium";
export const WEB_VERSION = "0.1.0";

/**
 * Android build the web is aligned with. One constant so About can show both
 * versions without a second hardcoded copy; bump it with the Android release.
 */
export const ANDROID_VERSION = "2.0.0-beta5";
