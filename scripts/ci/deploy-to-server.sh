#!/usr/bin/env bash
# Деплой APK + manifest.json на сервер (GitHub Actions / локально).
set -euo pipefail

: "${DEPLOY_SSH_HOST:?DEPLOY_SSH_HOST}"
: "${DEPLOY_SSH_USER:?DEPLOY_SSH_USER}"
: "${DEPLOY_SSH_KEY_FILE:?DEPLOY_SSH_KEY_FILE}"
: "${DEPLOY_REMOTE_DIR:?DEPLOY_REMOTE_DIR}"
: "${APK_FILE:?APK_FILE}"
: "${MANIFEST_FILE:=manifest.json}"

LOCATOR_GO_RELEASES_DIR="${LOCATOR_GO_RELEASES_DIR:-/root/locator_go/backend/static/releases}"
DEPLOY_SSH_PORT="${DEPLOY_SSH_PORT:-22}"
SCP_TIMEOUT_SEC="${SCP_TIMEOUT_SEC:-900}"

SSH_OPTS=(
  -i "$DEPLOY_SSH_KEY_FILE"
  -p "$DEPLOY_SSH_PORT"
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
  -o BatchMode=yes
  -o ConnectTimeout=30
  -o ServerAliveInterval=15
  -o ServerAliveCountMax=4
  -o TCPKeepAlive=yes
)
SSH=(ssh "${SSH_OPTS[@]}" "${DEPLOY_SSH_USER}@${DEPLOY_SSH_HOST}")
SCP=(scp "${SSH_OPTS[@]}")

echo "Primary deploy dir: $DEPLOY_REMOTE_DIR"
echo "Locator_go dir:     $LOCATOR_GO_RELEASES_DIR"
echo "SSH port:           $DEPLOY_SSH_PORT"
echo "SCP timeout:        ${SCP_TIMEOUT_SEC}s"

echo "SSH preflight..."
"${SSH[@]}" "echo SSH_OK && hostname && df -h '$DEPLOY_REMOTE_DIR' '$LOCATOR_GO_RELEASES_DIR' 2>/dev/null | tail -2"

echo "mkdir on server..."
"${SSH[@]}" "mkdir -p '$DEPLOY_REMOTE_DIR' '$LOCATOR_GO_RELEASES_DIR'"

APK_SIZE=$(wc -c < "$APK_FILE" | tr -d ' ')
echo "Uploading APK (${APK_SIZE} bytes) + manifest + deploy script..."
timeout "$SCP_TIMEOUT_SEC" "${SCP[@]}" -v \
  "$APK_FILE" "$MANIFEST_FILE" deploy-release-on-server.sh \
  "${DEPLOY_SSH_USER}@${DEPLOY_SSH_HOST}:${DEPLOY_REMOTE_DIR}/"

echo "Finalize on server..."
timeout 120 "${SSH[@]}" bash -s "$DEPLOY_REMOTE_DIR" "$LOCATOR_GO_RELEASES_DIR" "$APK_FILE" <<'REMOTE'
set -euo pipefail
PRIMARY="$1"
GO_DIR="$2"
APK="$3"

bash "$PRIMARY/deploy-release-on-server.sh" "$PRIMARY" "$APK"

if [[ "$PRIMARY" != "$GO_DIR" ]]; then
  install -m 644 "$PRIMARY/manifest.json" "$GO_DIR/manifest.json"
  install -m 644 "$PRIMARY/$APK" "$GO_DIR/$APK"
  ln -sf "$APK" "$GO_DIR/locator-latest.apk"
  echo "Synced manifest + APK to $GO_DIR"
fi

echo "=== manifest: $PRIMARY ==="
cat "$PRIMARY/manifest.json"
if [[ "$PRIMARY" != "$GO_DIR" ]]; then
  echo "=== manifest: $GO_DIR ==="
  cat "$GO_DIR/manifest.json"
fi

echo "=== manifest.json under releases dirs ==="
find /root/locator_go /var/www -maxdepth 6 -path '*/releases/manifest.json' 2>/dev/null | sort -u | while read -r f; do
  code=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version_code"])' "$f" 2>/dev/null || echo "?")
  echo "$code  $f"
done
REMOTE

echo "Deploy finished."
