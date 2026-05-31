package com.livemonitor.app;

/**
 * Central action/extra constants used between Activity, Service,
 * BroadcastReceivers, and UI screens.
 *
 * Keeping these in one file avoids typo bugs from raw string actions.
 */
public final class LiveMonitorActions {

    private LiveMonitorActions() {
        // Utility class.
    }

    public static final String PACKAGE = "com.livemonitor.app";

    public static final String ACTION_START_MONITORING =
        PACKAGE + ".action.START_MONITORING";

    public static final String ACTION_STOP_MONITORING =
        PACKAGE + ".action.STOP_MONITORING";

    public static final String ACTION_STOP_ALL =
        PACKAGE + ".action.STOP_ALL";

    public static final String ACTION_PAUSE_CHANNEL =
        PACKAGE + ".action.PAUSE_CHANNEL";

    public static final String ACTION_RESUME_CHANNEL =
        PACKAGE + ".action.RESUME_CHANNEL";

    public static final String ACTION_REMOVE_CHANNEL =
        PACKAGE + ".action.REMOVE_CHANNEL";

    public static final String ACTION_RESTORE_MONITORING =
        PACKAGE + ".action.RESTORE_MONITORING";

    public static final String ACTION_NETWORK_AVAILABLE =
        PACKAGE + ".action.NETWORK_AVAILABLE";

    public static final String ACTION_NETWORK_LOST =
        PACKAGE + ".action.NETWORK_LOST";

    public static final String ACTION_RECORDING_UPDATED =
        PACKAGE + ".action.RECORDING_UPDATED";

    public static final String ACTION_CHANNEL_UPDATED =
        PACKAGE + ".action.CHANNEL_UPDATED";

    public static final String ACTION_LOG_UPDATED =
        PACKAGE + ".action.LOG_UPDATED";

    public static final String ACTION_REMOTE_CONFIG_UPDATED =
        PACKAGE + ".action.REMOTE_CONFIG_UPDATED";

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_CHANNEL_ID = "channelId";
    public static final String EXTRA_CHANNEL_TITLE = "channelTitle";
    public static final String EXTRA_RECORDING_ID = "recordingId";
    public static final String EXTRA_VIDEO_ID = "videoId";
    public static final String EXTRA_VIDEO_URL = "videoUrl";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_SOURCE = "source";
    public static final String EXTRA_ENABLED_CHANNEL_COUNT = "enabledChannelCount";
    public static final String EXTRA_FROM_BOOT = "fromBoot";
    public static final String EXTRA_LOG_LEVEL = "logLevel";
    public static final String EXTRA_LOG_DETAILS = "logDetails";

    /*
     * Legacy actions used by the current simple MonitorService.
     * Keeping them allows backwards compatibility while we migrate.
     */
    public static final String LEGACY_ACTION_START = "START";
    public static final String LEGACY_ACTION_STOP = "STOP";

    public static boolean isStartAction(String action) {
        return ACTION_START_MONITORING.equals(action)
            || LEGACY_ACTION_START.equals(action);
    }

    public static boolean isStopAction(String action) {
        return ACTION_STOP_MONITORING.equals(action)
            || ACTION_STOP_ALL.equals(action)
            || LEGACY_ACTION_STOP.equals(action);
    }

    public static boolean isChannelMutationAction(String action) {
        return ACTION_START_MONITORING.equals(action)
            || ACTION_PAUSE_CHANNEL.equals(action)
            || ACTION_RESUME_CHANNEL.equals(action)
            || ACTION_REMOVE_CHANNEL.equals(action)
            || ACTION_STOP_MONITORING.equals(action);
    }
}
