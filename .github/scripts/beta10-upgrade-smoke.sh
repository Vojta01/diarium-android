#!/usr/bin/env bash
set -euo pipefail

ARTIFACTS="$GITHUB_WORKSPACE/artifacts"
mkdir -p "$ARTIFACTS"
BETA9="$RUNNER_TEMP/diarium-beta9.apk"
BETA10="$GITHUB_WORKSPACE/test-apk/diarium-beta10.apk"
PACKAGE='cz.digitalnivedomi.diarium'
CERT='0804b65bbcfc97271a7951b4e7a922381ce4d01bf2312298257bfacd5a5afae6'
MARKER='diarium-agent-beta10-upgrade-smoke-marker-v1'

curl --fail --location --silent --show-error \
  'https://github.com/Vojta01/diarium-android/releases/download/v2.0.0-beta9/diarium-2.0.0-beta9.apk' \
  --output "$BETA9"
echo "63eab38f2ab426b9b9ba3cb514f8ba6c21c5c1ff04411aabcd9339b66f5a992c  $BETA9" | sha256sum --check

SDK_APKSIGNER="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -n1)"
SDK_AAPT="$(find "$ANDROID_HOME/build-tools" -name aapt -type f | sort -V | tail -n1)"
"$SDK_APKSIGNER" verify --print-certs "$BETA9" | tee "$ARTIFACTS/beta9-certificate.txt"
"$SDK_APKSIGNER" verify --print-certs "$BETA10" | tee "$ARTIFACTS/beta10-certificate.txt"
grep -F "certificate SHA-256 digest: $CERT" "$ARTIFACTS/beta9-certificate.txt"
grep -F "certificate SHA-256 digest: $CERT" "$ARTIFACTS/beta10-certificate.txt"
"$SDK_AAPT" dump badging "$BETA9" | grep -F "package: name='$PACKAGE' versionCode='2000109' versionName='2.0.0-beta9'" | tee "$ARTIFACTS/beta9-badging.txt"
"$SDK_AAPT" dump badging "$BETA10" | grep -F "package: name='$PACKAGE' versionCode='2000110' versionName='2.0.0-beta10'" | tee "$ARTIFACTS/beta10-badging.txt"

adb wait-for-device
adb install "$BETA9"
adb logcat -c
adb shell monkey -p "$PACKAGE" 1
sleep 8
adb logcat -d -t 5000 > "$ARTIFACTS/beta9-logcat.txt"
if grep -E 'FATAL EXCEPTION|Fatal signal|am_crash' "$ARTIFACTS/beta9-logcat.txt"; then
  echo 'beta9 launch produced a fatal crash' >&2
  exit 1
fi

if adb shell run-as "$PACKAGE" sh -c 'mkdir -p files && printf "%s" "$1" > files/agent-upgrade-smoke-marker.txt' sh "$MARKER" 2>/dev/null; then
  echo 'MARKER_SEEDED=true' | tee "$ARTIFACTS/marker-status.txt"
else
  echo 'MARKER_SEEDED=false; release APK does not permit run-as, so no private app data was accessed' | tee "$ARTIFACTS/marker-status.txt"
fi

adb install -r "$BETA10"
adb shell dumpsys package "$PACKAGE" > "$ARTIFACTS/package-after-upgrade.txt"
grep -E 'versionCode=2000110|minSdk=26|targetSdk=34' "$ARTIFACTS/package-after-upgrade.txt"
adb logcat -c
adb shell monkey -p "$PACKAGE" 1
sleep 10
adb logcat -d -t 8000 > "$ARTIFACTS/beta10-logcat.txt"
if grep -E 'FATAL EXCEPTION|Fatal signal|am_crash' "$ARTIFACTS/beta10-logcat.txt"; then
  echo 'beta10 launch produced a fatal crash' >&2
  exit 1
fi
if grep -q '^MARKER_SEEDED=true' "$ARTIFACTS/marker-status.txt"; then
  adb shell run-as "$PACKAGE" cat files/agent-upgrade-smoke-marker.txt > "$ARTIFACTS/marker-after-upgrade.txt"
  test "$(cat "$ARTIFACTS/marker-after-upgrade.txt")" = "$MARKER"
fi
