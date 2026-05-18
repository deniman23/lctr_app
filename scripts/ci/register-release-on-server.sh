#!/usr/bin/env bash
# Регистрирует релиз в locator_go (эндпоинт нужно реализовать на сервере).
set -euo pipefail

API_BASE="${LOCATOR_API_BASE:-http://178.172.235.51:8080}"
ADMIN_KEY="${LOCATOR_ADMIN_API_KEY:?set LOCATOR_ADMIN_API_KEY}"
VERSION_NAME="${1:?version_name}"
VERSION_CODE="${2:?version_code}"
APK_URL="${3:?apk_url}"
SHA256="${4:?sha256}"

BODY=$(cat <<EOF
{
  "version_name": "$VERSION_NAME",
  "version_code": $VERSION_CODE,
  "apk_url": "$APK_URL",
  "sha256": "$SHA256"
}
EOF
)

HTTP=$(curl -sS -o /tmp/register-release.json -w "%{http_code}" \
  -X POST "$API_BASE/api/admin/releases" \
  -H "Content-Type: application/json" \
  -H "X-Admin-Key: $ADMIN_KEY" \
  -d "$BODY")

echo "register-release HTTP $HTTP"
cat /tmp/register-release.json
[[ "$HTTP" == "200" || "$HTTP" == "201" ]] || exit 1
