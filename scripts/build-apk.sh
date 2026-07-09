#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
DIST_DIR="$ROOT_DIR/dist"
mkdir -p "$DIST_DIR"

if [[ -z "${JAVA_HOME:-}" ]]; then
  LOCAL_JDK="$(find "$ROOT_DIR/.tooling" -maxdepth 2 -type d -name 'jdk*' 2>/dev/null | head -n 1 || true)"
  if [[ -n "$LOCAL_JDK" ]]; then
    export JAVA_HOME="$LOCAL_JDK/Contents/Home"
  fi
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JAVA_HOME must point to a JDK. Run scripts/install-local-jdk.sh first or install JDK 17+." >&2
  exit 1
fi

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"

if [[ ! -d "$ANDROID_HOME/platforms/android-36" ]]; then
  echo "Android SDK platform android-36 was not found at $ANDROID_HOME." >&2
  exit 1
fi

GRADLE_BIN="${GRADLE_BIN:-}"
if [[ -z "$GRADLE_BIN" ]]; then
  CACHED_GRADLE=( "$HOME"/.gradle/wrapper/dists/gradle-8.14.3-bin/*/gradle-8.14.3/bin/gradle )
  if [[ -x "${CACHED_GRADLE[0]:-}" ]]; then
    GRADLE_BIN="${CACHED_GRADLE[0]}"
  elif command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN="$(command -v gradle)"
  else
    echo "Gradle 8.14.3 is not cached and gradle is not on PATH." >&2
    exit 1
  fi
fi

cd "$ANDROID_DIR"
if [[ "${GRADLE_OFFLINE:-1}" == "0" ]]; then
  "$GRADLE_BIN" :app:assembleDebug
else
  "$GRADLE_BIN" --offline :app:assembleDebug
fi
cp "$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk" "$DIST_DIR/MacStatusLink.apk"
echo "$DIST_DIR/MacStatusLink.apk"
