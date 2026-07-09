#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLING_DIR="$ROOT_DIR/.tooling"
ARCHIVE="$TOOLING_DIR/temurin-21-mac-x64.tar.gz"
mkdir -p "$TOOLING_DIR"

if find "$TOOLING_DIR" -maxdepth 2 -type d -name 'jdk*' | grep -q .; then
  echo "A local JDK already exists in $TOOLING_DIR"
  exit 0
fi

curl -L -C - \
  --retry 8 \
  --retry-all-errors \
  --connect-timeout 20 \
  --speed-limit 1024 \
  --speed-time 30 \
  "https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jdk/hotspot/normal/eclipse?project=jdk" \
  -o "$ARCHIVE"
tar -xzf "$ARCHIVE" -C "$TOOLING_DIR"
rm -f "$ARCHIVE"
find "$TOOLING_DIR" -maxdepth 2 -type d -name 'jdk*' -print -quit
