#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_PATH="$HOME/Desktop/Mac Status Link.app"
CONTENTS="$APP_PATH/Contents"
MACOS="$CONTENTS/MacOS"
RESOURCES="$CONTENTS/Resources"

mkdir -p "$MACOS" "$RESOURCES"

cat > "$CONTENTS/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key>
  <string>MacStatusLink</string>
  <key>CFBundleIdentifier</key>
  <string>com.michelle.mac-status-link.companion</string>
  <key>CFBundleName</key>
  <string>Mac Status Link</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>1.1.0</string>
  <key>LSMinimumSystemVersion</key>
  <string>10.13</string>
</dict>
</plist>
PLIST

cat > "$MACOS/MacStatusLink" <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="/Users/michelle/Documents/Codex/2026-07-08-make-an-app-for-my-blu"
cd "$ROOT_DIR"

scripts/install-launch-agent.sh >/dev/null 2>&1 || true

INFO="$(scripts/show-connection-info.sh)"
CLIENTS_FILE="$ROOT_DIR/.mac-status-clients.json"
CLIENTS="No phone has checked in yet."
if [[ -f "$CLIENTS_FILE" ]]; then
  CLIENTS="$(node -e 'const fs=require("fs"); const p=process.argv[1]; const clients=JSON.parse(fs.readFileSync(p,"utf8")); if (!clients.length) { console.log("No phone has checked in yet."); } else { console.log(clients.map(c=>`${c.remote} last seen ${c.lastSeen}`).join("\n")); }' "$CLIENTS_FILE" 2>/dev/null || echo "No phone has checked in yet.")"
fi

osascript <<APPLESCRIPT
display dialog "$INFO

Last connected device:
$CLIENTS

The Mac status service is running and will keep accepting phone reconnects when internet/network access returns." buttons {"OK"} default button "OK" with title "Mac Status Link"
APPLESCRIPT
SCRIPT

chmod +x "$MACOS/MacStatusLink"
echo "$APP_PATH"
