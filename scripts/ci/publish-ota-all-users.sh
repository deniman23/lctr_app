#!/usr/bin/env bash
# Отправляет команду app_update (из manifest.json) на всех пользователей с устройствами.
set -euo pipefail

API_BASE="${LOCATOR_API_BASE:-http://87.232.65.52:8080}"
API_KEY="${LOCATOR_ADMIN_API_KEY:-${DEFAULT_ADMIN_API_KEY:-}}"

if [[ -z "$API_KEY" ]]; then
  echo "::notice::LOCATOR_ADMIN_API_KEY not set — skip OTA publish"
  exit 0
fi

USERS_JSON=$(curl -fsS "${API_BASE}/api/users/" -H "X-API-Key: ${API_KEY}") || {
  echo "::warning::Failed to list users for OTA"
  exit 0
}

USER_IDS=$(echo "$USERS_JSON" | python3 -c "
import json, sys
users = json.load(sys.stdin)
if not isinstance(users, list):
    raise SystemExit('unexpected users response')
for u in users:
    uid = u.get('id')
    if uid is not None:
        print(uid)
")

if [[ -z "$USER_IDS" ]]; then
  echo "No users found — skip OTA"
  exit 0
fi

echo "Publishing OTA to users: $(echo "$USER_IDS" | tr '\n' ' ')"

ok=0
fail=0
while IFS= read -r uid; do
  [[ -z "$uid" ]] && continue
  HTTP=$(curl -sS -o "/tmp/ota-${uid}.json" -w "%{http_code}" \
    -X POST "${API_BASE}/api/admin/releases/publish-update/${uid}" \
    -H "X-API-Key: ${API_KEY}" \
    -H "Content-Type: application/json")
  if [[ "$HTTP" == "202" || "$HTTP" == "200" ]]; then
    echo "user $uid: OK (HTTP $HTTP)"
    ok=$((ok + 1))
  else
    echo "::warning::user $uid: HTTP $HTTP"
    cat "/tmp/ota-${uid}.json" || true
    fail=$((fail + 1))
  fi
done <<< "$USER_IDS"

echo "OTA publish done: ok=$ok fail=$fail"
[[ "$fail" -eq 0 ]]
