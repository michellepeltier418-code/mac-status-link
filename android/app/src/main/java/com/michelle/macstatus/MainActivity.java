package com.michelle.macstatus;

import android.app.Activity;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

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
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String PREFS = "mac_status_link";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_TOKEN = "token";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> poller;

    private EditText endpointInput;
    private EditText tokenInput;
    private TextView connectionText;
    private TextView updatedText;
    private TextView batteryText;
    private TextView cpuText;
    private TextView memoryText;
    private TextView internetText;
    private TextView networkText;
    private TextView analysisText;
    private TextView updatesText;
    private ProgressBar batteryBar;
    private ProgressBar cpuBar;
    private ProgressBar memoryBar;
    private volatile String configuredEndpoint = "";
    private volatile String configuredToken = "";

    private final DecimalFormat oneDecimal = new DecimalFormat("0.0");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadScheduledExecutor();
        buildUi();
        loadSettings();
        requestNotificationPermissionIfNeeded();
        startNotificationService();
        startPolling();
    }

    @Override
    protected void onDestroy() {
        if (poller != null) {
            poller.cancel(true);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(246, 248, 250));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        scrollView.addView(root);

        TextView title = text("Mac Status Link", 28, Color.rgb(23, 32, 42), Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text("Live MacBook battery, CPU, RAM, and internet status.", 14, Color.rgb(88, 101, 115), Typeface.NORMAL);
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        LinearLayout config = panel();
        root.addView(config);

        TextView configTitle = text("Connection", 18, Color.rgb(23, 32, 42), Typeface.BOLD);
        config.addView(configTitle);

        endpointInput = new EditText(this);
        endpointInput.setSingleLine(true);
        endpointInput.setHint("http://your-mac-ip:5178 or https://your-tunnel");
        endpointInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpointInput.setTextSize(14);
        endpointInput.setPadding(dp(10), 0, dp(10), 0);
        config.addView(endpointInput, matchWrap());

        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint("Token from .mac-status-token");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setTextSize(14);
        tokenInput.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams tokenParams = matchWrap();
        tokenParams.topMargin = dp(8);
        config.addView(tokenInput, tokenParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(10), 0, 0);
        Button saveButton = new Button(this);
        saveButton.setText("Save");
        saveButton.setOnClickListener(view -> {
            saveSettingsFromInputs();
            updateConnection("Saved. Checking now...", false);
            startNotificationService();
            fetchOnce();
            fetchManifestOnce();
            startPolling();
        });
        Button refreshButton = new Button(this);
        refreshButton.setText("Refresh");
        refreshButton.setOnClickListener(view -> fetchOnce());
        actions.addView(refreshButton);
        actions.addView(saveButton);
        config.addView(actions);

        connectionText = text("Not connected yet.", 14, Color.rgb(88, 101, 115), Typeface.NORMAL);
        connectionText.setPadding(0, dp(8), 0, 0);
        config.addView(connectionText);

        updatedText = text("Last update: --", 13, Color.rgb(88, 101, 115), Typeface.NORMAL);
        updatedText.setPadding(0, dp(4), 0, 0);
        config.addView(updatedText);

        batteryBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        batteryText = addMetric(root, "Battery", batteryBar);

        cpuBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        cpuText = addMetric(root, "CPU", cpuBar);

        memoryBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        memoryText = addMetric(root, "RAM", memoryBar);

        internetText = addSimple(root, "Internet");
        networkText = addSimple(root, "Mac Network");
        analysisText = addSimple(root, "Analysis");
        updatesText = addUpdates(root);

        setContentView(scrollView);
    }

    private TextView addMetric(LinearLayout root, String label, ProgressBar bar) {
        LinearLayout card = panel();
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        root.addView(card, params);

        TextView title = text(label, 18, Color.rgb(23, 32, 42), Typeface.BOLD);
        card.addView(title);

        TextView value = text("--", 16, Color.rgb(23, 32, 42), Typeface.NORMAL);
        value.setPadding(0, dp(8), 0, dp(8));
        card.addView(value);

        bar.setMax(100);
        bar.setProgress(0);
        card.addView(bar, matchWrap());
        return value;
    }

    private TextView addSimple(LinearLayout root, String label) {
        LinearLayout card = panel();
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        root.addView(card, params);

        TextView title = text(label, 18, Color.rgb(23, 32, 42), Typeface.BOLD);
        card.addView(title);

        TextView value = text("--", 16, Color.rgb(23, 32, 42), Typeface.NORMAL);
        value.setPadding(0, dp(8), 0, 0);
        card.addView(value);
        return value;
    }

    private TextView addUpdates(LinearLayout root) {
        LinearLayout card = panel();
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        root.addView(card, params);

        TextView title = text("Updates", 18, Color.rgb(23, 32, 42), Typeface.BOLD);
        card.addView(title);

        TextView value = text("Manifest not checked yet.", 16, Color.rgb(23, 32, 42), Typeface.NORMAL);
        value.setPadding(0, dp(8), 0, dp(8));
        card.addView(value);

        Button button = new Button(this);
        button.setText("Check Updates");
        button.setOnClickListener(view -> fetchManifestOnce());
        card.addView(button);

        return value;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), Color.rgb(215, 222, 232));
        panel.setBackground(background);
        return panel;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setTypeface(Typeface.DEFAULT, style);
        textView.setLineSpacing(0, 1.08f);
        return textView;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        configuredEndpoint = prefs.getString(KEY_ENDPOINT, "");
        configuredToken = prefs.getString(KEY_TOKEN, "");
        endpointInput.setText(configuredEndpoint);
        tokenInput.setText(configuredToken);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
        }
    }

    private void startNotificationService() {
        Intent intent = new Intent(this, StatusNotificationService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void saveSettingsFromInputs() {
        configuredEndpoint = endpointInput.getText().toString().trim();
        configuredToken = tokenInput.getText().toString().trim();
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ENDPOINT, configuredEndpoint)
                .putString(KEY_TOKEN, configuredToken)
                .apply();
    }

    private void startPolling() {
        if (poller != null) {
            poller.cancel(false);
        }
        poller = executor.scheduleAtFixedRate(this::fetchStatus, 0, 2, TimeUnit.SECONDS);
    }

    private void fetchOnce() {
        executor.execute(this::fetchStatus);
    }

    private void fetchManifestOnce() {
        executor.execute(this::fetchManifest);
    }

    private void fetchStatus() {
        String endpoint = configuredEndpoint;
        String token = configuredToken;

        if (endpoint.isEmpty()) {
            mainHandler.post(() -> updateConnection("Enter the Mac status endpoint.", true));
            return;
        }

        try {
            JSONObject json = requestStatus(endpoint, token);
            mainHandler.post(() -> renderStatus(json));
        } catch (Exception error) {
            mainHandler.post(() -> {
                updateConnection(error.getMessage(), true);
                updatedText.setText("Last update failed");
            });
        }
    }

    private JSONObject requestStatus(String endpoint, String token) throws Exception {
        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String statusUrl = cleanEndpoint.endsWith("/api/status") ? cleanEndpoint : cleanEndpoint + "/api/status";
        URL url = new URL(statusUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
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

    private void fetchManifest() {
        String endpoint = configuredEndpoint;
        String token = configuredToken;

        if (endpoint.isEmpty()) {
            mainHandler.post(() -> updatesText.setText("Enter the Mac endpoint before checking updates."));
            return;
        }

        try {
            JSONObject manifest = requestJson(endpoint, token, "/api/manifest");
            mainHandler.post(() -> renderManifest(manifest));
        } catch (Exception error) {
            mainHandler.post(() -> updatesText.setText("Manifest check failed: " + error.getMessage()));
        }
    }

    private JSONObject requestJson(String endpoint, String token, String path) throws Exception {
        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        URL url = new URL(cleanEndpoint + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
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

    private void renderStatus(JSONObject json) {
        updateConnection("Connected to " + json.optString("hostname", "MacBook"), false);
        updatedText.setText("Last update: " + json.optString("timestamp", "--"));

        JSONObject battery = json.optJSONObject("battery");
        if (battery != null && battery.optBoolean("present", false)) {
            int percent = battery.optInt("percent", -1);
            batteryBar.setProgress(Math.max(0, percent));
            String state = battery.optBoolean("charging", false) ? "charging" : "on battery";
            String time = battery.optString("timeRemaining", "");
            batteryText.setText(percent + "%, " + state + (time.isEmpty() ? "" : ", " + time + " remaining"));
        } else {
            batteryBar.setProgress(0);
            batteryText.setText("No battery details available.");
        }

        JSONObject cpu = json.optJSONObject("cpu");
        double cpuPercent = cpu == null ? 0 : cpu.optDouble("percent", 0);
        cpuBar.setProgress((int) Math.round(cpuPercent));
        cpuText.setText(oneDecimal.format(cpuPercent) + "% used, " +
                (cpu == null ? "--" : cpu.optInt("cores", 0)) + " cores, load " +
                (cpu == null ? "--" : cpu.optDouble("loadAverage1m", 0)));

        JSONObject memory = json.optJSONObject("memory");
        double memoryPercent = memory == null ? 0 : memory.optDouble("percentUsed", 0);
        memoryBar.setProgress((int) Math.round(memoryPercent));
        memoryText.setText(oneDecimal.format(memoryPercent) + "% used, " +
                bytes(memory == null ? 0 : memory.optLong("usedBytes", 0)) + " of " +
                bytes(memory == null ? 0 : memory.optLong("totalBytes", 0)));

        JSONObject internet = json.optJSONObject("internet");
        if (internet != null && internet.optBoolean("online", false)) {
            internetText.setText("Online, " + internet.optInt("latencyMs", 0) + " ms probe");
            internetText.setTextColor(Color.rgb(23, 32, 42));
        } else {
            internetText.setText("Offline or probe failed" +
                    (internet == null ? "" : ": " + internet.optString("error", "unknown")));
            internetText.setTextColor(Color.rgb(172, 40, 40));
        }

        networkText.setText(networkSummary(json.optJSONObject("network")));

        JSONObject analysis = json.optJSONObject("analysis");
        if (analysis != null) {
            String level = analysis.optString("level", "ok").toUpperCase(Locale.US);
            analysisText.setText(level + ": " + joinMessages(analysis.optJSONArray("messages")));
            analysisText.setTextColor("OK".equals(level) ? Color.rgb(35, 117, 77) : Color.rgb(172, 109, 32));
        }
    }

    private void renderManifest(JSONObject manifest) {
        int latestCode = manifest.optInt("latestVersionCode", 0);
        String versionName = manifest.optString("latestVersionName", "unknown");
        String apkUrl = manifest.optString("apkUrl", "");

        if (latestCode > currentVersionCode()) {
            updatesText.setText("Update available: " + versionName + "\n" + apkUrl);
            updatesText.setTextColor(Color.rgb(172, 109, 32));
        } else {
            updatesText.setText("Up to date: " + versionName + "\nManifest APK: " + apkUrl);
            updatesText.setTextColor(Color.rgb(35, 117, 77));
        }
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

    private String networkSummary(JSONObject network) {
        if (network == null) {
            return "No network address reported.";
        }
        JSONArray addresses = network.optJSONArray("addresses");
        if (addresses == null || addresses.length() == 0) {
            return "No active IPv4 network address reported.";
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < addresses.length(); i++) {
            JSONObject entry = addresses.optJSONObject(i);
            if (entry != null) {
                parts.add(entry.optString("name", "network") + ": " + entry.optString("address", ""));
            }
        }
        return join(parts, "\n");
    }

    private String joinMessages(JSONArray messages) {
        if (messages == null || messages.length() == 0) {
            return "No analysis available.";
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < messages.length(); i++) {
            parts.add(messages.optString(i));
        }
        return join(parts, " ");
    }

    private String join(List<String> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private String bytes(long value) {
        double gb = value / 1024.0 / 1024.0 / 1024.0;
        return oneDecimal.format(gb) + " GB";
    }

    private void updateConnection(String message, boolean error) {
        connectionText.setText(message);
        connectionText.setTextColor(error ? Color.rgb(172, 40, 40) : Color.rgb(35, 117, 77));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
