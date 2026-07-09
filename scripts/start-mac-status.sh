#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

export MAC_STATUS_PORT="${MAC_STATUS_PORT:-5178}"
exec node server/mac-status-server.js
