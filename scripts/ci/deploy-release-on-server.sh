#!/usr/bin/env bash
# Запуск на сервере после SCP: symlink locator-latest.apk + проверка manifest.json.
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

python3 -c '
import json
import sys

apk = sys.argv[1]
with open("manifest.json", encoding="utf-8") as f:
    m = json.load(f)

errs = []
if m.get("filename") != apk:
    errs.append("filename: manifest=%r != %r" % (m.get("filename"), apk))
for key in ("version_code", "version_name", "sha256", "url"):
    if not m.get(key):
        errs.append("missing " + key)

if errs:
    print("manifest validation FAILED:", file=sys.stderr)
    for e in errs:
        print(" -", e, file=sys.stderr)
    sys.exit(1)

print("manifest.json OK")
print(json.dumps(m, indent=2, ensure_ascii=False))
' "$APK_FILENAME"

ls -la locator-latest.apk manifest.json "$APK_FILENAME"
ls -1t locator-*.apk 2>/dev/null | tail -n +6 | xargs -r rm -f || true
