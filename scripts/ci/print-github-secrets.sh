#!/usr/bin/env bash
# Печатает значения для GitHub Secrets (запускать локально после create-keystore.sh).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JKS="$ROOT/keystore/lctr-release.jks"
CREDS="$ROOT/keystore/credentials.txt"

if [[ ! -f "$JKS" ]]; then
  echo "Сначала: ./scripts/ci/create-keystore.sh" >&2
  exit 1
fi

# shellcheck disable=SC1090
source "$CREDS"

echo "========== GitHub Secrets (Repository → Settings → Secrets) =========="
echo ""
echo "ANDROID_KEYSTORE_BASE64"
echo "(скопируйте одну строку ниже)"
base64 < "$JKS" | tr -d '\n'
echo ""
echo ""
echo "ANDROID_KEYSTORE_PASSWORD = $ANDROID_KEYSTORE_PASSWORD"
echo "ANDROID_KEY_ALIAS         = $ANDROID_KEY_ALIAS"
echo "ANDROID_KEY_PASSWORD      = $ANDROID_KEY_PASSWORD"
echo ""
echo "DEPLOY_SSH_HOST     = 87.232.65.52"
echo "DEPLOY_SSH_USER     = <ваш SSH пользователь>"
echo "DEPLOY_SSH_KEY      = <приватный ключ SSH, весь файл>"
echo "DEPLOY_REMOTE_DIR   = /root/locator_go/backend/static/releases"
echo "LOCATOR_PUBLIC_BASE_URL (variable) = http://87.232.65.52:8080"
echo "LOCATOR_ADMIN_API_KEY = <когда будет API на сервере, можно позже>"
echo ""
echo "========== Проверка подписи локально =========="
echo "export ANDROID_KEYSTORE_PATH=$JKS"
echo "export ANDROID_KEYSTORE_PASSWORD=***"
echo "export ANDROID_KEY_ALIAS=$ANDROID_KEY_ALIAS"
echo "export ANDROID_KEY_PASSWORD=***"
echo "./gradlew :app:assembleRelease"
