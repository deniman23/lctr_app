#!/usr/bin/env bash
# Собирает release APK и копирует на сервер (настройте SERVER и REMOTE_DIR).
set -euo pipefail

SERVER="${LOCATOR_SERVER:-user@87.232.65.52}"
REMOTE_DIR="${LOCATOR_RELEASES_DIR:-/var/www/locator/static/releases}"
VERSION_NAME="${1:-1.1.0}"
VERSION_CODE="${2:-2}"

cd "$(dirname "$0")/.."
./gradlew :app:assembleRelease

APK="app/build/outputs/apk/release/app-release-unsigned.apk"
OUT_NAME="locator-${VERSION_NAME}-${VERSION_CODE}.apk"

if [[ ! -f "$APK" ]]; then
  APK="app/build/outputs/apk/release/app-release.apk"
fi

sha256=$(shasum -a 256 "$APK" | awk '{print $1}')
echo "SHA256: $sha256"
echo "Upload: $APK -> $SERVER:$REMOTE_DIR/$OUT_NAME"

scp "$APK" "$SERVER:$REMOTE_DIR/$OUT_NAME"
echo "Poll command payload example:"
cat <<EOF
{
  "command": {
    "type": "app_update",
    "id": "update-$(date +%s)",
    "payload": {
      "url": "/static/releases/$OUT_NAME",
      "version": "$VERSION_NAME",
      "version_code": $VERSION_CODE,
      "sha256": "$sha256",
      "wifi_only": true,
      "install_when_idle": true
    }
  }
}
EOF
