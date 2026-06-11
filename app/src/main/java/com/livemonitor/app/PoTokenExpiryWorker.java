package com.livemonitor.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * One-shot reminder fired when the currently cached YouTube PO token reaches
 * the app's refresh interval. A newer token capture replaces the old reminder.
 */
public class PoTokenExpiryWorker extends Worker {

    public static final String WORK_NAME = "po_token_expiry_notification";

    private static final String KEY_TOKEN_UPDATED_AT = "token_updated_at";
    private static final String KEY_VIDEO_ID = "video_id";

    public PoTokenExpiryWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context context, AppSettings settings) {
        if (context == null || settings == null || !settings.hasYtDlpPoToken()) {
            return;
        }

        long tokenUpdatedAt = settings.getYtDlpPoTokenUpdatedAt();
        if (tokenUpdatedAt <= 0L) {
            return;
        }

        long expiryAt = tokenUpdatedAt + settings.getYtDlpPoTokenRefreshIntervalMillis();
        long delayMillis = Math.max(0L, expiryAt - System.currentTimeMillis());

        Data inputData = new Data.Builder()
            .putLong(KEY_TOKEN_UPDATED_AT, tokenUpdatedAt)
            .putString(KEY_VIDEO_ID, settings.getYtDlpPoTokenVideoId())
            .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PoTokenExpiryWorker.class)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .build();

        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        );
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        AppStorage storage = new AppStorage(context);
        AppSettings settings = storage.loadSettings();

        if (!settings.hasYtDlpPoToken()) {
            return Result.success();
        }

        long scheduledTokenUpdatedAt = getInputData().getLong(KEY_TOKEN_UPDATED_AT, 0L);
        if (scheduledTokenUpdatedAt > 0L
            && settings.getYtDlpPoTokenUpdatedAt() > scheduledTokenUpdatedAt) {
            return Result.success();
        }

        if (!settings.isYtDlpPoTokenRefreshNeeded(System.currentTimeMillis())) {
            return Result.success();
        }

        String videoId = settings.getYtDlpPoTokenVideoId();
        if (YouTubePoTokenHelper.isBlank(videoId)) {
            videoId = getInputData().getString(KEY_VIDEO_ID);
        }

        NotificationHelper notificationHelper = new NotificationHelper(context);
        notificationHelper.showPoTokenExpiredNotification(videoId);
        storage.appendLog(LogItem.warning(
            LogItem.SOURCE_REMOTE_CONFIG,
            "Cached YouTube PO token reached its refresh interval. "
                + "Notification sent to refresh the token. videoId="
                + (YouTubePoTokenHelper.isBlank(videoId) ? "unknown" : videoId)
        ));

        return Result.success();
    }
}
