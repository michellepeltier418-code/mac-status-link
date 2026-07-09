package com.michelle.macstatus;

import android.app.Activity;
import android.app.AlertDialog;
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
    private static final String GITHUB_MANIFEST_URL = "https://api.github.com/repos/michellepeltier418-code/mac-status-link/contents/public/update-manifest.json?ref=main";
    private static final int COLOR_INK = Color.rgb(17, 24, 39);
    private static final int COLOR_MUTED = Color.rgb(82, 99, 118);
    private static final int COLOR_BLUE = Color.rgb(37, 99, 235);
    private static final int COLOR_TEAL = Color.rgb(13, 148, 136);
    private static final int COLOR_AMBER = Color.rgb(217, 119, 6);
    private static final int COLOR_ROSE = Color.rgb(225, 29, 72);
    private static final int COLOR_GREEN = Color.rgb(22, 163, 74);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> poller;

    private EditText endpointInput;
    private EditText tokenInput;
    private LinearLayout connectionDetails;
    private TextView endpointSummaryText;
    private Button editConnectionButton;
    private Button saveConnectionButton;
    private TextView heroStatusText;
    private TextView connectionText;
    private TextView updatedText;
    private TextView batteryText;
    private TextView cpuText;
    private TextView memoryText;
    private TextView internetText;
    private TextView networkText;
    private TextView analysisText;
    private TextView updatesText;
    private Button updatesButton;
    private ProgressBar batteryBar;
    private ProgressBar cpuBar;
    private ProgressBar memoryBar;
    private volatile String configuredEndpoint = "";
    private volatile String configuredToken = "";
    private boolean connectionDetailsVisible;

    private final DecimalFormat oneDecimal = new DecimalFormat("0.0");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        executor = Executors.newSingleThreadScheduledExecutor();
        buildUi();
        loadSettings();
        handleUpdateIntent(getIntent());
        requestNotificationPermissionIfNeeded();
        startNotificationService();
        startPolling();
        fetchManifestOnce();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleUpdateIntent(intent);
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
        scrollView.setBackgroundColor(Color.rgb(232, 240, 255));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(24));
        scrollView.addView(root);

        LinearLayout hero = heroPanel();
        root.addView(hero);

        TextView title = text("Mac Status Link", 30, Color.WHITE, Typeface.BOLD);
        hero.addView(title);

        TextView subtitle = text("Battery, CPU, RAM, internet, and update alerts in one live dashboard.", 14, Color.rgb(220, 238, 255), Typeface.NORMAL);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        hero.addView(subtitle);

        heroStatusText = text("Waiting for your MacBook...", 18, Color.WHITE, Typeface.BOLD);
        heroStatusText.setPadding(0, dp(8), 0, 0);
        hero.addView(heroStatusText);

        LinearLayout config = panel(Color.WHITE, Color.rgb(190, 205, 235));
        LinearLayout.LayoutParams configParams = matchWrap();
        configParams.topMargin = dp(14);
        root.addView(config, configParams);

        TextView configTitle = text("Connection", 18, COLOR_INK, Typeface.BOLD);
        config.addView(configTitle);

        endpointSummaryText = text("Endpoint: not set", 15, COLOR_MUTED, Typeface.BOLD);
        endpointSummaryText.setPadding(0, dp(8), 0, dp(8));
        config.addView(endpointSummaryText);

        connectionDetails = new LinearLayout(this);
        connectionDetails.setOrientation(LinearLayout.VERTICAL);
        connectionDetails.setVisibility(View.GONE);
        config.addView(connectionDetails);

        endpointInput = new EditText(this);
        endpointInput.setSingleLine(true);
        endpointInput.setHint("http://your-mac-ip:5178 or https://your-tunnel");
        endpointInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpointInput.setTextSize(14);
        endpointInput.setPadding(dp(10), 0, dp(10), 0);
        connectionDetails.addView(endpointInput, matchWrap());

        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint("Token from .mac-status-token");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setTextSize(14);
        tokenInput.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams tokenParams = matchWrap();
        tokenParams.topMargin = dp(8);
        connectionDetails.addView(tokenInput, tokenParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(10), 0, 0);
        saveConnectionButton = new Button(this);
        saveConnectionButton.setText("Save");
        styleButton(saveConnectionButton, COLOR_BLUE, Color.WHITE);
        saveConnectionButton.setOnClickListener(view -> {
            saveSettingsFromInputs();
            updateConnection("Saved. Checking now...", false);
            startNotificationService();
            fetchOnce();
            fetchManifestOnce();
            startPolling();
            setConnectionDetailsVisible(false);
        });
        Button refreshButton = new Button(this);
        refreshButton.setText("Refresh");
        styleButton(refreshButton, Color.rgb(219, 234, 254), COLOR_BLUE);
        refreshButton.setOnClickListener(view -> fetchOnce());
        editConnectionButton = new Button(this);
        editConnectionButton.setText("Edit Connection");
        styleButton(editConnectionButton, Color.rgb(240, 253, 250), COLOR_TEAL);
        editConnectionButton.setOnClickListener(view -> setConnectionDetailsVisible(!connectionDetailsVisible));
        actions.addView(refreshButton);
        actions.addView(editConnectionButton);
        actions.addView(saveConnectionButton);
        config.addView(actions);

        connectionText = text("Not connected yet.", 14, COLOR_MUTED, Typeface.NORMAL);
        connectionText.setPadding(0, dp(8), 0, 0);
        config.addView(connectionText);

        updatedText = text("Last update: --", 13, COLOR_MUTED, Typeface.NORMAL);
        updatedText.setPadding(0, dp(4), 0, 0);
        config.addView(updatedText);

        batteryBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        batteryText = addMetric(root, "Battery", batteryBar, Color.rgb(236, 253, 245), Color.rgb(20, 184, 166));

        cpuBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        cpuText = addMetric(root, "CPU", cpuBar, Color.rgb(239, 246, 255), COLOR_BLUE);

        memoryBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        memoryText = addMetric(root, "RAM", memoryBar, Color.rgb(255, 247, 237), COLOR_AMBER);

        internetText = addSimple(root, "Internet", Color.rgb(240, 253, 244), COLOR_GREEN);
        networkText = addSimple(root, "Mac Network", Color.rgb(245, 243, 255), Color.rgb(124, 58, 237));
        analysisText = addSimple(root, "Analysis", Color.rgb(255, 241, 242), COLOR_ROSE);
        updatesText = addUpdates(root);

        setContentView(scrollView);
    }

    private TextView addMetric(LinearLayout root, String label, ProgressBar bar, int fillColor, int accentColor) {
        LinearLayout card = panel(fillColor, lighten(accentColor));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        root.addView(card, params);

        TextView title = text(label, 18, accentColor, Typeface.BOLD);
        card.addView(title);

        TextView value = text("--", 16, COLOR_INK, Typeface.BOLD);
        value.setPadding(0, dp(8), 0, dp(8));
        card.addView(value);

        bar.setMax(100);
        bar.setProgress(0);
        if (Build.VERSION.SDK_INT >= 21) {
            bar.getProgressDrawable().setTint(accentColor);
        }
        card.addView(bar, matchWrap());
        return value;
    }

    private TextView addSimple(LinearLayout root, String label, int fillColor, int accentColor) {
        LinearLayout card = panel(fillColor, lighten(accentColor));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        root.addView(card, params);

        TextView title = text(label, 18, accentColor, Typeface.BOLD);
        card.addView(title);

        TextView value = text("--", 16, COLOR_INK, Typeface.NORMAL);
        value.setPadding(0, dp(8), 0, 0);
        card.addView(value);
        return value;
    }

    private TextView addUpdates(LinearLayout root) {
        LinearLayout card = panel(Color.rgb(238, 242, 255), Color.rgb(165, 180, 252));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        root.addView(card, params);

        TextView title = text("Updates", 18, Color.rgb(79, 70, 229), Typeface.BOLD);
        card.addView(title);

        TextView value = text("Manifest not checked yet.", 16, COLOR_INK, Typeface.NORMAL);
        value.setPadding(0, dp(8), 0, dp(8));
        card.addView(value);

        updatesButton = new Button(this);
        updatesButton.setText("Check Updates");
        styleButton(updatesButton, Color.rgb(79, 70, 229), Color.WHITE);
        updatesButton.setOnClickListener(view -> fetchManifestOnce());
        card.addView(updatesButton);

        return value;
    }

    private LinearLayout heroPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(37, 99, 235), Color.rgb(13, 148, 136)}
        );
        background.setCornerRadius(dp(8));
        panel.setBackground(background);
        return panel;
    }

    private LinearLayout panel(int fillColor, int strokeColor) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), strokeColor);
        panel.setBackground(background);
        return panel;
    }

    private void styleButton(Button button, int fillColor, int textColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(8));
        button.setTextColor(textColor);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(background);
    }

    private int lighten(int color) {
        int red = Math.min(255, (int) (Color.red(color) + (255 - Color.red(color)) * 0.72));
        int green = Math.min(255, (int) (Color.green(color) + (255 - Color.green(color)) * 0.72));
        int blue = Math.min(255, (int) (Color.blue(color) + (255 - Color.blue(color)) * 0.72));
        return Color.rgb(red, green, blue);
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
        updateEndpointSummary();
        setConnectionDetailsVisible(configuredEndpoint.trim().isEmpty() || configuredToken.trim().isEmpty());
    }

    private void setConnectionDetailsVisible(boolean visible) {
        connectionDetailsVisible = visible;
        if (connectionDetails != null) {
            connectionDetails.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (editConnectionButton != null) {
            editConnectionButton.setText(visible ? "Hide Details" : "Edit Connection");
        }
        if (saveConnectionButton != null) {
            saveConnectionButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void updateEndpointSummary() {
        String endpoint = configuredEndpoint == null ? "" : configuredEndpoint.trim();
        if (endpoint.isEmpty()) {
            endpointSummaryText.setText("Endpoint: not set");
            return;
        }
        endpointSummaryText.setText("Endpoint: " + endpoint);
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
        updateEndpointSummary();
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

    private void handleUpdateIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra("install_update", false)) {
            return;
        }
        String apkUrl = intent.getStringExtra("apk_url");
        if (apkUrl != null && !apkUrl.trim().isEmpty()) {
            UpdateInstaller.startDownload(this, apkUrl);
        }
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

        try {
            JSONObject manifest = requestPublicJson(GITHUB_MANIFEST_URL);
            mainHandler.post(() -> renderManifest(manifest));
        } catch (Exception error) {
            if (endpoint.isEmpty()) {
                mainHandler.post(() -> updatesText.setText("GitHub manifest check failed: " + error.getMessage()));
                return;
            }
            try {
                JSONObject manifest = requestJson(endpoint, token, "/api/manifest");
                mainHandler.post(() -> renderManifest(manifest));
            } catch (Exception fallbackError) {
                mainHandler.post(() -> updatesText.setText("Manifest check failed: " + fallbackError.getMessage()));
            }
        }
    }

    private JSONObject requestPublicJson(String urlValue) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlValue).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github.raw+json");

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
        String body = readAll(stream);
        connection.disconnect();

        if (statusCode < 200 || statusCode >= 400) {
            throw new IllegalStateException("GitHub manifest returned HTTP " + statusCode + ".");
        }

        return new JSONObject(body);
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
        heroStatusText.setText("Connected to " + json.optString("hostname", "MacBook"));

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
            analysisText.setText(level + ": " + joinMessages(analysis.optJSONArray("messages")) +
                    processSummary(json.optJSONObject("processes")));
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
            updatesButton.setText("Update");
            styleButton(updatesButton, COLOR_GREEN, Color.WHITE);
            updatesButton.setOnClickListener(view -> UpdateInstaller.startDownload(this, apkUrl));
            showUpdateDialog(versionName, apkUrl);
        } else {
            updatesText.setText("Up to date: " + versionName + "\nManifest APK: " + apkUrl);
            updatesText.setTextColor(Color.rgb(35, 117, 77));
            updatesButton.setText("Check Updates");
            styleButton(updatesButton, Color.rgb(79, 70, 229), Color.WHITE);
            updatesButton.setOnClickListener(view -> fetchManifestOnce());
        }
    }

    private void showUpdateDialog(String versionName, String apkUrl) {
        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("Mac Status Link " + versionName + " is ready to install.")
                .setPositiveButton("Update", (dialog, which) -> UpdateInstaller.startDownload(this, apkUrl))
                .setNegativeButton("Later", null)
                .show();
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

    private String processSummary(JSONObject processes) {
        if (processes == null) {
            return "\n\nTop CPU: waiting for process details.\nTop RAM: waiting for process details.";
        }

        String cpu = processList(processes.optJSONArray("topCpu"), "cpu");
        String memory = processList(processes.optJSONArray("topMemory"), "memory");
        return "\n\nTop CPU\n" + cpu + "\n\nTop RAM\n" + memory;
    }

    private String processList(JSONArray values, String mode) {
        if (values == null || values.length() == 0) {
            return "No process details reported.";
        }

        List<String> rows = new ArrayList<>();
        int limit = Math.min(values.length(), 5);
        for (int i = 0; i < limit; i++) {
            JSONObject entry = values.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            String name = entry.optString("name", "Unknown");
            int pid = entry.optInt("pid", 0);
            if ("memory".equals(mode)) {
                rows.add((i + 1) + ". " + name + " - " +
                        oneDecimal.format(entry.optDouble("memoryPercent", 0)) + "% RAM, pid " + pid);
            } else {
                rows.add((i + 1) + ". " + name + " - " +
                        oneDecimal.format(entry.optDouble("cpuPercent", 0)) + "% CPU, pid " + pid);
            }
        }
        return rows.isEmpty() ? "No process details reported." : join(rows, "\n");
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
        connectionText.setTextColor(error ? COLOR_ROSE : COLOR_GREEN);
        if (heroStatusText != null && error) {
            heroStatusText.setText("Connection needs attention");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
