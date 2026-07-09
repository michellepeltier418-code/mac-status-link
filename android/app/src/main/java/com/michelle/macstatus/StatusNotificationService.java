package com.michelle.macstatus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class StatusNotificationService extends Service {
    private static final String PREFS = "mac_status_link";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_TOKEN = "token";
    private static final String GITHUB_MANIFEST_URL = "https://raw.githubusercontent.com/michellepeltier418-code/mac-status-link/main/public/update-manifest.json";
    private static final String CHANNEL_STATUS = "mac_status_summary";
    private static final String CHANNEL_ALERTS = "mac_status_alerts";
    private static final String CHANNEL_UPDATES = "mac_status_updates";
    private static final int NOTIFICATION_STATUS_ID = 1001;
    private static final int NOTIFICATION_OFFLINE_ID = 1002;
    private static final int NOTIFICATION_UPDATE_ID = 1003;
    private static final long UPDATE_CHECK_INTERVAL_MS = 15 * 60 * 1000L;

    private final DecimalFormat oneDecimal = new DecimalFormat("0");
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> poller;
    private boolean hasConnected;
    private boolean offlineAlertShown;
    private long lastUpdateCheckMs;
    private int lastNotifiedVersionCode;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        executor = Executors.newSingleThreadScheduledExecutor();
        startForeground(NOTIFICATION_STATUS_ID, buildNotification(
                CHANNEL_STATUS,
                "Mac Status Link",
                "Waiting for MacBook status...",
                "Open the app to configure endpoint and token.",
                true
        ));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        restartPolling();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (poller != null) {
            poller.cancel(true);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void restartPolling() {
        if (poller != null) {
            poller.cancel(false);
        }
        poller = executor.scheduleAtFixedRate(this::pollStatus, 0, 2, TimeUnit.SECONDS);
    }

    private void pollStatus() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String endpoint = prefs.getString(KEY_ENDPOINT, "").trim();
        String token = prefs.getString(KEY_TOKEN, "").trim();

        if (endpoint.isEmpty()) {
            updateStatus("Mac Status Link", "Configure endpoint in the app.", "No Mac endpoint saved.");
            return;
        }

        try {
            JSONObject status = requestStatus(endpoint, token);
            hasConnected = true;
            offlineAlertShown = false;
            updateStatus("MacBook online", summaryLine(status), detailLine(status));
            maybeCheckManifest(endpoint, token);
        } catch (Exception error) {
            updateStatus("MacBook unreachable", "Last check failed", error.getMessage());
            if (hasConnected && !offlineAlertShown) {
                offlineAlertShown = true;
                notifyOffline(error.getMessage());
            }
        }
    }

    private JSONObject requestStatus(String endpoint, String token) throws Exception {
        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String statusUrl = cleanEndpoint.endsWith("/api/status") ? cleanEndpoint : cleanEndpoint + "/api/status";
        HttpURLConnection connection = (HttpURLConnection) new URL(statusUrl).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = readAll(stream);
        connection.disconnect();

        if (statusCode == 401) {
            throw new IllegalStateException("Token rejected by Mac status service.");
        }
        if (statusCode < 200 || statusCode >= 400) {
            throw new IllegalStateException("Server returned HTTP " + statusCode + ".");
        }
        return new JSONObject(body);
    }

    private void maybeCheckManifest(String endpoint, String token) {
        long now = System.currentTimeMillis();
        if (now - lastUpdateCheckMs < UPDATE_CHECK_INTERVAL_MS) {
            return;
        }
        lastUpdateCheckMs = now;

        try {
            JSONObject manifest;
            try {
                manifest = requestPublicJson(GITHUB_MANIFEST_URL);
            } catch (Exception error) {
                manifest = requestJson(endpoint, token, "/api/manifest");
            }
            int latestCode = manifest.optInt("latestVersionCode", 0);
            String versionName = manifest.optString("latestVersionName", "unknown");
            String apkUrl = manifest.optString("apkUrl", "");
            if (latestCode > currentVersionCode() && latestCode != lastNotifiedVersionCode) {
                lastNotifiedVersionCode = latestCode;
                notifyUpdate(versionName, apkUrl);
            }
        } catch (Exception ignored) {
            // Status polling should remain useful even if update metadata is temporarily unavailable.
        }
    }

    private JSONObject requestPublicJson(String urlValue) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = readAll(stream);
        connection.disconnect();

        if (statusCode < 200 || statusCode >= 400) {
            throw new IllegalStateException("GitHub manifest returned HTTP " + statusCode + ".");
        }
        return new JSONObject(body);
    }

    private JSONObject requestJson(String endpoint, String token, String path) throws Exception {
        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        HttpURLConnection connection = (HttpURLConnection) new URL(cleanEndpoint + path).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 400 ? connection.getInputStream() : connection.getErrorStream();
        String body = readAll(stream);
        connection.disconnect();

        if (statusCode < 200 || statusCode >= 400) {
            throw new IllegalStateException("Server returned HTTP " + statusCode + ".");
        }
        return new JSONObject(body);
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private String summaryLine(JSONObject status) {
        JSONObject battery = status.optJSONObject("battery");
        JSONObject cpu = status.optJSONObject("cpu");
        JSONObject memory = status.optJSONObject("memory");
        JSONObject internet = status.optJSONObject("internet");

        String batteryText = battery == null || !battery.optBoolean("present", false)
                ? "Battery --"
                : "Battery " + battery.optInt("percent", 0) + "%";
        String cpuText = "CPU " + oneDecimal.format(cpu == null ? 0 : cpu.optDouble("percent", 0)) + "%";
        String ramText = "RAM " + oneDecimal.format(memory == null ? 0 : memory.optDouble("percentUsed", 0)) + "%";
        String netText = internet != null && internet.optBoolean("online", false) ? "Internet online" : "Internet issue";

        return batteryText + " | " + cpuText + " | " + ramText + " | " + netText;
    }

    private String detailLine(JSONObject status) {
        JSONObject analysis = status.optJSONObject("analysis");
        String level = analysis == null ? "OK" : analysis.optString("level", "ok").toUpperCase();
        String messages = joinMessages(analysis == null ? null : analysis.optJSONArray("messages"));
        return level + ": " + messages;
    }

    private String joinMessages(JSONArray messages) {
        if (messages == null || messages.length() == 0) {
            return "MacBook looks healthy.";
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < messages.length(); i++) {
            parts.add(messages.optString(i));
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(parts.get(i));
        }
        return builder.toString();
    }

    private void updateStatus(String title, String text, String bigText) {
        Notification notification = buildNotification(CHANNEL_STATUS, title, text, bigText, true);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_STATUS_ID, notification);
    }

    private void notifyOffline(String reason) {
        Notification notification = buildNotification(
                CHANNEL_ALERTS,
                "MacBook went offline",
                "The phone cannot reach the Mac status service.",
                reason,
                false
        );
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_OFFLINE_ID, notification);
    }

    private void notifyUpdate(String versionName, String apkUrl) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("install_update", true);
        intent.putExtra("apk_url", apkUrl);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                2,
                intent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_UPDATES)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle("Mac Status Link update available")
                .setContentText("Version " + versionName + " is ready to install.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.stat_sys_download_done, "Update", pendingIntent)
                .setAutoCancel(true)
                .setShowWhen(true)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_UPDATE_ID, notification);
    }

    private long currentVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            return 0;
        }
    }

    private Notification buildNotification(String channelId, String title, String text, String bigText, boolean ongoing) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channelId)
                : new Notification.Builder(this);

        builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pendingIntent)
                .setOngoing(ongoing)
                .setShowWhen(true)
                .setStyle(new Notification.BigTextStyle().bigText(bigText));

        if (!ongoing) {
            builder.setAutoCancel(true);
        }

        return builder.build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel status = new NotificationChannel(
                CHANNEL_STATUS,
                "Mac status summary",
                NotificationManager.IMPORTANCE_LOW
        );
        status.setDescription("Live MacBook battery, CPU, RAM, and internet summary.");
        status.setLightColor(Color.rgb(23, 105, 170));

        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS,
                "Mac status alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        alerts.setDescription("Alerts when the MacBook status service becomes unreachable.");
        alerts.setLightColor(Color.rgb(172, 40, 40));

        NotificationChannel updates = new NotificationChannel(
                CHANNEL_UPDATES,
                "Mac Status Link updates",
                NotificationManager.IMPORTANCE_HIGH
        );
        updates.setDescription("Notifications when a newer APK is available from the update manifest.");
        updates.setLightColor(Color.rgb(35, 117, 77));

        manager.createNotificationChannel(status);
        manager.createNotificationChannel(alerts);
        manager.createNotificationChannel(updates);
    }
}
