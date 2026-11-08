package com.livemonitor.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

/** Moves completed server downloads from private app storage into the selected save folder. */
public final class ServerDownloadCompleteReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
        if (id < 0L) return;

        Data input = new Data.Builder().putLong(ServerDownloadCopyWorker.KEY_DOWNLOAD_ID, id).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ServerDownloadCopyWorker.class)
            .setInputData(input)
            .build();
        WorkManager.getInstance(context).enqueueUniqueWork("server-download-" + id, ExistingWorkPolicy.KEEP, request);
    }
}
