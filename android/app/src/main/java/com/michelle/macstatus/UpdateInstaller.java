package com.michelle.macstatus;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

public final class UpdateInstaller {
    static final String PREFS = "mac_status_link";
    static final String KEY_DOWNLOAD_ID = "update_download_id";
    static final String KEY_DOWNLOAD_URL = "update_download_url";

    private UpdateInstaller() {
    }

    public static void startDownload(Context context, String apkUrl) {
        if (apkUrl == null || apkUrl.trim().isEmpty()) {
            Toast.makeText(context, "No APK URL found in the update manifest.", Toast.LENGTH_LONG).show();
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(apkUrl.trim()));
        request.setTitle("Mac Status Link update");
        request.setDescription("Downloading the latest APK.");
        request.setMimeType("application/vnd.android.package-archive");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "MacStatusLink-update.apk");

        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        long id = manager.enqueue(request);

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_DOWNLOAD_URL, apkUrl.trim())
                .apply();

        Toast.makeText(context, "Downloading update. Installer will open when it finishes.", Toast.LENGTH_LONG).show();
    }

    public static void openDownloadedApk(Context context, long downloadId) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        Uri uri = manager.getUriForDownloadedFile(downloadId);
        if (uri == null) {
            Toast.makeText(context, "Update download was not found.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }
}
