package com.livemonitor.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Central persistence helper for the app.
 *
 * Stores:
 * - monitored channels
 * - recordings
 * - settings
 * - cached remote config
 * - structured logs
 *
 * Uses SharedPreferences + JSON for simplicity.
 * This keeps the app lightweight and avoids adding a database dependency.
 */
public class AppStorage {

    private static final String PREF_NAME = "live_monitor_storage";

    private static final String KEY_CHANNELS = "channels_json";
    private static final String KEY_RECORDINGS = "recordings_json";
    private static final String KEY_SETTINGS = "settings_json";
    private static final String KEY_REMOTE_CONFIG = "remote_config_json";
    private static final String KEY_LOGS = "logs_json";
    private static final String KEY_LAST_WORKING_PLAYER_CLIENT = "last_working_player_client";
    private static final String KEY_LAST_NETWORK_LOST_AT = "last_network_lost_at";
    private static final String KEY_LAST_NETWORK_RESTORED_AT = "last_network_restored_at";
    private static final String KEY_LAST_MISSED_STREAM_CHECKED_OUTAGE = "last_missed_stream_checked_outage";
    private static final String KEY_MISSED_STREAM_RECORDS = "missed_stream_records_json";

    private static final int DEFAULT_MAX_LOGS = 2_000;
    private static final int DEFAULT_MAX_RECORDINGS = 500;

    private final Context appContext;
    private final SharedPreferences preferences;

