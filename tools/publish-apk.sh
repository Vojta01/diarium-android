#!/usr/bin/env bash
# Publishes a locally built, stable-signed APK to the rolling `apk` branch of
# Vojta01/diarium-android.
#
# Why a branch and not a GitHub Release: creating a release needs an
# authenticated API call, and the stored `gh` token expired (HTTP 401, verified
# 2026-09-11). Pushing a branch over SSH still works, and the APK is fetched with
# a plain link, no API and no rate limit:
#
#   https://github.com/Vojta01/diarium-android/raw/apk/diarium-<version>.apk
#
# Usage: publish-apk.sh <path-to-apk> <version>
set -euo pipefail

APK="${1:?usage: publish-apk.sh <path-to-apk> <version>}"
VER="${2:?usage: publish-apk.sh <path-to-apk> <version>}"
REPO="ssh://git@github.com/Vojta01/diarium-android.git"
WORK="/tmp/diarium-apk-branch"

test -f "$APK" || { echo "APK not found: $APK" >&2; exit 1; }

# Refuse to publish anything but the stable key: a debug-signed APK would force
# every user to uninstall first, which is exactly what the release key fixed.
# The expected fingerprint is the stable key's own (keytool -list -v, 2026-09-11).
EXPECTED_CERT="08:04:B6:5B:BC:FC:97:27:1A:79:51:B4:E7:A9:22:38:1C:E4:D0:1B:F2:31:22:98:25:7B:FA:CD:5A:5A:FA:E6"
APKSIGNER=$(ls -1 /opt/android-sdk/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
if [ -n "$APKSIGNER" ]; then
  ACTUAL=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr 'a-f' 'A-F' | sed 's/\(..\)/\1:/g; s/:$//')
  if [ "$ACTUAL" != "$EXPECTED_CERT" ]; then
    echo "REFUSING to publish: APK is signed with ${ACTUAL:-no cert (unsigned?)}" >&2
    echo "expected the stable key $EXPECTED_CERT" >&2
    exit 1
  fi
  echo "signature OK — stable key $(echo "$EXPECTED_CERT" | cut -c1-11)…"
else
  echo "apksigner not found — cannot verify the signature before publishing" >&2
  exit 1
fi

rm -rf "$WORK"
if git ls-remote --exit-code --heads "$REPO" apk >/dev/null 2>&1; then
  git clone --quiet --depth 1 --branch apk "$REPO" "$WORK"
else
  git clone --quiet --depth 1 "$REPO" "$WORK"
  cd "$WORK" && git checkout --quiet --orphan apk && git rm -rq --cached . || true
fi

cd "$WORK"
cp "$APK" "diarium.apk"
cp "$APK" "diarium-${VER}.apk"
cat > README.md <<EOF
# Diarium Android — buildy

Nejnovější: **${VER}**

* \`diarium-${VER}.apk\` — tato verze (odkaz pro stažení)
* \`diarium.apk\` — vždy nejnovější (GitHub u raw odkazů cachuje, proto mají
  jednotlivé verze vlastní soubor)

Podepsané stabilním release klíčem, takže se aplikace aktualizuje přes sebe,
bez odinstalace.
EOF
git add -A
git commit --quiet -m "apk: ${VER}"
git push --quiet origin apk
echo "PUBLISHED ${VER}"
echo "https://github.com/Vojta01/diarium-android/raw/apk/diarium-${VER}.apk"
