#!/usr/bin/env bash
# Выполняется на сервере по SSH после SCP: symlink + проверка manifest.json.
set -euo pipefail

REMOTE_DIR="${1:?remote_dir}"
APK_FILENAME="${2:?apk_filename}"

cd "$REMOTE_DIR"

if [[ ! -f "$APK_FILENAME" ]]; then
  echo "ERROR: APK not found: $REMOTE_DIR/$APK_FILENAME" >&2
  exit 1
fi

if [[ ! -f manifest.json ]]; then
  echo "ERROR: manifest.json not found in $REMOTE_DIR" >&2
  exit 1
fi

ln -sf "$APK_FILENAME" locator-latest.apk

python3 <<PY
import json
import sys

with open("manifest.json", encoding="utf-8") as f:
    m = json.load(f)

apk = "${APK_FILENAME}"
errors = []
if m.get("filename") != apk:
    errors.append(f"filename mismatch: manifest={m.get('filename')!r} apk={apk!r}")
if not m.get("sha256"):
    errors.append("sha256 is empty")
if not m.get("version_code"):
    errors.append("version_code is empty")
if not m.get("url"):
    errors.append("url is empty")

if errors:
    print("manifest.json validation FAILED:", file=sys.stderr)
    for e in errors:
        print(" -", e, file=sys.stderr)
    sys.exit(1)

print("manifest.json OK")
print(json.dumps(m, indent=2, ensure_ascii=False))
PY

ls -la locator-latest.apk manifest.json "$APK_FILENAME"
ls -1t locator-*.apk 2>/dev/null | tail -n +6 | xargs -r rm -f || true