    public AppStorage(Context context) {
        appContext = context.getApplicationContext();
        preferences = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public Context getContext() {
        return appContext;
    }

    public synchronized void saveNetworkLostAt(long lostAtMillis) {
        preferences.edit().putLong(KEY_LAST_NETWORK_LOST_AT, Math.max(0L, lostAtMillis)).apply();
    }

    public synchronized long loadNetworkLostAt() {
        return preferences.getLong(KEY_LAST_NETWORK_LOST_AT, 0L);
    }

    public synchronized void saveNetworkRestoredAt(long restoredAtMillis) {
        preferences.edit().putLong(KEY_LAST_NETWORK_RESTORED_AT, Math.max(0L, restoredAtMillis)).apply();
    }

    public synchronized long loadNetworkRestoredAt() {
        return preferences.getLong(KEY_LAST_NETWORK_RESTORED_AT, 0L);
    }

    public synchronized void clearNetworkLostAt() {
        preferences.edit().remove(KEY_LAST_NETWORK_LOST_AT).apply();
    }

    public synchronized boolean markMissedStreamOutageChecked(long lostAtMillis, long restoredAtMillis) {
        String outageKey = Math.max(0L, lostAtMillis) + ":" + Math.max(0L, restoredAtMillis);
        String previous = preferences.getString(KEY_LAST_MISSED_STREAM_CHECKED_OUTAGE, "");
        if (outageKey.equals(previous)) {
            return false;
        }
        preferences.edit().putString(KEY_LAST_MISSED_STREAM_CHECKED_OUTAGE, outageKey).apply();
        return true;
    }

    public synchronized void addMissedStreamRecord(String channelId, String title, String details) {
        if (isBlank(channelId)) {
            return;
        }
        try {
            JSONObject root = new JSONObject(preferences.getString(KEY_MISSED_STREAM_RECORDS, "{}"));
            JSONArray records = root.optJSONArray(channelId);
            if (records == null) {
                records = new JSONArray();
            }
            JSONObject record = new JSONObject();
            record.put("createdAt", System.currentTimeMillis());
            record.put("title", isBlank(title) ? "Possible missed stream" : title.trim());
            record.put("details", details == null ? "" : details);
            JSONArray updated = new JSONArray();
            updated.put(record);
            for (int i = 0; i < records.length() && updated.length() < 10; i++) {
                JSONObject existing = records.optJSONObject(i);
                if (existing != null) {
                    updated.put(existing);
                }
            }
            root.put(channelId, updated);
            preferences.edit().putString(KEY_MISSED_STREAM_RECORDS, root.toString()).apply();
        } catch (JSONException ignored) {
            // Keep monitoring even if the warning badge cannot be persisted.
        }
    }

    public synchronized int countMissedStreamRecords(String channelId) {
        if (isBlank(channelId)) {
            return 0;
        }
        try {
            JSONObject root = new JSONObject(preferences.getString(KEY_MISSED_STREAM_RECORDS, "{}"));
            JSONArray records = root.optJSONArray(channelId);
            return records == null ? 0 : records.length();
        } catch (JSONException ignored) {
            return 0;
        }
    }

    public synchronized void clearMissedStreamRecords(String channelId) {
        if (isBlank(channelId)) {
            return;
        }
        try {
            JSONObject root = new JSONObject(preferences.getString(KEY_MISSED_STREAM_RECORDS, "{}"));
            root.remove(channelId);
            preferences.edit().putString(KEY_MISSED_STREAM_RECORDS, root.toString()).apply();
        } catch (JSONException ignored) {
            preferences.edit().putString(KEY_MISSED_STREAM_RECORDS, "{}").apply();
        }
    }

    public synchronized List<ChannelItem> loadChannels() {
        String rawJson = preferences.getString(KEY_CHANNELS, "[]");
        List<ChannelItem> channels = new ArrayList<>();

        try {
            JSONArray array = new JSONArray(rawJson);

            for (int i = 0; i < array.length(); i++) {
                JSONObject itemJson = array.optJSONObject(i);

                if (itemJson != null) {
                    channels.add(ChannelItem.fromJson(itemJson));
                }
            }
        } catch (JSONException ignored) {
            return new ArrayList<>();
        }

        return channels;
    }

    public synchronized void saveChannels(List<ChannelItem> channels) {
        JSONArray array = new JSONArray();

        if (channels != null) {
            for (ChannelItem channel : channels) {
                if (channel == null) {
                    continue;
                }

                try {
                    array.put(channel.toJson());
                } catch (JSONException ignored) {
                    // Skip invalid item and continue saving the rest.
                }
            }
        }

        preferences.edit()
            .putString(KEY_CHANNELS, array.toString())
            .apply();
    }

    public synchronized boolean addChannelIfMissing(ChannelItem newChannel) {
        if (newChannel == null) {
            return false;
        }

        List<ChannelItem> channels = loadChannels();

        for (ChannelItem existing : channels) {
            if (existing != null && existing.hasSameNormalizedUrl(newChannel)) {
                return false;
            }
        }

        channels.add(newChannel);
        saveChannels(channels);
        return true;
    }

    public synchronized void upsertChannel(ChannelItem channel) {
        if (channel == null) {
            return;
        }

        List<ChannelItem> channels = loadChannels();
        boolean updated = false;

        for (int i = 0; i < channels.size(); i++) {
            ChannelItem existing = channels.get(i);

            if (existing == null) {
                continue;
            }

            boolean sameId = existing.getId().equals(channel.getId());
            boolean sameUrl = existing.hasSameNormalizedUrl(channel);

            if (sameId || sameUrl) {
                channels.set(i, channel);
                updated = true;
                break;
            }
        }

        if (!updated) {
            channels.add(channel);
        }

        saveChannels(channels);
    }

    public synchronized void removeChannel(String channelId) {
        if (isBlank(channelId)) {
            return;
        }

        List<ChannelItem> channels = loadChannels();
        Iterator<ChannelItem> iterator = channels.iterator();

        while (iterator.hasNext()) {
            ChannelItem channel = iterator.next();

            if (channel != null && channelId.equals(channel.getId())) {
                iterator.remove();
            }
        }

        saveChannels(channels);
    }

    public synchronized ChannelItem findChannelById(String channelId) {
        if (isBlank(channelId)) {
            return null;
        }

        List<ChannelItem> channels = loadChannels();

        for (ChannelItem channel : channels) {
            if (channel != null && channelId.equals(channel.getId())) {
                return channel;
            }
        }

        return null;
    }

    public synchronized ChannelItem findChannelByNormalizedUrl(String url) {
        if (isBlank(url)) {
            return null;
        }

        String normalizedUrl = ChannelItem.normalizeUrl(url);
        List<ChannelItem> channels = loadChannels();

        for (ChannelItem channel : channels) {
            if (channel != null && normalizedUrl.equals(channel.getNormalizedUrl())) {
                return channel;
            }
        }

        return null;
    }

    public synchronized List<RecordingItem> loadRecordings() {
        String rawJson = preferences.getString(KEY_RECORDINGS, "[]");
        List<RecordingItem> recordings = new ArrayList<>();

        try {
            JSONArray array = new JSONArray(rawJson);

            for (int i = 0; i < array.length(); i++) {
                JSONObject itemJson = array.optJSONObject(i);

                if (itemJson != null) {
                    recordings.add(RecordingItem.fromJson(itemJson));
                }
            }
        } catch (JSONException ignored) {
            return new ArrayList<>();
        }

        return recordings;
    }

    public synchronized void saveRecordings(List<RecordingItem> recordings) {
        JSONArray array = new JSONArray();

        if (recordings != null) {
            List<RecordingItem> trimmed = trimRecordings(recordings, DEFAULT_MAX_RECORDINGS);

            for (RecordingItem recording : trimmed) {
                if (recording == null) {
                    continue;
                }

                try {
                    array.put(recording.toJson());
                } catch (JSONException ignored) {
                    // Skip invalid item and continue saving the rest.
                }
            }
        }

        preferences.edit()
            .putString(KEY_RECORDINGS, array.toString())
            .apply();
    }

    public synchronized void upsertRecording(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        List<RecordingItem> recordings = loadRecordings();
        boolean updated = false;

        for (int i = 0; i < recordings.size(); i++) {
            RecordingItem existing = recordings.get(i);

            if (existing != null && existing.getId().equals(recording.getId())) {
                recordings.set(i, recording);
                updated = true;
                break;
            }
        }

        if (!updated) {
            recordings.add(recording);
        }

        saveRecordings(recordings);
    }

    public synchronized void removeRecording(String recordingId) {
        if (isBlank(recordingId)) {
            return;
        }

        List<RecordingItem> recordings = loadRecordings();
        Iterator<RecordingItem> iterator = recordings.iterator();

        while (iterator.hasNext()) {
            RecordingItem recording = iterator.next();

            if (recording != null && recordingId.equals(recording.getId())) {
                iterator.remove();
            }
        }

        saveRecordings(recordings);
    }

    public synchronized RecordingItem findRecordingById(String recordingId) {
        if (isBlank(recordingId)) {
            return null;
        }

        List<RecordingItem> recordings = loadRecordings();

        for (RecordingItem recording : recordings) {
            if (recording != null && recordingId.equals(recording.getId())) {
                return recording;
            }
        }

        return null;
    }

    public synchronized RecordingItem findActiveRecordingForVideo(String videoId) {
        if (isBlank(videoId)) {
            return null;
        }

        List<RecordingItem> recordings = loadRecordings();

        for (RecordingItem recording : recordings) {
            if (recording != null && recording.matchesVideo(videoId) && recording.isActive()) {
                return recording;
            }
        }

        return null;
    }


    public synchronized RecordingItem findCompletedRecordingForVideo(String videoId) {
        if (isBlank(videoId)) {
            return null;
        }

        List<RecordingItem> recordings = loadRecordings();

        for (RecordingItem recording : recordings) {
            if (recording != null && recording.matchesVideo(videoId) && recording.isPlayableCompletedFile()) {
                return recording;
            }
        }

        return null;
    }

    public synchronized List<RecordingItem> loadActiveRecordings() {
        List<RecordingItem> result = new ArrayList<>();

        for (RecordingItem recording : loadRecordings()) {
            if (recording != null && recording.isActive()) {
                result.add(recording);
            }
        }

        return result;
    }

    public synchronized List<RecordingItem> loadCompletedRecordings() {
        List<RecordingItem> result = new ArrayList<>();

        for (RecordingItem recording : loadRecordings()) {
            if (recording != null && recording.isPlayableCompletedFile()) {
                result.add(recording);
            }
        }

        return result;
    }

    public synchronized void removeEmptyOrUnplayableFinishedRecordings() {
        /*
         * Before pruning stale Android/data paths, try to relink/import files from
         * the selected save folder. Users often move completed recordings out of
         * app storage manually; this keeps Past Recordings connected to those
         * selected-folder copies instead of deleting history entries immediately.
         */
        new SelectedFolderRecordingImporter(appContext).importFromSelectedFolder();

        List<RecordingItem> recordings = loadRecordings();
        boolean changed = false;
        Iterator<RecordingItem> iterator = recordings.iterator();

        while (iterator.hasNext()) {
            RecordingItem recording = iterator.next();

            if (recording == null) {
                iterator.remove();
                changed = true;
                continue;
            }

            if (recording.isFinished() && !recording.isPlayableCompletedFile()) {
                iterator.remove();
                changed = true;
            }
        }

        if (changed) {
            saveRecordings(recordings);
        }
    }

    public synchronized List<RecordingItem> loadRecoverableRecordings() {
        List<RecordingItem> result = new ArrayList<>();

        for (RecordingItem recording : loadRecordings()) {
            if (recording != null && recording.isRecoverable()) {
                result.add(recording);
            }
        }

        return result;
    }

    public synchronized AppSettings loadSettings() {
        String rawJson = preferences.getString(KEY_SETTINGS, "");

        if (isBlank(rawJson)) {
            return new AppSettings();
        }

        try {
            return AppSettings.fromJson(new JSONObject(rawJson));
        } catch (JSONException ignored) {
            return new AppSettings();
        }
    }

    public synchronized void saveSettings(AppSettings settings) {
        if (settings == null) {
            settings = new AppSettings();
        }

        try {
            preferences.edit()
                .putString(KEY_SETTINGS, settings.toJson().toString())
                .apply();
        } catch (JSONException ignored) {
            preferences.edit()
                .putString(KEY_SETTINGS, "")
                .apply();
        }
    }

    public synchronized RemoteConfig loadRemoteConfig() {
        String rawJson = preferences.getString(KEY_REMOTE_CONFIG, "");

        if (isBlank(rawJson)) {
            return new RemoteConfig();
        }

        try {
            return RemoteConfig.fromJson(new JSONObject(rawJson));
        } catch (JSONException ignored) {
            return new RemoteConfig();
        }
    }

    public synchronized boolean hasCachedRemoteConfig() {
        return !isBlank(preferences.getString(KEY_REMOTE_CONFIG, ""));
    }

    public synchronized void saveRemoteConfig(RemoteConfig remoteConfig) {
        if (remoteConfig == null) {
            remoteConfig = new RemoteConfig();
        }

        try {
            preferences.edit()
                .putString(KEY_REMOTE_CONFIG, remoteConfig.toJson().toString())
                .apply();
        } catch (JSONException ignored) {
            preferences.edit()
                .putString(KEY_REMOTE_CONFIG, "")
                .apply();
        }
    }


    public synchronized String getLastWorkingPlayerClient() {
        return preferences.getString(KEY_LAST_WORKING_PLAYER_CLIENT, "");
    }

    public synchronized void setLastWorkingPlayerClient(String client) {
        String normalized = client == null ? "" : client.trim().toLowerCase(java.util.Locale.US);

        if (normalized.isEmpty()) {
            preferences.edit().remove(KEY_LAST_WORKING_PLAYER_CLIENT).apply();
            return;
        }

        preferences.edit()
            .putString(KEY_LAST_WORKING_PLAYER_CLIENT, normalized)
            .apply();
    }

    public synchronized List<LogItem> loadLogs() {
        String rawJson = preferences.getString(KEY_LOGS, "[]");
        List<LogItem> logs = new ArrayList<>();

        try {
            JSONArray array = new JSONArray(rawJson);

            for (int i = 0; i < array.length(); i++) {
                JSONObject itemJson = array.optJSONObject(i);

                if (itemJson != null) {
                    logs.add(LogItem.fromJson(itemJson));
                }
            }
        } catch (JSONException ignored) {
            return new ArrayList<>();
        }

        return logs;
    }

    public synchronized void saveLogs(List<LogItem> logs) {
        AppSettings settings = loadSettings();
        int maxLogs = Math.max(100, settings.getLogRetentionLines());

        JSONArray array = new JSONArray();

        if (logs != null) {
            List<LogItem> trimmed = trimLogs(logs, maxLogs);

            for (LogItem log : trimmed) {
                if (log == null) {
                    continue;
                }

                try {
                    array.put(log.toJson());
                } catch (JSONException ignored) {
                    // Skip invalid item and continue saving the rest.
                }
            }
        }

        preferences.edit()
            .putString(KEY_LOGS, array.toString())
            .apply();
    }

    public synchronized void appendLog(LogItem log) {
        if (log == null || !shouldStoreLog(log)) {
            return;
        }

        List<LogItem> logs = loadLogs();
        logs.add(log);
        saveLogs(logs);
    }

    public synchronized void appendLogs(List<LogItem> newLogs) {
        if (newLogs == null || newLogs.isEmpty()) {
            return;
        }

        List<LogItem> logs = loadLogs();

        for (LogItem log : newLogs) {
            if (log != null && shouldStoreLog(log)) {
                logs.add(log);
            }
        }

        saveLogs(logs);
    }

    private boolean shouldStoreLog(LogItem log) {
        return loadSettings().isLogItemEnabled(log);
    }

    public synchronized List<LogItem> loadLogsForChannel(String channelId) {
        if (isBlank(channelId)) {
            return new ArrayList<>();
        }

        List<LogItem> result = new ArrayList<>();

        for (LogItem log : loadLogs()) {
            if (log != null && log.belongsToChannel(channelId)) {
                result.add(log);
            }
        }

        return result;
    }

    public synchronized List<LogItem> loadLogsForRecording(String recordingId) {
        if (isBlank(recordingId)) {
            return new ArrayList<>();
        }

        List<LogItem> result = new ArrayList<>();

        for (LogItem log : loadLogs()) {
            if (log != null && log.belongsToRecording(recordingId)) {
                result.add(log);
            }
        }

        return result;
    }

    public synchronized void clearAllLogs() {
        preferences.edit()
            .putString(KEY_LOGS, "[]")
            .apply();
    }

    public synchronized void clearLogsForChannel(String channelId) {
        if (isBlank(channelId)) {
            return;
        }

        List<LogItem> logs = loadLogs();
        Iterator<LogItem> iterator = logs.iterator();

        while (iterator.hasNext()) {
            LogItem log = iterator.next();

            if (log != null && log.belongsToChannel(channelId)) {
                iterator.remove();
            }
        }

        saveLogs(logs);
    }

    public synchronized String buildCopyTextForAllLogs() {
        StringBuilder builder = new StringBuilder();

        for (LogItem log : loadLogs()) {
            if (log != null && log.isCopyable()) {
                if (builder.length() > 0) {
                    builder.append("\n");
                }

                builder.append(log.toCopyLine());
            }
        }

        return builder.toString();
    }

    public synchronized String buildCopyTextForChannel(String channelId) {
        StringBuilder builder = new StringBuilder();

        for (LogItem log : loadLogsForChannel(channelId)) {
            if (log != null && log.isCopyable()) {
                if (builder.length() > 0) {
                    builder.append("\n");
                }

                builder.append(log.toCopyLine());
            }
        }

        return builder.toString();
    }

    public synchronized void clearRecordingsHistory() {
        preferences.edit()
            .putString(KEY_RECORDINGS, "[]")
            .apply();
    }

    public synchronized void clearAllAppData() {
        preferences.edit()
            .clear()
            .apply();
    }

    public synchronized JSONObject exportAllDataToJson() throws JSONException {
        JSONObject json = new JSONObject();

        json.put(KEY_CHANNELS, new JSONArray(preferences.getString(KEY_CHANNELS, "[]")));
        json.put(KEY_RECORDINGS, new JSONArray(preferences.getString(KEY_RECORDINGS, "[]")));

        String settingsRaw = preferences.getString(KEY_SETTINGS, "");
        json.put(KEY_SETTINGS, isBlank(settingsRaw) ? new JSONObject() : new JSONObject(settingsRaw));

        String remoteRaw = preferences.getString(KEY_REMOTE_CONFIG, "");
        json.put(
            KEY_REMOTE_CONFIG,
            isBlank(remoteRaw) ? new JSONObject() : new JSONObject(remoteRaw)
        );

        json.put(KEY_LOGS, new JSONArray(preferences.getString(KEY_LOGS, "[]")));

        return json;
    }

    private static List<LogItem> trimLogs(List<LogItem> logs, int maxLogs) {
        if (logs == null || logs.isEmpty()) {
            return new ArrayList<>();
        }

        int safeMaxLogs = Math.max(100, maxLogs);

        if (logs.size() <= safeMaxLogs) {
            return new ArrayList<>(logs);
        }

        return new ArrayList<>(logs.subList(logs.size() - safeMaxLogs, logs.size()));
    }

    private static List<RecordingItem> trimRecordings(
        List<RecordingItem> recordings,
        int maxRecordings
    ) {
        if (recordings == null || recordings.isEmpty()) {
            return new ArrayList<>();
        }

        int safeMaxRecordings = Math.max(50, maxRecordings);

        if (recordings.size() <= safeMaxRecordings) {
            return new ArrayList<>(recordings);
        }

        List<RecordingItem> active = new ArrayList<>();
        List<RecordingItem> finished = new ArrayList<>();

        for (RecordingItem recording : recordings) {
            if (recording != null && recording.isActive()) {
                active.add(recording);
            } else if (recording != null) {
                finished.add(recording);
            }
        }

        int remainingFinishedSlots = Math.max(0, safeMaxRecordings - active.size());

        if (finished.size() > remainingFinishedSlots) {
            finished = finished.subList(
                finished.size() - remainingFinishedSlots,
                finished.size()
            );
        }

        List<RecordingItem> result = new ArrayList<>();
        result.addAll(active);
        result.addAll(finished);

        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

        public synchronized List<ChannelItem> loadChannelsReadOnly() {
        return Collections.unmodifiableList(loadChannels());
    }

    public synchronized List<RecordingItem> loadRecordingsReadOnly() {
        return Collections.unmodifiableList(loadRecordings());
    }

    public synchronized List<LogItem> loadLogsReadOnly() {
        return Collections.unmodifiableList(loadLogs());
    }
}
