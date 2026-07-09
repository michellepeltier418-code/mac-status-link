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
echo "Stable endpoint candidates:"
stable_count=0
if [[ -n "${MAC_STATUS_PUBLIC_URL:-}" ]]; then
  echo "  $MAC_STATUS_PUBLIC_URL (MAC_STATUS_PUBLIC_URL)"
  stable_count=$((stable_count + 1))
fi
if command -v tailscale >/dev/null 2>&1; then
  tailscale_dns="$(tailscale status --json 2>/dev/null | node -e 'let d=""; process.stdin.on("data", c => d += c); process.stdin.on("end", () => { try { const j=JSON.parse(d); const name=(j.Self && j.Self.DNSName || "").replace(/\.$/, ""); if (name) console.log(name); } catch (_) {} });' 2>/dev/null || true)"
  if [[ -n "$tailscale_dns" ]]; then
    echo "  http://$tailscale_dns:$PORT (Tailscale MagicDNS)"
    stable_count=$((stable_count + 1))
  fi
  tailscale_ip="$(tailscale ip -4 2>/dev/null | head -n 1 || true)"
  if [[ -n "$tailscale_ip" ]]; then
    echo "  http://$tailscale_ip:$PORT (Tailscale IP)"
    stable_count=$((stable_count + 1))
  fi
fi
if [[ "$stable_count" -eq 0 ]]; then
  echo "  none detected yet"
fi
echo "  Tip: Tailscale Personal is free for personal use; install/sign in on both Mac and phone for away-from-laptop access."
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
