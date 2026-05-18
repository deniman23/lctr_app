#!/usr/bin/env bash
# Создаёт keystore для подписи release (один раз). НЕ коммитить в git.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KEYSTORE_DIR="$ROOT/keystore"
JKS="$KEYSTORE_DIR/lctr-release.jks"
CREDS="$KEYSTORE_DIR/credentials.txt"
ALIAS="${KEY_ALIAS:-lctr}"

if [[ -f "$JKS" ]]; then
  echo "Уже существует: $JKS"
  echo "Удалите вручную, если нужно пересоздать."
  exit 1
fi

mkdir -p "$KEYSTORE_DIR"

if [[ -z "${KEYSTORE_PASSWORD:-}" ]]; then
  STORE_PASS=$(openssl rand -base64 32 | tr -d '/+=' | head -c 24)
else
  STORE_PASS="$KEYSTORE_PASSWORD"
fi
KEY_PASS="${KEY_PASSWORD:-$STORE_PASS}"

if ! command -v keytool >/dev/null; then
  echo "Установите JDK (keytool). На Mac: brew install openjdk" >&2
  exit 1
fi

keytool -genkeypair -v \
  -keystore "$JKS" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -dname "CN=Locator App, OU=Mobile, O=LCTR, C=RU"

chmod 600 "$JKS"

cat > "$CREDS" <<EOF
# Храните в безопасном месте (1Password / менеджер паролей). Файл в .gitignore.
KEYSTORE_FILE=$JKS
ANDROID_KEYSTORE_PASSWORD=$STORE_PASS
ANDROID_KEY_ALIAS=$ALIAS
ANDROID_KEY_PASSWORD=$KEY_PASS
EOF
chmod 600 "$CREDS"

echo ""
echo "Создано:"
echo "  Keystore: $JKS"
echo "  Пароли:   $CREDS"
echo ""
echo "Дальше на Mac:"
echo "  ./scripts/ci/print-github-secrets.sh"
echo ""
echo "Скопируйте значения в GitHub → Settings → Secrets and variables → Actions"
