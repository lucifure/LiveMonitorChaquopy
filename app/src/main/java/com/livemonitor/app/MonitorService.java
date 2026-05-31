package com.livemonitor.app;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MonitorService extends Service implements NetworkMonitor.Listener {
    private static final String TAG = "MonitorService";
    private static final String FALLBACK_YT_API_KEY = "AIzaSyDnAsBrxe_aFkUSpqkrFDczUw-PpLoEhuY";

    private AppStorage storage;
    private AppSettings settings;
    private RemoteConfig remoteConfig;
    private NotificationHelper notificationHelper;
    private RecordingFileManager fileManager;
    private NetworkMonitor networkMonitor;
    private RecordingProgressTracker progressTracker;
    private PowerManager.WakeLock wakeLock;
    private ExecutorService executor;

    private final Map<String, Boolean> activeLoops = new ConcurrentHashMap<>();
    private final Map<String, RecordingItem> activeRecordings = new ConcurrentHashMap<>();

    private volatile boolean serviceRunning = false;
    private volatile boolean networkAvailable = true;
    private volatile boolean shuttingDown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        storage = new AppStorage(this);
        settings = storage.loadSettings();
        remoteConfig = new RemoteConfigFetcher(this).loadBestAvailableConfig();
        notificationHelper = new NotificationHelper(this);
        fileManager = new RecordingFileManager(this);
        networkMonitor = new NetworkMonitor(this);
        progressTracker = new RecordingProgressTracker(storage);
        executor = Executors.newCachedThreadPool();

        notificationHelper.createNotificationChannels();
        networkMonitor.setListener(this);
        networkMonitor.start();
        progressTracker.start();
        networkAvailable = networkMonitor.isConnectedNow();

        FFmpegRunner.setup(this);
        fileManager.registerRecoverableTsFilesInStorage();
        log(LogItem.LEVEL_SUCCESS, LogItem.SOURCE_SERVICE, null, "MonitorService created.", "");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        settings = storage.loadSettings();
        remoteConfig = new RemoteConfigFetcher(this).loadBestAvailableConfig();
        ensureForeground();
        acquireWakeLock();

        if (intent == null || intent.getAction() == null) {
            restoreSavedChannels();
            return START_STICKY;
        }

        String action = intent.getAction();

        if (LiveMonitorActions.isStartAction(action)) {
            handleStart(intent);
        } else if (LiveMonitorActions.ACTION_PAUSE_CHANNEL.equals(action)) {
            handlePause(intent);
        } else if (LiveMonitorActions.ACTION_RESUME_CHANNEL.equals(action)) {
            handleResume(intent);
        } else if (LiveMonitorActions.ACTION_REMOVE_CHANNEL.equals(action)) {
            handleRemove(intent);
        } else if (LiveMonitorActions.ACTION_STOP_MONITORING.equals(action)) {
            handleStopChannel(intent);
        } else if (LiveMonitorActions.ACTION_STOP_ALL.equals(action)
            || LiveMonitorActions.LEGACY_ACTION_STOP.equals(action)) {
            stopAll();
        } else if (LiveMonitorActions.ACTION_RESTORE_MONITORING.equals(action)
            || BootReceiver.ACTION_RESTORE_MONITORING.equals(action)) {
            restoreSavedChannels();
        }

        return START_STICKY;
    }

    private void handleStart(Intent intent) {
        String channelId = intent.getStringExtra(LiveMonitorActions.EXTRA_CHANNEL_ID);
        String url = intent.getStringExtra(LiveMonitorActions.EXTRA_URL);
        ChannelItem channel = storage.findChannelById(channelId);

        if (channel == null && url != null && !url.trim().isEmpty()) {
            channel = storage.findChannelByNormalizedUrl(url);
        }

        if (channel == null && url != null && !url.trim().isEmpty()) {
            channel = new ChannelItem(url);
        }

        if (channel == null) {
            return;
        }

        channel.setMaxRetries(settings.getMaxRetries());
        channel.resumeMonitoring();
        channel.markWaitingForLive();
        storage.upsertChannel(channel);
        startChannelLoop(channel);
    }

    private void handlePause(Intent intent) {
        ChannelItem channel = getChannelFromIntent(intent);
        if (channel == null) return;

        channel.markPausedByUser();
        storage.upsertChannel(channel);
        activeLoops.remove(channel.getId());
        notificationHelper.showChannelMonitoringNotification(channel);
        broadcastChannelUpdated("Channel paused.");
    }

    private void handleResume(Intent intent) {
        ChannelItem channel = getChannelFromIntent(intent);
        if (channel == null) return;

        channel.resumeMonitoring();
        channel.markWaitingForLive();
        storage.upsertChannel(channel);
        startChannelLoop(channel);
        broadcastChannelUpdated("Channel resumed.");
    }

    private void handleRemove(Intent intent) {
        ChannelItem channel = getChannelFromIntent(intent);
        if (channel == null) return;

        activeLoops.remove(channel.getId());
        notificationHelper.cancelChannelNotification(channel);
        storage.removeChannel(channel.getId());
        broadcastChannelUpdated("Channel removed.");
    }

    private void handleStopChannel(Intent intent) {
        ChannelItem channel = getChannelFromIntent(intent);
        if (channel == null) return;

        activeLoops.remove(channel.getId());
        channel.markStopped();
        storage.upsertChannel(channel);
        notificationHelper.cancelChannelNotification(channel);
        broadcastChannelUpdated("Channel stopped.");
    }

    private ChannelItem getChannelFromIntent(Intent intent) {
        if (intent == null) return null;

        String channelId = intent.getStringExtra(LiveMonitorActions.EXTRA_CHANNEL_ID);
        String url = intent.getStringExtra(LiveMonitorActions.EXTRA_URL);
        ChannelItem channel = storage.findChannelById(channelId);

        return channel != null || url == null
            ? channel
            : storage.findChannelByNormalizedUrl(url);
    }

    private void restoreSavedChannels() {
        List<ChannelItem> channels = storage.loadChannels();

        for (ChannelItem channel : channels) {
            if (channel != null && channel.shouldMonitor()) {
                channel.markWaitingForLive();
                storage.upsertChannel(channel);
                startChannelLoop(channel);
            }
        }

        broadcastChannelUpdated("Saved monitoring restored.");
    }

    private void startChannelLoop(ChannelItem channel) {
        if (channel == null || activeLoops.containsKey(channel.getId())) return;

        serviceRunning = true;
        activeLoops.put(channel.getId(), true);
        notificationHelper.showChannelMonitoringNotification(channel);
        executor.execute(() -> monitorChannel(channel.getId()));
        updateServiceNotification();
        log(LogItem.LEVEL_SUCCESS, LogItem.SOURCE_SERVICE, channel, "Monitoring started.", "");
    }

    private void monitorChannel(String channelId) {
        while (serviceRunning && activeLoops.containsKey(channelId)) {
            ChannelItem channel = storage.findChannelById(channelId);

            if (channel == null || !channel.shouldMonitor()) {
                activeLoops.remove(channelId);
                break;
            }

            if (!networkAvailable) {
                channel.markPausedByNetwork("Waiting for internet connection.");
                storage.upsertChannel(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
                sleep(5_000L);
                continue;
            }

            if (!settings.canStartNewRecordingNow() && !channel.isRecording()) {
                channel.markWaitingForLive();
                storage.upsertChannel(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
                sleep(settings.getPollIntervalMillis());
                continue;
            }

            try {
                channel.setLastCheckAt(System.currentTimeMillis());
                storage.upsertChannel(channel);

                String resolvedChannelId = resolveChannelId(channel.getUrl());

                if (resolvedChannelId == null) {
                    handleRetry(channel, "Could not resolve channel ID.");
                    continue;
                }

                LiveInfo liveInfo = checkLive(resolvedChannelId);

                if (liveInfo == null) {
                    channel.markWaitingForLive();
                    channel.resetRetries();
                    storage.upsertChannel(channel);
                    notificationHelper.showChannelMonitoringNotification(channel);
                    broadcastChannelUpdated("Waiting for live.");
                    sleep(settings.getPollIntervalMillis());
                    continue;
                }

                if (channel.isSameCurrentVideo(liveInfo.videoId) && channel.isRecording()) {
                    sleep(settings.getPollIntervalMillis());
                    continue;
                }

                channel.markLiveDetected(liveInfo.videoId, liveInfo.videoUrl);
                storage.upsertChannel(channel);
                notificationHelper.showLiveDetectedNotification(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
                broadcastChannelUpdated("Live detected.");
                startRecording(channel, liveInfo);
                sleep(settings.getPollIntervalMillis());
            } catch (Exception e) {
                ChannelItem latest = storage.findChannelById(channelId);
                if (latest != null) handleRetry(latest, e.getMessage());
            }
        }

        updateServiceNotification();
    }

    private void startRecording(ChannelItem channel, LiveInfo liveInfo) {
        if (channel == null || liveInfo == null || activeRecordings.containsKey(channel.getId())) {
            return;
        }

        RecordingItem recording = fileManager.createRecordingItem(
            channel,
            liveInfo.videoId,
            liveInfo.videoUrl,
            settings
        );

        fileManager.cleanupTempFolderBeforeRecording();
        recording.markRecording();
        storage.upsertRecording(recording);
        activeRecordings.put(channel.getId(), recording);
        progressTracker.track(recording);

        channel.markRecording(liveInfo.videoId, liveInfo.videoUrl);
        storage.upsertChannel(channel);
        notificationHelper.showChannelMonitoringNotification(channel);

        executor.execute(() -> runRecording(channel.getId(), recording, liveInfo));
    }

    private void runRecording(String channelId, RecordingItem recording, LiveInfo liveInfo) {
        ChannelItem channel = storage.findChannelById(channelId);

        try {
            String videoId = liveInfo == null ? "" : liveInfo.videoId;

            log(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_RECORDER,
                channel,
                "Getting HLS manifest.",
                "videoId="
                    + videoId
                    + ", clients="
                    + getConfiguredClientCountForLog()
                    + ", apiKeys="
                    + getConfiguredApiKeyCountForLog()
            );

            String manifestUrl = getHlsManifestUrl(videoId, channel);

            if (manifestUrl == null || manifestUrl.trim().isEmpty()) {
                throw new IllegalStateException(
                    "Could not get HLS manifest URL. Manifest resolver returned empty URL."
                );
            }

            log(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_RECORDER,
                channel,
                "HLS manifest found.",
                "videoId=" + videoId + ", manifest=" + describeUrlForLog(manifestUrl)
            );

            log(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_RECORDER,
                channel,
                "Recording started.",
                ""
            );

            final ChannelItem logChannel = channel;

            FFmpegRunner.executeAsync(
                manifestUrl,
                recording.getTempTsPath(),
                returnCode -> onRecordingFinished(channelId, recording.getId(), returnCode),
                message -> {
                    if (message != null
                        && !message.startsWith("frame=")
                        && !message.startsWith("size=")) {
                        log(LogItem.LEVEL_DEBUG, LogItem.SOURCE_FFMPEG, logChannel, message, "");
                    }
                }
            );
        } catch (Exception e) {
            String errorMessage = normalizeErrorMessage(e);

            recording.markFailed(errorMessage);
            storage.upsertRecording(recording);
            activeRecordings.remove(channelId);
            progressTracker.untrack(recording);

            ChannelItem latest = storage.findChannelById(channelId);

            if (latest != null) {
                latest.markFailed(errorMessage);
                storage.upsertChannel(latest);
                notificationHelper.showChannelMonitoringNotification(latest);
            }

            log(
                LogItem.LEVEL_ERROR,
                LogItem.SOURCE_RECORDER,
                latest,
                "Recording failed.",
                errorMessage
            );

            broadcastRecordingUpdated("Recording failed.");
        }
    }

    private void onRecordingFinished(String channelId, String recordingId, int returnCode) {
        RecordingItem recording = storage.findRecordingById(recordingId);
        ChannelItem channel = storage.findChannelById(channelId);

        if (recording == null) return;

        activeRecordings.remove(channelId);
        progressTracker.untrack(recording);

        if (returnCode == 0) {
            convertRecording(recording, channel);
        } else if (returnCode == 255 || returnCode == -1) {
            recording.markStoppedByUser();
            storage.upsertRecording(recording);
        } else {
            recording.markRecoverable("Recorder exited with code " + returnCode);
            storage.upsertRecording(recording);
        }

        if (channel != null) {
            channel.markRecordingFinished();
            channel.markWaitingForLive();
            storage.upsertChannel(channel);
            notificationHelper.showChannelMonitoringNotification(channel);
        }

        broadcastRecordingUpdated("Recording updated.");
    }

    private void convertRecording(RecordingItem recording, ChannelItem channel) {
        if (!settings.isConvertTsToMp4()) {
            recording.markCompleted(recording.getTempTsPath());
            storage.upsertRecording(recording);
            return;
        }

        recording.markConverting();
        storage.upsertRecording(recording);
        broadcastRecordingUpdated("Converting recording.");

        String command = "-y -i " + quote(recording.getTempTsPath())
            + " -c copy -movflags +faststart "
            + quote(recording.getFinalMp4Path());

        try {
            ReturnCode code = FFmpegKit.execute(command).getReturnCode();

            if (ReturnCode.isSuccess(code)) {
                recording.markCompleted(recording.getFinalMp4Path());
                safeDelete(recording.getTempTsPath());
                log(LogItem.LEVEL_SUCCESS, LogItem.SOURCE_RECORDER, channel, "Recording completed.", recording.getFinalMp4Path());
            } else {
                recording.markRecoverable("MP4 conversion failed.");
                log(LogItem.LEVEL_WARNING, LogItem.SOURCE_RECORDER, channel, "Conversion failed.", "");
            }
        } catch (Exception e) {
            recording.markRecoverable(e.getMessage());
            log(LogItem.LEVEL_ERROR, LogItem.SOURCE_RECORDER, channel, "Conversion error.", e.getMessage());
        }

        storage.upsertRecording(recording);
    }

    private void handleRetry(ChannelItem channel, String message) {
        if (channel == null) return;

        if (!channel.canRetry()) {
            channel.markFailed(message);
            storage.upsertChannel(channel);
            notificationHelper.showChannelMonitoringNotification(channel);
            log(LogItem.LEVEL_ERROR, LogItem.SOURCE_SERVICE, channel, "Max retries reached.", message);
            sleep(settings.getPollIntervalMillis());
            return;
        }

        channel.markRetrying(message);
        storage.upsertChannel(channel);
        notificationHelper.showChannelMonitoringNotification(channel);
        broadcastChannelUpdated("Retrying.");
        sleep(channel.getNextRetryDelayMillis());
    }

    private String resolveChannelId(String channelUrl) {
        try {
            if (channelUrl == null) return null;

            if (channelUrl.contains("/channel/")) {
                return channelUrl.substring(channelUrl.indexOf("/channel/") + 9)
                    .replaceAll("[/?#].*", "");
            }

            String handle = null;

            if (channelUrl.contains("/@")) {
                handle = channelUrl.substring(channelUrl.indexOf("/@") + 2)
                    .replaceAll("[/?#].*", "");
            } else if (channelUrl.contains("/c/") || channelUrl.contains("/user/")) {
                handle = channelUrl.substring(channelUrl.lastIndexOf("/") + 1)
                    .replaceAll("[/?#].*", "");
            }

            if (handle == null || handle.trim().isEmpty()) return null;            String apiUrl = "https://www.googleapis.com/youtube/v3/channels"
                + "?part=id&forHandle=" + URLEncoder.encode(handle, "UTF-8")
                + "&key=" + getApiKey();

            JSONObject json = new JSONObject(httpGet(apiUrl));
            JSONArray items = json.optJSONArray("items");

            return items != null && items.length() > 0
                ? items.getJSONObject(0).getString("id")
                : null;
        } catch (Exception e) {
            Log.w(TAG, "resolveChannelId failed", e);
            return null;
        }
    }

    private LiveInfo checkLive(String channelId) {
        try {
            String apiUrl = "https://www.googleapis.com/youtube/v3/search"
                + "?part=snippet&channelId=" + URLEncoder.encode(channelId, "UTF-8")
                + "&eventType=live&type=video&maxResults=1&key=" + getApiKey();

            JSONObject json = new JSONObject(httpGet(apiUrl));
            JSONArray items = json.optJSONArray("items");

            if (items == null || items.length() == 0) return null;

            JSONObject item = items.getJSONObject(0);
            String videoId = item.getJSONObject("id").getString("videoId");
            String title = item.getJSONObject("snippet").getString("title");

            return new LiveInfo(videoId, title, "https://youtube.com/watch?v=" + videoId);
        } catch (Exception e) {
            Log.w(TAG, "checkLive failed", e);
            return null;
        }
    }

    private String getHlsManifestUrl(String videoId, ChannelItem channel) {
        if (videoId == null || videoId.trim().isEmpty()) {
            throw new IllegalStateException("Could not get HLS manifest URL. videoId is empty.");
        }

        List<RemoteConfig.YoutubeClient> clients = remoteConfig.getYoutubeClients();
        int clientCount = clients == null || clients.isEmpty() ? 1 : clients.size();
        int apiKeyCount = Math.max(1, remoteConfig.getApiKeys().size());

        String lastFailure = "";

        for (int clientIndex = 0; clientIndex < clientCount; clientIndex++) {
            RemoteConfig.YoutubeClient client = remoteConfig.getClientForAttempt(clientIndex);

            if (client == null || !client.isValid()) {
                lastFailure = "Invalid YouTube client. clientIndex=" + clientIndex;

                log(
                    LogItem.LEVEL_WARNING,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "HLS manifest client skipped.",
                    lastFailure
                );

                continue;
            }

            for (int keyIndex = 0; keyIndex < apiKeyCount; keyIndex++) {
                String apiKey = getApiKeyForAttempt(keyIndex);
                String apiUrl = remoteConfig.getInnertubeBaseUrl() + "/player?key=" + apiKey;

                String attemptDetails = "videoId="
                    + videoId
                    + ", clientIndex="
                    + clientIndex
                    + ", client="
                    + describeClientForLog(client)
                    + ", keyAttempt="
                    + keyIndex
                    + ", key="
                    + maskApiKeyForLog(apiKey);

                log(
                    LogItem.LEVEL_INFO,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "Trying HLS manifest request.",
                    attemptDetails
                );

                try {
                    JSONObject context = new JSONObject()
                        .put("client", client.toInnertubeClientJson());

                    JSONObject body = new JSONObject()
                        .put("context", context)
                        .put("videoId", videoId)
                        .put("contentCheckOk", true)
                        .put("racyCheckOk", true)
                        .put("playbackContext", new JSONObject()
                            .put("contentPlaybackContext", new JSONObject()
                                .put("html5Preference", "HTML5_PREF_WANTS")));

                    String response = httpPost(apiUrl, body.toString(), client);
                    JSONObject json = new JSONObject(response);

                    JSONObject playabilityStatus = json.optJSONObject("playabilityStatus");
                    String status = playabilityStatus == null
                        ? ""
                        : playabilityStatus.optString("status", "");
                    String reason = playabilityStatus == null
                        ? ""
                        : playabilityStatus.optString("reason", "");

                    JSONObject streamingData = json.optJSONObject("streamingData");

                    if (streamingData == null) {
                        lastFailure = "No streamingData. "
                            + attemptDetails
                            + ", status="
                            + status
                            + ", reason="
                            + reason
                            + ", response="
                            + summarizeInnertubeResponseForLog(json);

                        log(
                            LogItem.LEVEL_WARNING,
                            LogItem.SOURCE_RECORDER,
                            channel,
                            "HLS manifest attempt failed.",
                            lastFailure
                        );

                        continue;
                    }

                    String hlsManifestUrl = streamingData.optString("hlsManifestUrl", "");

                    if (hlsManifestUrl == null || hlsManifestUrl.trim().isEmpty()) {
                        lastFailure = "No hlsManifestUrl. "
                            + attemptDetails
                            + ", status="
                            + status
                            + ", reason="
                            + reason
                            + ", streamingDataKeys="
                            + streamingData.names();

                        log(
                            LogItem.LEVEL_WARNING,
                            LogItem.SOURCE_RECORDER,
                            channel,
                            "HLS manifest attempt failed.",
                            lastFailure
                        );

                        continue;
                    }

                    log(
                        LogItem.LEVEL_SUCCESS,
                        LogItem.SOURCE_RECORDER,
                        channel,
                        "HLS manifest request succeeded.",
                        attemptDetails + ", manifest=" + describeUrlForLog(hlsManifestUrl)
                    );

                    return hlsManifestUrl;
                } catch (Exception e) {
                    lastFailure = attemptDetails + ", error=" + normalizeErrorMessage(e);

                    log(
                        LogItem.LEVEL_WARNING,
                        LogItem.SOURCE_RECORDER,
                        channel,
                        "HLS manifest request error.",
                        lastFailure
                    );

                    Log.w(TAG, "getHlsManifestUrl attempt failed: " + lastFailure, e);
                }
            }
        }

        throw new IllegalStateException("Could not get HLS manifest URL. " + lastFailure);
    }

    private String httpGet(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", remoteConfig.getUserAgent());
        return readResponse(connection);
    }

    private String httpPost(
        String urlString,
        String body,
        RemoteConfig.YoutubeClient client
    ) throws Exception {
        byte[] bodyBytes = body.getBytes("UTF-8");

        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("User-Agent", remoteConfig.getUserAgent());
        connection.setRequestProperty("Origin", remoteConfig.getWebPlayerBaseUrl());
        connection.setRequestProperty("Referer", remoteConfig.getWebPlayerBaseUrl() + "/");
        connection.setRequestProperty("X-Youtube-Client-Name", client.getClientName());
        connection.setRequestProperty("X-Youtube-Client-Version", client.getClientVersion());
        connection.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        connection.getOutputStream().write(bodyBytes);

        return readResponse(connection);
    }

    private String readResponse(HttpURLConnection connection) throws Exception {
        int responseCode = connection.getResponseCode();

        InputStream stream = responseCode >= 200 && responseCode < 300
            ? connection.getInputStream()
            : connection.getErrorStream();

        BufferedReader reader = null;

        try {
            StringBuilder builder = new StringBuilder();

            if (stream != null) {
                reader = new BufferedReader(new InputStreamReader(stream));
                String line;

                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            }

            String response = builder.toString();

            if (responseCode < 200 || responseCode >= 300) {
                throw new IllegalStateException(
                    "HTTP "
                        + responseCode
                        + ": "
                        + shortenForLog(response, 700)
                );
            }

            return response;
        } finally {
            if (reader != null) {
                reader.close();
            }

            connection.disconnect();
        }
    }

    private String getApiKey() {
        return getApiKeyForAttempt(0);
    }

    private String getApiKeyForAttempt(int attempt) {
        String key = remoteConfig.getApiKeyForAttempt(attempt);

        if (key == null || key.trim().isEmpty()) {
            return FALLBACK_YT_API_KEY;
        }

        return key.trim();
    }

    private int getConfiguredClientCountForLog() {
        List<RemoteConfig.YoutubeClient> clients = remoteConfig.getYoutubeClients();
        return clients == null || clients.isEmpty() ? 1 : clients.size();
    }

    private int getConfiguredApiKeyCountForLog() {
        return Math.max(1, remoteConfig.getApiKeys().size());
    }

    private static String describeClientForLog(RemoteConfig.YoutubeClient client) {
        if (client == null) {
            return "null";
        }

        return client.getClientName() + "/" + client.getClientVersion();
    }

    private static String maskApiKeyForLog(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return "empty";
        }

        String trimmed = apiKey.trim();

        if (trimmed.length() <= 8) {
            return "****";
        }

        return trimmed.substring(0, 4)
            + "..."
            + trimmed.substring(trimmed.length() - 4);
    }

    private static String summarizeInnertubeResponseForLog(JSONObject json) {
        if (json == null) {
            return "empty";
        }

        JSONObject playabilityStatus = json.optJSONObject("playabilityStatus");
        JSONObject videoDetails = json.optJSONObject("videoDetails");

        String status = playabilityStatus == null
            ? ""
            : playabilityStatus.optString("status", "");
        String reason = playabilityStatus == null
            ? ""
            : playabilityStatus.optString("reason", "");
        String playableInEmbed = playabilityStatus == null
            ? ""
            : playabilityStatus.optString("playableInEmbed", "");
        String liveContent = videoDetails == null
            ? ""
            : videoDetails.optString("isLiveContent", "");

        return "status="
            + status
            + ", reason="
            + reason
            + ", playableInEmbed="
            + playableInEmbed
            + ", isLiveContent="
            + liveContent
            + ", topLevelKeys="
            + json.names();
    }

    private static String describeUrlForLog(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "empty";
        }

        try {
            URL parsed = new URL(url);
            return parsed.getProtocol() + "://" + parsed.getHost() + parsed.getPath();
        } catch (Exception e) {
            return shortenForLog(url, 160);
        }
    }

    private static String normalizeErrorMessage(Exception e) {
        if (e == null) {
            return "Unknown error.";
        }

        String message = e.getMessage();

        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }

        return message.trim();
    }

    private static String shortenForLog(String value, int maxLength) {
        if (value == null) {
            return "";
        }

        if (maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength) + "...";
    }

    @Override
    public void onNetworkAvailable() {
        networkAvailable = true;

        for (ChannelItem channel : storage.loadChannels()) {
            if (channel != null && channel.shouldMonitor()) {
                channel.markWaitingForLive();
                storage.upsertChannel(channel);
                startChannelLoop(channel);
            }
        }

        broadcast(LiveMonitorActions.ACTION_NETWORK_AVAILABLE, "Network restored.");
    }

    @Override
    public void onNetworkLost() {
        networkAvailable = false;

        for (ChannelItem channel : storage.loadChannels()) {
            if (channel != null && channel.shouldMonitor()) {
                channel.markPausedByNetwork("Network unavailable.");
                storage.upsertChannel(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
            }
        }

        broadcast(LiveMonitorActions.ACTION_NETWORK_LOST, "Network lost.");
    }

    @Override
    public void onNetworkChanged(boolean connected) {
        networkAvailable = connected;
    }

    private void ensureForeground() {
        startForeground(
            NotificationHelper.SERVICE_NOTIFICATION_ID,
            notificationHelper.buildServiceNotification(activeLoops.size())
        );
    }

    private void updateServiceNotification() {
        if (notificationHelper.canPostNotifications()) {
            startForeground(
                NotificationHelper.SERVICE_NOTIFICATION_ID,
                notificationHelper.buildServiceNotification(activeLoops.size())
            );
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveMonitor::WakeLock");
        wakeLock.acquire(12 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void stopAll() {
        if (shuttingDown) return;

        shuttingDown = true;
        serviceRunning = false;
        activeLoops.clear();

        for (RecordingItem recording : activeRecordings.values()) {
            if (recording != null) {
                recording.markStoppedByUser();
                storage.upsertRecording(recording);
            }
        }

        activeRecordings.clear();
        FFmpegRunner.cancel();

        if (progressTracker != null) progressTracker.stop();
        if (networkMonitor != null) networkMonitor.stop();

        notificationHelper.cancelAllChannelNotifications(storage.loadChannels());
        releaseWakeLock();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }

        stopSelf();
    }

    private void log(String level, String source, ChannelItem channel, String message, String details) {
        LogItem item = channel == null
            ? new LogItem(level, source, "", "", "", "", message, details)
            : LogItem.channel(level, source, channel, message);

        if (channel != null && details != null && !details.trim().isEmpty()) {
            item.setDetails(details);
        }

        storage.appendLog(item);
        broadcast(LiveMonitorActions.ACTION_LOG_UPDATED, message);
    }

    private void broadcastChannelUpdated(String message) {
        broadcast(LiveMonitorActions.ACTION_CHANNEL_UPDATED, message);
    }

    private void broadcastRecordingUpdated(String message) {
        broadcast(LiveMonitorActions.ACTION_RECORDING_UPDATED, message);
    }

    private void broadcast(String action, String message) {
        Intent intent = new Intent(action);
        intent.putExtra(LiveMonitorActions.EXTRA_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(Math.max(500L, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String quote(String value) {
        return value == null ? "''" : "'" + value.replace("'", "'\\''") + "'";
    }

    private static void safeDelete(String path) {
        try {
            if (path != null) {
                File file = new File(path);
                if (file.exists()) file.delete();
            }
        } catch (Exception ignored) {
            // Ignore cleanup failure.
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopAll();

        if (executor != null) {
            executor.shutdownNow();
        }

        super.onDestroy();
    }

    private static class LiveInfo {
        final String videoId;
        final String title;
        final String videoUrl;

        LiveInfo(String videoId, String title, String videoUrl) {
            this.videoId = videoId;
            this.title = title == null ? videoId : title;
            this.videoUrl = videoUrl;
        }
    }
                                                              }
