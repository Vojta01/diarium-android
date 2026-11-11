/**
 * Canonical public links. Screens must import these instead of hardcoding URLs.
 */
export const GITHUB_WEB_URL = "https://github.com/Vojta01/diarium";
export const GITHUB_ANDROID_URL = "https://github.com/Vojta01/diarium-android";
/** Stable APK published on the diarium-android `apk` branch. */
export const ANDROID_APK_URL =
  "https://raw.githubusercontent.com/Vojta01/diarium-android/apk/diarium.apk";

export const LINKS = {
  githubWeb: GITHUB_WEB_URL,
  githubAndroid: GITHUB_ANDROID_URL,
  androidApk: ANDROID_APK_URL,
} as const;
