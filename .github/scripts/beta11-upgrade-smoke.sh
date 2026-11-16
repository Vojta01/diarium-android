#!/usr/bin/env bash
set -euo pipefail

CANDIDATE_PATH='test-apk/diarium-beta11.apk'
SHA_FILE="$GITHUB_WORKSPACE/.github/beta11-candidate.sha256"
if [[ ! -f "$SHA_FILE" ]]; then
  echo 'Committed candidate SHA-256 file is missing' >&2
  exit 2
fi
CANDIDATE_SHA="$(<"$SHA_FILE")"
if [[ ! "$CANDIDATE_SHA" =~ ^[[:xdigit:]]{64}$ ]]; then
  echo 'Candidate SHA-256 must be exactly 64 hexadecimal characters' >&2
  exit 2
fi

ARTIFACTS="$GITHUB_WORKSPACE/artifacts"
mkdir -p "$ARTIFACTS"
BETA10="$RUNNER_TEMP/diarium-beta10.apk"
CANDIDATE="$GITHUB_WORKSPACE/$CANDIDATE_PATH"
PACKAGE='cz.digitalnivedomi.diarium'
CERT='0804b65bbcfc97271a7951b4e7a922381ce4d01bf2312298257bfacd5a5afae6'
MARKER='diarium-agent-beta11-upgrade-smoke-marker-v1'

[[ -f "$CANDIDATE" ]]
printf '%s  %s\n' "$CANDIDATE_SHA" "$CANDIDATE" | sha256sum --check

curl --fail --location --silent --show-error \
  'https://github.com/Vojta01/diarium-android/releases/download/v2.0.0-beta10/diarium-2.0.0-beta10.apk' \
  --output "$BETA10"
printf '%s  %s\n' '8ae394c44460b38a7b60dad066d6ea9637b350c1662002077a8cd4e964ed018e' "$BETA10" | sha256sum --check

SDK_APKSIGNER="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -n1)"
SDK_AAPT="$(find "$ANDROID_HOME/build-tools" -name aapt -type f | sort -V | tail -n1)"
"$SDK_APKSIGNER" verify --print-certs "$BETA10" | tee "$ARTIFACTS/beta10-certificate.txt"
"$SDK_APKSIGNER" verify --print-certs "$CANDIDATE" | tee "$ARTIFACTS/beta11-certificate.txt"
grep -F "certificate SHA-256 digest: $CERT" "$ARTIFACTS/beta10-certificate.txt"
grep -F "certificate SHA-256 digest: $CERT" "$ARTIFACTS/beta11-certificate.txt"
"$SDK_AAPT" dump badging "$BETA10" | grep -F "package: name='$PACKAGE' versionCode='2000110' versionName='2.0.0-beta10'" | tee "$ARTIFACTS/beta10-badging.txt"
"$SDK_AAPT" dump badging "$CANDIDATE" | grep -F "package: name='$PACKAGE' versionCode='2000111' versionName='2.0.0-beta11'" | tee "$ARTIFACTS/beta11-badging.txt"

adb wait-for-device
adb install "$BETA10"
check_app_crash() {
  local log_file="$1"
  local version="$2"
  # Emulator services can crash independently (the API 34 image's Bluetooth
  # service did so in the first run). Fail only on crash evidence attributed to
  # this package, not any system-wide Fatal signal in the shared logcat buffer.
  if ! python3 - "$log_file" "$PACKAGE" <<'PY'
import sys
from pathlib import Path

log_path, package = sys.argv[1:]
lines = Path(log_path).read_text(errors="replace").splitlines()
failures = []
for i, line in enumerate(lines):
    if "am_crash" in line and package in line:
        failures.append(line)
    if "FATAL EXCEPTION" in line:
        context = "\n".join(lines[i:min(len(lines), i + 16)])
        if f"Process: {package}" in context:
            failures.append(context)
    if "Fatal signal" in line and f"({package})" in line:
        failures.append(line)
if failures:
    print("\n".join(failures), file=sys.stderr)
    raise SystemExit(1)
PY
  then
    echo "$version launch produced a fatal app crash" >&2
    return 1
  fi
}

adb shell monkey -p "$PACKAGE" 1
sleep 8
adb logcat -d -t 5000 > "$ARTIFACTS/beta10-logcat.txt"
check_app_crash "$ARTIFACTS/beta10-logcat.txt" beta10

if adb shell run-as "$PACKAGE" sh -c 'mkdir -p files && printf "%s" "$1" > files/agent-upgrade-smoke-marker.txt' sh "$MARKER" 2>/dev/null; then
  echo 'MARKER_SEEDED=true' | tee "$ARTIFACTS/marker-status.txt"
else
  echo 'MARKER_SEEDED=false; release APK does not permit run-as, so no private app data was accessed' | tee "$ARTIFACTS/marker-status.txt"
fi

# Install over the existing app. Never uninstall, clear app data, or wipe the emulator.
adb install -r "$CANDIDATE"
adb shell dumpsys package "$PACKAGE" > "$ARTIFACTS/package-after-upgrade.txt"
grep -E 'versionCode=2000111|minSdk=26|targetSdk=34' "$ARTIFACTS/package-after-upgrade.txt"
adb shell monkey -p "$PACKAGE" 1
sleep 10
adb logcat -d -t 8000 > "$ARTIFACTS/beta11-logcat.txt"
check_app_crash "$ARTIFACTS/beta11-logcat.txt" beta11
if grep -q '^MARKER_SEEDED=true' "$ARTIFACTS/marker-status.txt"; then
  adb shell run-as "$PACKAGE" cat files/agent-upgrade-smoke-marker.txt > "$ARTIFACTS/marker-after-upgrade.txt"
  test "$(cat "$ARTIFACTS/marker-after-upgrade.txt")" = "$MARKER"
fi
