package com.livemonitor.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Central notification helper.
 *
 * Notification behavior:
 * - One low-noise monitoring notification per channel.
 * - One alert notification when live is detected.
 * - No recording progress in notification to avoid clutter.
 * - Foreground service notification for service lifecycle.
 */
public class NotificationHelper {

    public static final String CHANNEL_SERVICE = "live_monitor_service";
    public static final String CHANNEL_MONITORING = "live_monitor_channels";
    public static final String CHANNEL_LIVE_ALERTS = "live_monitor_live_alerts";

    public static final int SERVICE_NOTIFICATION_ID = 1;
    public static final int LIVE_ALERT_ID_OFFSET = 2_000_000;

    private final Context appContext;
    private final NotificationManager notificationManager;

    public NotificationHelper(Context context) {
        this.appContext = context.getApplicationContext();
        this.notificationManager =
            (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    public void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || notificationManager == null) {
            return;
        }

        NotificationChannel serviceChannel = new NotificationChannel(
            CHANNEL_SERVICE,
            "Live Monitor Service",
            NotificationManager.IMPORTANCE_LOW
        );
        serviceChannel.setDescription("Keeps Live Monitor running while channels are monitored.");
        serviceChannel.setShowBadge(false);

        NotificationChannel monitoringChannel = new NotificationChannel(
            CHANNEL_MONITORING,
            "Channel Monitoring",
            NotificationManager.IMPORTANCE_LOW
        );
        monitoringChannel.setDescription("Shows one quiet notification per monitored channel.");
        monitoringChannel.setShowBadge(false);

        NotificationChannel liveAlertsChannel = new NotificationChannel(
            CHANNEL_LIVE_ALERTS,
            "Live Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        );
        liveAlertsChannel.setDescription("Alerts when a monitored channel goes live.");
        liveAlertsChannel.setShowBadge(true);

        notificationManager.createNotificationChannel(serviceChannel);
        notificationManager.createNotificationChannel(monitoringChannel);
        notificationManager.createNotificationChannel(liveAlertsChannel);
    }

    public Notification buildServiceNotification(int monitoredCount) {
        Intent intent = new Intent(appContext, MainActivity.class);
        PendingIntent pendingIntent = buildActivityPendingIntent(
            intent,
            SERVICE_NOTIFICATION_ID
        );

        String title = "Live Monitor is running";
        String text = monitoredCount <= 0
            ? "Waiting for channels"
            : "Monitoring " + monitoredCount + " channel" + (monitoredCount == 1 ? "" : "s");

        return new NotificationCompat.Builder(appContext, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
    }

    public Notification buildChannelMonitoringNotification(ChannelItem channel) {
        Intent intent = new Intent(appContext, MainActivity.class);
        PendingIntent pendingIntent = buildActivityPendingIntent(
            intent,
            channel == null ? SERVICE_NOTIFICATION_ID + 10 : channel.getNotificationId()
        );

        String title = channel == null
            ? "Monitoring channel"
            : "Monitoring: " + channel.getDisplayTitle();

        String text = buildChannelStatusText(channel);

        return new NotificationCompat.Builder(appContext, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build();
    }

    public Notification buildLiveDetectedNotification(ChannelItem channel) {
        Intent intent = new Intent(appContext, MainActivity.class);
        PendingIntent pendingIntent = buildActivityPendingIntent(
            intent,
            channel == null
                ? LIVE_ALERT_ID_OFFSET
                : LIVE_ALERT_ID_OFFSET + channel.getNotificationId()
        );

        String channelTitle = channel == null ? "A monitored channel" : channel.getDisplayTitle();
        String title = "Live detected";
        String text = channelTitle + " is live. Recording will start.";

        if (channel != null && channel.hasCurrentVideoId()) {
            text = text + " videoId=" + channel.getCurrentVideoId();
        }

        return new NotificationCompat.Builder(appContext, CHANNEL_LIVE_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build();
    }

    public static final int PO_TOKEN_SETUP_NOTIFICATION_ID = 3_000_000;

    public void showPoTokenSetupNotification() {
        if (notificationManager == null || !canPostNotifications()) {
            return;
        }

        Intent intent = new Intent(appContext, YouTubeSignInActivity.class);
        PendingIntent pendingIntent = buildActivityPendingIntent(
            intent,
            PO_TOKEN_SETUP_NOTIFICATION_ID
        );

        String title = "YouTube PO token required";
        String text = "yt-dlp cannot record live streams without a PO token. "
            + "Tap to open YouTube PO Token Setup, load a live video, then tap "
            + "'Generate/Refresh PO token'.";

        Notification notification = new NotificationCompat.Builder(appContext, CHANNEL_LIVE_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build();

        notificationManager.notify(PO_TOKEN_SETUP_NOTIFICATION_ID, notification);
    }

    public void showChannelMonitoringNotification(ChannelItem channel) {
        if (channel == null || notificationManager == null || !canPostNotifications()) {
            return;
        }

        notificationManager.notify(
            channel.getNotificationId(),
            buildChannelMonitoringNotification(channel)
        );
    }

    public void showLiveDetectedNotification(ChannelItem channel) {
        if (channel == null || notificationManager == null || !canPostNotifications()) {
            return;
        }

        notificationManager.notify(
            LIVE_ALERT_ID_OFFSET + channel.getNotificationId(),
            buildLiveDetectedNotification(channel)
        );
    }

    public void cancelChannelNotification(ChannelItem channel) {
        if (channel == null || notificationManager == null) {
            return;
        }

        notificationManager.cancel(channel.getNotificationId());
        notificationManager.cancel(LIVE_ALERT_ID_OFFSET + channel.getNotificationId());
    }

    public void cancelChannelNotificationById(int notificationId) {
        if (notificationManager == null) {
            return;
        }

        notificationManager.cancel(notificationId);
        notificationManager.cancel(LIVE_ALERT_ID_OFFSET + notificationId);
    }

    public void cancelAllChannelNotifications(Iterable<ChannelItem> channels) {
        if (channels == null) {
            return;
        }

        for (ChannelItem channel : channels) {
            cancelChannelNotification(channel);
        }
    }

    public boolean canPostNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }

        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED;
    }

    private String buildChannelStatusText(ChannelItem channel) {
        if (channel == null) {
            return "Waiting for live stream.";
        }

        String status = channel.getStatus();

        if (ChannelItem.STATUS_RECORDING.equals(status)) {
            return "Recording active live stream.";
        }

        if (ChannelItem.STATUS_WAITING_FOR_LIVE.equals(status)) {
            return "Waiting for live stream.";
        }

        if (ChannelItem.STATUS_RETRYING.equals(status)) {
            return "Retrying after stream/network issue. Attempt "
                + channel.getRetryCount()
                + "/"
                + channel.getMaxRetries();
        }

        if (ChannelItem.STATUS_PAUSED_NETWORK.equals(status)) {
            return "Paused because network is unavailable.";
        }

        if (ChannelItem.STATUS_PAUSED_BY_USER.equals(status)) {
            return "Paused by user.";
        }

        if (ChannelItem.STATUS_LIVE_DETECTED.equals(status)) {
            return "Live detected. Preparing recording.";
        }

        if (ChannelItem.STATUS_FAILED.equals(status)) {
            String error = channel.getLastError();
            return error == null || error.trim().isEmpty()
                ? "Monitoring failed."
                : "Monitoring failed: " + error;
        }

        return "Monitoring enabled.";
    }

    private PendingIntent buildActivityPendingIntent(Intent intent, int requestCode) {
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getActivity(appContext, requestCode, intent, flags);
    }
}
