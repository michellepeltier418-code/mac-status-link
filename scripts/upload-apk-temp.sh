#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="${1:-$ROOT_DIR/dist/MacStatusLink.apk}"

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found: $APK_PATH" >&2
  echo "Run npm run build:apk first." >&2
  exit 1
fi

TEMP_RESPONSE="$(curl -fsS -F "file=@$APK_PATH" https://temp.sh/upload 2>/dev/null || true)"
if [[ "$TEMP_RESPONSE" == http* ]]; then
  echo "$TEMP_RESPONSE"
  exit 0
fi

TMPFILES_RESPONSE="$(curl -fsS -F "file=@$APK_PATH" https://tmpfiles.org/api/v1/upload)"
TMPFILES_URL="$(node -e 'const fs=require("fs"); const data=JSON.parse(fs.readFileSync(0,"utf8")); console.log(data.data && data.data.url ? data.data.url : "");' <<< "$TMPFILES_RESPONSE")"

if [[ -z "$TMPFILES_URL" ]]; then
  echo "Upload failed. temp.sh response: $TEMP_RESPONSE" >&2
  echo "tmpfiles.org response: $TMPFILES_RESPONSE" >&2
  exit 1
fi

echo "$TMPFILES_URL"
