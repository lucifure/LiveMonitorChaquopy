package com.livemonitor.app;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
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
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel;
import com.yausername.youtubedl_android.mapper.VideoInfo;
import com.yausername.ffmpeg.FFmpeg;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class MonitorService extends Service implements NetworkMonitor.Listener {
    private static final String TAG = "MonitorService";
    private static final String FALLBACK_YT_API_KEY = "AIzaSyDnAsBrxe_aFkUSpqkrFDczUw-PpLoEhuY";
    private static final long MIN_FREE_BYTES_BEFORE_RECORDING = 512L * 1024L * 1024L;
    private static final long MIN_FREE_BYTES_BEFORE_CONVERSION = 256L * 1024L * 1024L;
    private static final int DIRECT_DOWNLOAD_MAX_ATTEMPTS = 3;
    private static final int INNERTUBE_HTTP_MAX_ATTEMPTS = 2;
    private static final long HTTP_429_COOLDOWN_MILLIS = 10L * 60L * 1_000L;
    private static final long MISSED_STREAM_OUTAGE_MIN_MILLIS = 2L * 60L * 1_000L;
    private static final long DIRECT_DOWNLOAD_PROGRESS_INTERVAL_MS = 2_000L;

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
    private final Set<String> processingRecordingIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<String> userHaltedRecordingIds = ConcurrentHashMap.newKeySet();
    private final Set<String> activeYoutubedlAndroidRecordings = ConcurrentHashMap.newKeySet();
    private final Set<String> ytDlpFragmentEndSignals = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> ytDlpFragmentErrorCounts = new ConcurrentHashMap<>();
    private final Set<String> restartingRecordings = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> channelRateLimitCooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, String> liveFallbackLogState = new ConcurrentHashMap<>();
    private final Set<String> stoppingRecordingIds = ConcurrentHashMap.newKeySet();
    private final Set<String> finalizingRecordingIds = ConcurrentHashMap.newKeySet();
    private final Set<String> directDownloadVideoIds = ConcurrentHashMap.newKeySet();
    private final Set<String> discardDirectDownloadPartialIds = ConcurrentHashMap.newKeySet();
    private final Set<String> finalizedRecordingIds = ConcurrentHashMap.newKeySet();
    private final Set<String> selectedFolderCopyIds = ConcurrentHashMap.newKeySet();

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
        progressTracker.setUpdateIntervalMs(DIRECT_DOWNLOAD_PROGRESS_INTERVAL_MS);
        executor = Executors.newCachedThreadPool();
        progressTracker.setListener(new RecordingProgressTracker.Listener() {
            @Override
            public void onRecordingProgressUpdated(RecordingItem recording) {
                broadcastRecordingUpdated("");
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
        PoTokenRefreshWorker.scheduleIfNeeded(this);
        log(LogItem.LEVEL_SUCCESS, LogItem.SOURCE_SERVICE, null, "MonitorService created.", "");
    }


    private void prepareYoutubedlAndroid() {
        if (youtubedlAndroidReady) {
            return;
        }

        try {
            YoutubeDL.getInstance().init(getApplicationContext());
            FFmpeg.getInstance().init(getApplicationContext());
            youtubedlAndroidReady = true;
            log(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_REMOTE_CONFIG,
                null,
                "youtubedl-android ready.",
                "Bundled Android yt-dlp and ffmpeg runtimes initialized for private testing."
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
        ensureForeground();
        acquireWakeLock();

        if (intent == null || intent.getAction() == null) {
            refreshRecorderEnvironment();
            restoreSavedChannels();
            return START_STICKY;
        }

        String action = intent.getAction();

        if (isImmediateRecordingControlAction(action)) {
            dispatchImmediateRecordingControl(intent, action);
            return START_STICKY;
        }

        refreshRecorderEnvironment();

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
            Intent stopIntent = new Intent(intent);
            executor.execute(() -> handleStopRecording(stopIntent));
        } else if (LiveMonitorActions.ACTION_PAUSE_RECORDING.equals(action)) {
            handlePauseRecording(intent);
        } else if (LiveMonitorActions.ACTION_RESUME_RECORDING.equals(action)) {
            handleResumeRecording(intent);
        } else if (LiveMonitorActions.ACTION_STOP_ALL.equals(action)
            || LiveMonitorActions.LEGACY_ACTION_STOP.equals(action)) {
            executor.execute(this::stopAll);
        } else if (LiveMonitorActions.ACTION_RESTORE_MONITORING.equals(action)
            || BootReceiver.ACTION_RESTORE_MONITORING.equals(action)) {
            restoreSavedChannels();
        }

        return START_STICKY;
    }

    private boolean isImmediateRecordingControlAction(String action) {
        return LiveMonitorActions.ACTION_STOP_RECORDING.equals(action)
            || LiveMonitorActions.ACTION_PAUSE_RECORDING.equals(action)
            || LiveMonitorActions.ACTION_RESUME_RECORDING.equals(action);
    }

    private void dispatchImmediateRecordingControl(Intent intent, String action) {
        Intent actionIntent = new Intent(intent);
        if (LiveMonitorActions.ACTION_STOP_RECORDING.equals(action)) {
            executor.execute(() -> handleStopRecording(actionIntent));
        } else if (LiveMonitorActions.ACTION_PAUSE_RECORDING.equals(action)) {
            executor.execute(() -> handlePauseRecording(actionIntent));
        } else if (LiveMonitorActions.ACTION_RESUME_RECORDING.equals(action)) {
            executor.execute(() -> handleResumeRecording(actionIntent));
        }
    }

    private void refreshRecorderEnvironment() {
        remoteConfig = new RemoteConfigFetcher(this).loadBestAvailableConfig();
        prepareYoutubedlAndroid();
        prepareYtDlpExecutable();
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
        stopActiveRecordingForRemovedChannel(channel);
        notificationHelper.cancelChannelNotification(channel);
        storage.removeChannel(channel.getId());
        broadcastChannelUpdated("Channel removed.");
    }

    private void stopActiveRecordingForRemovedChannel(ChannelItem channel) {
        if (channel == null || isBlank(channel.getId())) {
            return;
        }

        RecordingItem recording = findActiveRecordingForChannel(channel.getId());
        if (recording == null) {
            return;
        }

        activeRecordings.remove(channel.getId());
        activeRecordings.remove(recording.getId());
        activeRecordings.remove(recording.getChannelId());
        progressTracker.untrack(recording);

        recording.markStoppedByUser();
        recording.showInDownloading();
        storage.upsertRecording(recording);

        cancelActiveRecording(recording, "handleStopRecording user action");
        waitForRecordingFileAfterCancellation(recording);

        RecordingItem latest = storage.findRecordingById(recording.getId());
        boolean savedPlayableFile = saveStoppedRecordingForDownloads(
            latest == null ? recording : latest,
            channel
        );

        log(
            savedPlayableFile ? LogItem.LEVEL_SUCCESS : LogItem.LEVEL_WARNING,
            LogItem.SOURCE_RECORDER,
            channel,
            savedPlayableFile
                ? "Active recording stopped because channel was removed."
                : "Active recording stopped because channel was removed, but no playable file was found.",
            "recordingId=" + recording.getId()
        );
    }

    private RecordingItem findActiveRecordingForChannel(String channelId) {
        if (isBlank(channelId)) {
            return null;
        }

        RecordingItem recording = activeRecordings.get(channelId);
        if (recording != null) {
            return recording;
        }

        for (RecordingItem candidate : activeRecordings.values()) {
            if (candidate != null && channelId.equals(candidate.getChannelId())) {
                return candidate;
            }
        }

        for (RecordingItem candidate : storage.loadActiveRecordings()) {
            if (candidate != null && channelId.equals(candidate.getChannelId())) {
                return candidate;
            }
        }

        return null;
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

        videoId = videoId.trim();
        if (directDownloadVideoIds.contains(videoId)) {
            log(LogItem.LEVEL_INFO, LogItem.SOURCE_RECORDER, null, "Direct download already running.", "videoId=" + videoId);
            broadcastRecordingUpdated("Direct download is already running.");
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
        directDownloadVideoIds.add(videoId);
        executor.execute(() -> runDirectVideoDownload(recording));
        broadcastRecordingUpdated("Direct download started.");
    }

    private boolean skipDuplicateFinalizationRequest(String recordingId, ChannelItem channel) {
        if (isBlank(recordingId)) {
            return false;
        }
        if (finalizingRecordingIds.add(recordingId)) {
            return false;
        }
        log(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_RECORDER,
            channel,
            "Skipping duplicate finalization",
            "recordingId=" + recordingId
        );
        return true;
    }

    private ChannelItem pauseMonitoringForUserStoppedRecording(String channelId, RecordingItem recording, String logMessage) {
        String resolvedChannelId = channelId;
        if (isBlank(resolvedChannelId) && recording != null && !isBlank(recording.getChannelId())) {
            resolvedChannelId = recording.getChannelId();
        }

        if (isBlank(resolvedChannelId)) {
            return null;
        }

        activeLoops.remove(resolvedChannelId);
        activeRecordings.remove(resolvedChannelId);

        ChannelItem channel = storage.findChannelById(resolvedChannelId);
        if (channel == null) {
            return null;
        }

        activeLoops.remove(channel.getId());
        activeRecordings.remove(channel.getId());
        channel.markPausedByUser();
        storage.upsertChannel(channel);
        notificationHelper.showChannelMonitoringNotification(channel);
        log(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_SERVICE,
            channel,
            logMessage,
            recording == null ? "" : "recordingId=" + recording.getId()
        );
        broadcastChannelUpdated(logMessage);
        return channel;
    }

    private void handlePauseRecording(Intent intent) {
        if (intent == null) return;

        String recordingId = intent.getStringExtra(LiveMonitorActions.EXTRA_RECORDING_ID);
        if (!beginProcessingRecordingAction(recordingId, "pause")) {
            return;
        }

        try {
            String channelId = intent.getStringExtra(LiveMonitorActions.EXTRA_CHANNEL_ID);
            if (skipDuplicateFinalizationRequest(recordingId, null)) {
                return;
            }
            RecordingItem recording = storage.findRecordingById(recordingId);

            if (recording == null) {
                return;
            }

            userHaltedRecordingIds.add(recording.getId());
            recording.markPausedByUser();
            recording.showInDownloading();
            storage.upsertRecording(recording);
            activeRecordings.remove(recording.getId());
            activeRecordings.remove(recording.getChannelId());
            progressTracker.untrack(recording);

            pauseMonitoringForUserStoppedRecording(
                channelId,
                recording,
                "Recording paused by user; channel monitoring paused until manually resumed."
            );

            cancelActiveRecording(recording, "handlePauseRecording user action");
            broadcastRecordingUpdated("Recording paused.");
        } finally {
            if (!isBlank(recordingId)) {
                finalizingRecordingIds.remove(recordingId);
            }
            finishProcessingRecordingAction(recordingId);
        }
    }

    private void handleResumeRecording(Intent intent) {
        if (intent == null) return;

        String recordingId = intent.getStringExtra(LiveMonitorActions.EXTRA_RECORDING_ID);
        if (!beginProcessingRecordingAction(recordingId, "resume")) {
            return;
        }

        try {
            userHaltedRecordingIds.remove(recordingId);
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

        if (!networkAvailable) {
            recording.markPausedNetwork("Network unavailable; keeping recorded files until internet returns.");
            recording.showInDownloading();
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Resume delayed until network returns.");
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

        executor.execute(() -> resumeRecordingOrFinalizeStoppedLive(channel.getId(), recording));
        broadcastRecordingUpdated("Recording resumed.");
        } finally {
            finishProcessingRecordingAction(recordingId);
        }
    }

    private void resumeRecordingOrFinalizeStoppedLive(String channelId, RecordingItem recording) {
        ChannelItem channel = storage.findChannelById(channelId);

        try {
            if (!networkAvailable) {
                recording.markPausedNetwork("Network unavailable; keeping recorded files until internet returns.");
                storage.upsertRecording(recording);
                activeRecordings.remove(recording.getId());
                activeRecordings.remove(recording.getChannelId());
                if (!isBlank(channelId)) {
                    activeRecordings.remove(channelId);
                }
                progressTracker.untrack(recording);
                broadcastRecordingUpdated("Resume delayed until network returns.");
                return;
            }

            String resolvedChannelId = channel == null ? null : resolveChannelId(channel.getUrl());
            LiveInfo liveInfo = resolvedChannelId == null ? null : checkLive(resolvedChannelId);

            if (liveInfo == null || !recording.matchesVideo(liveInfo.videoId)) {
                activeRecordings.remove(recording.getId());
                activeRecordings.remove(recording.getChannelId());
                if (!isBlank(channelId)) {
                    activeRecordings.remove(channelId);
                }
                progressTracker.untrack(recording);

                if (saveStoppedRecordingForDownloads(recording, channel)) {
                    if (channel != null) {
                        channel.markRecordingFinished();
                        channel.markWaitingForLive();
                        storage.upsertChannel(channel);
                        notificationHelper.showChannelMonitoringNotification(channel);
                    }
                    broadcastRecordingUpdated("Paused recording finalized because the live stream ended.");
                } else {
                    recording.markRecoverable("Paused recording could not resume because the live stream ended and no playable file was found.");
                    storage.upsertRecording(recording);
                    broadcastRecordingUpdated("Paused recording is recoverable.");
                }
                return;
            }

            runRecording(channelId, recording, liveInfo);
        } catch (Exception e) {
            activeRecordings.remove(recording.getId());
            activeRecordings.remove(recording.getChannelId());
            if (!isBlank(channelId)) {
                activeRecordings.remove(channelId);
            }
            progressTracker.untrack(recording);
            recording.markRecoverable("Resume failed. " + normalizeErrorMessage(e));
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Recording resume failed.");
        }
    }

    private void handleStopRecording(Intent intent) {
        if (intent == null) return;

        String recordingId = intent.getStringExtra(LiveMonitorActions.EXTRA_RECORDING_ID);
        if (!beginProcessingRecordingAction(recordingId, "stop")) {
            return;
        }

        try {
            String channelId = intent.getStringExtra(LiveMonitorActions.EXTRA_CHANNEL_ID);
            RecordingItem recording = storage.findRecordingById(recordingId);
            boolean savePartial = intent.getBooleanExtra(LiveMonitorActions.EXTRA_SAVE_PARTIAL, true);

            if (recording != null) {
                if (!savePartial) {
                    discardDirectDownloadPartialIds.add(recording.getId());
                }

                activeRecordings.remove(recording.getId());
                activeRecordings.remove(recording.getChannelId());
                progressTracker.untrack(recording);

                if (isBlank(channelId) && !isBlank(recording.getChannelId())) {
                    channelId = recording.getChannelId();
                }

                userHaltedRecordingIds.add(recording.getId());
                recording.markStoppedByUser();
                recording.showInDownloading();
                storage.upsertRecording(recording);
            }

            ChannelItem stoppedChannel = pauseMonitoringForUserStoppedRecording(
                channelId,
                recording,
                "Recording stopped by user; channel monitoring paused until manually resumed."
            );

            if (skipDuplicateFinalizationRequest(recordingId, stoppedChannel)) {
                cancelActiveRecording(recording, "handleStopRecording duplicate user action");
                broadcastRecordingUpdated("Recording stop is already in progress; monitoring is paused.");
                return;
            }

            if (!isBlank(recordingId) && !stoppingRecordingIds.add(recordingId)) {
                cancelActiveRecording(recording, "handleStopRecording duplicate stop action");
                broadcastRecordingUpdated("Recording stop is already in progress; monitoring is paused.");
                return;
            }

            try {
                cancelActiveRecording(recording, "handleStopRecording user action");

                boolean savedPlayableFile = false;
                if (recording != null) {
                    waitForRecordingFileAfterCancellation(recording);
                    RecordingItem latest = storage.findRecordingById(recording.getId());
                    RecordingItem toFinalize = latest == null ? recording : latest;
                    if (!savePartial && isBlank(toFinalize.getChannelId())) {
                        deleteDirectDownloadTempFiles(toFinalize.getTempTsPath());
                        toFinalize.markStoppedByUser();
                        toFinalize.hideFromDownloading();
                        storage.upsertRecording(toFinalize);
                    } else {
                        ChannelItem channel = stoppedChannel != null
                            ? stoppedChannel
                            : (isBlank(channelId) ? null : storage.findChannelById(channelId));
                        savedPlayableFile = saveStoppedRecordingForDownloads(toFinalize, channel);
                    }
                }

                if (!savedPlayableFile) {
                    broadcastRecordingUpdated("Download stopped; no file was saved because no stream data was received.");
                } else {
                    broadcastRecordingUpdated("Download stopped and saved.");
                }
            } finally {
                if (!isBlank(recordingId)) {
                    stoppingRecordingIds.remove(recordingId);
                }
            }
        } finally {
            if (!isBlank(recordingId)) {
                finalizingRecordingIds.remove(recordingId);
            }
            finishProcessingRecordingAction(recordingId);
        }
    }

    private boolean beginProcessingRecordingAction(String recordingId, String actionLabel) {
        if (isBlank(recordingId)) {
            return true;
        }

        if (processingRecordingIds.add(recordingId)) {
            return true;
        }

        log(
            LogItem.LEVEL_WARNING,
            LogItem.SOURCE_SERVICE,
            null,
            "Ignoring duplicate action for recordingId already being processed.",
            "action=" + actionLabel + ", recordingId=" + recordingId
        );
        return false;
    }

    private void finishProcessingRecordingAction(String recordingId) {
        if (!isBlank(recordingId)) {
            processingRecordingIds.remove(recordingId);
        }
    }

    private boolean saveStoppedRecordingForDownloads(RecordingItem recording) {
        return saveStoppedRecordingForDownloads(recording, null);
    }

    private boolean saveStoppedRecordingForDownloads(RecordingItem recording, ChannelItem channel) {
        if (recording == null) {
            return false;
        }

        if (!finalizedRecordingIds.add(recording.getId())) {
            log(LogItem.LEVEL_INFO, LogItem.SOURCE_RECORDER, channel, "Skipping duplicate finalization.", "recordingId=" + recording.getId());
            return recording.isCompleted() || recording.hasExistingFinalMp4File() || recording.hasExistingTempTsFile();
        }

        if (recording.isCompleted()) {
            copyCompletedRecordingToSelectedFolder(recording, channel);
            recording.hideFromDownloading();
            storage.upsertRecording(recording);
            return true;
        }

        if (recording.hasExistingFinalMp4File()) {
            recording.markCompleted(recording.getFinalMp4Path());
            copyCompletedRecordingToSelectedFolder(recording, channel);
            recording.hideFromDownloading();
            storage.upsertRecording(recording);
            return true;
        }

        if (mergeStoppedDashSidecars(recording, channel)) {
            return true;
        }

        String existingTempSegmentPath = recording.getFirstExistingTempSegmentPath();

        if (!isBlank(existingTempSegmentPath)) {
            if (settings != null && settings.isConvertTsToMp4()) {
                return convertRecording(recording, channel);
            }

            recording.markCompleted(existingTempSegmentPath);
            copyCompletedRecordingToSelectedFolder(recording, channel);
            recording.hideFromDownloading();
            storage.upsertRecording(recording);
            return true;
        }

        recording.markStoppedByUser();
        recording.hideFromDownloading();
        storage.upsertRecording(recording);
        return false;
    }


    private boolean mergeStoppedDashSidecars(RecordingItem recording, ChannelItem channel) {
        List<File> sidecars = findYtDlpDashSidecarFiles(recording);

        if (sidecars.size() < 2 || isBlank(recording.getFinalMp4Path())) {
            return false;
        }

        File videoFile = selectDashVideoSidecar(sidecars);
        File audioFile = selectDashAudioSidecar(sidecars, videoFile);

        if (videoFile == null || audioFile == null || videoFile.equals(audioFile)) {
            return false;
        }

        long sourceBytesBeforeMerge = Math.max(0L, videoFile.length())
            + Math.max(0L, audioFile.length());
        String sourceSummary = describeMergeSourceFiles(videoFile, audioFile);

        if (!ensureRecordingStorageAvailable(channel, estimateDashMergeRequiredBytes(sidecars))) {
            recording.markRecoverable("Not enough free storage to merge stopped DASH recording. " + fileManager.getStorageSummary());
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Stopped recording is recoverable; storage is low.");
            return false;
        }

        recording.markConverting();
        storage.upsertRecording(recording);
        broadcastRecordingUpdated("Merging stopped recording.");

        String command = "-y -i " + quote(videoFile.getAbsolutePath())
            + " -i " + quote(audioFile.getAbsolutePath())
            + " -map 0:v:0? -map 1:a:0? -c copy -movflags +faststart "
            + quote(recording.getFinalMp4Path());

        try {
            ReturnCode code = FFmpegKit.execute(command).getReturnCode();

            if (!ReturnCode.isSuccess(code)) {
                recording.markRecoverable("Stopped DASH merge failed.");
                storage.upsertRecording(recording);
                log(LogItem.LEVEL_WARNING, LogItem.SOURCE_RECORDER, channel, "Stopped DASH merge failed.", describeReturnCode(code));
                return false;
            }

            if (!recording.hasExistingFinalMp4File()) {
                recording.markRecoverable("Stopped DASH merge completed but the final MP4 was not created.");
                storage.upsertRecording(recording);
                return false;
            }

            File finalFile = new File(recording.getFinalMp4Path());
            long mergedBytes = Math.max(0L, finalFile.length());

            if (isSuspiciouslySmallMerge(sourceBytesBeforeMerge, mergedBytes)) {
                String details = "mergedBytes="
                    + mergedBytes
                    + ", sourceBytes="
                    + sourceBytesBeforeMerge
                    + ", sources="
                    + sourceSummary
                    + ", output="
                    + recording.getFinalMp4Path();
                recording.markRecoverable("Stopped DASH merge output is much smaller than its source fragments. " + details);
                storage.upsertRecording(recording);
                log(
                    LogItem.LEVEL_WARNING,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "Stopped DASH merge output is suspiciously small.",
                    details
                );
                broadcastRecordingUpdated("Stopped recording needs review; merged file is smaller than its fragments.");
                return false;
            }

            recording.markCompleted(recording.getFinalMp4Path());
            copyCompletedRecordingToSelectedFolder(recording, channel);
            recording.hideFromDownloading();
            storage.upsertRecording(recording);

            for (File sidecar : sidecars) {
                if (sidecar != null) {
                    safeDelete(sidecar.getAbsolutePath());
                }
            }

            log(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_RECORDER,
                channel,
                "Stopped DASH recording merged.",
                recording.getFinalMp4Path()
            );
            return true;
        } catch (Exception e) {
            recording.markRecoverable("Stopped DASH merge error. " + normalizeErrorMessage(e));
            storage.upsertRecording(recording);
            log(LogItem.LEVEL_ERROR, LogItem.SOURCE_RECORDER, channel, "Stopped DASH merge error.", normalizeErrorMessage(e));
            return false;
        }
    }

    private boolean isSuspiciouslySmallMerge(long sourceBytes, long mergedBytes) {
        if (sourceBytes <= 0L || mergedBytes <= 0L) {
            return false;
        }

        long missingBytes = sourceBytes - mergedBytes;
        return missingBytes > 5L * 1024L * 1024L && mergedBytes * 100L < sourceBytes * 80L;
    }

    private String describeMergeSourceFiles(File videoFile, File audioFile) {
        return describeMergeSourceFile(videoFile) + "; " + describeMergeSourceFile(audioFile);
    }

    private String describeMergeSourceFile(File file) {
        if (file == null) {
            return "null";
        }

        return file.getName() + "=" + Math.max(0L, file.length()) + "B";
    }

    private List<File> findYtDlpDashSidecarFiles(RecordingItem recording) {
        if (recording == null || isBlank(recording.getFinalMp4Path())) {
            return Collections.emptyList();
        }

        File finalFile = new File(recording.getFinalMp4Path());
        String finalName = finalFile.getName();
        int dot = finalName.lastIndexOf('.');
        String baseName = dot > 0 ? finalName.substring(0, dot) : finalName;

        if (isBlank(baseName)) {
            return Collections.emptyList();
        }

        List<File> sidecars = new ArrayList<>();
        Set<String> scannedDirectories = new LinkedHashSet<>();

        collectYtDlpDashSidecars(finalFile.getParentFile(), baseName, scannedDirectories, sidecars);

        for (String segmentPath : recording.getTempSegmentPaths()) {
            if (!isBlank(segmentPath)) {
                collectYtDlpDashSidecars(new File(segmentPath).getParentFile(), baseName, scannedDirectories, sidecars);
            }
        }

        sidecars.sort((left, right) -> Long.compare(Math.max(0L, right.length()), Math.max(0L, left.length())));
        return sidecars;
    }

    private void collectYtDlpDashSidecars(
        File directory,
        String baseName,
        Set<String> scannedDirectories,
        List<File> sidecars
    ) {
        if (directory == null || !directory.exists() || isBlank(baseName) || sidecars == null) {
            return;
        }

        String directoryPath = directory.getAbsolutePath();
        if (scannedDirectories != null && !scannedDirectories.add(directoryPath)) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file == null || !file.isFile() || file.length() <= 0L) {
                continue;
            }

            String name = file.getName();
            if ((name.startsWith(baseName + ".f") || name.startsWith(baseName + ".dash"))
                && isLikelyMediaSidecar(name)) {
                sidecars.add(file);
            }
        }
    }

    private boolean isLikelyMediaSidecar(String fileName) {
        if (isBlank(fileName)) {
            return false;
        }

        String lower = fileName.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".mp4")
            || lower.endsWith(".m4a")
            || lower.endsWith(".webm")
            || lower.endsWith(".mkv")
            || lower.endsWith(".mp4.part")
            || lower.endsWith(".m4a.part")
            || lower.endsWith(".webm.part")
            || lower.endsWith(".mkv.part");
    }

    private File selectDashVideoSidecar(List<File> sidecars) {
        if (sidecars == null || sidecars.isEmpty()) {
            return null;
        }

        for (File file : sidecars) {
            if (file != null && !isAudioDashSidecar(file.getName())) {
                return file;
            }
        }

        return sidecars.get(0);
    }

    private File selectDashAudioSidecar(List<File> sidecars, File videoFile) {
        if (sidecars == null || sidecars.isEmpty()) {
            return null;
        }

        for (File file : sidecars) {
            if (file != null && !file.equals(videoFile) && isAudioDashSidecar(file.getName())) {
                return file;
            }
        }

        for (File file : sidecars) {
            if (file != null && !file.equals(videoFile)) {
                return file;
            }
        }

        return null;
    }

    private boolean isAudioDashSidecar(String fileName) {
        if (isBlank(fileName)) {
            return false;
        }

        String lower = fileName.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".m4a")
            || lower.endsWith(".m4a.part")
            || lower.endsWith(".opus")
            || lower.endsWith(".opus.part")
            || lower.contains(".f139.")
            || lower.contains(".f140.")
            || lower.contains(".f141.")
            || lower.contains(".f249.")
            || lower.contains(".f250.")
            || lower.contains(".f251.")
            || lower.contains(".f599.")
            || lower.contains(".f600.");
    }

    private long estimateDashMergeRequiredBytes(List<File> sidecars) {
        long totalBytes = 0L;

        if (sidecars != null) {
            for (File file : sidecars) {
                if (file != null && file.exists()) {
                    totalBytes += Math.max(0L, file.length());
                }
            }
        }

        return Math.max(MIN_FREE_BYTES_BEFORE_CONVERSION, totalBytes + MIN_FREE_BYTES_BEFORE_CONVERSION);
    }

    private void waitForRecordingFileAfterCancellation(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        long deadline = System.currentTimeMillis() + 12_000L;

        while (System.currentTimeMillis() < deadline) {
            RecordingItem latest = storage.findRecordingById(recording.getId());
            RecordingItem candidate = latest == null ? recording : latest;

            if (!candidate.getBestPlayablePath().trim().isEmpty()
                || findYtDlpDashSidecarFiles(candidate).size() >= 2) {
                return;
            }

            sleep(150L);
        }
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

            Long cooldownUntil = channelRateLimitCooldownUntil.get(channelId);

            if (cooldownUntil != null && cooldownUntil > System.currentTimeMillis()) {
                long remainingMillis = cooldownUntil - System.currentTimeMillis();
                channel.markRetrying("YouTube returned HTTP 429; cooling down before the next check.");
                storage.upsertChannel(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
                broadcastChannelUpdated("Rate-limit cooldown active.");
                sleep(Math.min(remainingMillis, settings.getPollIntervalMillis()));
                continue;
            }

            channelRateLimitCooldownUntil.remove(channelId);

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

                ChannelItem latestBeforeRecording = storage.findChannelById(channelId);
                if (latestBeforeRecording == null || !latestBeforeRecording.shouldMonitor() || !activeLoops.containsKey(channelId)) {
                    activeLoops.remove(channelId);
                    break;
                }

                latestBeforeRecording.markLiveDetected(liveInfo.videoId, liveInfo.videoUrl);
                storage.upsertChannel(latestBeforeRecording);
                notificationHelper.showLiveDetectedNotification(latestBeforeRecording);
                notificationHelper.showChannelMonitoringNotification(latestBeforeRecording);
                broadcastChannelUpdated("Live detected.");
                startRecording(latestBeforeRecording, liveInfo);
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

        ChannelItem latestChannel = storage.findChannelById(channel.getId());
        if (latestChannel == null || !latestChannel.shouldMonitor() || !activeLoops.containsKey(latestChannel.getId())) {
            if (latestChannel != null) {
                log(
                    LogItem.LEVEL_INFO,
                    LogItem.SOURCE_SERVICE,
                    latestChannel,
                    "Recording start skipped because channel monitoring is paused or stopped.",
                    "videoId=" + liveInfo.videoId
                );
            }
            return;
        }

        channel = latestChannel;

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
        if (liveInfo != null && liveInfo.streamStartedAt > 0L) {
            recording.setStreamStartedAt(liveInfo.streamStartedAt);
        }
        recording.setDiagnosticMessage("Recording journal opened; waiting for manifest.");
        storage.upsertRecording(recording);
        final String recordingChannelId = channel.getId();
        activeRecordings.put(recordingChannelId, recording);
        progressTracker.track(recording);

        channel.markRecording(liveInfo.videoId, liveInfo.videoUrl);
        storage.upsertChannel(channel);
        notificationHelper.showChannelMonitoringNotification(channel);
        broadcastChannelUpdated("Recording moved to Downloading.");
        broadcastRecordingUpdated("Recording added to Downloading.");

        executor.execute(() -> runRecording(recordingChannelId, recording, liveInfo));
    }

    private void runRecording(String channelId, RecordingItem recording, LiveInfo liveInfo) {
        ChannelItem channel = storage.findChannelById(channelId);

        try {
            String videoId = liveInfo == null ? "" : liveInfo.videoId;

            YtDlpPrimaryRecorderDecision primaryRecorderDecision = evaluateYtDlpPrimaryRecorder(recording);
            logYtDlpPrimaryRecorderDecision(channel, recording, primaryRecorderDecision);

            if (primaryRecorderDecision.shouldTry()) {
                boolean ytDlpHandledRecording = tryYtDlpPrimaryRecording(
                    channelId,
                    channel,
                    recording,
                    liveInfo,
                    primaryRecorderDecision
                );

                if (ytDlpHandledRecording) {
                    return;
                }
            }

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

            if (shouldStopRecorderAfterUserRequest(channelId, channel, recording)) {
                return;
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

            String ffmpegOutputPath = prepareFreshFfmpegOutputPath(recording);

            startFfmpegKitRecording(
                channelId,
                channel,
                recording,
                manifestUrl,
                ffmpegOutputPath,
                false,
                "Recording started."
            );
        } catch (Exception e) {
            String errorMessage = normalizeErrorMessage(e);

            restartingRecordings.remove(recording.getId());
            activeRecordings.remove(channelId);
            activeRecordings.remove(recording.getChannelId());
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

            if (isHttp429Error(errorMessage)) {
                startHttp429Cooldown(latest, errorMessage);
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

    private String prepareFreshFfmpegOutputPath(RecordingItem recording) {
        if (recording == null) {
            return "";
        }

        if (!currentRecordingSegmentHasData(recording)) {
            return recording.getCurrentTempSegmentPath();
        }

        String chunkPath = createResumeChunkPath(recording);
        recording.addTempChunkPath(chunkPath);
        storage.upsertRecording(recording);
        return chunkPath;
    }

    private YtDlpPrimaryRecorderDecision evaluateYtDlpPrimaryRecorder(RecordingItem recording) {
        if (recording == null) {
            return YtDlpPrimaryRecorderDecision.skip("recording object is missing.");
        }

        if (settings == null) {
            return YtDlpPrimaryRecorderDecision.skip("app settings are unavailable.");
        }

        if (!settings.isLiveFromStartEnabled()) {
            return YtDlpPrimaryRecorderDecision.skip("live-from-start setting is disabled.");
        }

        if (recording.hasExistingTempTsFile()) {
            return YtDlpPrimaryRecorderDecision.skip(
                "existing temp TS data is present; primary yt-dlp recorder will not overwrite or resume it."
            );
        }

        if (canRunYtDlpProcessRecorder()) {
            return YtDlpPrimaryRecorderDecision.tryRecorder(
                YtDlpPrimaryRecorderDecision.RECORDER_DIRECT_EXECUTABLE,
                "direct yt-dlp executable is available."
            );
        }

        if (youtubedlAndroidReady) {
            return YtDlpPrimaryRecorderDecision.tryRecorder(
                YtDlpPrimaryRecorderDecision.RECORDER_YOUTUBEDL_ANDROID,
                "bundled youtubedl-android runtime is available for recording."
            );
        }

        return YtDlpPrimaryRecorderDecision.skip(
            "no direct executable yt-dlp path and bundled youtubedl-android runtime is not ready. "
                + describeYtDlpProcessRecorderAvailability()
        );
    }

    private boolean shouldTryYtDlpPrimaryRecording(RecordingItem recording) {
        return evaluateYtDlpPrimaryRecorder(recording).shouldTry();
    }

    private void logYtDlpPrimaryRecorderDecision(
        ChannelItem channel,
        RecordingItem recording,
        YtDlpPrimaryRecorderDecision decision
    ) {
        if (decision == null) {
            decision = YtDlpPrimaryRecorderDecision.skip("primary recorder decision was unavailable.");
        }

        String details = "recordingId="
            + (recording == null ? "" : recording.getId())
            + ", liveFromStart="
            + (settings != null && settings.isLiveFromStartEnabled())
            + ", hasExistingTempTs="
            + (recording != null && recording.hasExistingTempTsFile())
            + ", directExecutable="
            + describeYtDlpProcessRecorderAvailability()
            + ", youtubedlAndroidReady="
            + youtubedlAndroidReady
            + ", decision="
            + (decision.shouldTry() ? "try" : "skip")
            + ", primaryRecorder="
            + decision.getRecorderName()
            + ", reason="
            + decision.getReason();

        log(
            decision.shouldTry() ? LogItem.LEVEL_INFO : LogItem.LEVEL_WARNING,
            LogItem.SOURCE_RECORDER,
            channel,
            decision.shouldTry()
                ? "yt-dlp primary recorder is eligible."
                : "yt-dlp primary recorder skipped.",
            details
        );
    }


    private boolean canRunYtDlpProcessRecorder() {
        if (remoteConfig == null) {
            return false;
        }

        String executable = remoteConfig.getYtDlpExecutable();

        if (isBlank(executable)) {
            return false;
        }

        File executableFile = new File(executable);
        return executableFile.isAbsolute()
            && executableFile.exists()
            && executableFile.isFile()
            && executableFile.canExecute();
    }

    private String describeYtDlpProcessRecorderAvailability() {
        if (remoteConfig == null) {
            return "remoteConfig=missing";
        }

        String executable = remoteConfig.getYtDlpExecutable();

        if (isBlank(executable)) {
            return "path=empty";
        }

        File executableFile = new File(executable);
        return "path="
            + executable
            + ", absolute="
            + executableFile.isAbsolute()
            + ", exists="
            + executableFile.exists()
            + ", file="
            + executableFile.isFile()
            + ", executable="
            + executableFile.canExecute();
    }

    private boolean tryYtDlpPrimaryRecording(
        String channelId,
        ChannelItem channel,
        RecordingItem recording,
        LiveInfo liveInfo,
        YtDlpPrimaryRecorderDecision primaryRecorderDecision
    ) {
        String resolvedVideoUrl = liveInfo != null && !isBlank(liveInfo.videoUrl)
            ? liveInfo.videoUrl
            : recording.getVideoUrl();

        if (isBlank(resolvedVideoUrl)) {
            resolvedVideoUrl = YouTubeUrlUtils.buildWatchUrl(recording.getVideoId());
        }

        String videoUrl = buildPrimaryRecorderInputUrl(channel, resolvedVideoUrl);

        if (isBlank(videoUrl)) {
            return false;
        }

        if (YtDlpPrimaryRecorderDecision.RECORDER_YOUTUBEDL_ANDROID.equals(
            primaryRecorderDecision.getRecorderName()
        )) {
            updateYoutubedlAndroidRuntimeIfNeeded(
                "before primary yt-dlp recording",
                "Updating bundled yt-dlp before primary recorder starts.",
                channel
            );
        }

        RecorderCommandBuilder builder = new RecorderCommandBuilder();
        List<YtDlpResolveAttempt> attempts = buildYtDlpPrimaryRecordAttempts(
            builder,
            videoUrl,
            recording.getCurrentTempSegmentPath(),
            recording.getFinalMp4Path(),
            fileManager.getTempDirectory().getAbsolutePath(),
            settings,
            remoteConfig,
            false
        );
        String lastFailureReason = "yt-dlp recorder did not run.";

        for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
            if (shouldStopRecorderAfterUserRequest(channelId, channel, recording)) {
                return true;
            }

            YtDlpResolveAttempt attempt = attempts.get(attemptIndex);
            String validationError = validateYtDlpRecordingOutputPath(attempt.args);
            if (!isBlank(validationError)) {
                lastFailureReason = validationError + " (" + attempt.describe() + ")";
                log(
                    LogItem.LEVEL_ERROR,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "Refusing to start yt-dlp recorder with unsafe output path.",
                    lastFailureReason
                );
                return startFfmpegFallbackAfterYtDlpFailure(channelId, channel, recording, liveInfo, lastFailureReason);
            }

            String ytDlpRecorderMode = attempt.allowLiveFromStart
                ? "live-from-start"
                : "live-edge";
            recording.setDiagnosticMessage(
                attempts.size() > 1
                    ? "yt-dlp "
                        + ytDlpRecorderMode
                        + " recorder is starting (attempt "
                        + (attemptIndex + 1)
                        + " of "
                        + attempts.size()
                        + ")."
                    : "yt-dlp " + ytDlpRecorderMode + " recorder is starting."
            );
            storage.upsertRecording(recording);
            broadcastRecordingUpdated(attempt.allowLiveFromStart
                ? "yt-dlp recorder starting from DVR beginning."
                : "yt-dlp recorder starting from live edge.");

            log(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_RECORDER,
                channel,
                "Starting yt-dlp primary recorder.",
                "videoId="
                    + recording.getVideoId()
                    + ", primaryRecorder="
                    + primaryRecorderDecision.getRecorderName()
                    + ", attempt="
                    + (attemptIndex + 1)
                    + "/"
                    + attempts.size()
                    + ", playerClient="
                    + attempt.playerClient
                    + ", "
                    + attempt.describe()
                    + ", retries=infinite, command="
                    + shortenForLog(builder.toLogString(attempt.args), 500)
            );

            try {
                recording.setDiagnosticMessage("yt-dlp " + ytDlpRecorderMode + " recorder is running.");
                storage.upsertRecording(recording);
                broadcastRecordingUpdated(attempt.allowLiveFromStart
                    ? "yt-dlp recorder is running from DVR beginning."
                    : "yt-dlp recorder is running from live edge.");

                int exitCode = YtDlpPrimaryRecorderDecision.RECORDER_YOUTUBEDL_ANDROID.equals(
                    primaryRecorderDecision.getRecorderName()
                )
                    ? recordLiveStreamWithYoutubedlAndroid(recording.getId(), videoUrl, attempt.args, channel)
                    : YtDlpRunner.recordLiveStream(
                        recording.getId(),
                        attempt.args,
                        recording.getCurrentTempSegmentPath(),
                        message -> log(
                            isReadOnlyFilesystemError(message) ? LogItem.LEVEL_ERROR : LogItem.LEVEL_DEBUG,
                            LogItem.SOURCE_RECORDER,
                            channel,
                            isReadOnlyFilesystemError(message)
                                ? "yt-dlp recorder filesystem write failure."
                                : "yt-dlp recorder output.",
                            shortenForLog(message, 500)
                        )
                    );

                RecordingItem latest = storage.findRecordingById(recording.getId());

                if (latest != null) {
                    recording = latest;
                }

                if (RecordingItem.STATUS_PAUSED_BY_USER.equals(recording.getStatus())) {
                    storage.upsertRecording(recording);
                    broadcastRecordingUpdated("Recording paused.");
                    return true;
                }

                if (RecordingItem.STATUS_STOPPED_BY_USER.equals(recording.getStatus())) {
                    storage.upsertRecording(recording);
                    broadcastRecordingUpdated("Recording stopped.");
                    return true;
                }

                if (exitCode == 0) {
                    if (recordingHasAnyOutputData(recording)) {
                        saveLastWorkingPlayerClient(attempt, channel);
                    }
                    log(
                        LogItem.LEVEL_SUCCESS,
                        LogItem.SOURCE_RECORDER,
                        channel,
                        "yt-dlp primary recorder completed.",
                        "recordingId=" + recording.getId() + ", " + attempt.describe()
                    );
                    onRecordingFinished(channelId, recording.getId(), 0);
                    return true;
                }

                lastFailureReason = addYtDlpAccessGuidance(
                    "yt-dlp recorder exited with code "
                        + exitCode
                        + " ("
                        + attempt.describe()
                        + ")"
                );

                if (isClientLevelFailure(lastFailureReason)
                    && attemptIndex + 1 < attempts.size()
                    && !recordingHasAnyOutputData(recording)) {
                    logClientLevelFailure(channel, recording, attempt, lastFailureReason);
                    safeDelete(recording.getCurrentTempSegmentPath());
                    attemptIndex = skipRemainingAttemptsForPlayerClient(attempts, attemptIndex, attempt.playerClient);
                    continue;
                }

                if (isHttp429Error(lastFailureReason)) {
                    return stopRecordingForHttp429Cooldown(channelId, channel, recording, lastFailureReason);
                }
            } catch (Exception e) {
                String errorMessage = normalizeErrorMessage(e);

                if (isReadOnlyFilesystemError(errorMessage)) {
                    return handleReadOnlyFilesystemRecordingFailure(channelId, channel, recording, errorMessage);
                }

                if (isLiveNotReadyError(errorMessage)) {
                    return handleYtDlpPrimaryLiveNotReady(channelId, channel, recording, errorMessage);
                }

                lastFailureReason = addYtDlpAccessGuidance(errorMessage + " (" + attempt.describe() + ")");

                if (isClientLevelFailure(lastFailureReason)
                    && attemptIndex + 1 < attempts.size()
                    && !recordingHasAnyOutputData(recording)) {
                    logClientLevelFailure(channel, recording, attempt, lastFailureReason);
                    safeDelete(recording.getCurrentTempSegmentPath());
                    attemptIndex = skipRemainingAttemptsForPlayerClient(attempts, attemptIndex, attempt.playerClient);
                    continue;
                }

                if (isHttp429Error(lastFailureReason)) {
                    return stopRecordingForHttp429Cooldown(channelId, channel, recording, lastFailureReason);
                }
            }

            RecordingItem latestAfterAttempt = storage.findRecordingById(recording.getId());
            if (latestAfterAttempt != null) {
                recording = latestAfterAttempt;
            }

            if (shouldStopRecorderAfterUserRequest(channelId, channel, recording)) {
                return true;
            }

            if (attemptIndex + 1 < attempts.size() && !recordingHasAnyOutputData(recording)) {
                log(
                    LogItem.LEVEL_WARNING,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "yt-dlp primary recorder attempt failed before writing data; trying next yt-dlp recorder attempt.",
                    "recordingId="
                        + recording.getId()
                        + ", "
                        + attempt.describe()
                        + ", reason="
                        + shortenForLog(lastFailureReason, 300)
                );
                safeDelete(recording.getCurrentTempSegmentPath());
                continue;
            }

            return startFfmpegFallbackAfterYtDlpFailure(
                channelId,
                channel,
                recording,
                liveInfo,
                lastFailureReason
            );
        }

        return startFfmpegFallbackAfterYtDlpFailure(
            channelId,
            channel,
            recording,
            liveInfo,
            lastFailureReason
        );
    }

    private void saveLastWorkingPlayerClient(YtDlpResolveAttempt attempt, ChannelItem channel) {
        if (attempt == null || isBlank(attempt.playerClient) || storage == null) {
            return;
        }

        storage.setLastWorkingPlayerClient(attempt.playerClient);
        log(
            LogItem.LEVEL_SUCCESS,
            LogItem.SOURCE_RECORDER,
            channel,
            "yt-dlp player client succeeded; saved as last-working client.",
            "playerClient=" + attempt.playerClient
        );
    }

    private int skipRemainingAttemptsForPlayerClient(
        List<YtDlpResolveAttempt> attempts,
        int currentIndex,
        String playerClient
    ) {
        if (attempts == null || isBlank(playerClient)) {
            return currentIndex;
        }

        int index = currentIndex;

        while (index + 1 < attempts.size()
            && playerClient.equals(attempts.get(index + 1).playerClient)) {
            index++;
        }

        return index;
    }

    private void logClientLevelFailure(
        ChannelItem channel,
        RecordingItem recording,
        YtDlpResolveAttempt attempt,
        String reason
    ) {
        log(
            LogItem.LEVEL_WARNING,
            LogItem.SOURCE_RECORDER,
            channel,
            "yt-dlp player client failed before writing data; trying next player client.",
            "recordingId="
                + (recording == null ? "" : recording.getId())
                + ", playerClient="
                + (attempt == null ? "" : attempt.playerClient)
                + ", reason="
                + shortenForLog(reason, 300)
        );
    }

    private boolean handleReadOnlyFilesystemRecordingFailure(
        String channelId,
        ChannelItem channel,
        RecordingItem recording,
        String reason
    ) {
        String writeCheck = verifyRecordingDirectoryWritable();
        String details = "recordingId="
            + (recording == null ? "" : recording.getId())
            + ", reason="
            + reason
            + ", writeCheck="
            + writeCheck;

        log(
            LogItem.LEVEL_ERROR,
            LogItem.SOURCE_RECORDER,
            channel,
            "Recording directory became read-only or yt-dlp output path was malformed.",
            details
        );

        if (recording != null) {
            recording.markRecoverable("Recorder filesystem write failed. " + reason + " " + writeCheck);
            storage.upsertRecording(recording);

            if (recordingHasAnyOutputData(recording)) {
                activeRecordings.remove(recording.getId());
                activeRecordings.remove(recording.getChannelId());
                if (!isBlank(channelId)) {
                    activeRecordings.remove(channelId);
                }
                progressTracker.untrack(recording);
                cancelActiveRecording(recording, "read-only filesystem failure");
                saveStoppedRecordingForDownloads(recording, channel);
                broadcastRecordingUpdated("Existing recording data was salvaged after a filesystem write failure.");
                return true;
            }
        }

        broadcastRecordingUpdated("Recorder filesystem write failed; recording is recoverable.");
        return false;
    }

    private String validateYtDlpRecordingOutputPath(List<String> args) {
        String outputTemplate = findYtDlpOptionValue(args, "-o");
        if (isBlank(outputTemplate)) {
            return "yt-dlp output template is empty.";
        }

        try {
            File outputFile = new File(outputTemplate);
            if (!outputFile.isAbsolute()) {
                return "yt-dlp output template must be absolute: " + outputTemplate;
            }

            File baseDir = fileManager == null ? null : fileManager.getBaseRecordingDirectory();
            if (baseDir == null) {
                return "recordings base directory is unavailable.";
            }

            String outputPath = outputFile.getCanonicalPath();
            String basePath = baseDir.getCanonicalPath();
            if (!outputPath.equals(basePath) && !outputPath.startsWith(basePath + File.separator)) {
                return "yt-dlp output template is outside recordings directory: " + outputPath;
            }
        } catch (Exception e) {
            return "yt-dlp output template validation failed: " + normalizeErrorMessage(e);
        }

        return "";
    }

    private String verifyRecordingDirectoryWritable() {
        try {
            File directory = fileManager == null ? null : fileManager.getTempDirectory();
            if (directory == null) {
                return "temp directory unavailable";
            }
            if (!directory.exists() && !directory.mkdirs()) {
                return "temp directory could not be created: " + directory.getAbsolutePath();
            }
            File probe = File.createTempFile("write-test", ".tmp", directory);
            safeDelete(probe.getAbsolutePath());
            return "writable=" + directory.getAbsolutePath();
        } catch (Exception e) {
            return "not writable: " + normalizeErrorMessage(e);
        }
    }

    private boolean isReadOnlyFilesystemError(String message) {
        if (isBlank(message)) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.US);
        return lower.contains("read-only file system") || lower.contains("errno 30");
    }

    private boolean isClientLevelFailure(String output) {
        if (output == null) {
            return false;
        }

        String normalized = output.toLowerCase(java.util.Locale.US);
        return normalized.contains("no video formats found")
            || normalized.contains("http error 429")
            || normalized.contains("sign in to confirm you")
            || normalized.contains("skipping client");
    }

    private boolean shouldStopRecorderAfterUserRequest(
        String channelId,
        ChannelItem channel,
        RecordingItem recording
    ) {
        if (recording == null) {
            return true;
        }

        RecordingItem latest = storage.findRecordingById(recording.getId());
        if (latest != null) {
            recording = latest;
        }

        String status = recording.getStatus();
        boolean stoppedByUser = RecordingItem.STATUS_PAUSED_BY_USER.equals(status)
            || RecordingItem.STATUS_STOPPED_BY_USER.equals(status)
            || RecordingItem.STATUS_COMPLETED.equals(status);

        if (!stoppedByUser) {
            return false;
        }

        activeRecordings.remove(recording.getId());
        activeRecordings.remove(recording.getChannelId());
        if (!isBlank(channelId)) {
            activeRecordings.remove(channelId);
        }
        progressTracker.untrack(recording);

        if (channel != null) {
            notificationHelper.showChannelMonitoringNotification(channel);
        }

        String message = RecordingItem.STATUS_PAUSED_BY_USER.equals(status)
            ? "Recording paused."
            : "Recording stopped.";

        log(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_RECORDER,
            channel,
            "Stopping recorder attempts after user request.",
            "recordingId=" + recording.getId() + ", status=" + status
        );
        broadcastRecordingUpdated(message);
        return true;
    }

    private String buildPrimaryRecorderInputUrl(ChannelItem channel, String resolvedVideoUrl) {
        String channelLiveUrl = buildChannelLiveUrl(channel == null ? "" : channel.getUrl());
        if (!isBlank(channelLiveUrl)) {
            return channelLiveUrl;
        }
        return resolvedVideoUrl;
    }

    private String buildChannelLiveUrl(String channelUrl) {
        if (isBlank(channelUrl) || YouTubeUrlUtils.isDirectVideoUrl(channelUrl)) {
            return "";
        }

        String trimmed = channelUrl.trim().replaceAll("/+$", "");
        if (trimmed.endsWith("/live")) {
            return trimmed;
        }
        return trimmed + "/live";
    }

    private List<YtDlpResolveAttempt> buildYtDlpPrimaryRecordAttempts(
        RecorderCommandBuilder builder,
        String videoUrl,
        String outputPath,
        String finalMp4OutputPath,
        String tempDirectoryPath,
        AppSettings appSettings,
        RemoteConfig config,
        boolean allowWaitForVideo
    ) {
        List<YtDlpResolveAttempt> attempts = new ArrayList<>();
        boolean retryWithoutLiveFromStart = appSettings != null && appSettings.isLiveFromStartEnabled();

        if (isBlank(finalMp4OutputPath)) {
            return attempts;
        }

        List<String> playerClients = buildPlayerClientAttemptOrder(config);

        for (String playerClient : playerClients) {
            attempts.add(buildDashPrimaryRecordAttempt(
                builder,
                playerClient,
                videoUrl,
                finalMp4OutputPath,
                tempDirectoryPath,
                appSettings,
                config,
                true,
                allowWaitForVideo
            ));

            if (retryWithoutLiveFromStart) {
                attempts.add(buildDashPrimaryRecordAttempt(
                    builder,
                    playerClient,
                    videoUrl,
                    finalMp4OutputPath,
                    tempDirectoryPath,
                    appSettings,
                    config,
                    false,
                    allowWaitForVideo
                ));
            }
        }

        return attempts;
    }

    private List<String> buildPlayerClientAttemptOrder(RemoteConfig config) {
        List<String> clients = new ArrayList<>();

        /*
         * Try yt-dlp's own YouTube client auto-selection before forcing android_vr.
         * Termux succeeds this way more often, and it lets yt-dlp attach its current
         * default request identity/headers instead of our explicit extractor arg.
         */
        addUniquePlayerClient(clients, "auto");
        addUniquePlayerClient(clients, storage == null ? "" : storage.getLastWorkingPlayerClient());

        List<String> configuredClients = config == null
            ? new RemoteConfig().getYtDlpPlayerClientFallback()
            : config.getYtDlpPlayerClientFallback();

        for (String client : configuredClients) {
            addUniquePlayerClient(clients, client);
        }

        if (clients.isEmpty()) {
            addUniquePlayerClient(clients, "android_vr");
        }

        return clients;
    }

    private void addUniquePlayerClient(List<String> clients, String client) {
        String normalized = normalizePlayerClient(client);

        if (isBlank(normalized) || clients.contains(normalized)) {
            return;
        }

        clients.add(normalized);
    }

    private String normalizePlayerClient(String client) {
        return isBlank(client) ? "" : client.trim().toLowerCase(java.util.Locale.US);
    }

    private YtDlpResolveAttempt buildDashPrimaryRecordAttempt(
        RecorderCommandBuilder builder,
        String playerClient,
        String videoUrl,
        String outputPath,
        String tempDirectoryPath,
        AppSettings appSettings,
        RemoteConfig config,
        boolean allowLiveFromStart,
        boolean allowWaitForVideo
    ) {
        String normalizedClient = normalizePlayerClient(playerClient);
        boolean autoClient = "auto".equals(normalizedClient);
        boolean mwebPoToken = "mweb".equals(normalizedClient)
            && appSettings != null
            && appSettings.hasYtDlpCookies()
            && appSettings.hasYtDlpPoToken();
        String extractorArgs = autoClient
            ? RecorderCommandBuilder.EXTRACTOR_ARGS_NONE
            : mwebPoToken
                ? "youtube:player_client=mweb;po_token=mweb.gvs+<redacted>;player-skip=webpage,configs"
                : "youtube:player_client=" + normalizedClient;
        String description = (autoClient
                ? "youtube:player_client=auto, no explicit extractor args"
                : mwebPoToken
                    ? "youtube:player_client=mweb, poTokenWithCookies=true, playerSkip=webpage,configs"
                    : "youtube:player_client=" + normalizedClient + ", noPoToken=true")
            + ", format=bv*[height<=480]+ba/b DASH"
            + (appSettings != null && appSettings.isLiveFromStartEnabled()
                ? ", liveFromStart=" + allowLiveFromStart
                : "");

        return new YtDlpResolveAttempt(
            builder.buildDashRecordArgs(
                normalizedClient,
                videoUrl,
                outputPath,
                tempDirectoryPath,
                appSettings,
                config,
                allowLiveFromStart,
                allowWaitForVideo
            ),
            extractorArgs,
            allowLiveFromStart,
            description,
            normalizedClient
        );
    }

    private YtDlpResolveAttempt buildYtDlpPrimaryRecordAttempt(
        RecorderCommandBuilder builder,
        String videoUrl,
        String outputPath,
        String unusedTempDirectoryPath,
        AppSettings appSettings,
        RemoteConfig config,
        String extractorArg,
        boolean allowLiveFromStart,
        boolean allowWaitForVideo
    ) {
        return buildYtDlpPrimaryRecordAttempt(
            builder,
            videoUrl,
            outputPath,
            appSettings,
            config,
            extractorArg,
            allowLiveFromStart,
            allowWaitForVideo
        );
    }

    private YtDlpResolveAttempt buildYtDlpPrimaryRecordAttempt(
        RecorderCommandBuilder builder,
        String videoUrl,
        String outputPath,
        AppSettings appSettings,
        RemoteConfig config,
        String extractorArg,
        boolean allowLiveFromStart,
        boolean allowWaitForVideo
    ) {
        return new YtDlpResolveAttempt(
            builder.buildYtDlpRecordArgs(
                videoUrl,
                outputPath,
                appSettings,
                config,
                extractorArg,
                allowLiveFromStart,
                allowWaitForVideo
            ),
            extractorArg,
            allowLiveFromStart,
            buildYtDlpExtractorAttemptDescription(extractorArg, allowLiveFromStart),
            extractPlayerClientFromExtractorArgs(extractorArg)
        );
    }

    private String extractPlayerClientFromExtractorArgs(String extractorArgs) {
        if (isBlank(extractorArgs)) {
            return "";
        }

        String marker = "player_client=";
        String value = extractorArgs;
        int index = value.indexOf(marker);

        if (index < 0) {
            return "";
        }

        value = value.substring(index + marker.length());
        int semicolon = value.indexOf(';');

        if (semicolon >= 0) {
            value = value.substring(0, semicolon);
        }

        return normalizePlayerClient(value);
    }

    private boolean handleYtDlpPrimaryLiveNotReady(
        String channelId,
        ChannelItem channel,
        RecordingItem recording,
        String errorMessage
    ) {
        restartingRecordings.remove(recording.getId());
        activeRecordings.remove(channelId);
        activeRecordings.remove(recording.getChannelId());
        activeRecordings.remove(recording.getId());
        progressTracker.untrack(recording);
        discardUnstartedRecording(recording);

        ChannelItem latest = channel == null ? storage.findChannelById(channelId) : channel;

        if (latest != null) {
            latest.markWaitingForLive();
            storage.upsertChannel(latest);
            notificationHelper.showChannelMonitoringNotification(latest);
        }

        log(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_RECORDER,
            latest,
            "yt-dlp reported the live event is not active yet; waiting instead of saving an empty file.",
            errorMessage
        );

        broadcastChannelUpdated("Waiting for live.");
        broadcastRecordingUpdated("Live event is not active yet; no empty file was saved.");
        return true;
    }

    private int recordLiveStreamWithYoutubedlAndroid(
        String recordingId,
        String videoUrl,
        List<String> args,
        ChannelItem channel
    ) throws Exception {
        if (!youtubedlAndroidReady) {
            throw new IllegalStateException("youtubedl-android recorder is not ready.");
        }

        cleanYtDlpTempFragments(args);

        YoutubeDLRequest request = buildYoutubedlAndroidRequest(videoUrl, args);
        String processId = isBlank(recordingId) ? "yt-dlp-primary-recorder" : recordingId;

        log(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_RECORDER,
            channel,
            "Starting bundled youtubedl-android primary recorder process.",
            "processId=" + processId + ", diagnostics=" + buildYtDlpRecorderDiagnostics(args)
        );

        Function3<Float, Long, String, Unit> callback = new Function3<Float, Long, String, Unit>() {
            @Override
            public Unit invoke(Float progress, Long etaSeconds, String line) {
                if (!isBlank(line)) {
                    String shortLine = shortenForLog(line, 500);
                    boolean fragmentErrorSignal = isYtDlpFragmentErrorSignal(line);
                    boolean fragmentEndSignal = shouldFinalizeForYtDlpFragmentSignal(processId, line);

                    log(
                        fragmentErrorSignal ? LogItem.LEVEL_WARNING : LogItem.LEVEL_DEBUG,
                        LogItem.SOURCE_RECORDER,
                        channel,
                        fragmentErrorSignal
                            ? "youtubedl-android fragment download signal."
                            : "youtubedl-android recorder output.",
                        shortLine
                    );

                    if (fragmentEndSignal && ytDlpFragmentEndSignals.add(processId)) {
                        executor.execute(() -> finalizeLikelyEndedRecording(
                            processId,
                            "yt-dlp reported fragment download failures: " + shortLine
                        ));
                    }
                }
                return Unit.INSTANCE;
            }
        };

        activeYoutubedlAndroidRecordings.add(processId);
        Thread diagnosticsThread = startYtDlpRecorderDiagnostics(processId, args, channel);

        try {
            executeYoutubedlAndroidRequest(request, processId, callback);
        } finally {
            activeYoutubedlAndroidRecordings.remove(processId);
            ytDlpFragmentEndSignals.remove(processId);
            ytDlpFragmentErrorCounts.remove(processId);
            diagnosticsThread.interrupt();
        }

        return 0;
    }

    private boolean isYtDlpFragmentErrorSignal(String line) {
        if (isBlank(line)) {
            return false;
        }

        String lower = line.toLowerCase(java.util.Locale.US);

        return lower.contains("http error 404")
            || lower.contains("did not get any data blocks")
            || lower.contains("video is no longer live")
            || (lower.contains("retrying fragment") && lower.contains("not found"));
    }

    private boolean shouldFinalizeForYtDlpFragmentSignal(String processId, String line) {
        if (isBlank(processId) || isBlank(line)) {
            return false;
        }

        String lower = line.toLowerCase(java.util.Locale.US);
        if (lower.contains("video is no longer live")) {
            return true;
        }

        if (lower.contains("did not get any data blocks")
            || lower.contains("http error 404")
            || (lower.contains("retrying fragment") && lower.contains("not found"))) {
            int count = ytDlpFragmentErrorCounts.merge(processId, 1, Integer::sum);
            return count >= 3;
        }

        return false;
    }

    private void finalizeLikelyEndedRecording(String recordingId, String reason) {
        if (isBlank(recordingId)) {
            return;
        }

        RecordingItem recording = storage.findRecordingById(recordingId);

        if (recording == null || !recording.isActive()) {
            return;
        }

        String channelId = recording.getChannelId();
        ChannelItem channel = isBlank(channelId) ? null : storage.findChannelById(channelId);

        activeRecordings.remove(recording.getId());
        if (!isBlank(channelId)) {
            activeRecordings.remove(channelId);
        }
        progressTracker.untrack(recording);
        cancelActiveRecording(recording, "finalizeLikelyEndedRecording");
        waitForRecordingFileAfterCancellation(recording);

        RecordingItem latest = storage.findRecordingById(recording.getId());
        RecordingItem toSave = latest == null ? recording : latest;
        toSave.setDiagnosticMessage(reason);
        storage.upsertRecording(toSave);
        boolean saved = saveStoppedRecordingForDownloads(toSave, channel);

        if (channel != null) {
            channel.markRecordingFinished();
            channel.markWaitingForLive();
            storage.upsertChannel(channel);
            notificationHelper.showChannelMonitoringNotification(channel);
        }

        log(
            saved ? LogItem.LEVEL_SUCCESS : LogItem.LEVEL_WARNING,
            LogItem.SOURCE_RECORDER,
            channel,
            saved ? "Likely-ended recording finalized." : "Likely-ended recording stopped without a playable file.",
            "recordingId=" + recordingId + ", reason=" + reason
        );
        broadcastRecordingUpdated(saved ? "Recording ended and was saved." : "Recording ended, but no playable file was found.");
    }

    private void executeYoutubedlAndroidRequest(
        YoutubeDLRequest request,
        String processId,
        Function3<Float, Long, String, Unit> callback
    ) throws Exception {
        YoutubeDL youtubeDL = YoutubeDL.getInstance();

        try {
            youtubeDL.getClass()
                .getMethod("execute", YoutubeDLRequest.class, String.class, Function3.class)
                .invoke(youtubeDL, request, processId, callback);
            return;
        } catch (NoSuchMethodException ignored) {
            // Older youtubedl-android versions used request, callback, processId ordering.
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw unwrapYoutubedlAndroidExecutionError(e);
        }

        try {
            youtubeDL.getClass()
                .getMethod("execute", YoutubeDLRequest.class, Function3.class, String.class)
                .invoke(youtubeDL, request, callback, processId);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw unwrapYoutubedlAndroidExecutionError(e);
        }
    }

    private Exception unwrapYoutubedlAndroidExecutionError(java.lang.reflect.InvocationTargetException error) {
        Throwable cause = error == null ? null : error.getCause();

        if (cause instanceof Exception) {
            return (Exception) cause;
        }

        return new IllegalStateException(
            cause == null ? "youtubedl-android recorder failed." : cause.getMessage(),
            cause
        );
    }

    private YoutubeDLRequest buildYoutubedlAndroidRequest(String videoUrl, List<String> args) {
        YoutubeDLRequest request = new YoutubeDLRequest(videoUrl);

        if (!hasYtDlpOption(args, "--ffmpeg-location")) {
            String ffmpegLocation = getYoutubedlAndroidFfmpegLocation();

            if (!isBlank(ffmpegLocation)) {
                request.addOption("--ffmpeg-location", ffmpegLocation);
            }
        }

        if (args == null) {
            return request;
        }

        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);

            if (videoUrl.equals(arg)) {
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

        return request;
    }

    private void cleanYtDlpTempFragments(List<String> args) {
        String tempPath = findYtDlpPathValue(args, "temp");

        if (isBlank(tempPath)) {
            return;
        }

        try {
            File tempDirectory = new File(tempPath);
            File[] files = tempDirectory.listFiles();

            if (files == null) {
                return;
            }

            for (File file : files) {
                if (file == null || !file.isFile()) {
                    continue;
                }

                String name = file.getName();

                if (name.endsWith(".part")
                    || name.endsWith(".ytdl")
                    || name.endsWith(".ts")
                    || name.endsWith(".m4s")) {
                    safeDelete(file.getAbsolutePath());
                }
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup, matching the Termux script without blocking recording.
        }
    }

    private String findYtDlpPathValue(List<String> args, String pathType) {
        if (args == null || isBlank(pathType)) {
            return "";
        }

        String prefix = pathType + ":";

        for (int i = 0; i < args.size() - 1; i++) {
            if (!"-P".equals(args.get(i)) && !"--paths".equals(args.get(i))) {
                continue;
            }

            String value = args.get(i + 1);

            if (!isBlank(value) && value.startsWith(prefix)) {
                return value.substring(prefix.length()).trim();
            }
        }

        return "";
    }

    private Thread startYtDlpRecorderDiagnostics(
        String processId,
        List<String> args,
        ChannelItem channel
    ) {
        Thread diagnosticsThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()
                && activeYoutubedlAndroidRecordings.contains(processId)) {
                try {
                    Thread.sleep(15_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (!activeYoutubedlAndroidRecordings.contains(processId)) {
                    break;
                }

                if (!isVerboseDebugLoggingEnabled()) {
                    continue;
                }

                log(
                    LogItem.LEVEL_INFO,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "youtubedl-android recorder diagnostics.",
                    "processId=" + processId + ", " + buildYtDlpRecorderDiagnostics(args)
                );
            }
        }, "YtDlpRecorderDiagnostics-" + processId);

        diagnosticsThread.setDaemon(true);
        diagnosticsThread.start();
        return diagnosticsThread;
    }

    private String buildYtDlpRecorderDiagnostics(List<String> args) {
        String homePath = findYtDlpPathValue(args, "home");
        String tempPath = findYtDlpPathValue(args, "temp");
        String outputTemplate = findYtDlpOptionValue(args, "-o");

        if (isBlank(homePath) && !isBlank(outputTemplate)) {
            File outputFile = new File(outputTemplate);
            File parent = outputFile.isAbsolute() ? outputFile.getParentFile() : null;

            if (parent != null) {
                homePath = parent.getAbsolutePath();
            }
        }

        return "home="
            + describeDirectoryForDiagnostics(homePath, outputTemplate)
            + ", temp="
            + describeDirectoryForDiagnostics(tempPath, outputTemplate)
            + ", outputTemplate="
            + outputTemplate
            + ", format="
            + findYtDlpOptionValue(args, "-f")
            + ", extractorArgs="
            + findYtDlpOptionValue(args, "--extractor-args");
    }

    private String findYtDlpOptionValue(List<String> args, String option) {
        if (args == null || isBlank(option)) {
            return "";
        }

        for (int i = 0; i < args.size() - 1; i++) {
            if (option.equals(args.get(i))) {
                String value = args.get(i + 1);
                return value == null ? "" : value;
            }
        }

        return "";
    }

    private String describeDirectoryForDiagnostics(String directoryPath, String outputTemplate) {
        if (isBlank(directoryPath)) {
            return "empty";
        }

        try {
            File directory = new File(directoryPath);
            StringBuilder builder = new StringBuilder();
            builder.append(directoryPath)
                .append("{exists=")
                .append(directory.exists())
                .append(", dir=")
                .append(directory.isDirectory());

            if (!directory.exists() || !directory.isDirectory()) {
                builder.append('}');
                return builder.toString();
            }

            File[] files = directory.listFiles();
            int fileCount = 0;
            long totalBytes = 0L;
            StringBuilder sample = new StringBuilder();
            String outputBase = getOutputTemplateBaseName(outputTemplate);

            if (files != null) {
                for (File file : files) {
                    if (file == null || !file.isFile()) {
                        continue;
                    }

                    long length = Math.max(0L, file.length());
                    String name = file.getName();
                    boolean relevant = isBlank(outputBase) || name.startsWith(outputBase);

                    if (!relevant) {
                        continue;
                    }

                    fileCount++;
                    totalBytes += length;

                    if (sample.length() < 350) {
                        if (sample.length() > 0) {
                            sample.append("; ");
                        }

                        sample.append(name).append('=').append(length).append('B');
                    }
                }
            }

            builder.append(", files=")
                .append(fileCount)
                .append(", bytes=")
                .append(totalBytes)
                .append(", sample=[")
                .append(sample)
                .append("]}");
            return builder.toString();
        } catch (RuntimeException e) {
            return directoryPath + "{error=" + e.getClass().getSimpleName() + "}";
        }
    }

    private String getOutputTemplateBaseName(String outputTemplate) {
        if (isBlank(outputTemplate)) {
            return "";
        }

        String name = new File(outputTemplate).getName();
        int dot = name.lastIndexOf('.');

        if (dot <= 0) {
            return name;
        }

        return name.substring(0, dot);
    }

    private String getYoutubedlAndroidFfmpegLocation() {
        File ffmpegBinDir = new File(
            getNoBackupFilesDir(),
            "youtubedl-android/packages/ffmpeg/usr/bin"
        );

        if (ffmpegBinDir.exists() && ffmpegBinDir.isDirectory()) {
            return ffmpegBinDir.getAbsolutePath();
        }

        File ffmpegExecutable = new File(ffmpegBinDir, "ffmpeg");
        return ffmpegExecutable.getAbsolutePath();
    }

    private boolean hasYtDlpOption(List<String> args, String option) {
        if (args == null || isBlank(option)) {
            return false;
        }

        for (String arg : args) {
            if (option.equals(arg)) {
                return true;
            }
        }

        return false;
    }

    private boolean stopRecordingForHttp429Cooldown(
        String channelId,
        ChannelItem channel,
        RecordingItem recording,
        String reason
    ) {
        if (recording == null) {
            startHttp429Cooldown(channel, reason);
            return true;
        }

        ChannelItem latestChannel = channel;

        if (latestChannel == null && !isBlank(channelId)) {
            latestChannel = storage.findChannelById(channelId);
        }

        if (latestChannel == null && !isBlank(recording.getChannelId())) {
            latestChannel = storage.findChannelById(recording.getChannelId());
        }

        startHttp429Cooldown(latestChannel, reason);
        restartingRecordings.remove(recording.getId());
        activeRecordings.remove(recording.getId());
        activeRecordings.remove(recording.getChannelId());

        if (!isBlank(channelId)) {
            activeRecordings.remove(channelId);
        }

        progressTracker.untrack(recording);
        cancelActiveRecording(recording, "stopRecordingForHttp429Cooldown");

        if (currentRecordingSegmentHasData(recording)) {
            recording.markRecoverable("YouTube HTTP 429 rate limit detected; recording paused during cooldown. " + reason);
            storage.upsertRecording(recording);
        } else {
            discardUnstartedRecording(recording);
        }

        broadcastRecordingUpdated("YouTube rate-limit cooldown active; recorder attempts paused.");
        return true;
    }

    private boolean startFfmpegFallbackAfterYtDlpFailure(
        String channelId,
        ChannelItem channel,
        RecordingItem recording,
        LiveInfo liveInfo,
        String failureReason
    ) {
        RecordingItem latest = storage.findRecordingById(recording.getId());

        if (latest != null) {
            recording = latest;
        }

        if (RecordingItem.STATUS_PAUSED_BY_USER.equals(recording.getStatus())) {
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Recording paused.");
            return true;
        }

        if (RecordingItem.STATUS_STOPPED_BY_USER.equals(recording.getStatus())) {
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Recording stopped.");
            return true;
        }

        try {
            String fallbackOutputPath = recording.getCurrentTempSegmentPath();

            if (currentRecordingSegmentHasData(recording)) {
                fallbackOutputPath = createResumeChunkPath(recording);
                recording.addTempChunkPath(fallbackOutputPath);
            }

            String activeChannelId = isBlank(channelId) ? recording.getChannelId() : channelId;

            recording.markRecording();
            recording.showInDownloading();
            recording.setDiagnosticMessage("yt-dlp live-from-start failed; falling back to FFmpeg live-edge recording.");
            storage.upsertRecording(recording);

            if (!isBlank(activeChannelId)) {
                activeRecordings.put(activeChannelId, recording);
            }

            progressTracker.track(recording);

            String videoId = liveInfo == null ? recording.getVideoId() : liveInfo.videoId;
            ResolvedInput resolvedInput = resolveRecordingInputUrl(videoId, channel, liveInfo, true);
            String manifestUrl = resolvedInput.url;

            if (isBlank(manifestUrl)) {
                throw new IllegalStateException("FFmpeg fallback could not resolve a playable stream URL.");
            }

            if (shouldStopRecorderAfterUserRequest(activeChannelId, channel, recording)) {
                return true;
            }

            log(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_RECORDER,
                channel,
                "yt-dlp primary recorder failed; using FFmpeg fallback.",
                "recordingId="
                    + recording.getId()
                    + ", reason="
                    + shortenForLog(failureReason, 300)
                    + ", fallbackInput="
                    + describeUrlForLog(manifestUrl)
            );

            startFfmpegKitRecording(
                activeChannelId,
                channel,
                recording,
                manifestUrl,
                fallbackOutputPath,
                false,
                "yt-dlp failed; FFmpeg fallback started."
            );

            return true;
        } catch (Exception fallbackError) {
            String combinedError = "yt-dlp recorder failed ("
                + failureReason
                + ") and FFmpeg fallback failed ("
                + normalizeErrorMessage(fallbackError)
                + ").";

            restartingRecordings.remove(recording.getId());
            activeRecordings.remove(channelId);
            activeRecordings.remove(recording.getId());
            progressTracker.untrack(recording);
            recording.markRecoverable(combinedError);
            storage.upsertRecording(recording);

            if (channel != null) {
                channel.markFailed(combinedError);
                storage.upsertChannel(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
            }

            log(
                LogItem.LEVEL_ERROR,
                LogItem.SOURCE_RECORDER,
                channel,
                "yt-dlp primary recorder and FFmpeg fallback failed.",
                combinedError
            );

            broadcastRecordingUpdated("Recording is recoverable; fallback failed.");
            return true;
        }
    }

    private String addYtDlpAccessGuidance(String failureReason) {
        if (!isYoutubeBotProtectionError(failureReason)) {
            return failureReason;
        }

        boolean hasPoToken = settings != null && settings.hasYtDlpPoToken();
        boolean hasConfiguredCookies = (settings != null && settings.hasYtDlpCookies())
            || (remoteConfig != null && remoteConfig.hasYtDlpCookies());

        String guidance;
        if (!hasPoToken) {
            guidance = "YouTube requires a GVS PO token to access live stream formats. "
                + "Open 'YouTube PO Token Setup' in Settings, load a live video page in the WebView, "
                + "then tap 'Generate/Refresh PO token'. The token is extracted automatically when the page loads.";
            notificationHelper.showPoTokenSetupNotification();
        } else if (hasConfiguredCookies) {
            guidance = "YouTube bot/rate-limit challenge detected even though yt-dlp cookies and PO token are configured; "
                + "refresh the PO token via 'YouTube PO Token Setup' in Settings, reduce retry rate, "
                + "or check that your cookies are from a current signed-in browser session.";
        } else {
            guidance = "YouTube bot/rate-limit challenge detected; configure YouTube cookies.txt "
                + "via 'YouTube PO Token Setup' in Settings, or set up a valid GVS PO token. "
                + "The app cannot safely impersonate or bypass YouTube's bot checks without legitimate session data.";
        }

        return failureReason + " " + guidance;
    }

    private void startHttp429Cooldown(ChannelItem channel, String reason) {
        if (channel == null) {
            return;
        }

        channelRateLimitCooldownUntil.put(
            channel.getId(),
            System.currentTimeMillis() + HTTP_429_COOLDOWN_MILLIS
        );
        channel.markRetrying("YouTube HTTP 429 rate limit detected; cooling down for 10 minutes.");
        storage.upsertChannel(channel);
        notificationHelper.showChannelMonitoringNotification(channel);
        broadcastChannelUpdated("Rate-limit cooldown active.");
        log(
            LogItem.LEVEL_WARNING,
            LogItem.SOURCE_SERVICE,
            channel,
            "HTTP 429 cooldown started.",
            "cooldownMillis=" + HTTP_429_COOLDOWN_MILLIS + ", reason=" + reason
        );
    }

    private boolean isHttp429Error(String message) {
        if (isBlank(message)) {
            return false;
        }

        String lower = message.toLowerCase(java.util.Locale.US);
        return lower.contains("http 429")
            || lower.contains("http error 429")
            || lower.contains("too many requests");
    }

    private boolean isYoutubeBotProtectionError(String message) {
        if (isBlank(message)) {
            return false;
        }

        String lower = message.toLowerCase(java.util.Locale.US);

        return lower.contains("sign in to confirm")
            || lower.contains("not a bot")
            || lower.contains("http error 429")
            || lower.contains("too many requests")
            || lower.contains("po token")
            || lower.contains("po_token")
            || lower.contains("gvs po")
            || lower.contains("precondition check failed")
            || lower.contains("token required");
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
            boolean recorderProcessRunning = FFmpegRunner.isRunning(recordingId)
                || YtDlpRunner.isRecording(recordingId)
                || activeYoutubedlAndroidRecordings.contains(recordingId);

            if (!networkAvailable) {
                stalledRecording.markPausedNetwork("Network unavailable; keeping recorded files until internet returns.");
                storage.upsertRecording(stalledRecording);
                activeRecordings.remove(stalledRecording.getId());
                activeRecordings.remove(channelId);
                progressTracker.untrack(stalledRecording);
                broadcastRecordingUpdated("Recording paused until network returns.");
                return;
            }

            String resolvedChannelId = resolveChannelId(channel.getUrl());
            LiveInfo liveInfo = resolvedChannelId == null ? null : checkLive(resolvedChannelId);

            if (recorderProcessRunning && liveInfo != null && stalledRecording.matchesVideo(liveInfo.videoId)) {
                finalizeLikelyEndedRecording(
                    recordingId,
                    "No recorder file growth for the stall threshold even though /live still reports the same video."
                );
                return;
            }

            if (liveInfo == null) {
                activeRecordings.remove(stalledRecording.getId());
                activeRecordings.remove(channelId);
                progressTracker.untrack(stalledRecording);
                cancelRequested = cancelActiveRecording(stalledRecording, "recoverStalledRecording live missing");
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
                cancelRequested = cancelActiveRecording(stalledRecording, "recoverStalledRecording live video changed");
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

            if (shouldStopRecorderAfterUserRequest(channelId, channel, recording)) {
                return;
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

            startFfmpegKitRecording(
                channelId,
                channel,
                recording,
                manifestUrl,
                chunkPath,
                false,
                "Recording stalled; resume recorder started."
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
        return cancelActiveRecording(recording, "unspecified caller");
    }

    private boolean cancelActiveRecording(RecordingItem recording, String trigger) {
        if (recording == null) {
            return false;
        }

        log(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_RECORDER,
            null,
            "Requesting active recording cancellation.",
            "recordingId=" + recording.getId() + ", trigger=" + trigger
        );

        boolean cancelled = FFmpegRunner.cancel(recording.getId());
        cancelled = YtDlpRunner.cancelRecording(recording.getId()) || cancelled;

        cancelled = cancelYoutubedlAndroidRecording(recording.getId()) || cancelled;

        if (cancelled) {
            log(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_RECORDER,
                null,
                "Active recording cancellation requested.",
                "recordingId=" + recording.getId() + ", trigger=" + trigger + ", title=" + recording.getDisplayTitle()
            );
        }

        return cancelled;
    }

    private boolean cancelYoutubedlAndroidRecording(String recordingId) {
        if (!youtubedlAndroidReady || isBlank(recordingId)) {
            return false;
        }

        boolean cancelled = destroyYoutubedlAndroidProcess(recordingId);
        for (String activeProcessId : new ArrayList<>(activeYoutubedlAndroidRecordings)) {
            if (!isBlank(activeProcessId)
                && (activeProcessId.equals(recordingId) || activeProcessId.startsWith(recordingId + "-"))) {
                cancelled = destroyYoutubedlAndroidProcess(activeProcessId) || cancelled;
            }
        }
        return cancelled;
    }

    private boolean destroyYoutubedlAndroidProcess(String processId) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId);
            activeYoutubedlAndroidRecordings.remove(processId);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
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

            if (!youtubedlAndroidReady) {
                throw new IllegalStateException("youtubedl-android downloader is not ready.");
            }

            String videoId = recording.getVideoId();
            String watchUrl = YouTubeUrlUtils.buildWatchUrl(videoId);
            String outputPath = recording.getFinalMp4Path();
            String tempOutputPath = outputPath + ".download";
            recording.setTempTsPath(tempOutputPath);

            recording.setDiagnosticMessage("Starting yt-dlp completed-video download.");
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Starting direct video download.");

            log(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_RECORDER,
                null,
                "Starting yt-dlp completed-video download.",
                "videoId=" + videoId + ", input=" + watchUrl + ", output=" + outputPath
            );

            updateYoutubedlAndroidRuntimeIfNeeded(
                "before completed-video direct download",
                "Updating bundled yt-dlp before direct download starts.",
                null
            );

            executeDirectVideoDownloadAttempt(recording, watchUrl, tempOutputPath, true);

            File outputFile = finalizeDirectDownloadTempFile(tempOutputPath, outputPath);
            if (!outputFile.exists() || outputFile.length() <= 0L) {
                throw new IllegalStateException("yt-dlp finished without creating an output file.");
            }

            recording.updateProgress(outputFile.length(), recording.getDurationSeconds());
            recording.markCompleted(outputPath);
            recording.hideFromDownloading();
            storage.upsertRecording(recording);
            copyCompletedRecordingToSelectedFolder(recording, null);
            storage.upsertRecording(recording);
            log(LogItem.LEVEL_SUCCESS, LogItem.SOURCE_RECORDER, null, "Direct video download completed.", "videoId=" + videoId + ", bytes=" + outputFile.length());
        } catch (Exception e) {
            if (isCancellationException(e) && savePartialDirectDownload(recording)) {
                return;
            }
            recording.markFailed(normalizeErrorMessage(e));
            storage.upsertRecording(recording);
            log(LogItem.LEVEL_ERROR, LogItem.SOURCE_RECORDER, null, "Direct download failed.", normalizeErrorMessage(e));
        } finally {
            activeRecordings.remove(recording.getId());
            progressTracker.untrack(recording);
            directDownloadVideoIds.remove(recording.getVideoId());
            discardDirectDownloadPartialIds.remove(recording.getId());
            broadcastRecordingUpdated("Direct download updated.");
        }
    }

    private void executeDirectVideoDownloadAttempt(
        RecordingItem recording,
        String watchUrl,
        String tempOutputPath,
        boolean useTvEmbeddedClient
    ) throws Exception {
        List<String> args = buildDirectVideoDownloadArgs(watchUrl, tempOutputPath, useTvEmbeddedClient);
        YoutubeDLRequest request = buildYoutubedlAndroidRequest(watchUrl, args);
        String processId = recording.getId() + (useTvEmbeddedClient ? "-direct-tv" : "-direct-default");
        Function3<Float, Long, String, Unit> callback = new Function3<Float, Long, String, Unit>() {
            @Override
            public Unit invoke(Float progress, Long etaSeconds, String line) {
                if (!isBlank(line)) {
                    log(LogItem.LEVEL_DEBUG, LogItem.SOURCE_RECORDER, null, "yt-dlp direct download output.", shortenForLog(line, 500));
                }

                long bytes = calculateDirectDownloadTempBytes(tempOutputPath);
                recording.updateProgress(bytes, recording.getDurationSeconds());
                storage.upsertRecording(recording);
                return Unit.INSTANCE;
            }
        };

        activeYoutubedlAndroidRecordings.add(processId);
        try {
            executeYoutubedlAndroidRequest(request, processId, callback);
        } catch (Exception e) {
            if (!useTvEmbeddedClient) {
                throw e;
            }
            log(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_RECORDER,
                null,
                "yt-dlp direct download retrying with default YouTube client.",
                normalizeErrorMessage(e)
            );
            executeDirectVideoDownloadAttempt(recording, watchUrl, tempOutputPath, false);
        } finally {
            activeYoutubedlAndroidRecordings.remove(processId);
        }
    }

    private List<String> buildDirectVideoDownloadArgs(String watchUrl, String outputPath, boolean useTvEmbeddedClient) {
        List<String> args = new ArrayList<>();
        args.add(watchUrl);
        args.add("--js-runtime");
        args.add("quickjs");
        args.add("--no-part");
        args.add("--retries");
        args.add("10");
        args.add("--fragment-retries");
        args.add("10");
        args.add("--socket-timeout");
        args.add("10");
        args.add("--force-ipv4");
        args.add("--no-check-certificates");
        args.add("--no-update");
        args.add("-f");
        args.add("best[height<=480][protocol^=m3u8]/best[protocol^=m3u8]/best[height<=480]/best");
        if (useTvEmbeddedClient) {
            args.add("--extractor-args");
            args.add("youtube:player_client=tv_embedded;skip=dash");
        }
        args.add("--merge-output-format");
        args.add("mp4");
        args.add("-o");
        args.add(outputPath);
        return args;
    }

    private long calculateDirectDownloadTempBytes(String tempOutputPath) {
        if (isBlank(tempOutputPath)) {
            return 0L;
        }
        long total = 0L;
        for (File file : findDirectDownloadTempFiles(tempOutputPath)) {
            total += file.exists() ? Math.max(0L, file.length()) : 0L;
        }
        return Math.max(0L, total);
    }

    private File finalizeDirectDownloadTempFile(String tempOutputPath, String outputPath) {
        File outputFile = new File(outputPath);
        File source = null;
        for (File candidate : findDirectDownloadTempFiles(tempOutputPath)) {
            if (candidate.exists() && candidate.length() > 0L && (source == null || candidate.length() > source.length())) {
                source = candidate;
            }
        }
        if (source == null) {
            return outputFile;
        }
        if (!source.equals(outputFile) && source.exists() && source.length() > 0L) {
            File parent = outputFile.getParentFile();
            if (parent != null) parent.mkdirs();
            if (!source.renameTo(outputFile)) {
                copyFile(source, outputFile);
                source.delete();
            }
        }
        return outputFile;
    }

    private List<File> findDirectDownloadTempFiles(String tempOutputPath) {
        List<File> files = new ArrayList<>();
        if (isBlank(tempOutputPath)) {
            return files;
        }
        File tempFile = new File(tempOutputPath);
        files.add(tempFile);
        files.add(new File(tempOutputPath + ".part"));
        File parent = tempFile.getParentFile();
        String prefix = tempFile.getName();
        File[] siblings = parent == null ? null : parent.listFiles();
        if (siblings != null) {
            for (File sibling : siblings) {
                if (sibling != null && sibling.isFile() && sibling.getName().startsWith(prefix) && !files.contains(sibling)) {
                    files.add(sibling);
                }
            }
        }
        return files;
    }

    private void deleteDirectDownloadTempFiles(String tempOutputPath) {
        for (File file : findDirectDownloadTempFiles(tempOutputPath)) {
            safeDelete(file.getAbsolutePath());
        }
    }

    private boolean isCancellationException(Exception e) {
        String message = normalizeErrorMessage(e);
        return message != null && message.toLowerCase(java.util.Locale.US).contains("canceled");
    }

    private boolean savePartialDirectDownload(RecordingItem recording) {
        if (recording == null || discardDirectDownloadPartialIds.contains(recording.getId())) return false;
        String tempPath = recording.getTempTsPath();
        String finalPath = recording.getFinalMp4Path();
        if (isBlank(tempPath) || isBlank(finalPath)) return false;
        long bytes = calculateDirectDownloadTempBytes(tempPath);
        if (bytes <= 0L) return false;
        int dot = finalPath.lastIndexOf('.');
        String partialPath = dot > 0 ? finalPath.substring(0, dot) + "_partial" + finalPath.substring(dot) : finalPath + "_partial";
        File partialFile = finalizeDirectDownloadTempFile(tempPath, partialPath);
        if (!partialFile.exists() || partialFile.length() <= 0L) return false;
        recording.updateProgress(partialFile.length(), recording.getDurationSeconds());
        recording.markCompleted(partialPath);
        recording.setErrorMessage("Saved partial download.");
        storage.upsertRecording(recording);
        copyCompletedRecordingToSelectedFolder(recording, null);
        log(LogItem.LEVEL_SUCCESS, LogItem.SOURCE_RECORDER, null, "Partial direct download saved.", "videoId=" + recording.getVideoId() + ", bytes=" + partialFile.length());
        return true;
    }

    private void onRecordingFinished(String channelId, String recordingId, int returnCode) {
        RecordingItem recording = storage.findRecordingById(recordingId);
        ChannelItem channel = storage.findChannelById(channelId);

        if (recording == null) return;

        boolean userHaltedRecording = userHaltedRecordingIds.contains(recordingId)
            || recording.isPausedByUser()
            || RecordingItem.STATUS_STOPPED_BY_USER.equals(recording.getStatus());
        if (userHaltedRecording) {
            restartingRecordings.remove(recordingId);
            activeRecordings.remove(channelId);
            activeRecordings.remove(recordingId);
            progressTracker.untrack(recording);
            storage.upsertRecording(recording);

            if (channel != null) {
                activeLoops.remove(channel.getId());
                channel.markPausedByUser();
                storage.upsertChannel(channel);
                notificationHelper.showChannelMonitoringNotification(channel);
            }

            broadcastRecordingUpdated("Recording paused or stopped by user.");
            return;
        }

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

        LiveInfo confirmedLiveInfo = confirmCleanExitStillLooksFinished(channel, recording, liveInfo);
        if (confirmedLiveInfo != null && recording.matchesVideo(confirmedLiveInfo.videoId)) {
            recording.markRecording();
            recording.showInDownloading();
            recording.setDiagnosticMessage("Live re-check flickered negative after clean recorder exit; restarting recorder.");
            storage.upsertRecording(recording);

            String activeChannelId = isBlank(channelId) ? channel.getId() : channelId;
            activeRecordings.put(activeChannelId, recording);
            progressTracker.track(recording);

            channel.markRecording(confirmedLiveInfo.videoId, confirmedLiveInfo.videoUrl);
            storage.upsertChannel(channel);
            notificationHelper.showChannelMonitoringNotification(channel);
            executor.execute(() -> runRecording(activeChannelId, recording, confirmedLiveInfo));
            return CleanExitAction.RESTARTED;
        }

        long ageMillis = recording.getStartedAt() <= 0L
            ? Long.MAX_VALUE
            : System.currentTimeMillis() - recording.getStartedAt();
        if (ageMillis < 5L * 60L * 1_000L) {
            restartingRecordings.remove(recording.getId());
            activeRecordings.remove(channelId);
            activeRecordings.remove(recording.getId());
            progressTracker.untrack(recording);
            recording.markRecoverable("Recorder exited within the startup grace period; refusing to mark complete after transient negative live checks.");
            storage.upsertRecording(recording);
            log(
                LogItem.LEVEL_WARNING,
                LogItem.SOURCE_RECORDER,
                channel,
                "Clean recorder exit deferred during startup grace period.",
                "recordingId=" + recording.getId()
            );
            broadcastRecordingUpdated("Recording is recoverable; startup live status was unstable.");
            return CleanExitAction.DEFERRED;
        }

        String completionReason = liveInfo == null
            ? "confirmed no active stream after repeated live checks"
            : "confirmed live video changed to " + liveInfo.videoId;

        log(
            LogItem.LEVEL_SUCCESS,
            LogItem.SOURCE_RECORDER,
            channel,
            "Recording completed; live re-check confirmed final completion.",
            "recordingId=" + recording.getId() + ", reason=" + completionReason
        );

        return CleanExitAction.FINALIZE;
    }

    private LiveInfo confirmCleanExitStillLooksFinished(ChannelItem channel, RecordingItem recording, LiveInfo firstLiveInfo) {
        LiveInfo latestLiveInfo = firstLiveInfo;

        for (int attempt = 2; attempt <= 3; attempt++) {
            try {
                Thread.sleep(15_000L);
                latestLiveInfo = resolveCurrentLiveInfo(channel);
                if (latestLiveInfo != null && recording != null && recording.matchesVideo(latestLiveInfo.videoId)) {
                    return latestLiveInfo;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return latestLiveInfo;
            } catch (Exception e) {
                log(
                    LogItem.LEVEL_WARNING,
                    LogItem.SOURCE_RECORDER,
                    channel,
                    "Clean recorder exit confirmation check failed.",
                    "attempt=" + attempt + ", reason=" + normalizeErrorMessage(e)
                );
            }
        }

        return latestLiveInfo;
    }

    private boolean convertRecording(RecordingItem recording, ChannelItem channel) {
        if (recording == null) {
            return false;
        }

        if (recording.hasExistingFinalMp4File()) {
            recording.markCompleted(recording.getFinalMp4Path());
            copyCompletedRecordingToSelectedFolder(recording, channel);
            recording.hideFromDownloading();
            storage.upsertRecording(recording);
            log(LogItem.LEVEL_SUCCESS, LogItem.SOURCE_RECORDER, channel, "Recording completed by yt-dlp merge.", recording.getFinalMp4Path());
            return true;
        }

        if (!settings.isConvertTsToMp4()) {
            recording.markCompleted(recording.getTempTsPath());
            copyCompletedRecordingToSelectedFolder(recording, channel);
            recording.hideFromDownloading();
            storage.upsertRecording(recording);
            return true;
        }

        long requiredBytes = estimateConversionRequiredBytes(recording);

        if (!ensureRecordingStorageAvailable(channel, requiredBytes)) {
            recording.markRecoverable("Not enough free storage to convert safely. " + fileManager.getStorageSummary());
            storage.upsertRecording(recording);
            broadcastRecordingUpdated("Recording is recoverable; storage is low.");
            return false;
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
                copyCompletedRecordingToSelectedFolder(recording, channel);
                recording.hideFromDownloading();
                for (String segmentPath : tempSegments) {
                    safeDelete(segmentPath);
                }
                log(LogItem.LEVEL_SUCCESS, LogItem.SOURCE_RECORDER, channel, "Recording completed.", recording.getFinalMp4Path());
                storage.upsertRecording(recording);
                return true;
            } else {
                recording.markRecoverable("MP4 conversion failed.");
                log(LogItem.LEVEL_WARNING, LogItem.SOURCE_RECORDER, channel, "Conversion failed.", "");
            }
        } catch (Exception e) {
            recording.markRecoverable(e.getMessage());
            log(LogItem.LEVEL_ERROR, LogItem.SOURCE_RECORDER, channel, "Conversion error.", e.getMessage());
        }

        storage.upsertRecording(recording);
        return false;
    }

    private synchronized void copyCompletedRecordingToSelectedFolder(RecordingItem recording, ChannelItem channel) {
        if (recording == null || !fileManager.hasCustomSaveLocation()) {
            return;
        }

        Uri folderUri = fileManager.getCustomSaveLocationUri();
        String folderName = fileManager.getCustomSaveLocationDisplayName();
        File source = new File(recording.getBestPlayablePath());

        if (folderUri == null || !source.exists() || !source.isFile()) {
            return;
        }

        String copyDetails = buildSelectedFolderCopyDetails(recording, source, folderName);
        if (!selectedFolderCopyIds.add(recording.getId())) {
            log(LogItem.LEVEL_INFO, LogItem.SOURCE_STORAGE, channel, "Skipping selected-folder copy; copy is already finalized for this recording.", copyDetails);
            return;
        }

        if (recording.isCopiedToSelectedFolder()) {
            log(LogItem.LEVEL_INFO, LogItem.SOURCE_STORAGE, channel, "Skipping selected-folder copy; recording was already copied.", copyDetails);
            return;
        }

        recording.markCopyingToFolder(folderName);
        storage.upsertRecording(recording);
        broadcastRecordingUpdated("Copying to selected folder…");
        log(LogItem.LEVEL_INFO, LogItem.SOURCE_STORAGE, channel, "Copying to selected folder.", copyDetails);

        try {
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(
                folderUri,
                DocumentsContract.getTreeDocumentId(folderUri)
            );
            Uri destination = DocumentsContract.createDocument(
                getContentResolver(),
                parentUri,
                source.getName().endsWith(".ts") ? "video/mp2t" : "video/mp4",
                source.getName()
            );

            if (destination == null) {
                throw new IllegalStateException("Could not create destination file.");
            }

            try (InputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) {
                    throw new IllegalStateException("Could not open destination file.");
                }

                byte[] buffer = new byte[1024 * 64];
                int read;

                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }

            recording.markCompleted(source.getAbsolutePath());
            recording.markCopiedToSelectedFolder(folderName);
            log(LogItem.LEVEL_SUCCESS, LogItem.SOURCE_STORAGE, channel, "Saved to selected folder.", copyDetails);
            broadcastRecordingUpdated("Saved to: " + folderName);
        } catch (Exception e) {
            recording.markCompleted(source.getAbsolutePath());
            recording.setSavedToDisplay("App storage (folder copy failed: " + normalizeErrorMessage(e) + ")");
            log(LogItem.LEVEL_WARNING, LogItem.SOURCE_STORAGE, channel, "Folder copy failed.", copyDetails + ", error=" + normalizeErrorMessage(e));
        }
    }

    private String buildSelectedFolderCopyDetails(RecordingItem recording, File source, String folderName) {
        if (recording == null) {
            return "folder=" + folderName;
        }
        return "recordingId=" + recording.getId()
            + ", videoId=" + recording.getVideoId()
            + ", source=" + (source == null ? "" : source.getAbsolutePath())
            + ", folder=" + folderName;
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

        if (isHttp429Error(message)) {
            startHttp429Cooldown(channel, message);
            sleep(Math.min(HTTP_429_COOLDOWN_MILLIS, settings.getPollIntervalMillis()));
            return;
        }

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

                return new LiveInfo(videoId, title, "https://youtube.com/watch?v=" + videoId, fetchLiveStartTimestampMillis(videoId));
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

            String status = playabilityStatus == null
                ? ""
                : playabilityStatus.optString("status", "");

            if (isBlank(hlsManifestUrl)) {
                String responseSummary = summarizeInnertubeResponseForLog(playerResponse);
                if (shouldLogLiveFallbackState(channelId, "inactive:" + videoId + ":" + status)) {
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
                            + responseSummary
                    );
                }
                return null;
            }

            if (shouldLogLiveFallbackState(channelId, "active:" + videoId + ":" + status)) {
                log(
                    LogItem.LEVEL_INFO,
                    LogItem.SOURCE_SERVICE,
                    null,
                    "Channel /live fallback found an active live video.",
                    "channelId=" + channelId + ", videoId=" + videoId + ", status=" + status
                );
            }

            return new LiveInfo(videoId, title, "https://youtube.com/watch?v=" + videoId, extractLiveStartTimestampMillis(playerResponse));
        } catch (Exception e) {
            Log.w(TAG, "channel /live fallback failed", e);
            return null;
        }
    }

    private long fetchLiveStartTimestampMillis(String videoId) {
        String safeVideoId = normalizeVideoIdForLookup(videoId);
        if (isBlank(safeVideoId)) {
            return 0L;
        }
        try {
            String html = httpGet(YouTubeUrlUtils.buildWatchUrl(safeVideoId));
            return extractLiveStartTimestampMillis(extractInitialPlayerResponse(html));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long extractLiveStartTimestampMillis(JSONObject playerResponse) {
        if (playerResponse == null) {
            return 0L;
        }
        try {
            JSONObject microformat = playerResponse.optJSONObject("microformat");
            JSONObject renderer = microformat == null ? null : microformat.optJSONObject("playerMicroformatRenderer");
            JSONObject liveDetails = renderer == null ? null : renderer.optJSONObject("liveBroadcastDetails");
            String timestamp = liveDetails == null ? "" : liveDetails.optString("startTimestamp", "");
            if (isBlank(timestamp)) {
                return 0L;
            }
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", java.util.Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date parsed = format.parse(timestamp);
            return parsed == null ? 0L : parsed.getTime();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private boolean shouldLogLiveFallbackState(String channelId, String state) {
        if (isVerboseDebugLoggingEnabled()) {
            return true;
        }

        String key = isBlank(channelId) ? "unknown" : channelId.trim();
        String normalizedState = state == null ? "" : state;
        String previousState = liveFallbackLogState.put(key, normalizedState);
        return !normalizedState.equals(previousState);
    }

    private boolean isVerboseDebugLoggingEnabled() {
        return settings != null && settings.isLogDebugEnabled();
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

                    if (isHttp429Error(normalizeErrorMessage(e))) {
                        throw e;
                    }

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

                    if (isHttp429Error(normalizeErrorMessage(e))) {
                        throw e;
                    }

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

    private void startFfmpegKitRecording(
        String channelId,
        ChannelItem channel,
        RecordingItem recording,
        String manifestUrl,
        String outputPath,
        boolean appendOutput,
        String broadcastMessage
    ) {
        if (recording == null) {
            return;
        }

        if (shouldStopRecorderAfterUserRequest(channelId, channel, recording)) {
            return;
        }

        final ChannelItem logChannel = channel;

        recording.setDiagnosticMessage("FFmpeg recorder is running.");
        storage.upsertRecording(recording);

        if (!isBlank(broadcastMessage)) {
            broadcastRecordingUpdated(broadcastMessage);
        }

        FFmpegRunner.executeAsync(
            recording.getId(),
            manifestUrl,
            outputPath,
            appendOutput,
            returnCode -> onRecordingFinished(channelId, recording.getId(), returnCode),
            message -> {
                if (message != null
                    && !message.startsWith("frame=")
                    && !message.startsWith("size=")) {
                    log(LogItem.LEVEL_DEBUG, LogItem.SOURCE_FFMPEG, logChannel, message, "");
                }
            }
        );
    }

    private boolean recordingHasAnyOutputData(RecordingItem recording) {
        return currentRecordingSegmentHasData(recording)
            || fileHasData(recording == null ? "" : recording.getFinalMp4Path())
            || !findYtDlpDashSidecarFiles(recording).isEmpty();
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

    private boolean fileHasData(String path) {
        if (isBlank(path)) {
            return false;
        }

        try {
            File file = new File(path);
            return file.exists() && file.length() > 0L;
        } catch (RuntimeException ignored) {
            return false;
        }
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
                    + ", playerClient="
                    + attempt.playerClient
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

                if (isHttp429Error(normalizeErrorMessage(e))) {
                    startHttp429Cooldown(channel, normalizeErrorMessage(e));
                    throw e;
                }

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
            "channel=stable, reason=" + reason
        );

        try {
            YoutubeDL.getInstance().updateYoutubeDL(getApplicationContext(), UpdateChannel._STABLE);

            log(
                LogItem.LEVEL_SUCCESS,
                LogItem.SOURCE_REMOTE_CONFIG,
                null,
                "youtubedl-android runtime updated.",
                "Updated bundled yt-dlp from the stable channel. version=" + getBundledYtDlpVersionForLog()
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

    private String getBundledYtDlpVersionForLog() {
        try {
            Object version = YoutubeDL.getInstance()
                .getClass()
                .getMethod("version", android.content.Context.class)
                .invoke(YoutubeDL.getInstance(), getApplicationContext());
            return version == null ? "unknown" : String.valueOf(version);
        } catch (Exception e) {
            return "unknown (" + normalizeErrorMessage(e) + ")";
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
        LinkedHashSet<String> extractorArgs = buildYtDlpExtractorArgAttempts();
        boolean allowLiveFromStart = settings != null && settings.isLiveFromStartEnabled();

        for (String extractorArg : extractorArgs) {
            attempts.add(buildYtDlpResolveAttempt(
                builder,
                videoUrl,
                extractorArg,
                true
            ));

            if (allowLiveFromStart) {
                attempts.add(buildYtDlpResolveAttempt(
                    builder,
                    videoUrl,
                    extractorArg,
                    false
                ));
            }
        }

        return attempts;
    }

    private YtDlpResolveAttempt buildYtDlpResolveAttempt(
        RecorderCommandBuilder builder,
        String videoUrl,
        String extractorArg,
        boolean allowLiveFromStart
    ) {
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
            buildYtDlpExtractorAttemptDescription(extractorArg, allowLiveFromStart),
            extractPlayerClientFromExtractorArgs(extractorArg)
        );
    }

    private LinkedHashSet<String> buildYtDlpExtractorArgAttempts() {
        return buildYtDlpExtractorArgAttempts(false);
    }

    private LinkedHashSet<String> buildYtDlpExtractorArgAttempts(boolean includePoToken) {
        LinkedHashSet<String> extractorArgs = new LinkedHashSet<>();

        String poTokenExtractorArgs = includePoToken && settings != null
            ? settings.buildYtDlpPoTokenExtractorArgs()
            : "";
        boolean hasPoToken = !isBlank(poTokenExtractorArgs);
        boolean hasCookies = settings != null && settings.hasYtDlpCookies();

        /*
         * Keep the without-PO-token branch behavior stable even when the user
         * has a cached PO token from setup. The HLS clients below are the fast
         * live-from-start path and should always be attempted before token-bound
         * GVS/DASH clients, because stale or video-specific tokens can return no
         * formats or stall before bytes are written.
         *
         * tv_embedded (TVHTML5_SIMPLY_EMBEDDED_PLAYER) is the most lenient
         * YouTube client regarding po_token enforcement on live streams. Adding
         * skip=dash forces HLS-only output, which is required for
         * --live-from-start to walk the DVR fragment index from the beginning.
         *
         * web;skip=dash is the next best option when cookies are present because
         * an authenticated web session avoids most bot-check blocks.
         */
        extractorArgs.add("youtube:player_client=tv_embedded;skip=dash");

        if (hasCookies) {
            extractorArgs.add("youtube:player_client=web;skip=dash");
        }

        if (hasPoToken) {
            logYtDlpPoTokenCacheState();
        } else {
            log(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_RECORDER,
                null,
                includePoToken
                    ? "No PO token cached. Trying live-stream fallback clients first."
                    : "PO-token extractor attempts disabled for this without-PO-token chain.",
                hasCookies
                    ? "Cookies are saved — tv_embedded (HLS) and web (HLS) will be tried first."
                    : "tv_embedded (HLS) fallback attempts will be tried first."
            );
        }

        String localExtractorArgs = settings == null ? "" : settings.getYtDlpExtractorArgs();

        if (!isBlank(localExtractorArgs) && (includePoToken || !containsPoTokenArg(localExtractorArgs))) {
            extractorArgs.add(localExtractorArgs.trim());
        }

        // Let yt-dlp choose its own current YouTube clients after explicit app
        // settings. Forced clients can become stale faster than yt-dlp defaults.
        extractorArgs.add(RecorderCommandBuilder.EXTRACTOR_ARGS_NONE);

        String configuredExtractorArgs = remoteConfig == null ? "" : remoteConfig.getYtDlpExtractorArgs();

        if (!isBlank(configuredExtractorArgs) && (includePoToken || !containsPoTokenArg(configuredExtractorArgs))) {
            extractorArgs.add(configuredExtractorArgs.trim());
        } else if (remoteConfig != null) {
            RemoteConfig.YoutubeClient primaryClient = remoteConfig.getPrimaryClient();

            if (primaryClient != null && primaryClient.isValid()) {
                extractorArgs.add(buildYtDlpPlayerClientArg(primaryClient.getClientName()));
            }
        }

        if (hasPoToken) {
            extractorArgs.add(poTokenExtractorArgs.trim());
        }

        /*
         * TV_EMBEDDED is listed first because it has historically been the most
         * lenient YouTube client about po_token enforcement for live streams and
         * always returns HLS manifests compatible with --live-from-start.
         *
         * The remaining clients are tried in approximate order of how reliably
         * they return playable formats without a po_token, with web-based clients
         * before native app clients because native clients (android, ios) now
         * require GVS po_token for DASH and frequently have no HLS fallback.
         */
        addPreferredYtDlpClient(extractorArgs, "TV_EMBEDDED", includePoToken);
        addPreferredYtDlpClient(extractorArgs, "WEB_EMBEDDED_PLAYER", includePoToken);
        addPreferredYtDlpClient(extractorArgs, "WEB_SAFARI", includePoToken);
        addPreferredYtDlpClient(extractorArgs, "MWEB", includePoToken);
        addPreferredYtDlpClient(extractorArgs, "WEB", includePoToken);
        addPreferredYtDlpClient(extractorArgs, "WEB_CREATOR", includePoToken);
        addPreferredYtDlpClient(extractorArgs, "ANDROID", includePoToken);
        addPreferredYtDlpClient(extractorArgs, "IOS", includePoToken);
        addPreferredYtDlpClient(extractorArgs, "MEDIACONNECT", includePoToken);

        if (remoteConfig != null) {
            for (RemoteConfig.YoutubeClient client : remoteConfig.getYoutubeClients()) {
                if (client != null && client.isValid()) {
                    addPreferredYtDlpClient(extractorArgs, client.getClientName(), includePoToken);
                }
            }
        }

        logYtDlpExtractorAttemptList(extractorArgs);
        return extractorArgs;
    }

    private void logYtDlpPoTokenCacheState() {
        if (settings == null || !settings.hasYtDlpPoToken()) {
            return;
        }

        boolean refreshNeeded = settings.isYtDlpPoTokenRefreshNeeded(System.currentTimeMillis());
        log(
            refreshNeeded ? LogItem.LEVEL_WARNING : LogItem.LEVEL_INFO,
            LogItem.SOURCE_RECORDER,
            null,
            refreshNeeded
                ? "Cached YouTube PO token may need refresh."
                : "Cached YouTube PO token will be used.",
            "client="
                + settings.getYtDlpPoTokenClient()
                + ", type="
                + settings.getYtDlpPoTokenType()
                + ", videoId="
                + settings.getYtDlpPoTokenVideoId()
                + ", source="
                + settings.getYtDlpPoTokenSource()
                + ", updatedAt="
                + settings.getYtDlpPoTokenUpdatedAt()
                + ", session="
                + settings.getYtDlpPoTokenSessionBinding()
                + ", refreshNeeded="
                + refreshNeeded
        );
    }

    private void logYtDlpExtractorAttemptList(LinkedHashSet<String> extractorArgs) {
        if (extractorArgs == null || extractorArgs.isEmpty()) {
            return;
        }

        List<String> descriptions = new ArrayList<>();

        for (String extractorArg : extractorArgs) {
            descriptions.add(redactYtDlpExtractorArgForLog(extractorArg));
        }

        log(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_RECORDER,
            null,
            "yt-dlp extractor args attempt list built.",
            "attempts=" + descriptions
        );
    }

    private String buildYtDlpExtractorAttemptDescription(
        String extractorArg,
        boolean allowLiveFromStart
    ) {
        String extractorDescription = RecorderCommandBuilder.EXTRACTOR_ARGS_NONE.equals(extractorArg)
            ? "extractorArgs=yt-dlp-default"
            : "extractorArgs=" + redactYtDlpExtractorArgForLog(extractorArg);
        String liveFromStartDescription = settings != null && settings.isLiveFromStartEnabled()
            ? ", liveFromStart=" + allowLiveFromStart
            : "";

        return extractorDescription + liveFromStartDescription;
    }

    private boolean containsPoTokenArg(String extractorArg) {
        return extractorArg != null
            && extractorArg.toLowerCase(java.util.Locale.US).contains("po_token=");
    }

    private String redactYtDlpExtractorArgForLog(String extractorArg) {
        if (extractorArg == null) {
            return "";
        }

        return extractorArg.replaceAll("(?i)(po_token=[^;\\s]+\\+)[^;\\s]+", "$1<redacted>");
    }

    private void addPreferredYtDlpClient(LinkedHashSet<String> extractorArgs, String clientName) {
        addPreferredYtDlpClient(extractorArgs, clientName, false);
    }

    private void addPreferredYtDlpClient(
        LinkedHashSet<String> extractorArgs,
        String clientName,
        boolean includePoToken
    ) {
        if (extractorArgs == null || isBlank(clientName)) {
            return;
        }

        String normalizedClient = clientName.trim().toLowerCase();

        /*
         * When a PO token is cached, add a variant with the token injected for
         * this specific client prefix first. GVS tokens are session-bound rather
         * than strictly client-bound, so applying the same token value under
         * each client's prefix gives yt-dlp the best chance of finding a working
         * combination without requiring a fresh token per client.
         */
        if (includePoToken && settings != null && settings.hasYtDlpPoToken()) {
            String tokenValue = settings.getYtDlpPoTokenValue();
            String tokenType = settings.getYtDlpPoTokenType();

            if (!isBlank(tokenValue) && !isBlank(tokenType)) {
                extractorArgs.add(
                    "youtube:player_client=" + normalizedClient
                        + ";po_token=" + normalizedClient + "." + tokenType + "+" + tokenValue
                );
            }
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

        if (!hasYtDlpOption(args, "--ffmpeg-location")) {
            String ffmpegLocation = getYoutubedlAndroidFfmpegLocation();

            if (!isBlank(ffmpegLocation)) {
                request.addOption("--ffmpeg-location", ffmpegLocation);
            }
        }

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
            || "--fragment-retries".equals(arg)
            || "--retries".equals(arg)
            || "--extractor-retries".equals(arg)
            || "--file-access-retries".equals(arg)
            || "--retry-sleep".equals(arg)
            || "--wait-for-video".equals(arg)
            || "--js-runtime".equals(arg)
            || "--user-agent".equals(arg)
            || "--extractor-args".equals(arg)
            || "--ffmpeg-location".equals(arg)
            || "--merge-output-format".equals(arg)
            || "-P".equals(arg)
            || "--paths".equals(arg)
            || "--cookies".equals(arg)
            || "--cookies-from-browser".equals(arg)
            || "--add-header".equals(arg);
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
            YouTubeUrlUtils.buildWatchUrl(safeVideoId),
            0L
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
        long restoredAtMillis = System.currentTimeMillis();
        storage.saveNetworkRestoredAt(restoredAtMillis);
        long lostAtMillis = storage.loadNetworkLostAt();
        if (lostAtMillis > 0L) {
            storage.clearNetworkLostAt();
            maybeReportMissedStreamsAfterOutage(lostAtMillis, restoredAtMillis);
        }

        for (ChannelItem channel : storage.loadChannels()) {
            if (channel != null && channel.shouldMonitor()) {
                channel.markWaitingForLive();
                storage.upsertChannel(channel);
                startChannelLoop(channel);
            }
        }

        for (RecordingItem recording : storage.loadRecordings()) {
            if (recording != null
                && RecordingItem.STATUS_PAUSED_NETWORK.equals(recording.getStatus())
                && !activeRecordings.containsKey(recording.getId())) {
                ChannelItem channel = storage.findChannelById(recording.getChannelId());

                if (channel != null && channel.shouldMonitor()) {
                    recording.markRecording();
                    recording.showInDownloading();
                    storage.upsertRecording(recording);
                    activeRecordings.put(channel.getId(), recording);
                    progressTracker.track(recording);
                    executor.execute(() -> resumeRecordingOrFinalizeStoppedLive(channel.getId(), recording));
                }
            }
        }

        broadcast(LiveMonitorActions.ACTION_NETWORK_AVAILABLE, "Network restored.");
    }

    @Override
    public void onNetworkLost() {
        networkAvailable = false;
        storage.saveNetworkLostAt(System.currentTimeMillis());

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

    private void maybeReportMissedStreamsAfterOutage(long lostAtMillis, long restoredAtMillis) {
        long outageMillis = Math.max(0L, restoredAtMillis - lostAtMillis);
        if (outageMillis < MISSED_STREAM_OUTAGE_MIN_MILLIS
            || !storage.markMissedStreamOutageChecked(lostAtMillis, restoredAtMillis)) {
            return;
        }

        executor.execute(() -> {
            for (ChannelItem channel : storage.loadChannels()) {
                if (channel == null || !channel.shouldMonitor()) {
                    continue;
                }

                String outageDetails = "outageStart=" + lostAtMillis
                    + ", outageEnd=" + restoredAtMillis
                    + ", outageMillis=" + outageMillis
                    + ", action=review recent channel streams for was_live entries in this window";
                storage.addMissedStreamRecord(
                    channel.getId(),
                    "Possible missed live stream during network outage",
                    outageDetails
                );
                log(
                    LogItem.LEVEL_ERROR,
                    LogItem.SOURCE_SERVICE,
                    channel,
                    "MISSED STREAM DETECTED during network outage.",
                    outageDetails
                );
                notificationHelper.showChannelMonitoringNotification(channel);
            }

            broadcastChannelUpdated("Network restored; possible missed streams were flagged for review.");
        });
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
                recording.markStoppedBySystem("MonitorService is shutting down; recording cancellation was not user initiated.");
                storage.upsertRecording(recording);
                log(
                    LogItem.LEVEL_WARNING,
                    LogItem.SOURCE_RECORDER,
                    null,
                    "System-triggered recording stop during service shutdown.",
                    "recordingId=" + recording.getId()
                );
                cancelYoutubedlAndroidRecording(recording.getId());
            }
        }

        activeRecordings.clear();
        restartingRecordings.clear();
        FFmpegRunner.cancel();
        YtDlpRunner.cancelAllRecordings();
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

    private static void copyFile(File source, File destination) {
        try (InputStream in = new FileInputStream(source); OutputStream out = new java.io.FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
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


    private static class YtDlpPrimaryRecorderDecision {
        static final String RECORDER_NONE = "none";
        static final String RECORDER_DIRECT_EXECUTABLE = "direct-executable";
        static final String RECORDER_YOUTUBEDL_ANDROID = "youtubedl-android";

        private final boolean shouldTry;
        private final String recorderName;
        private final String reason;

        private YtDlpPrimaryRecorderDecision(boolean shouldTry, String recorderName, String reason) {
            this.shouldTry = shouldTry;
            this.recorderName = isBlank(recorderName) ? RECORDER_NONE : recorderName;
            this.reason = isBlank(reason) ? "no reason provided." : reason;
        }

        static YtDlpPrimaryRecorderDecision skip(String reason) {
            return new YtDlpPrimaryRecorderDecision(false, RECORDER_NONE, reason);
        }

        static YtDlpPrimaryRecorderDecision tryRecorder(String recorderName, String reason) {
            return new YtDlpPrimaryRecorderDecision(true, recorderName, reason);
        }

        boolean shouldTry() {
            return shouldTry;
        }

        String getRecorderName() {
            return recorderName;
        }

        String getReason() {
            return reason;
        }
    }

    private static class YtDlpResolveAttempt {
        final List<String> args;
        final String extractorArgs;
        final boolean allowLiveFromStart;
        final String description;
        final String playerClient;

        YtDlpResolveAttempt(
            List<String> args,
            String extractorArgs,
            boolean allowLiveFromStart,
            String description,
            String playerClient
        ) {
            this.args = args;
            this.extractorArgs = extractorArgs == null ? "" : extractorArgs;
            this.allowLiveFromStart = allowLiveFromStart;
            this.description = description == null ? "" : description;
            this.playerClient = playerClient == null ? "" : playerClient;
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
        final long streamStartedAt;

        LiveInfo(String videoId, String title, String videoUrl, long streamStartedAt) {
            this.videoId = videoId;
            this.title = title == null ? videoId : title;
            this.videoUrl = videoUrl;
            this.streamStartedAt = Math.max(0L, streamStartedAt);
        }
    }
                                                              }
