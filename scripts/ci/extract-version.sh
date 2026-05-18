#!/usr/bin/env bash
set -euo pipefail
# BASH_SOURCE: корректный путь и при `source` из GitHub Actions (где $0 = bash)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PROPS="$ROOT/app/version.properties"

export VERSION_CODE
export VERSION_NAME

if [[ -f "$PROPS" ]]; then
  VERSION_CODE=$(grep -E '^versionCode=' "$PROPS" | cut -d= -f2 | tr -d '[:space:]')
  VERSION_NAME=$(grep -E '^versionName=' "$PROPS" | cut -d= -f2 | tr -d '[:space:]')
else
  GRADLE="$ROOT/app/build.gradle.kts"
  VERSION_CODE=$(grep -E '^\s*versionCode\s*=' "$GRADLE" | head -1 | grep -oE '[0-9]+')
  VERSION_NAME=$(grep -E '^\s*versionName\s*=' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')
fi

if [[ -z "$VERSION_CODE" || -z "$VERSION_NAME" ]]; then
  echo "Failed to read version from $PROPS" >&2
  exit 1
fi

echo "version_code=$VERSION_CODE"
echo "version_name=$VERSION_NAME"
