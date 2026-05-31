package com.livemonitor.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

/**
 * Restores saved monitoring after device reboot or app update.
 *
 * Triggered by manifest receiver actions:
 * - android.intent.action.BOOT_COMPLETED
 * - android.intent.action.LOCKED_BOOT_COMPLETED
 * - android.intent.action.MY_PACKAGE_REPLACED
 *
 * Important:
 * This receiver does not blindly start monitoring.
 * It only starts the foreground service if:
 * - restoreMonitoringOnBoot is enabled in AppSettings
 * - at least one saved channel should be monitored
 */
public class BootReceiver extends BroadcastReceiver {

    public static final String ACTION_RESTORE_MONITORING =
        "com.livemonitor.app.action.RESTORE_MONITORING";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }

        Context appContext = context.getApplicationContext();
        String action = intent.getAction();

        AppStorage storage = new AppStorage(appContext);
        AppSettings settings = storage.loadSettings();

        if (!settings.isRestoreMonitoringOnBoot()) {
            storage.appendLog(new LogItem(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_BOOT,
                "",
                "",
                "",
                "",
                "Boot restore skipped.",
                "restoreMonitoringOnBoot is disabled."
            ));
            return;
        }

        if (!isSupportedBootAction(action)) {
            storage.appendLog(new LogItem(
                LogItem.LEVEL_DEBUG,
                LogItem.SOURCE_BOOT,
                "",
                "",
                "",
                "",
                "BootReceiver ignored unsupported action.",
                action == null ? "" : action
            ));
            return;
        }

        List<ChannelItem> channels = storage.loadChannels();
        int enabledCount = countEnabledChannels(channels);

        if (enabledCount <= 0) {
            storage.appendLog(new LogItem(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_BOOT,
                "",
                "",
                "",
                "",
                "Boot restore skipped.",
                "No saved channels are enabled for monitoring."
            ));
            return;
        }

        /*
         * Do not start new recordings outside schedule.
         * The service will still re-check settings, but this avoids starting
         * the service unnecessarily when scheduled monitoring is closed.
         */
        if (!settings.canStartNewRecordingNow()) {
            storage.appendLog(new LogItem(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_BOOT,
                "",
                "",
                "",
                "",
                "Boot restore delayed by schedule.",
                "Saved channels exist, but current time is outside monitoring schedule."
            ));
            return;
        }

        markChannelsWaitingForLive(storage, channels);

        Intent serviceIntent = new Intent(appContext, MonitorService.class);
        serviceIntent.setAction(ACTION_RESTORE_MONITORING);
        serviceIntent.putExtra("source", "boot");
        serviceIntent.putExtra("enabledChannelCount", enabledCount);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent);
            } else {
                appContext.startService(serviceIntent);
            }

            storage.appendLog(new LogItem(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_BOOT,
                "",
                "",
                "",
                "",
                "Boot restore started monitoring service.",
                "enabledChannelCount=" + enabledCount + ", action=" + action
            ));
        } catch (Exception e) {
            storage.appendLog(new LogItem(
                LogItem.LEVEL_ERROR,
                LogItem.SOURCE_BOOT,
                "",
                "",
                "",
                "",
                "Boot restore failed to start monitoring service.",
                e.getMessage()
            ));
        }
    }

    private static boolean isSupportedBootAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    }

    private static int countEnabledChannels(List<ChannelItem> channels) {
        if (channels == null || channels.isEmpty()) {
            return 0;
        }

        int count = 0;

        for (ChannelItem channel : channels) {
            if (channel != null && channel.shouldMonitor()) {
                count++;
            }
        }

        return count;
    }

    private static void markChannelsWaitingForLive(
        AppStorage storage,
        List<ChannelItem> channels
    ) {
        if (storage == null || channels == null || channels.isEmpty()) {
            return;
        }

        boolean changed = false;

        for (ChannelItem channel : channels) {
            if (channel == null || !channel.shouldMonitor()) {
                continue;
            }

            channel.markWaitingForLive();
            changed = true;
        }

        if (changed) {
            storage.saveChannels(channels);
        }
    }
}
