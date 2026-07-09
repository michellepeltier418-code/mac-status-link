package com.michelle.macstatus;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            return;
        }

        long completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        SharedPreferences prefs = context.getSharedPreferences(UpdateInstaller.PREFS, Context.MODE_PRIVATE);
        long expectedId = prefs.getLong(UpdateInstaller.KEY_DOWNLOAD_ID, -2);
        if (completedId != expectedId) {
            return;
        }

        UpdateInstaller.openDownloadedApk(context, completedId);
    }
}
