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
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import kotlin.Unit;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MonitorService extends Service implements NetworkMonitor.Listener {
    private static final String TAG = "MonitorService";
    private static final String FALLBACK_YT_API_KEY = "AIzaSyDnAsBrxe_aFkUSpqkrFDczUw-PpLoEhuY";
    private static final long MIN_FREE_BYTES_BEFORE_RECORDING = 512L * 1024L * 1024L;
    private static final long MIN_FREE_BYTES_BEFORE_CONVERSION = 256L * 1024L * 1024L;
    private static final int DIRECT_DOWNLOAD_MAX_ATTEMPTS = 3;
    private static final int INNERTUBE_HTTP_MAX_ATTEMPTS = 2;
    private static final int YOUTUBEDL_ANDROID_RECORD_MAX_ATTEMPTS = 3;

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
    private final Set<String> restartingRecordings = ConcurrentHashMap.newKeySet();

    private volatile boolean serviceRunning = false;
    private volatile boolean networkAvailable = true;
    private volatile boolean shuttingDown = false;
    private volatile boolean ytDlpExecutableReady = false;
    private volatile boolean youtubedlAndroidReady = false;
    private volatile boolean youtubedlAndroidUpdateAttempted = false;
    private volatile String ytDlpExecutableStatus = "yt-dlp executable has not been prepared yet.";

    @Override
    public void onCreate() {
        super.onCreate();
        storage = new AppStorage(this);
        settings = storage.loadSettings();
        remoteConfig = new RemoteConfigFetcher(this).loadBestAvailableConfig();
        prepareYoutubedlAndroid();
        prepareYtDlpExecutable();
        notificationHelper = new NotificationHelper(this);
        fileManager = new RecordingFileManager(this);
        networkMonitor = new NetworkMonitor(this);
        progressTracker = new RecordingProgressTracker(storage);
        executor = Executors.newCachedThreadPool();
        progressTracker.setListener(new RecordingProgressTracker.Listener() {
            @Override
            public void onRecordingProgressUpdated(RecordingItem recording) {
                // The UI refreshes via existing broadcasts; avoid broadcasting every tick.
            }

            @Override
            public void onRecordingStalled(RecordingItem recording) {
                if (recording != null) {
                    executor.execute(() -> recoverStalledRecording(recording.getId()));
                }
            }
        });

        notificationHelper.createNotificationChannels();
        networkMonitor.setListener(this);
        networkMonitor.start();
        progressTracker.start();
        networkAvailable = networkMonitor.isConnectedNow();

        FFmpegRunner.setup(this);
        fileManager.registerRecoverableTsFilesInStorage();
        log(LogItem.LEVEL_SUCCESS, LogItem.SOURCE_SERVICE, null, "MonitorService created.", "");
    }


    private void prepareYoutubedlAndroid() {
        if (youtubedlAndroidReady) {
            return;
        }

        try {
            YoutubeDL.getInstance().init(getApplicationContext());
            youtubedlAndroidReady = true;
            log(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_REMOTE_CONFIG,
                null,
                "youtubedl-android ready.",
                "Bundled Android yt-dlp runtime initialized for private testing."
            );
        } catch (YoutubeDLException | RuntimeException e) {
            youtubedlAndroidReady = false;
            log(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_REMOTE_CONFIG,
                null,
                "youtubedl-android setup failed.",
                normalizeErrorMessage(e)
            );
        }
    }

    private void prepareYtDlpExecutable() {
        YtDlpEnvironment.Result result = YtDlpEnvironment.prepare(this, remoteConfig);

        if (result == null) {
            return;
        }

        ytDlpExecutableReady = result.isSuccess() || youtubedlAndroidReady;
        ytDlpExecutableStatus = youtubedlAndroidReady
            ? "Using bundled youtubedl-android runtime."
            : result.getMessage();

        String details = ytDlpExecutableStatus;

        if (!youtubedlAndroidReady && !isBlank(result.getExecutablePath())) {
            details += " path=" + result.getExecutablePath();
        }

        log(
            ytDlpExecutableReady ? LogItem.LEVEL_SUCCESS : LogItem.LEVEL_WARNING,
            LogItem.SOURCE_REMOTE_CONFIG,
            null,
            ytDlpExecutableReady ? "yt-dlp resolver ready." : "yt-dlp executable needs setup.",
            details
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        settings = storage.loadSettings();
        remoteConfig = new RemoteConfigFetcher(this).loadBestAvailableConfig();
        prepareYoutubedlAndroid();
        prepareYtDlpExecutable();
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
        } else if (LiveMonitorActions.ACTION_DOWNLOAD_VIDEO.equals(action)) {
            handleDownloadVideo(intent);
        } else if (LiveMonitorActions.ACTION_STOP_RECORDING.equals(action)) {
            handleStopRecording(intent);
        } else if (LiveMonitorActions.ACTION_PAUSE_RECORDING.equals(action)) {
            handlePauseRecording(intent);
        } else if (LiveMonitorActions.ACTION_RESUME_RECORDING.equals(action)) {
            handleResumeRecording(intent);
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

    private void handleDownloadVideo(Intent intent) {
        if (intent == null) return;

        String url = intent.getStringExtra(LiveMonitorActions.EXTRA_URL);
        String videoId = intent.getStringExtra(LiveMonitorActions.EXTRA_VIDEO_ID);

        if (videoId == null || videoId.trim().isEmpty()) {
            videoId = YouTubeUrlUtils.extractVideoId(url);
        }

        if (videoId == null || videoId.trim().isEmpty()) {
            log(LogItem.LEVEL_ERROR, LogItem.SOURCE_RECORDER, null, "Direct download failed.", "Could not detect video ID.");
            return;
        }

        String watchUrl = YouTubeUrlUtils.buildWatchUrl(videoId);
        RecordingItem recording = fileManager.createRecordingItem(null, videoId, watchUrl, settings);
        recording.setTitle("Direct download - " + videoId);
        recording.markRecording();
        recording.setDiagnosticMessage("Direct download journal opened.");
        storage.upsertRecording(recording);
        activeRecordings.put(recording.getId(), recording);
        progressTracker.track(recording);
        executor.execute(() -> runDirectVideoDownload(recording));
        broadcastRecordingUpdated("Direct download started.");
    }

    private void handlePauseRecording(Intent intent) {
        if (intent == null) return;

        String recordingId = intent.getStringExtra(LiveMonitorActions.EXTRA_RECORDING_ID);
        String channelId = intent.getStringExtra(LiveMonitorActions.EXTRA_CHANNEL_ID);
        RecordingItem recording = storage.findRecordingById(recordingId);

        if (recording == null) {
            return;
        }

        recording.markPausedByUser();
        recording.showInDownloading();
        storage.upsertRecording(recording);
        activeRecordings.remove(recording.getId());
        activeRecordings.remove(recording.getChannelId());
        progressTracker.untrack(recording);

        if ((channelId == null || channelId.trim().isEmpty())
            && recording.getChannelId() != null
            && !recording.getChannelId().trim().isEmpty()) {
            channelId = recording.getChannelId();
        }

        if (channelId != null && !channelId.trim().isEmpty()) {
            ChannelItem channel = storage.findChannelById(channelId);

            if (channel != null) {
                channel.markRecording(recording.getVideoId(), recording.getVideoUrl());
                storage.upsertChannel(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
            }
        }

        cancelActiveRecording(recording);
        broadcastRecordingUpdated("Recording paused.");
    }

    private void handleResumeRecording(Intent intent) {
        if (intent == null) return;

        String recordingId = intent.getStringExtra(LiveMonitorActions.EXTRA_RECORDING_ID);
        String channelId = intent.getStringExtra(LiveMonitorActions.EXTRA_CHANNEL_ID);
        RecordingItem recording = storage.findRecordingById(recordingId);

        if (recording == null) {
            return;
        }

        if ((channelId == null || channelId.trim().isEmpty())
            && recording.getChannelId() != null
            && !recording.getChannelId().trim().isEmpty()) {
            channelId = recording.getChannelId();
        }

        ChannelItem channel = storage.findChannelById(channelId);

        if (channel == null) {
            recording.markFailed("Cannot resume recording because the channel was not found.");
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Recording resume failed.");
            return;
        }

        recording.markRecording();
        recording.showInDownloading();
        storage.upsertRecording(recording);
        activeRecordings.put(channel.getId(), recording);
        progressTracker.track(recording);

        channel.markRecording(recording.getVideoId(), recording.getVideoUrl());
        storage.upsertChannel(channel);
        notificationHelper.showChannelMonitoringNotification(channel);

        LiveInfo liveInfo = new LiveInfo(
            recording.getVideoId(),
            recording.getTitle(),
            recording.getVideoUrl()
        );
        executor.execute(() -> runRecording(channel.getId(), recording, liveInfo));
        broadcastRecordingUpdated("Recording resumed.");
    }

    private void handleStopRecording(Intent intent) {
        if (intent == null) return;

        String recordingId = intent.getStringExtra(LiveMonitorActions.EXTRA_RECORDING_ID);
        String channelId = intent.getStringExtra(LiveMonitorActions.EXTRA_CHANNEL_ID);
        RecordingItem recording = storage.findRecordingById(recordingId);

        if (recording != null) {
            activeRecordings.remove(recording.getId());
            activeRecordings.remove(recording.getChannelId());
            progressTracker.untrack(recording);

            if ((channelId == null || channelId.trim().isEmpty())
                && recording.getChannelId() != null
                && !recording.getChannelId().trim().isEmpty()) {
                channelId = recording.getChannelId();
            }

            saveStoppedRecordingForDownloads(recording);
        }

        if (channelId != null && !channelId.trim().isEmpty()) {
            ChannelItem channel = storage.findChannelById(channelId);

            if (channel != null) {
                channel.markRecordingFinished();
                channel.markWaitingForLive();
                storage.upsertChannel(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
            }
        }

        cancelActiveRecording(recording);
        broadcastRecordingUpdated("Download stopped and saved.");
    }

    private void saveStoppedRecordingForDownloads(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        if (recording.isCompleted()) {
            recording.hideFromDownloading();
            storage.upsertRecording(recording);
            return;
        }

        if (recording.hasExistingFinalMp4File()) {
            recording.markCompleted(recording.getFinalMp4Path());
        } else if (recording.hasExistingTempTsFile()) {
            recording.markCompleted(recording.getTempTsPath());
        } else {
            recording.markStoppedByUser();
        }

        recording.hideFromDownloading();
        storage.upsertRecording(recording);
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

        if (!ensureRecordingStorageAvailable(channel, MIN_FREE_BYTES_BEFORE_RECORDING)) {
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
        recording.setDiagnosticMessage("Recording journal opened; waiting for manifest.");
        storage.upsertRecording(recording);
        activeRecordings.put(channel.getId(), recording);
        progressTracker.track(recording);

        channel.markRecording(liveInfo.videoId, liveInfo.videoUrl);
        storage.upsertChannel(channel);
        notificationHelper.showChannelMonitoringNotification(channel);
        broadcastChannelUpdated("Recording moved to Downloading.");
        broadcastRecordingUpdated("Recording added to Downloading.");

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
                "Resolving playable stream URL.",
                "videoId="
                    + videoId
                    + ", extractorMode="
                    + remoteConfig.getYoutubeExtractorMode()
                    + ", clients="
                    + getConfiguredClientCountForLog()
                    + ", apiKeys="
                    + getConfiguredApiKeyCountForLog()
            );

            ResolvedInput resolvedInput = resolveRecordingInputUrl(videoId, channel, liveInfo, true);
            String manifestUrl = resolvedInput.url;

            if (manifestUrl == null || manifestUrl.trim().isEmpty()) {
                throw new IllegalStateException(
                    "Could not get playable stream URL. Resolver returned empty URL."
                );
            }

            if (!isBlank(resolvedInput.videoId) && !resolvedInput.videoId.equals(videoId)) {
                videoId = resolvedInput.videoId;
                recording.setVideoId(videoId);
                recording.setVideoUrl(YouTubeUrlUtils.buildWatchUrl(videoId));
                storage.upsertRecording(recording);
            }

            recording.setDiagnosticMessage("Playable URL resolved by " + resolvedInput.source + "; starting recorder.");
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Playable URL resolved.");

            log(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_RECORDER,
                channel,
                "Playable stream URL found.",
                "videoId="
                    + videoId
                    + ", source="
                    + resolvedInput.source
                    + ", input="
                    + describeUrlForLog(manifestUrl)
            );

            log(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_RECORDER,
                channel,
                "Recording started.",
                youtubedlAndroidReady
                    ? "resolver=youtubedl-android, recorder=FFmpegKit"
                    : "recorder=FFmpegKit"
            );

            if (youtubedlAndroidReady) {
                startYoutubedlAndroidRecording(channelId, channel, recording, liveInfo);
                return;
            }

            final ChannelItem logChannel = channel;

            recording.setDiagnosticMessage("FFmpeg recorder is running.");
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Recording started.");

            FFmpegRunner.executeAsync(
                recording.getId(),
                manifestUrl,
                recording.getCurrentTempSegmentPath(),
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

            restartingRecordings.remove(recording.getId());
            activeRecordings.remove(channelId);
            activeRecordings.remove(recording.getId());
            progressTracker.untrack(recording);

            ChannelItem latest = storage.findChannelById(channelId);

            if (isLiveNotReadyError(errorMessage)) {
                discardUnstartedRecording(recording);

                if (latest != null) {
                    latest.markWaitingForLive();
                    storage.upsertChannel(latest);
                    notificationHelper.showChannelMonitoringNotification(latest);
                }

                log(
                    LogItem.LEVEL_INFO,
                    LogItem.SOURCE_RECORDER,
                    latest,
                    "Live event is not active yet; waiting.",
                    errorMessage
                );

                broadcastChannelUpdated("Waiting for live.");
                broadcastRecordingUpdated("Live event is not active yet.");
                return;
            }

            recording.markFailed(errorMessage);
            storage.upsertRecording(recording);

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

    private boolean isLiveNotReadyError(String message) {
        if (isBlank(message)) {
            return false;
        }

        String lower = message.toLowerCase(java.util.Locale.US);

        return lower.contains("live_stream_offline")
            || lower.contains("will begin")
            || lower.contains("waiting for live")
            || lower.contains("live event is not active")
            || lower.contains("premiere will begin");
    }

    private void discardUnstartedRecording(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        if (recording.hasExistingFinalMp4File() || recording.hasExistingTempTsFile()) {
            saveStoppedRecordingForDownloads(recording);
            return;
        }

        storage.removeRecording(recording.getId());
    }

    private void recoverStalledRecording(String recordingId) {
        if (isBlank(recordingId)) {
            return;
        }

        RecordingItem stalledRecording = storage.findRecordingById(recordingId);

        if (stalledRecording == null || !stalledRecording.isActive()) {
            return;
        }

        if (!RecordingItem.STATUS_RECORDING.equals(stalledRecording.getStatus())) {
            return;
        }

        String channelId = stalledRecording.getChannelId();

        if (isBlank(channelId)) {
            stalledRecording.markRecoverable("Recorder stalled and cannot restart because the channel is unknown.");
            storage.upsertRecording(stalledRecording);
            activeRecordings.remove(stalledRecording.getId());
            progressTracker.untrack(stalledRecording);
            broadcastRecordingUpdated("Recording is recoverable after a stall.");
            return;
        }

        ChannelItem channel = storage.findChannelById(channelId);

        if (channel == null || !channel.shouldMonitor()) {
            stalledRecording.markRecoverable("Recorder stalled and cannot restart because monitoring is no longer active.");
            storage.upsertRecording(stalledRecording);
            activeRecordings.remove(stalledRecording.getId());
            activeRecordings.remove(channelId);
            progressTracker.untrack(stalledRecording);
            broadcastRecordingUpdated("Recording is recoverable after a stall.");
            return;
        }

        if (!restartingRecordings.add(recordingId)) {
            return;
        }

        boolean cancelRequested = false;

        try {
            if (FFmpegRunner.isRunning(recordingId)) {
                stalledRecording.setDiagnosticMessage(
                    "No file growth detected; keeping FFmpeg alive while reconnect retries continue."
                );
                storage.upsertRecording(stalledRecording);

                log(
                    LogItem.LEVEL_WARNING,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "Recording progress stalled; not restarting yet.",
                    "recordingId=" + recordingId
                );
                broadcastRecordingUpdated("Recorder reconnect is still running.");
                return;
            }

            String resolvedChannelId = resolveChannelId(channel.getUrl());
            LiveInfo liveInfo = resolvedChannelId == null ? null : checkLive(resolvedChannelId);

            if (liveInfo == null) {
                activeRecordings.remove(stalledRecording.getId());
                activeRecordings.remove(channelId);
                progressTracker.untrack(stalledRecording);
                cancelRequested = cancelActiveRecording(stalledRecording);
                saveStoppedRecordingForDownloads(stalledRecording);
                channel.markRecordingFinished();
                channel.markWaitingForLive();
                storage.upsertChannel(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
                broadcastRecordingUpdated("Stalled recording saved; waiting for live.");
                return;
            }

            if (!isBlank(stalledRecording.getVideoId())
                && !stalledRecording.matchesVideo(liveInfo.videoId)) {
                activeRecordings.remove(stalledRecording.getId());
                activeRecordings.remove(channelId);
                progressTracker.untrack(stalledRecording);
                cancelRequested = cancelActiveRecording(stalledRecording);
                saveStoppedRecordingForDownloads(stalledRecording);
                channel.markRecordingFinished();
                channel.markWaitingForLive();
                storage.upsertChannel(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
                broadcastRecordingUpdated("Stalled recording saved; live video changed.");
                return;
            }

            stalledRecording.markRecording();
            stalledRecording.showInDownloading();
            stalledRecording.setDiagnosticMessage("Recorder stalled; resuming same recording with a fresh stream URL.");
            storage.upsertRecording(stalledRecording);
            activeRecordings.put(channelId, stalledRecording);
            progressTracker.track(stalledRecording);
            broadcastRecordingUpdated("Recovering stalled recording.");

            log(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_RECORDER,
                channel,
                "Recording stalled; resuming same recording.",
                stalledRecording.getBestPlayablePath()
            );

            channel.markRecording(liveInfo.videoId, liveInfo.videoUrl);
            storage.upsertChannel(channel);
            notificationHelper.showChannelMonitoringNotification(channel);
            executor.execute(() -> resumeRecording(channelId, stalledRecording, liveInfo));
        } catch (Exception e) {
            String errorMessage = normalizeErrorMessage(e);

            stalledRecording.markRecoverable("Recorder stalled and restart failed. " + errorMessage);
            storage.upsertRecording(stalledRecording);
            activeRecordings.remove(stalledRecording.getId());
            activeRecordings.remove(channelId);
            progressTracker.untrack(stalledRecording);

            log(
                LogItem.LEVEL_ERROR,
                LogItem.SOURCE_RECORDER,
                channel,
                "Stalled recording recovery failed.",
                errorMessage
            );
            broadcastRecordingUpdated("Recording is recoverable after a stall.");
        } finally {
            if (!cancelRequested) {
                restartingRecordings.remove(recordingId);
            }
        }
    }


    private void resumeRecording(String channelId, RecordingItem recording, LiveInfo liveInfo) {
        ChannelItem channel = storage.findChannelById(channelId);

        try {
            String videoId = liveInfo == null ? recording.getVideoId() : liveInfo.videoId;

            log(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_RECORDER,
                channel,
                "Resolving playable stream URL for same recording resume.",
                "recordingId=" + recording.getId() + ", videoId=" + videoId
            );

            ResolvedInput resolvedInput = resolveRecordingInputUrl(videoId, channel, liveInfo, true);
            String manifestUrl = resolvedInput.url;

            if (manifestUrl == null || manifestUrl.trim().isEmpty()) {
                throw new IllegalStateException(
                    "Could not get playable stream URL. Resolver returned empty URL."
                );
            }

            if (!isBlank(resolvedInput.videoId) && !recording.matchesVideo(resolvedInput.videoId)) {
                throw new IllegalStateException(
                    "Resolved stream changed while resuming. expected="
                        + recording.getVideoId()
                        + ", resolved="
                        + resolvedInput.videoId
                );
            }

            String chunkPath = createResumeChunkPath(recording);
            recording.addTempChunkPath(chunkPath);
            recording.setDiagnosticMessage("Recording stalled; resuming same recording.");
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Recording stalled; resuming same recording.");

            log(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_RECORDER,
                channel,
                "Playable stream URL found for resume.",
                "recordingId="
                    + recording.getId()
                    + ", source="
                    + resolvedInput.source
                    + ", input="
                    + describeUrlForLog(manifestUrl)
                    + ", chunk="
                    + chunkPath
            );

            if (youtubedlAndroidReady) {
                startYoutubedlAndroidRecording(channelId, channel, recording, liveInfo);
                return;
            }

            final ChannelItem logChannel = channel;

            FFmpegRunner.executeAsync(
                recording.getId(),
                manifestUrl,
                chunkPath,
                false,
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

            restartingRecordings.remove(recording.getId());
            recording.markRecoverable("Recorder stalled and resume failed. " + errorMessage);
            storage.upsertRecording(recording);
            activeRecordings.remove(recording.getId());
            activeRecordings.remove(channelId);
            progressTracker.untrack(recording);

            log(
                LogItem.LEVEL_ERROR,
                LogItem.SOURCE_RECORDER,
                channel,
                "Stalled recording resume failed.",
                errorMessage
            );
            broadcastRecordingUpdated("Recording is recoverable after a stall.");
        }
    }

    private String createResumeChunkPath(RecordingItem recording) {
        String tempTsPath = recording == null ? "" : recording.getTempTsPath();

        if (isBlank(tempTsPath)) {
            throw new IllegalStateException("Cannot resume recording without a temp TS path.");
        }

        File tempFile = new File(tempTsPath);
        File parent = tempFile.getParentFile();
        String name = tempFile.getName();
        int extensionIndex = name.toLowerCase(java.util.Locale.US).lastIndexOf(".ts");
        String baseName = extensionIndex > 0 ? name.substring(0, extensionIndex) : name;
        int segmentNumber = Math.max(1, recording.getTempSegmentPaths().size()) + 1;
        File chunkFile;

        do {
            chunkFile = new File(parent, baseName + ".part" + segmentNumber + ".ts");
            segmentNumber++;
        } while (chunkFile.exists());

        return chunkFile.getAbsolutePath();
    }

    private boolean cancelActiveRecording(RecordingItem recording) {
        if (recording == null) {
            return false;
        }

        boolean cancelled = FFmpegRunner.cancel(recording.getId());

        if (youtubedlAndroidReady) {
            try {
                YoutubeDL.getInstance().destroyProcessById(recording.getId());
                cancelled = true;
            } catch (RuntimeException ignored) {
            }
        }

        if (cancelled) {
            log(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_RECORDER,
                null,
                "Active recording cancellation requested.",
                recording.getDisplayTitle()
            );
        }

        return cancelled;
    }

    private boolean ensureRecordingStorageAvailable(ChannelItem channel, long minimumBytes) {
        long safeMinimumBytes = Math.max(MIN_FREE_BYTES_BEFORE_CONVERSION, minimumBytes);

        if (fileManager.hasEnoughUsableSpace(safeMinimumBytes)) {
            return true;
        }

        String details = "requiredBytes="
            + safeMinimumBytes
            + ", "
            + fileManager.getStorageSummary();

        log(
            LogItem.LEVEL_ERROR,
            LogItem.SOURCE_RECORDER,
            channel,
            "Not enough free storage for recording.",
            details
        );
        broadcastRecordingUpdated("Storage is too low for recording.");
        return false;
    }

    private long estimateConversionRequiredBytes(RecordingItem recording) {
        long tempBytes = 0L;

        if (recording != null) {
            for (String segmentPath : recording.getTempSegmentPaths()) {
                if (isBlank(segmentPath)) {
                    continue;
                }

                try {
                    File tempFile = new File(segmentPath);

                    if (tempFile.exists()) {
                        tempBytes += Math.max(0L, tempFile.length());
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return Math.max(MIN_FREE_BYTES_BEFORE_CONVERSION, tempBytes + MIN_FREE_BYTES_BEFORE_CONVERSION);
    }


    private void runDirectVideoDownload(RecordingItem recording) {
        if (recording == null) return;

        try {
            if (!ensureRecordingStorageAvailable(null, MIN_FREE_BYTES_BEFORE_RECORDING)) {
                activeRecordings.remove(recording.getId());
                progressTracker.untrack(recording);
                recording.markFailed("Not enough free storage for direct download. " + fileManager.getStorageSummary());
                storage.upsertRecording(recording);
                broadcastRecordingUpdated("Direct download failed; storage is low.");
                return;
            }

            String videoId = recording.getVideoId();
            Exception lastError = null;
            ReturnCode lastCode = null;

            for (int attempt = 1; attempt <= DIRECT_DOWNLOAD_MAX_ATTEMPTS; attempt++) {
                recording.setDiagnosticMessage("Direct download attempt " + attempt + " of " + DIRECT_DOWNLOAD_MAX_ATTEMPTS + ".");
                storage.upsertRecording(recording);
                broadcastRecordingUpdated("Direct download attempt " + attempt + ".");

                try {
                    String inputUrl = getDirectDownloadInputUrl(videoId, null);

                    if (inputUrl == null || inputUrl.trim().isEmpty()) {
                        throw new IllegalStateException("Could not get playable URL for ended live/video.");
                    }

                    log(
                        LogItem.LEVEL_SUCCESS,
                        LogItem.SOURCE_RECORDER,
                        null,
                        "Direct video URL found.",
                        "attempt=" + attempt + ", input=" + describeUrlForLog(inputUrl)
                    );

                    String command = "-y -hide_banner -loglevel info"
                        + " -reconnect 1 -reconnect_streamed 1 -reconnect_on_network_error 1"
                        + " -reconnect_delay_max 5 -rw_timeout 90000000"
                        + " -i " + quote(inputUrl)
                        + " -c copy -f mpegts "
                        + quote(recording.getTempTsPath());

                    lastCode = FFmpegKit.execute(command).getReturnCode();

                    if (ReturnCode.isSuccess(lastCode) || ReturnCode.isCancel(lastCode)) {
                        break;
                    }

                    if (attempt < DIRECT_DOWNLOAD_MAX_ATTEMPTS) {
                        sleep(getAttemptBackoffMillis(attempt));
                    }
                } catch (Exception e) {
                    lastError = e;
                    log(
                        LogItem.LEVEL_WARNING,
                        LogItem.SOURCE_RECORDER,
                        null,
                        "Direct download attempt failed.",
                        "attempt=" + attempt + ", error=" + normalizeErrorMessage(e)
                    );
                    if (attempt < DIRECT_DOWNLOAD_MAX_ATTEMPTS) {
                        sleep(getAttemptBackoffMillis(attempt));
                    }
                }
            }

            activeRecordings.remove(recording.getId());
            progressTracker.untrack(recording);

            if (ReturnCode.isSuccess(lastCode)) {
                convertRecording(recording, null);
            } else if (ReturnCode.isCancel(lastCode)) {
                saveStoppedRecordingForDownloads(recording);
            } else if (recording.hasExistingTempTsFile()) {
                recording.markRecoverable("Direct download stopped after retries. " + describeReturnCode(lastCode));
                storage.upsertRecording(recording);
            } else if (lastError != null) {
                recording.markFailed(normalizeErrorMessage(lastError));
                storage.upsertRecording(recording);
            } else {
                recording.markFailed("Direct download failed. " + describeReturnCode(lastCode));
                storage.upsertRecording(recording);
            }
        } catch (Exception e) {
            activeRecordings.remove(recording.getId());
            progressTracker.untrack(recording);
            recording.markFailed(normalizeErrorMessage(e));
            storage.upsertRecording(recording);
            log(LogItem.LEVEL_ERROR, LogItem.SOURCE_RECORDER, null, "Direct download failed.", normalizeErrorMessage(e));
        }

        broadcastRecordingUpdated("Direct download updated.");
    }

    private void onRecordingFinished(String channelId, String recordingId, int returnCode) {
        RecordingItem recording = storage.findRecordingById(recordingId);
        ChannelItem channel = storage.findChannelById(channelId);

        if (recording == null) return;

        boolean restartingAfterStall = restartingRecordings.contains(recordingId);

        if (restartingAfterStall && (returnCode == 255 || returnCode == -1)) {
            restartingRecordings.remove(recordingId);
            broadcastRecordingUpdated("Recorder is restarting after a stall.");
            return;
        }

        if (!recording.isPausedByUser()
            && returnCode == 0
            && RecordingItem.STATUS_RECORDING.equals(recording.getStatus())) {
            CleanExitAction cleanExitAction = handleCleanLiveRecordingExit(channelId, recording, channel);

            if (cleanExitAction == CleanExitAction.RESTARTED
                || cleanExitAction == CleanExitAction.DEFERRED) {
                return;
            }
        }

        restartingRecordings.remove(recordingId);
        activeRecordings.remove(channelId);
        activeRecordings.remove(recordingId);
        progressTracker.untrack(recording);

        if (recording.isPausedByUser()) {
            storage.upsertRecording(recording);
        } else if (returnCode == 0 && recording.isFinished()) {
            storage.upsertRecording(recording);
        } else if (returnCode == 0) {
            convertRecording(recording, channel);
        } else if (returnCode == 255 || returnCode == -1) {
            saveStoppedRecordingForDownloads(recording);
        } else {
            recording.markRecoverable("Recorder exited with code " + returnCode);
            storage.upsertRecording(recording);
        }

        if (channel != null && !recording.isPausedByUser()) {
            channel.markRecordingFinished();
            channel.markWaitingForLive();
            storage.upsertChannel(channel);
            notificationHelper.showChannelMonitoringNotification(channel);
        }

        broadcastRecordingUpdated("Recording updated.");
    }

    private CleanExitAction handleCleanLiveRecordingExit(
        String channelId,
        RecordingItem recording,
        ChannelItem channel
    ) {
        if (channel == null || !channel.shouldMonitor()) {
            log(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_RECORDER,
                channel,
                "Recording completed; monitoring is no longer active.",
                "recordingId=" + recording.getId()
            );
            return CleanExitAction.FINALIZE;
        }

        LiveInfo liveInfo;

        try {
            liveInfo = resolveCurrentLiveInfo(channel);
        } catch (Exception e) {
            String errorMessage = normalizeErrorMessage(e);

            restartingRecordings.remove(recording.getId());
            activeRecordings.remove(channelId);
            activeRecordings.remove(recording.getId());
            progressTracker.untrack(recording);

            recording.markRecoverable("Recorder exited cleanly, but live status re-check failed. " + errorMessage);
            storage.upsertRecording(recording);

            if (channel != null) {
                channel.markWaitingForLive();
                storage.upsertChannel(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
            }

            log(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_RECORDER,
                channel,
                "Recorder clean exit is not being finalized because live status could not be confirmed.",
                errorMessage
            );

            broadcastRecordingUpdated("Recording is recoverable; live status check failed.");
            return CleanExitAction.DEFERRED;
        }

        if (liveInfo != null && recording.matchesVideo(liveInfo.videoId)) {
            recording.markRecording();
            recording.showInDownloading();
            recording.setDiagnosticMessage("FFmpeg exited cleanly while the same live video is still active; restarting recorder.");
            storage.upsertRecording(recording);

            String activeChannelId = isBlank(channelId) ? channel.getId() : channelId;
            activeRecordings.put(activeChannelId, recording);
            progressTracker.track(recording);

            channel.markRecording(liveInfo.videoId, liveInfo.videoUrl);
            storage.upsertChannel(channel);
            notificationHelper.showChannelMonitoringNotification(channel);

            log(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_RECORDER,
                channel,
                "FFmpeg exited with code 0 while the live stream is still active; restarting recorder after transient HLS EOF.",
                "recordingId=" + recording.getId() + ", videoId=" + liveInfo.videoId
            );

            if (youtubedlAndroidReady && currentRecordingSegmentHasData(recording)) {
                recording.addTempChunkPath(createResumeChunkPath(recording));
                storage.upsertRecording(recording);
            }

            broadcastRecordingUpdated("Recorder restarted after transient HLS EOF.");
            String restartChannelId = isBlank(channelId) ? channel.getId() : channelId;
            executor.execute(() -> runRecording(restartChannelId, recording, liveInfo));
            return CleanExitAction.RESTARTED;
        }

        String completionReason = liveInfo == null
            ? "live status check found no active stream"
            : "live video changed to " + liveInfo.videoId;

        log(
            LogItem.LEVEL_SUCCESS,
            LogItem.SOURCE_RECORDER,
            channel,
            "Recording completed; live re-check confirmed final completion.",
            "recordingId=" + recording.getId() + ", reason=" + completionReason
        );

        return CleanExitAction.FINALIZE;
    }

    private void convertRecording(RecordingItem recording, ChannelItem channel) {
        if (!settings.isConvertTsToMp4()) {
            recording.markCompleted(recording.getTempTsPath());
            recording.hideFromDownloading();
            storage.upsertRecording(recording);
            return;
        }

        long requiredBytes = estimateConversionRequiredBytes(recording);

        if (!ensureRecordingStorageAvailable(channel, requiredBytes)) {
            recording.markRecoverable("Not enough free storage to convert safely. " + fileManager.getStorageSummary());
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Recording is recoverable; storage is low.");
            return;
        }

        recording.markConverting();
        storage.upsertRecording(recording);
        broadcastRecordingUpdated("Converting recording.");

        List<String> tempSegments = recording.getTempSegmentPaths();
        String command = buildConversionCommand(recording, tempSegments);

        try {
            ReturnCode code = FFmpegKit.execute(command).getReturnCode();

            if (ReturnCode.isSuccess(code)) {
                recording.markCompleted(recording.getFinalMp4Path());
                recording.hideFromDownloading();
                for (String segmentPath : tempSegments) {
                    safeDelete(segmentPath);
                }
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


    private String buildConversionCommand(RecordingItem recording, List<String> tempSegments) {
        if (tempSegments == null || tempSegments.isEmpty()) {
            return "-y -i " + quote(recording.getTempTsPath())
                + " -c copy -movflags +faststart "
                + quote(recording.getFinalMp4Path());
        }

        if (tempSegments.size() == 1) {
            return "-y -i " + quote(tempSegments.get(0))
                + " -c copy -movflags +faststart "
                + quote(recording.getFinalMp4Path());
        }

        return "-y -i " + quote("concat:" + joinConcatSegments(tempSegments))
            + " -c copy -movflags +faststart "
            + quote(recording.getFinalMp4Path());
    }

    private String joinConcatSegments(List<String> tempSegments) {
        StringBuilder builder = new StringBuilder();

        for (String segmentPath : tempSegments) {
            if (isBlank(segmentPath)) {
                continue;
            }

            if (builder.length() > 0) {
                builder.append('|');
            }

            builder.append(segmentPath);
        }

        return builder.toString();
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

            if (handle == null || handle.trim().isEmpty()) return null;

            String apiUrl = "https://www.googleapis.com/youtube/v3/channels"
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

            if (items != null && items.length() > 0) {
                JSONObject item = items.getJSONObject(0);
                String videoId = item.getJSONObject("id").getString("videoId");
                String title = item.getJSONObject("snippet").getString("title");

                return new LiveInfo(videoId, title, "https://youtube.com/watch?v=" + videoId);
            }
        } catch (Exception e) {
            Log.w(TAG, "YouTube Data API live check failed; trying channel /live fallback", e);
        }

        return checkLiveFromChannelLivePage(channelId);
    }

    private LiveInfo checkLiveFromChannelLivePage(String channelId) {
        if (isBlank(channelId)) {
            return null;
        }

        String liveUrl = remoteConfig.getWebPlayerBaseUrl()
            + "/channel/"
            + urlEncodeForQuery(channelId)
            + "/live";

        try {
            String html = httpGet(liveUrl);
            JSONObject playerResponse = extractInitialPlayerResponse(html);

            if (playerResponse == null) {
                return null;
            }

            JSONObject videoDetails = playerResponse.optJSONObject("videoDetails");
            JSONObject streamingData = playerResponse.optJSONObject("streamingData");
            JSONObject playabilityStatus = playerResponse.optJSONObject("playabilityStatus");

            if (videoDetails == null) {
                return null;
            }

            String videoId = videoDetails.optString("videoId", "");
            String title = videoDetails.optString("title", "");
            String hlsManifestUrl = streamingData == null
                ? ""
                : streamingData.optString("hlsManifestUrl", "");

            if (isBlank(videoId)) {
                return null;
            }

            if (isBlank(hlsManifestUrl)) {
                log(
                    LogItem.LEVEL_INFO,
                    LogItem.SOURCE_SERVICE,
                    null,
                    "Channel /live fallback ignored a non-active live event.",
                    "channelId="
                        + channelId
                        + ", videoId="
                        + videoId
                        + ", response="
                        + summarizeInnertubeResponseForLog(playerResponse)
                );
                return null;
            }

            String status = playabilityStatus == null
                ? ""
                : playabilityStatus.optString("status", "");

            log(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_SERVICE,
                null,
                "Channel /live fallback found an active live video.",
                "channelId=" + channelId + ", videoId=" + videoId + ", status=" + status
            );

            return new LiveInfo(videoId, title, "https://youtube.com/watch?v=" + videoId);
        } catch (Exception e) {
            Log.w(TAG, "channel /live fallback failed", e);
            return null;
        }
    }


    private ResolvedInput resolveRecordingInputUrl(
        String videoId,
        ChannelItem channel,
        LiveInfo liveInfo,
        boolean allowLiveIdRetry
    ) throws Exception {
        String safeVideoId = normalizeVideoIdForLookup(videoId);
        String videoUrl = liveInfo != null && !isBlank(liveInfo.videoUrl)
            ? liveInfo.videoUrl
            : YouTubeUrlUtils.buildWatchUrl(safeVideoId);
        Exception ytDlpError = null;
        Exception javaError = null;

        if (remoteConfig.isYtDlpFirst() && remoteConfig.isYtDlpEnabled()) {
            if (ytDlpExecutableReady) {
                try {
                    return resolveWithYtDlp(videoUrl, safeVideoId, channel);
                } catch (Exception e) {
                    ytDlpError = e;
                    logExtractorFallback(
                        channel,
                        remoteConfig.isJavaHlsEnabled()
                            ? "yt-dlp extractor failed; trying Java HLS fallback."
                            : "yt-dlp extractor failed and Java HLS fallback is disabled.",
                        e
                    );
                }
            } else {
                ytDlpError = buildYtDlpNotReadyError();
                logExtractorFallback(
                    channel,
                    remoteConfig.isJavaHlsEnabled()
                        ? "yt-dlp executable needs setup; trying Java HLS fallback."
                        : "yt-dlp executable needs setup and Java HLS fallback is disabled.",
                    ytDlpError
                );
            }
        }

        if (remoteConfig.isJavaHlsEnabled()) {
            try {
                return new ResolvedInput(getHlsManifestUrl(safeVideoId, channel), safeVideoId, "java-hls");
            } catch (Exception e) {
                javaError = e;
                log(
                    LogItem.LEVEL_WARNING,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "Java HLS resolver failed.",
                    normalizeErrorMessage(e)
                );
            }
        }

        if (!remoteConfig.isYtDlpFirst() && remoteConfig.isYtDlpEnabled()) {
            if (ytDlpExecutableReady) {
                try {
                    return resolveWithYtDlp(videoUrl, safeVideoId, channel);
                } catch (Exception e) {
                    ytDlpError = e;
                    log(
                        LogItem.LEVEL_WARNING,
                        LogItem.SOURCE_RECORDER,
                        channel,
                        "yt-dlp extractor failed after Java fallback.",
                        normalizeErrorMessage(e)
                    );
                }
            } else {
                ytDlpError = buildYtDlpNotReadyError();
                log(
                    LogItem.LEVEL_WARNING,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "yt-dlp executable needs setup after Java fallback.",
                    normalizeErrorMessage(ytDlpError)
                );
            }
        }

        if (allowLiveIdRetry && channel != null && shouldRetryWithFreshLiveId(ytDlpError, javaError)) {
            LiveInfo freshLiveInfo = resolveFreshLiveInfo(channel, safeVideoId);

            if (freshLiveInfo != null && !safeVideoId.equals(freshLiveInfo.videoId)) {
                log(
                    LogItem.LEVEL_INFO,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "Live video ID changed; retrying resolver with fresh ID.",
                    "oldVideoId=" + safeVideoId + ", newVideoId=" + freshLiveInfo.videoId
                );

                channel.markRecording(freshLiveInfo.videoId, freshLiveInfo.videoUrl);
                storage.upsertChannel(channel);
                return resolveRecordingInputUrl(freshLiveInfo.videoId, channel, freshLiveInfo, false);
            }

            log(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_RECORDER,
                channel,
                "Live video ID retry did not find a newer playable event.",
                "videoId=" + safeVideoId
            );
        }

        String message = buildResolverFailureMessage(ytDlpError, javaError);
        throw new IllegalStateException(message);
    }

    private IllegalStateException buildYtDlpNotReadyError() {
        String executable = remoteConfig == null ? "" : remoteConfig.getYtDlpExecutable();
        String problem = isBlank(ytDlpExecutableStatus)
            ? YtDlpEnvironment.describeExecutableProblem(executable)
            : ytDlpExecutableStatus;

        return new IllegalStateException(
            "yt-dlp executable is not ready for this Android service. "
                + problem
                + " Bundle app/src/main/jniLibs/<abi>/libyt-dlp.so or configure "
                + "ytDlpExecutable as an absolute executable path owned by com.livemonitor.app."
        );
    }

    private void startYoutubedlAndroidRecording(
        String channelId,
        ChannelItem channel,
        RecordingItem recording,
        LiveInfo liveInfo
    ) {
        String videoUrl = liveInfo == null || isBlank(liveInfo.videoUrl)
            ? recording.getVideoUrl()
            : liveInfo.videoUrl;

        if (isBlank(videoUrl) && !isBlank(recording.getVideoId())) {
            videoUrl = YouTubeUrlUtils.buildWatchUrl(recording.getVideoId());
        }

        final String safeVideoUrl = videoUrl;
        final RecorderCommandBuilder builder = new RecorderCommandBuilder();
        final List<YtDlpResolveAttempt> attempts = buildYtDlpResolveAttempts(builder, safeVideoUrl);

        activeRecordings.put(recording.getId(), recording);
        recording.setDiagnosticMessage("youtubedl-android recorder is running.");
        storage.upsertRecording(recording);
        broadcastRecordingUpdated("Recording started with youtubedl-android.");

        executor.execute(() -> {
            int finalCode = -1;
            Exception lastError = null;

            for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
                if (shuttingDown || !activeRecordings.containsKey(recording.getId())) {
                    finalCode = 255;
                    break;
                }

                if (attemptIndex > 0 && currentRecordingSegmentHasData(recording)) {
                    String nextChunkPath = createResumeChunkPath(recording);
                    recording.addTempChunkPath(nextChunkPath);
                    storage.upsertRecording(recording);
                }

                String attemptOutputPath = recording.getCurrentTempSegmentPath();
                YtDlpResolveAttempt attempt = attempts.get(attemptIndex);
                List<String> args = builder.buildYtDlpRecordArgs(
                    safeVideoUrl,
                    attemptOutputPath,
                    settings,
                    remoteConfig,
                    attempt.extractorArgs,
                    attempt.allowLiveFromStart
                );

                log(
                    LogItem.LEVEL_INFO,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "Starting youtubedl-android recorder attempt.",
                    "recordingId="
                        + recording.getId()
                        + ", attempt="
                        + (attemptIndex + 1)
                        + "/"
                        + attempts.size()
                        + ", "
                        + attempt.describe()
                        + ", output="
                        + attemptOutputPath
                        + ", command="
                        + shortenForLog(builder.toLogString(args), 500)
                );

                try {
                    YoutubeDLRequest request = buildYoutubedlAndroidRequestFromArgs(safeVideoUrl, args);
                    YoutubeDLResponse response = YoutubeDL.getInstance().execute(
                        request,
                        recording.getId(),
                        (progress, etaInSeconds, line) -> {
                            if (!isBlank(line)
                                && !line.startsWith("frame=")
                                && !line.startsWith("size=")) {
                                log(
                                    LogItem.LEVEL_DEBUG,
                                    LogItem.SOURCE_RECORDER,
                                    channel,
                                    "youtubedl-android recorder output.",
                                    shortenForLog(line, 500)
                                );
                            }

                            return Unit.INSTANCE;
                        }
                    );
                    finalCode = response == null ? -1 : response.getExitCode();

                    if (finalCode == 0 || finalCode == 255) {
                        break;
                    }

                    lastError = new IllegalStateException(
                        response == null
                            ? "youtubedl-android exited without a response."
                            : normalizeYoutubedlAndroidFailure(response)
                    );
                } catch (Exception e) {
                    if (!activeRecordings.containsKey(recording.getId()) || shuttingDown) {
                        finalCode = 255;
                        break;
                    }

                    lastError = e;
                    finalCode = -1;
                }

                if (refreshYoutubedlAndroidAfterExtractorFailure(lastError, recording.getVideoId(), channel)) {
                    attemptIndex = -1;
                    lastError = null;
                    continue;
                }

                if (attemptIndex + 1 < attempts.size()) {
                    log(
                        LogItem.LEVEL_WARNING,
                        LogItem.SOURCE_RECORDER,
                        channel,
                        "youtubedl-android recorder attempt failed; trying next client.",
                        "recordingId="
                            + recording.getId()
                            + ", "
                            + attempt.describe()
                            + ", error="
                            + normalizeErrorMessage(lastError)
                    );
                    sleep(getAttemptBackoffMillis(Math.min(attemptIndex + 1, YOUTUBEDL_ANDROID_RECORD_MAX_ATTEMPTS)));
                }
            }

            if (finalCode != 0 && finalCode != 255 && lastError != null) {
                recording.setDiagnosticMessage("youtubedl-android recorder failed: " + normalizeErrorMessage(lastError));
                storage.upsertRecording(recording);
            }

            onRecordingFinished(channelId, recording.getId(), finalCode);
        });
    }

    private boolean currentRecordingSegmentHasData(RecordingItem recording) {
        if (recording == null || isBlank(recording.getCurrentTempSegmentPath())) {
            return false;
        }

        try {
            File file = new File(recording.getCurrentTempSegmentPath());
            return file.exists() && file.length() > 0L;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private YoutubeDLRequest buildYoutubedlAndroidRequestFromArgs(String videoUrl, List<String> args) {
        YoutubeDLRequest request = new YoutubeDLRequest(videoUrl);

        if (args == null) {
            return request;
        }

        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);

            if (isBlank(arg) || videoUrl.equals(arg)) {
                continue;
            }

            if ("--js-runtime".equals(arg) || "--js-runtimes".equals(arg)) {
                i++;
                continue;
            }

            String value = i + 1 < args.size() ? args.get(i + 1) : null;

            if (isYtDlpOptionWithValue(arg) && value != null) {
                request.addOption(arg, value);
                i++;
            } else {
                request.addOption(arg);
            }
        }

        return request;
    }

    private String normalizeYoutubedlAndroidFailure(YoutubeDLResponse response) {
        if (response == null) {
            return "youtubedl-android failed without output.";
        }

        String err = response.getErr();
        String out = response.getOut();
        String details = !isBlank(err) ? err : out;

        if (isBlank(details)) {
            details = "exitCode=" + response.getExitCode();
        }

        return shortenForLog(details, 1000);
    }

    private ResolvedInput resolveWithYtDlp(
        String videoUrl,
        String videoId,
        ChannelItem channel
    ) throws Exception {
        if (isBlank(videoUrl)) {
            throw new IllegalArgumentException("video URL is empty.");
        }

        RecorderCommandBuilder builder = new RecorderCommandBuilder();
        updateYoutubedlAndroidRuntimeIfNeeded(
            "before first yt-dlp resolve",
            "Updating bundled yt-dlp before first resolve attempt.",
            channel
        );
        List<YtDlpResolveAttempt> attempts = buildYtDlpResolveAttempts(builder, videoUrl);
        Exception lastError = null;

        for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
            YtDlpResolveAttempt attempt = attempts.get(attemptIndex);

            log(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_RECORDER,
                channel,
                youtubedlAndroidReady ? "Trying youtubedl-android stream resolver." : "Trying yt-dlp stream resolver.",
                "videoId="
                    + videoId
                    + ", attempt="
                    + (attemptIndex + 1)
                    + "/"
                    + attempts.size()
                    + ", "
                    + attempt.describe()
                    + ", timeoutSeconds="
                    + remoteConfig.getYtDlpResolveTimeoutSeconds()
                    + ", command="
                    + shortenForLog(builder.toLogString(attempt.args), 500)
            );

            try {
                String url = youtubedlAndroidReady
                    ? resolvePlayableUrlWithYoutubedlAndroid(videoUrl, attempt.args, channel)
                    : YtDlpRunner.resolvePlayableUrl(
                        attempt.args,
                        remoteConfig.getYtDlpResolveTimeoutSeconds(),
                        message -> log(
                            LogItem.LEVEL_DEBUG,
                            LogItem.SOURCE_RECORDER,
                            channel,
                            "yt-dlp output.",
                            shortenForLog(message, 500)
                        )
                    );

                if (isBlank(url)) {
                    throw new IllegalStateException("yt-dlp returned an empty stream URL.");
                }

                log(
                    LogItem.LEVEL_SUCCESS,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "yt-dlp stream resolver succeeded.",
                    "videoId=" + videoId + ", " + attempt.describe() + ", input=" + describeUrlForLog(url)
                );

                return new ResolvedInput(url, videoId, youtubedlAndroidReady ? "youtubedl-android" : "yt-dlp");
            } catch (Exception e) {
                lastError = e;

                if (refreshYoutubedlAndroidAfterExtractorFailure(e, videoId, channel)) {
                    attempts = buildYtDlpResolveAttempts(builder, videoUrl);
                    attemptIndex = -1;
                    lastError = null;
                    continue;
                }

                if (attemptIndex + 1 < attempts.size()) {
                    log(
                        LogItem.LEVEL_WARNING,
                        LogItem.SOURCE_RECORDER,
                        channel,
                        "yt-dlp resolver attempt failed; trying next YouTube client.",
                        "videoId=" + videoId + ", " + attempt.describe() + ", error=" + normalizeErrorMessage(e)
                    );
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }

        throw new IllegalStateException("yt-dlp did not run any resolver attempts.");
    }

    private boolean refreshYoutubedlAndroidAfterExtractorFailure(
        Exception error,
        String videoId,
        ChannelItem channel
    ) {
        if (!isNoVideoFormatsError(error)) {
            return false;
        }

        return updateYoutubedlAndroidRuntimeIfNeeded(
            "after No video formats found for videoId=" + videoId,
            "yt-dlp reported no video formats; updating bundled runtime before retry.",
            channel
        );
    }

    private boolean updateYoutubedlAndroidRuntimeIfNeeded(
        String reason,
        String message,
        ChannelItem channel
    ) {
        if (!youtubedlAndroidReady || youtubedlAndroidUpdateAttempted) {
            return false;
        }

        youtubedlAndroidUpdateAttempted = true;

        log(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_RECORDER,
            channel,
            message,
            "channel=nightly, reason=" + reason
        );

        try {
            YoutubeDL.getInstance().updateYoutubeDL(getApplicationContext(), UpdateChannel._NIGHTLY);

            log(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_REMOTE_CONFIG,
                null,
                "youtubedl-android runtime updated.",
                "Updated bundled yt-dlp from the nightly channel. Continuing yt-dlp-first resolution."
            );

            return true;
        } catch (YoutubeDLException | RuntimeException updateError) {
            log(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_REMOTE_CONFIG,
                null,
                "youtubedl-android runtime update failed.",
                normalizeErrorMessage(updateError)
            );

            return false;
        }
    }

    private boolean isNoVideoFormatsError(Exception error) {
        return normalizeErrorMessage(error).toLowerCase(java.util.Locale.US).contains("no video formats found");
    }


    private List<YtDlpResolveAttempt> buildYtDlpResolveAttempts(
        RecorderCommandBuilder builder,
        String videoUrl
    ) {
        List<YtDlpResolveAttempt> attempts = new ArrayList<>();
        LinkedHashSet<String> extractorArgs = new LinkedHashSet<>();

        // First let yt-dlp choose its own current YouTube clients. Forced
        // clients can become stale faster than yt-dlp's internal defaults.
        extractorArgs.add(RecorderCommandBuilder.EXTRACTOR_ARGS_NONE);

        String configuredExtractorArgs = remoteConfig == null ? "" : remoteConfig.getYtDlpExtractorArgs();

        if (!isBlank(configuredExtractorArgs)) {
            extractorArgs.add(configuredExtractorArgs.trim());
        } else if (remoteConfig != null) {
            RemoteConfig.YoutubeClient primaryClient = remoteConfig.getPrimaryClient();

            if (primaryClient != null && primaryClient.isValid()) {
                extractorArgs.add(buildYtDlpPlayerClientArg(primaryClient.getClientName()));
            }
        }

        /*
         * YouTube can return "No video formats found" for one yt-dlp player
         * client while another client remains playable. The Java HLS resolver
         * already rotates through RemoteConfig clients; mirror that behavior for
         * yt-dlp-first so it does not give up after the default WEB client.
         */
        addPreferredYtDlpClient(extractorArgs, "ANDROID");
        addPreferredYtDlpClient(extractorArgs, "IOS");
        addPreferredYtDlpClient(extractorArgs, "MWEB");
        addPreferredYtDlpClient(extractorArgs, "WEB");

        if (remoteConfig != null) {
            for (RemoteConfig.YoutubeClient client : remoteConfig.getYoutubeClients()) {
                if (client != null && client.isValid()) {
                    addPreferredYtDlpClient(extractorArgs, client.getClientName());
                }
            }
        }

        boolean retryWithoutLiveFromStart = settings != null && settings.isLiveFromStartEnabled();

        for (String extractorArg : extractorArgs) {
            if (retryWithoutLiveFromStart) {
                attempts.add(buildYtDlpResolveAttempt(
                    builder,
                    videoUrl,
                    extractorArg,
                    false
                ));
            }

            attempts.add(buildYtDlpResolveAttempt(
                builder,
                videoUrl,
                extractorArg,
                true
            ));
        }

        return attempts;
    }

    private YtDlpResolveAttempt buildYtDlpResolveAttempt(
        RecorderCommandBuilder builder,
        String videoUrl,
        String extractorArg,
        boolean allowLiveFromStart
    ) {
        String extractorDescription = RecorderCommandBuilder.EXTRACTOR_ARGS_NONE.equals(extractorArg)
            ? "extractorArgs=yt-dlp-default"
            : "extractorArgs=" + extractorArg;
        String liveFromStartDescription = settings != null && settings.isLiveFromStartEnabled()
            ? ", liveFromStart=" + allowLiveFromStart
            : "";

        return new YtDlpResolveAttempt(
            builder.buildYtDlpResolveArgs(
                videoUrl,
                settings,
                remoteConfig,
                extractorArg,
                allowLiveFromStart
            ),
            extractorArg,
            allowLiveFromStart,
            extractorDescription + liveFromStartDescription
        );
    }

    private void addPreferredYtDlpClient(LinkedHashSet<String> extractorArgs, String clientName) {
        if (extractorArgs == null || isBlank(clientName)) {
            return;
        }

        extractorArgs.add(buildYtDlpPlayerClientArg(clientName));
    }

    private String buildYtDlpPlayerClientArg(String clientName) {
        return "youtube:player_client=" + clientName.trim().toLowerCase();
    }


    private String resolvePlayableUrlWithYoutubedlAndroid(
        String videoUrl,
        List<String> args,
        ChannelItem channel
    ) throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest(videoUrl);

        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);

            if ("--get-url".equals(arg) || videoUrl.equals(arg)) {
                continue;
            }

            String value = i + 1 < args.size() ? args.get(i + 1) : null;

            if (isYtDlpOptionWithValue(arg) && value != null && !value.startsWith("-")) {
                request.addOption(arg, value);
                i++;
            } else {
                request.addOption(arg);
            }
        }

        VideoInfo streamInfo = YoutubeDL.getInstance().getInfo(request);
        String url = streamInfo == null ? "" : streamInfo.getUrl();

        log(
            LogItem.LEVEL_DEBUG,
            LogItem.SOURCE_RECORDER,
            channel,
            "youtubedl-android output.",
            "resolvedUrl=" + describeUrlForLog(url)
        );

        return url;
    }

    private static boolean isYtDlpOptionWithValue(String arg) {
        if (arg == null) {
            return false;
        }

        return "-f".equals(arg)
            || "-o".equals(arg)
            || "--socket-timeout".equals(arg)
            || "--user-agent".equals(arg)
            || "--extractor-args".equals(arg)
            || "--cookies".equals(arg)
            || "--cookies-from-browser".equals(arg)
            || "--add-header".equals(arg)
            || "--wait-for-video".equals(arg)
            || "--fragment-retries".equals(arg)
            || "--retries".equals(arg)
            || "--extractor-retries".equals(arg)
            || "--file-access-retries".equals(arg)
            || "--retry-sleep".equals(arg);
    }

    private void logExtractorFallback(ChannelItem channel, String title, Exception error) {
        String level = remoteConfig.isJavaHlsEnabled()
            ? LogItem.LEVEL_WARNING
            : LogItem.LEVEL_ERROR;

        log(level, LogItem.SOURCE_RECORDER, channel, title, normalizeErrorMessage(error));
    }

    private LiveInfo resolveCurrentLiveInfo(ChannelItem channel) throws Exception {
        if (channel == null) {
            return null;
        }

        String resolvedChannelId = resolveChannelId(channel.getUrl());

        if (resolvedChannelId == null) {
            throw new IllegalStateException("Could not resolve channel ID for live status re-check.");
        }

        return checkLive(resolvedChannelId);
    }

    private LiveInfo resolveFreshLiveInfo(ChannelItem channel, String currentVideoId) {
        try {
            LiveInfo freshLiveInfo = resolveCurrentLiveInfo(channel);

            if (freshLiveInfo == null || isBlank(freshLiveInfo.videoId)) {
                return null;
            }

            if (currentVideoId != null && currentVideoId.equals(freshLiveInfo.videoId)) {
                return null;
            }

            return freshLiveInfo;
        } catch (Exception e) {
            log(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_RECORDER,
                channel,
                "Fresh live video ID lookup failed.",
                normalizeErrorMessage(e)
            );
            return null;
        }
    }

    private boolean shouldRetryWithFreshLiveId(Exception ytDlpError, Exception javaError) {
        String combined = (ytDlpError == null ? "" : normalizeErrorMessage(ytDlpError))
            + " "
            + (javaError == null ? "" : normalizeErrorMessage(javaError));
        String lower = combined.toLowerCase();

        return lower.contains("unavailable")
            || lower.contains("no streamingdata")
            || lower.contains("no hlsmanifesturl")
            || lower.contains("not active")
            || lower.contains("premiere")
            || lower.contains("private")
            || lower.contains("members")
            || lower.contains("sign in")
            || lower.contains("reload")
            || lower.contains("empty stream url")
            || lower.contains("resolver returned empty");
    }

    private String buildResolverFailureMessage(Exception ytDlpError, Exception javaError) {
        StringBuilder builder = new StringBuilder("Could not resolve playable YouTube stream URL.");

        if (ytDlpError != null) {
            builder.append(" yt-dlp: ").append(normalizeErrorMessage(ytDlpError));
        }

        if (javaError != null) {
            builder.append(" Java HLS: ").append(normalizeErrorMessage(javaError));
        }

        if (ytDlpError == null && javaError == null) {
            builder.append(" No resolver was enabled by remote config.");
        }

        return builder.toString();
    }

    private static String normalizeVideoIdForLookup(String videoId) {
        String normalized = YouTubeUrlUtils.extractVideoId(videoId);
        return isBlank(normalized) ? nullToEmpty(videoId).trim() : normalized;
    }

    private String getHlsManifestUrl(String videoId, ChannelItem channel) {
        if (videoId == null || videoId.trim().isEmpty()) {
            throw new IllegalStateException("Could not get HLS manifest URL. videoId is empty.");
        }

        List<RemoteConfig.YoutubeClient> clients = getManifestClientsForAttempts();
        int apiKeyCount = Math.max(1, remoteConfig.getApiKeys().size());

        String lastFailure = "";

        for (int clientIndex = 0; clientIndex < clients.size(); clientIndex++) {
            RemoteConfig.YoutubeClient client = clients.get(clientIndex);

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

                    String response = httpPostWithRetry(apiUrl, body.toString(), client);
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

        String watchPageManifestUrl = getHlsManifestUrlFromWatchPage(videoId, channel, lastFailure);

        if (watchPageManifestUrl != null && !watchPageManifestUrl.trim().isEmpty()) {
            return watchPageManifestUrl;
        }

        throw new IllegalStateException("Could not get HLS manifest URL. " + lastFailure);
    }

    private String getHlsManifestUrlFromWatchPage(
        String videoId,
        ChannelItem channel,
        String previousFailure
    ) {
        String watchUrl = remoteConfig.getWebPlayerBaseUrl()
            + "/watch?v="
            + urlEncodeForQuery(videoId);

        log(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_RECORDER,
            channel,
            "Trying watch page HLS fallback.",
            "videoId=" + videoId + ", previousFailure=" + shortenForLog(previousFailure, 300)
        );

        try {
            String html = httpGet(watchUrl);
            JSONObject playerResponse = extractInitialPlayerResponse(html);

            if (playerResponse == null) {
                log(
                    LogItem.LEVEL_WARNING,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "Watch page HLS fallback failed.",
                    "ytInitialPlayerResponse not found. videoId=" + videoId
                );

                return "";
            }

            JSONObject streamingData = playerResponse.optJSONObject("streamingData");

            if (streamingData == null) {
                log(
                    LogItem.LEVEL_WARNING,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "Watch page HLS fallback failed.",
                    "No streamingData. videoId="
                        + videoId
                        + ", response="
                        + summarizeInnertubeResponseForLog(playerResponse)
                );

                return "";
            }

            String hlsManifestUrl = streamingData.optString("hlsManifestUrl", "");

            if (hlsManifestUrl == null || hlsManifestUrl.trim().isEmpty()) {
                log(
                    LogItem.LEVEL_WARNING,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "Watch page HLS fallback failed.",
                    "No hlsManifestUrl. videoId="
                        + videoId
                        + ", streamingDataKeys="
                        + streamingData.names()
                );

                return "";
            }

            log(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_RECORDER,
                channel,
                "Watch page HLS fallback succeeded.",
                "videoId=" + videoId + ", manifest=" + describeUrlForLog(hlsManifestUrl)
            );

            return hlsManifestUrl;
        } catch (Exception e) {
            log(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_RECORDER,
                channel,
                "Watch page HLS fallback error.",
                "videoId=" + videoId + ", error=" + normalizeErrorMessage(e)
            );

            Log.w(TAG, "getHlsManifestUrlFromWatchPage failed", e);
            return "";
        }
    }

    private JSONObject extractInitialPlayerResponse(String html) throws Exception {
        if (html == null || html.isEmpty()) {
            return null;
        }

        String marker = "ytInitialPlayerResponse";
        int markerIndex = html.indexOf(marker);

        while (markerIndex >= 0) {
            int equalsIndex = html.indexOf('=', markerIndex + marker.length());

            if (equalsIndex < 0) {
                return null;
            }

            int objectStart = html.indexOf('{', equalsIndex + 1);

            if (objectStart < 0) {
                return null;
            }

            int objectEnd = findJsonObjectEnd(html, objectStart);

            if (objectEnd > objectStart) {
                return new JSONObject(html.substring(objectStart, objectEnd + 1));
            }

            markerIndex = html.indexOf(marker, objectStart + 1);
        }

        return null;
    }

    private int findJsonObjectEnd(String text, int objectStart) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;

        for (int i = objectStart; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }

                continue;
            }

            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;

                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }

    private String urlEncodeForQuery(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }


    private String getDirectDownloadInputUrl(String videoId, ChannelItem channel) {
        String safeVideoId = normalizeVideoIdForLookup(videoId);
        LiveInfo liveInfo = new LiveInfo(
            safeVideoId,
            "",
            YouTubeUrlUtils.buildWatchUrl(safeVideoId)
        );

        try {
            return resolveRecordingInputUrl(safeVideoId, channel, liveInfo, false).url;
        } catch (Exception e) {
            log(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_RECORDER,
                channel,
                "Primary resolver unavailable for direct download; trying progressive fallback.",
                normalizeErrorMessage(e)
            );
        }

        return getProgressiveVideoUrl(safeVideoId, channel);
    }

    private String getProgressiveVideoUrl(String videoId, ChannelItem channel) {
        List<RemoteConfig.YoutubeClient> clients = getManifestClientsForAttempts();
        int apiKeyCount = Math.max(1, remoteConfig.getApiKeys().size());
        String lastFailure = "";

        for (int clientIndex = 0; clientIndex < clients.size(); clientIndex++) {
            RemoteConfig.YoutubeClient client = clients.get(clientIndex);

            if (client == null || !client.isValid()) continue;

            for (int keyIndex = 0; keyIndex < apiKeyCount; keyIndex++) {
                String apiKey = getApiKeyForAttempt(keyIndex);
                String apiUrl = remoteConfig.getInnertubeBaseUrl() + "/player?key=" + apiKey;
                String details = "videoId=" + videoId
                    + ", clientIndex=" + clientIndex
                    + ", client=" + describeClientForLog(client)
                    + ", keyAttempt=" + keyIndex;

                try {
                    JSONObject context = new JSONObject()
                        .put("client", client.toInnertubeClientJson());
                    JSONObject body = new JSONObject()
                        .put("context", context)
                        .put("videoId", videoId)
                        .put("contentCheckOk", true)
                        .put("racyCheckOk", true);
                    JSONObject json = new JSONObject(httpPostWithRetry(apiUrl, body.toString(), client));
                    JSONObject streamingData = json.optJSONObject("streamingData");

                    if (streamingData == null) {
                        lastFailure = "No streamingData. " + details + ", response=" + summarizeInnertubeResponseForLog(json);
                        continue;
                    }

                    String url = findBestFormatUrl(streamingData.optJSONArray("formats"));

                    if (url == null || url.trim().isEmpty()) {
                        url = findBestFormatUrl(streamingData.optJSONArray("adaptiveFormats"));
                    }

                    if (url != null && !url.trim().isEmpty()) {
                        return url;
                    }

                    lastFailure = "No direct format URL. " + details;
                } catch (Exception e) {
                    lastFailure = details + ", error=" + normalizeErrorMessage(e);
                    Log.w(TAG, "getProgressiveVideoUrl failed: " + lastFailure, e);
                }
            }
        }

        throw new IllegalStateException("Could not get ended-live/video download URL. " + lastFailure);
    }

    private static String findBestFormatUrl(JSONArray formats) {
        if (formats == null) {
            return "";
        }

        String fallback = "";

        for (int i = 0; i < formats.length(); i++) {
            JSONObject format = formats.optJSONObject(i);

            if (format == null) continue;

            String url = format.optString("url", "");

            if (url == null || url.trim().isEmpty()) continue;

            String mimeType = format.optString("mimeType", "");

            if (mimeType.contains("video/mp4") && mimeType.contains("audio")) {
                return url;
            }

            if (fallback.isEmpty() && mimeType.contains("video")) {
                fallback = url;
            }
        }

        return fallback;
    }

    private String httpGet(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", remoteConfig.getUserAgent());
        return readResponse(connection);
    }

    private String httpPostWithRetry(
        String urlString,
        String body,
        RemoteConfig.YoutubeClient client
    ) throws Exception {
        Exception lastError = null;

        for (int attempt = 1; attempt <= INNERTUBE_HTTP_MAX_ATTEMPTS; attempt++) {
            try {
                return httpPost(urlString, body, client);
            } catch (Exception e) {
                lastError = e;

                if (!isRetryableInnertubeError(e) || attempt >= INNERTUBE_HTTP_MAX_ATTEMPTS) {
                    throw e;
                }

                sleep(getAttemptBackoffMillis(attempt));
            }
        }

        throw lastError == null ? new IllegalStateException("Innertube request failed.") : lastError;
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
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", getUserAgentForClient(client));

        if (isWebInnertubeClient(client)) {
            connection.setRequestProperty("Origin", remoteConfig.getWebPlayerBaseUrl());
            connection.setRequestProperty("Referer", remoteConfig.getWebPlayerBaseUrl() + "/");
        }

        String clientHeaderName = getClientHeaderName(client);

        if (!isBlank(clientHeaderName)) {
            connection.setRequestProperty("X-Youtube-Client-Name", clientHeaderName);
        }

        if (client != null && !isBlank(client.getClientVersion())) {
            connection.setRequestProperty("X-Youtube-Client-Version", client.getClientVersion());
        }

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

    private boolean isRetryableInnertubeError(Exception e) {
        String message = normalizeErrorMessage(e).toLowerCase();
        return message.contains("timeout")
            || message.contains("connection")
            || message.contains("http 408")
            || message.contains("http 429")
            || message.contains("http 500")
            || message.contains("http 502")
            || message.contains("http 503")
            || message.contains("http 504");
    }

    private long getAttemptBackoffMillis(int attempt) {
        int safeAttempt = Math.max(1, attempt);
        return Math.min(20_000L, safeAttempt * safeAttempt * 1_500L);
    }

    private static String describeReturnCode(ReturnCode code) {
        if (code == null) {
            return "returnCode=unknown";
        }

        return "returnCode=" + code.getValue();
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
        return getManifestClientsForAttempts().size();
    }

    private int getConfiguredApiKeyCountForLog() {
        return Math.max(1, remoteConfig.getApiKeys().size());
    }

    private List<RemoteConfig.YoutubeClient> getManifestClientsForAttempts() {
        List<RemoteConfig.YoutubeClient> clients = new ArrayList<>();
        List<RemoteConfig.YoutubeClient> configuredClients = remoteConfig.getYoutubeClients();

        if (configuredClients != null) {
            for (RemoteConfig.YoutubeClient client : configuredClients) {
                addUniqueValidClient(clients, client);
            }
        }

        for (RemoteConfig.YoutubeClient defaultClient : RemoteConfig.getDefaultClients()) {
            addUniqueValidClient(clients, defaultClient);
        }

        if (clients.isEmpty()) {
            addUniqueValidClient(clients, new RemoteConfig.YoutubeClient());
        }

        return clients;
    }

    private static void addUniqueValidClient(
        List<RemoteConfig.YoutubeClient> clients,
        RemoteConfig.YoutubeClient candidate
    ) {
        if (clients == null || candidate == null || !candidate.isValid()) {
            return;
        }

        String candidateKey = getClientAttemptKey(candidate);

        for (RemoteConfig.YoutubeClient existing : clients) {
            if (candidateKey.equals(getClientAttemptKey(existing))) {
                return;
            }
        }

        clients.add(candidate);
    }

    private static String getClientAttemptKey(RemoteConfig.YoutubeClient client) {
        if (client == null) {
            return "";
        }

        return nullToEmpty(client.getClientName()).toUpperCase()
            + "/"
            + nullToEmpty(client.getClientVersion())
            + "/"
            + nullToEmpty(client.getUserAgent());
    }

    private static String getClientHeaderName(RemoteConfig.YoutubeClient client) {
        if (client == null) {
            return "";
        }

        if (!isBlank(client.getClientId())) {
            return client.getClientId().trim();
        }

        String clientName = nullToEmpty(client.getClientName()).toUpperCase();

        if (clientName.contains("WEB_EMBEDDED")) {
            return "56";
        }

        if ("WEB".equals(clientName)) {
            return "1";
        }

        if ("MWEB".equals(clientName)) {
            return "2";
        }

        if (clientName.contains("ANDROID_VR")) {
            return "28";
        }

        if ("ANDROID".equals(clientName)) {
            return "3";
        }

        if ("IOS".equals(clientName)) {
            return "5";
        }

        if (clientName.contains("TV")) {
            return "7";
        }

        return "";
    }

    private static boolean isWebInnertubeClient(RemoteConfig.YoutubeClient client) {
        if (client == null) {
            return true;
        }

        String clientName = nullToEmpty(client.getClientName()).toUpperCase();
        return clientName.startsWith("WEB")
            || "MWEB".equals(clientName)
            || clientName.contains("TV");
    }

    private String getUserAgentForClient(RemoteConfig.YoutubeClient client) {
        if (client != null && !isBlank(client.getUserAgent())) {
            return client.getUserAgent();
        }

        return remoteConfig.getUserAgent();
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
            StringBuilder builder = new StringBuilder();
            builder.append(parsed.getProtocol()).append("://").append(parsed.getHost());

            int port = parsed.getPort();

            if (port >= 0) {
                builder.append(':').append(port);
            }

            builder.append(redactUrlPathForLog(parsed.getPath()));

            if (parsed.getQuery() != null && !parsed.getQuery().isEmpty()) {
                builder.append('?').append(redactUrlQueryForLog(parsed.getQuery()));
            }

            return shortenForLog(builder.toString(), 320);
        } catch (Exception e) {
            return shortenForLog(redactSensitiveUrlTextForLog(url), 160);
        }
    }

    private static String redactUrlPathForLog(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        String[] parts = path.split("/", -1);
        StringBuilder builder = new StringBuilder(path.length());

        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append('/');
            }

            String part = parts[i];

            if (i > 0 && shouldRedactUrlPathValue(parts[i - 1])) {
                builder.append("<redacted>");
            } else {
                builder.append(part);
            }
        }

        return builder.toString();
    }

    private static boolean shouldRedactUrlPathValue(String previousPart) {
        if (previousPart == null) {
            return false;
        }

        String key = previousPart.toLowerCase();

        return "ip".equals(key)
            || "sig".equals(key)
            || "signature".equals(key)
            || "lsig".equals(key)
            || "spc".equals(key)
            || "bui".equals(key)
            || "ei".equals(key)
            || "expire".equals(key)
            || "tx".equals(key)
            || "txs".equals(key)
            || "xpc".equals(key)
            || "n".equals(key)
            || "rqh".equals(key);
    }

    private static String redactUrlQueryForLog(String query) {
        if (query == null || query.isEmpty()) {
            return "";
        }

        String[] params = query.split("&", -1);
        StringBuilder builder = new StringBuilder(query.length());

        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                builder.append('&');
            }

            String param = params[i];
            int equals = param.indexOf('=');
            String key = equals >= 0 ? param.substring(0, equals) : param;

            builder.append(key);

            if (equals >= 0) {
                builder.append('=');
                builder.append(shouldRedactUrlQueryValue(key) ? "<redacted>" : param.substring(equals + 1));
            }
        }

        return builder.toString();
    }

    private static boolean shouldRedactUrlQueryValue(String key) {
        if (key == null) {
            return false;
        }

        String normalized = key.toLowerCase();

        return normalized.contains("sig")
            || "ip".equals(normalized)
            || "spc".equals(normalized)
            || "bui".equals(normalized)
            || "expire".equals(normalized)
            || "ei".equals(normalized)
            || "xpc".equals(normalized)
            || "n".equals(normalized);
    }

    private static String redactSensitiveUrlTextForLog(String value) {
        if (value == null) {
            return "";
        }

        return value
            .replaceAll("(?i)(/ip/)[^/?#]+", "$1<redacted>")
            .replaceAll("(?i)(/(?:sig|signature|lsig|spc|bui|ei|expire|tx|txs|xpc|n|rqh)/)[^/?#]+", "$1<redacted>")
            .replaceAll("(?i)([?&](?:ip|sig|signature|lsig|spc|bui|ei|expire|xpc|n)=)[^&#]+", "$1<redacted>");
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

        for (RecordingItem recording : activeRecordings.values()) {
            if (recording != null && recording.isActive()) {
                recording.setDiagnosticMessage("Network unavailable; FFmpeg reconnect is waiting for segments.");
                storage.upsertRecording(recording);
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
        restartingRecordings.clear();
        FFmpegRunner.cancel();
        FFmpegKit.cancel();

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

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
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


    private enum CleanExitAction {
        FINALIZE,
        RESTARTED,
        DEFERRED
    }

    private static class YtDlpResolveAttempt {
        final List<String> args;
        final String extractorArgs;
        final boolean allowLiveFromStart;
        final String description;

        YtDlpResolveAttempt(
            List<String> args,
            String extractorArgs,
            boolean allowLiveFromStart,
            String description
        ) {
            this.args = args;
            this.extractorArgs = extractorArgs == null ? "" : extractorArgs;
            this.allowLiveFromStart = allowLiveFromStart;
            this.description = description == null ? "" : description;
        }

        String describe() {
            return description;
        }
    }

    private static class ResolvedInput {
        final String url;
        final String videoId;
        final String source;

        ResolvedInput(String url, String videoId, String source) {
            this.url = nullToEmpty(url).trim();
            this.videoId = nullToEmpty(videoId).trim();
            this.source = isBlank(source) ? "unknown" : source.trim();
        }
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
