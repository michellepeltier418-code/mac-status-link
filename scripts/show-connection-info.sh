#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOKEN_FILE="$ROOT_DIR/.mac-status-token"
PORT="${MAC_STATUS_PORT:-5178}"

if [[ ! -f "$TOKEN_FILE" ]]; then
  echo "Token file does not exist yet. Start the Mac service first:"
  echo "  npm run serve"
  exit 1
fi

echo "Mac Status Link phone setup"
echo
echo "Endpoint candidates:"
if command -v node >/dev/null 2>&1; then
  node -e 'const os=require("os"); for (const [name, entries] of Object.entries(os.networkInterfaces())) for (const entry of entries || []) if (entry.family === "IPv4" && !entry.internal) console.log(`  http://${entry.address}:${process.env.MAC_STATUS_PORT || 5178} (${name})`);'
elif command -v ipconfig >/dev/null 2>&1; then
  for device in en0 en1; do
    ip="$(ipconfig getifaddr "$device" 2>/dev/null || true)"
    if [[ -n "$ip" ]]; then
      echo "  http://$ip:$PORT ($device)"
    fi
  done
fi

echo
echo "Token:"
cat "$TOKEN_FILE"
echo
