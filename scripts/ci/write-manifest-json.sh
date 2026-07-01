#!/usr/bin/env bash
# Печатает manifest.json для OTA / static/releases на сервере.
set -euo pipefail

export VERSION_NAME="${1:?version_name}"
export VERSION_CODE="${2:?version_code}"
export FILENAME="${3:?apk_filename}"
export SHA256="${4:?sha256}"
export LOCATOR_PUBLIC_BASE_URL="${LOCATOR_PUBLIC_BASE_URL:-http://87.232.65.52:8080}"
export ANDROID_PACKAGE_NAME="${ANDROID_PACKAGE_NAME:-com.example.lctr_app}"
export MANIFEST_FORCE="${MANIFEST_FORCE:-false}"
export MANIFEST_CHANGELOG="${MANIFEST_CHANGELOG:-Release ${VERSION_NAME} (build ${VERSION_CODE})}"

python3 <<'PY'
import json
import os

base = os.environ["LOCATOR_PUBLIC_BASE_URL"].rstrip("/")
filename = os.environ["FILENAME"]
force = os.environ.get("MANIFEST_FORCE", "false").lower() in ("1", "true", "yes")

manifest = {
    "version_name": os.environ["VERSION_NAME"],
    "version_code": int(os.environ["VERSION_CODE"]),
    "package_name": os.environ["ANDROID_PACKAGE_NAME"],
    "filename": filename,
    "sha256": os.environ["SHA256"],
    "force": force,
    "changelog": os.environ["MANIFEST_CHANGELOG"],
    "url": f"{base}/static/releases/{filename}",
}

print(json.dumps(manifest, indent=2, ensure_ascii=False))
PY
