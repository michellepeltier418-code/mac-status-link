# Mac Status Link

Mac Status Link has two pieces:

1. A MacBook status service that exposes battery, CPU, RAM, internet, and network details.
2. An Android APK that polls the Mac service every 2 seconds.

## Run the Mac status service

```bash
npm run serve
```

The server listens on port `5178` and writes a private token to `.mac-status-token`.

Use one of the printed `http://<mac-ip>:5178` endpoint URLs in the Android app when the phone is on the same Wi-Fi.

To show the endpoint and token again:

```bash
scripts/show-connection-info.sh
```

If the global helper is installed, this works from any folder:

```bash
macstatus-info
```

To start the service automatically when you log in:

```bash
scripts/install-launch-agent.sh
```

For checking the MacBook while away from the Wi-Fi network, the phone must be able to reach this Mac. Use a private tunnel such as Tailscale, Cloudflare Tunnel, or ngrok, then use that HTTPS tunnel URL in the Android app. Keep the token private.

## Build the Android APK

```bash
scripts/install-local-jdk.sh
npm run build:apk
```

The APK is copied to:

```text
dist/MacStatusLink.apk
```

Upload the APK to temporary hosting. The script tries temp.sh first and falls back to tmpfiles.org:

```bash
scripts/upload-apk-temp.sh
```

## Android app setup

1. Install `MacStatusLink.apk` on the Blu View Speed 5G phone.
2. Start the Mac status service.
3. Open the app.
4. Enter the Mac endpoint, such as `http://192.168.1.25:5178`.
5. Enter the token from `.mac-status-token`.
6. Tap Save.

The app refreshes every 2 seconds while open.

## Notification Summary

Version `1.1.0` starts a foreground notification service from the Android app. The notification shade shows battery, CPU, RAM, internet, and analysis summary, and it posts an alert if the Mac was previously reachable and then becomes unreachable.

## Update Manifest

The live manifest file is `public/update-manifest.json`. When the Mac service is running, it is also exposed at:

```text
http://<mac-endpoint>:5178/api/manifest
```

## Mac Companion App

Create or refresh the Desktop companion app:

```bash
scripts/create-mac-companion-app.sh
```

Then launch `Mac Status Link.app` from the Desktop. It ensures the LaunchAgent is loaded, shows endpoint/token info, and reports the last phone/device that checked in.
