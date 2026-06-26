package com.livemonitor.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.livemonitor.app.databinding.ActivityMainBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Main home screen.
 *
 * Layout:
 * - fixed top URL input + Add Channel button
 * - Monitoring tab
 * - Downloading tab for active downloads/recordings
 * - 3-dot menu for logs, downloaded files, settings
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private AppStorage storage;
    private ChannelAdapter channelAdapter;
    private RecordingAdapter recordingAdapter;
    private BroadcastReceiver updateReceiver;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private final Runnable downloadTimerRefresh = new Runnable() {
        @Override
        public void run() {
            if (!showingMonitoring && recordingAdapter != null) {
                refreshAll();
            }
            timerHandler.postDelayed(this, 15_000L);
        }
    };

    private boolean showingMonitoring = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        storage = new AppStorage(this);

        requestNotificationPermission();
        setupAdapters();
        setupClickListeners();
        setupUpdateReceiver();

        fetchRemoteConfigOnStart();
        refreshAll();
        showMonitoringTab();
        timerHandler.postDelayed(downloadTimerRefresh, 15_000L);

        storage.appendLog(LogItem.info(LogItem.SOURCE_UI, "Live Monitor opened."));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        timerHandler.removeCallbacks(downloadTimerRefresh);

        if (updateReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(updateReceiver);
        }
    }

    private void setupAdapters() {
        channelAdapter = new ChannelAdapter(this);
        channelAdapter.setListener(new ChannelAdapter.Listener() {
            @Override
            public void onChannelClicked(ChannelItem channel) {
                openChannelLog(channel);
            }

            @Override
            public void onPauseResumeClicked(ChannelItem channel) {
                toggleChannelPaused(channel);
            }

            @Override
            public void onStopClicked(ChannelItem channel) {
                stopChannel(channel);
            }

            @Override
            public void onDeleteClicked(ChannelItem channel) {
                confirmDeleteChannel(channel);
            }
        });

        recordingAdapter = new RecordingAdapter(this);
        recordingAdapter.setMode(RecordingAdapter.Mode.DOWNLOADING);
        recordingAdapter.setListener(new RecordingAdapter.Listener() {
            @Override
            public void onRecordingClicked(RecordingItem recording) {
                Toast.makeText(
                    MainActivity.this,
                    UiTextUtils.formatRecordingDetails(recording),
                    Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onOpenFileClicked(RecordingItem recording) {
                Intent intent = new Intent(MainActivity.this, DownloadedFilesActivity.class);
                startActivity(intent);
            }

            @Override
            public void onPauseResumeClicked(RecordingItem recording) {
                toggleRecordingPaused(recording);
            }

            @Override
            public void onDeleteClicked(RecordingItem recording) {
                confirmStopDownload(recording);
            }
        });

        setupMonitoringListHeader();
        binding.channelListView.setAdapter(channelAdapter);

        binding.recordingListView.setAdapter(recordingAdapter);
        binding.recordingListView.setEmptyView(binding.emptyDownloadsText);
    }


    private void setupMonitoringListHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        moveToHeader(binding.monitoringHeader, header);
        moveToHeader(binding.storageHealthCard, header);
        moveToHeader(binding.emptyMonitoringText, header);

        binding.channelListView.addHeaderView(header, null, false);
    }

    private static void moveToHeader(View view, LinearLayout header) {
        ViewGroup parent = (ViewGroup) view.getParent();
        if (parent != null) {
            parent.removeView(view);
        }
        header.addView(view);
    }

    private void setupClickListeners() {
        binding.btnAddChannel.setOnClickListener(v -> handleUrlSubmit());

        binding.bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();

            if (itemId == R.id.nav_monitoring) {
                showMonitoringTab();
                return true;
            }

            if (itemId == R.id.nav_downloading) {
                showDownloadsTab();
                return true;
            }

            if (itemId == R.id.nav_files) {
                startActivity(new Intent(this, DownloadedFilesActivity.class));
                return true;
            }

            if (itemId == R.id.nav_settings) {
                startActivity(new Intent(this, LogActivity.class));
                return true;
            }

            return false;
        });

        binding.btnMenu.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void setupUpdateReceiver() {
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }

                refreshAll();

                String action = intent.getAction();
                String message = intent.getStringExtra(LiveMonitorActions.EXTRA_MESSAGE);

                if (shouldShowHeaderStatus(action, message)) {
                    binding.statusText.setText(formatHeaderStatus(message));
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(LiveMonitorActions.ACTION_CHANNEL_UPDATED);
        filter.addAction(LiveMonitorActions.ACTION_RECORDING_UPDATED);
        filter.addAction(LiveMonitorActions.ACTION_LOG_UPDATED);
        filter.addAction(LiveMonitorActions.ACTION_REMOTE_CONFIG_UPDATED);
        filter.addAction(LiveMonitorActions.ACTION_NETWORK_AVAILABLE);
        filter.addAction(LiveMonitorActions.ACTION_NETWORK_LOST);

        /*
         * Legacy log broadcast from existing MonitorService.
         */
        filter.addAction("MONITOR_LOG");

        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver, filter);
    }

    private void handleUrlSubmit() {
        if (showingMonitoring) {
            addChannelFromInput();
        } else {
            downloadVideoFromInput();
        }
    }

    private void addChannelFromInput() {
        String url = cleanUrl(binding.urlInput.getText().toString());

        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a YouTube channel URL.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (YouTubeUrlUtils.isDirectVideoUrl(url)) {
            Toast.makeText(this, "To download a completed livestream, go to the Downloads tab.", Toast.LENGTH_SHORT).show();
            return;
        }

        ChannelItem existing = storage.findChannelByNormalizedUrl(url);

        if (existing != null) {
            existing.resumeMonitoring();
            storage.upsertChannel(existing);
            startMonitoringService(existing);

            binding.urlInput.setText("");
            refreshAll();

            Toast.makeText(this, "Channel already exists. Monitoring resumed.", Toast.LENGTH_SHORT)
                .show();
            return;
        }

        ChannelItem channel = new ChannelItem(url);
        AppSettings settings = storage.loadSettings();
        channel.setMaxRetries(settings.getMaxRetries());
        channel.markWaitingForLive();

        storage.upsertChannel(channel);
        storage.appendLog(LogItem.channel(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_UI,
            channel,
            "Channel added."
        ));

        startMonitoringService(channel);

        binding.urlInput.setText("");
        refreshAll();

        Toast.makeText(this, "Channel added.", Toast.LENGTH_SHORT).show();
    }



    private void downloadVideoFromInput() {
        String url = cleanUrl(binding.urlInput.getText().toString());

        if (url.isEmpty()) {
            Toast.makeText(this, "Please paste a YouTube video URL.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!url.contains("youtube.com") && !url.contains("youtu.be")) {
            Toast.makeText(this, "Paste a valid YouTube URL.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!YouTubeUrlUtils.isDirectVideoUrl(url)) {
            Toast.makeText(this, "To monitor a channel, go to the Home tab.", Toast.LENGTH_SHORT).show();
            return;
        }

        String videoId = YouTubeUrlUtils.extractVideoId(url);
        if (videoId.isEmpty()) {
            Toast.makeText(this, "Could not detect video ID.", Toast.LENGTH_SHORT).show();
            return;
        }

        storage.appendLog(LogItem.info(LogItem.SOURCE_UI, "ManualDownload: video download requested."));
        startDirectVideoDownload(url, videoId);
    }

    private void confirmDownloadVideoUrl(String url) {
        String videoId = YouTubeUrlUtils.extractVideoId(url);

        if (videoId.isEmpty()) {
            Toast.makeText(this, "Could not detect video ID.", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Download video")
            .setMessage("This looks like a YouTube video/live replay link. Download it now?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download", (dialog, which) -> startDirectVideoDownload(url, videoId))
            .show();
    }

    private void startDirectVideoDownload(String url, String videoId) {
        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(LiveMonitorActions.ACTION_DOWNLOAD_VIDEO);
        intent.putExtra(LiveMonitorActions.EXTRA_URL, url);
        intent.putExtra(LiveMonitorActions.EXTRA_VIDEO_ID, videoId);
        startServiceCompat(intent);

        binding.urlInput.setText("");
        refreshAll();
        Toast.makeText(this, "Video download started.", Toast.LENGTH_SHORT).show();
    }

    private void showManualDownloadDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(18);
        content.setPadding(padding, padding, padding, 0);

        EditText urlInput = new EditText(this);
        urlInput.setHint("Paste YouTube video URL");
        urlInput.setSingleLine(true);
        content.addView(urlInput);

        Spinner qualitySpinner = new Spinner(this);
        String[] qualities = {
            AppSettings.QUALITY_360P,
            AppSettings.QUALITY_480P,
            AppSettings.QUALITY_720P,
            AppSettings.QUALITY_1080P
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, qualities);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qualitySpinner.setAdapter(adapter);
        String currentQuality = storage.loadSettings().getDownloadQuality();
        for (int i = 0; i < qualities.length; i++) {
            if (qualities[i].equals(currentQuality)) {
                qualitySpinner.setSelection(i);
                break;
            }
        }
        content.addView(qualitySpinner);

        new AlertDialog.Builder(this)
            .setTitle("Download Video")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download", (dialog, which) -> {
                String url = cleanUrl(urlInput.getText().toString());
                if (!url.contains("youtube.com") && !url.contains("youtu.be")) {
                    Toast.makeText(this, "Paste a valid YouTube URL.", Toast.LENGTH_SHORT).show();
                    return;
                }
                String videoId = YouTubeUrlUtils.extractVideoId(url);
                if (videoId.isEmpty()) {
                    Toast.makeText(this, "Could not detect video ID.", Toast.LENGTH_SHORT).show();
                    return;
                }
                storage.appendLog(LogItem.info(LogItem.SOURCE_UI, "ManualDownload: video download requested."));
                startDirectVideoDownload(url, videoId);
            })
            .show();
    }

    private void toggleChannelPaused(ChannelItem channel) {
        if (channel == null) {
            return;
        }

        if (channel.shouldMonitor()) {
            channel.markPausedByUser();
            storage.upsertChannel(channel);
            sendChannelAction(LiveMonitorActions.ACTION_PAUSE_CHANNEL, channel);

            storage.appendLog(LogItem.channel(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_UI,
                channel,
                "Channel paused by user."
            ));
        } else {
            channel.resumeMonitoring();
            channel.markWaitingForLive();
            storage.upsertChannel(channel);
            sendChannelAction(LiveMonitorActions.ACTION_RESUME_CHANNEL, channel);

            storage.appendLog(LogItem.channel(
                LogItem.LEVEL_INFO,
                LogItem.SOURCE_UI,
                channel,
                "Channel resumed by user."
            ));
        }

        refreshAll();
    }

    private void stopChannel(ChannelItem channel) {
        if (channel == null) {
            return;
        }

        channel.markStopped();
        storage.upsertChannel(channel);
        sendChannelAction(LiveMonitorActions.ACTION_STOP_MONITORING, channel);

        storage.appendLog(LogItem.channel(
            LogItem.LEVEL_WARNING,
            LogItem.SOURCE_UI,
            channel,
            "Channel stopped by user."
        ));

        refreshAll();
    }


    private void confirmDeleteChannel(ChannelItem channel) {
        if (channel == null) {
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Delete channel?")
            .setMessage("Stop monitoring and delete this channel from the Monitoring section?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> deleteChannel(channel))
            .show();
    }

    private void deleteChannel(ChannelItem channel) {
        if (channel == null) {
            return;
        }

        sendChannelAction(LiveMonitorActions.ACTION_REMOVE_CHANNEL, channel);
        storage.appendLog(LogItem.channel(
            LogItem.LEVEL_WARNING,
            LogItem.SOURCE_UI,
            channel,
            "Channel deleted by user."
        ));

        refreshAll();
        Toast.makeText(this, "Channel deleted.", Toast.LENGTH_SHORT).show();
    }

    private void confirmStopDownload(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        boolean directDownload = recording.getChannelId().trim().isEmpty();
        if (directDownload) {
            String downloaded = RecordingProgressTracker.formatBytes(recording.getBytesRecorded());
            new AlertDialog.Builder(this)
                .setTitle("Save partial download?")
                .setMessage("You have downloaded " + downloaded + " so far.")
                .setNegativeButton("Discard", (dialog, which) -> stopDownload(recording, false))
                .setPositiveButton("Save Partial", (dialog, which) -> stopDownload(recording, true))
                .show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Stop and save recording?")
            .setMessage("Stop and save this recording? Monitoring for this channel will also be paused.")
            .setNegativeButton("Keep Recording", null)
            .setPositiveButton("Stop & Save", (dialog, which) -> stopDownload(recording, true))
            .show();
    }

    private void toggleRecordingPaused(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(
            recording.isPausedByUser()
                ? LiveMonitorActions.ACTION_RESUME_RECORDING
                : LiveMonitorActions.ACTION_PAUSE_RECORDING
        );
        intent.putExtra(LiveMonitorActions.EXTRA_RECORDING_ID, recording.getId());
        intent.putExtra(LiveMonitorActions.EXTRA_CHANNEL_ID, recording.getChannelId());
        startServiceCompat(intent);

        if (recording.isPausedByUser()) {
            recording.markRecording();
            Toast.makeText(this, "Recording resumed.", Toast.LENGTH_SHORT).show();
        } else {
            recording.markPausedByUser();
            Toast.makeText(this, "Recording paused.", Toast.LENGTH_SHORT).show();
        }

        storage.upsertRecording(recording);
        refreshAll();
    }

    private void stopDownload(RecordingItem recording) {
        stopDownload(recording, true);
    }

    private void stopDownload(RecordingItem recording, boolean savePartial) {
        if (recording == null) {
            return;
        }

        ChannelItem channel = storage.findChannelById(recording.getChannelId());
        if (channel != null) {
            channel.markStopped();
            storage.upsertChannel(channel);
        }

        recording.markStoppedByUser();
        recording.showInDownloading();
        storage.upsertRecording(recording);

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(LiveMonitorActions.ACTION_STOP_RECORDING);
        intent.putExtra(LiveMonitorActions.EXTRA_RECORDING_ID, recording.getId());
        intent.putExtra(LiveMonitorActions.EXTRA_CHANNEL_ID, recording.getChannelId());
        intent.putExtra(LiveMonitorActions.EXTRA_SAVE_PARTIAL, savePartial);
        startServiceCompat(intent);

        refreshAll();
        Toast.makeText(this, "Stopping, saving, and pausing monitoring…", Toast.LENGTH_SHORT).show();
    }

    private void startMonitoringService(ChannelItem channel) {
        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(LiveMonitorActions.ACTION_START_MONITORING);
        intent.putExtra(LiveMonitorActions.EXTRA_CHANNEL_ID, channel.getId());
        intent.putExtra(LiveMonitorActions.EXTRA_URL, channel.getUrl());

        startServiceCompat(intent);
    }

    private void sendChannelAction(String action, ChannelItem channel) {
        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(action);
        intent.putExtra(LiveMonitorActions.EXTRA_CHANNEL_ID, channel.getId());
        intent.putExtra(LiveMonitorActions.EXTRA_URL, channel.getUrl());

        startServiceCompat(intent);
    }

    private void startServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void showOverflowMenu() {
        PopupMenu menu = new PopupMenu(this, binding.btnMenu);
        menu.getMenu().add("Log");
        menu.getMenu().add("Downloaded Files");
        menu.getMenu().add("Settings");
        menu.getMenu().add("Stop All");

        menu.setOnMenuItemClickListener(item -> {
            String title = String.valueOf(item.getTitle());

            if ("Log".equals(title)) {
                startActivity(new Intent(this, LogActivity.class));
                return true;
            }

            if ("Downloaded Files".equals(title)) {
                startActivity(new Intent(this, DownloadedFilesActivity.class));
                return true;
            }

            if ("Settings".equals(title)) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }

            if ("Stop All".equals(title)) {
                stopAllMonitoring();
                return true;
            }

            return false;
        });

        menu.show();
    }

    private void stopAllMonitoring() {
        List<ChannelItem> channels = storage.loadChannels();

        for (ChannelItem channel : channels) {
            if (channel != null) {
                channel.markStopped();
            }
        }

        storage.saveChannels(channels);

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(LiveMonitorActions.ACTION_STOP_ALL);
        startServiceCompat(intent);

        storage.appendLog(LogItem.warning(LogItem.SOURCE_UI, "Stop All selected."));
        refreshAll();

        Toast.makeText(this, "All monitoring stopped.", Toast.LENGTH_SHORT).show();
    }

    private void openChannelLog(ChannelItem channel) {
        if (channel == null) {
            return;
        }

        Intent intent = new Intent(this, ChannelLogActivity.class);
        intent.putExtra(LiveMonitorActions.EXTRA_CHANNEL_ID, channel.getId());
        intent.putExtra(LiveMonitorActions.EXTRA_CHANNEL_TITLE, channel.getDisplayTitle());
        startActivity(intent);
    }

    private void showMonitoringTab() {
        showingMonitoring = true;

        binding.monitoringPanel.setVisibility(View.VISIBLE);
        binding.downloadsPanel.setVisibility(View.GONE);
        binding.urlInput.setHint("Paste YouTube channel URL...");
        binding.btnAddChannel.setText("＋  ADD");

        refreshAll();
    }

    private void showDownloadsTab() {
        showingMonitoring = false;

        binding.monitoringPanel.setVisibility(View.GONE);
        binding.downloadsPanel.setVisibility(View.VISIBLE);
        binding.urlInput.setHint("Paste YouTube video URL");
        binding.btnAddChannel.setText("⇩  GET");

        refreshAll();
    }

    private void refreshAll() {
        reconcileStaleRecordingCards();
        List<ChannelItem> channels = loadVisibleMonitoringChannels();
        channelAdapter.setChannels(channels);

        List<RecordingItem> recordings = loadVisibleDownloadingItems();
        recordingAdapter.setRecordings(recordings);
        updateStorageHealthCard();

        int monitoringCount = 0;

        for (ChannelItem channel : channels) {
            if (channel != null && channel.shouldMonitor()) {
                monitoringCount++;
            }
        }

        if (monitoringCount > 0) {
            binding.statusText.setText("Monitoring " + monitoringCount);
        } else {
            binding.statusText.setText("Remote config ready");
        }

        if (showingMonitoring) {
            binding.emptyMonitoringText.setVisibility(
                channels.isEmpty() ? View.VISIBLE : View.GONE
            );
        } else {
            binding.emptyDownloadsText.setVisibility(
                recordings.isEmpty() ? View.VISIBLE : View.GONE
            );
        }
    }


    private void reconcileStaleRecordingCards() {
        boolean changed = false;
        List<RecordingItem> recordings = storage.loadRecordings();

        for (RecordingItem recording : recordings) {
            if (recording == null || !RecordingItem.STATUS_RECORDING.equals(recording.getStatus())) {
                continue;
            }

            ChannelItem channel = storage.findChannelById(recording.getChannelId());
            if (channel == null) {
                continue;
            }

            boolean channelStillRecordingSameVideo = channel.isRecording()
                && (recording.getVideoId().trim().isEmpty() || channel.isSameCurrentVideo(recording.getVideoId()));

            if (!channelStillRecordingSameVideo) {
                channel.markRecording(recording.getVideoId(), recording.getVideoUrl());
                storage.upsertChannel(channel);
                changed = true;
            }

            if (recording.isHiddenFromDownloading()) {
                recording.showInDownloading();
                storage.upsertRecording(recording);
                changed = true;
            }
        }

        if (changed) {
            storage.appendLog(LogItem.info(LogItem.SOURCE_UI, "Reconciled active recording UI state."));
        }
    }

    private List<ChannelItem> loadVisibleMonitoringChannels() {
        List<ChannelItem> result = new ArrayList<>();

        for (ChannelItem channel : storage.loadChannels()) {
            if (channel != null && !channel.isRecording()) {
                result.add(channel);
            }
        }

        return result;
    }

    private List<RecordingItem> loadVisibleDownloadingItems() {
        List<RecordingItem> result = new ArrayList<>();

        for (RecordingItem recording : storage.loadActiveRecordings()) {
            if (recording != null && !recording.isHiddenFromDownloading()) {
                result.add(recording);
            }
        }

        return result;
    }

    private void updateStorageHealthCard() {
        AppSettings settings = storage.loadSettings();
        java.io.File externalDir = getExternalFilesDir(null);
        java.io.File baseDir = externalDir == null ? getFilesDir() : externalDir;
        long usable = baseDir.getUsableSpace();
        long total = baseDir.getTotalSpace();
        String freeSpace = RecordingProgressTracker.formatBytes(usable);
        int freePercent = total <= 0L ? 0 : Math.round((usable * 100f) / total);
        String folder = settings.getSaveLocationDisplayName();
        boolean hasCustomFolder = !settings.getSaveLocationUri().trim().isEmpty();
        String permission = hasCustomFolder && hasPersistedWritePermission(settings.getSaveLocationUri())
            ? "write permission valid"
            : hasCustomFolder ? "write permission needs reselect" : "using app storage";
        String freeValue = freeSpace;
        String freeUnit = "free";
        int unitSeparator = freeSpace.lastIndexOf(' ');
        if (unitSeparator > 0 && unitSeparator < freeSpace.length() - 1) {
            freeValue = freeSpace.substring(0, unitSeparator);
            freeUnit = freeSpace.substring(unitSeparator + 1) + " Free";
        }
        String optimizedLabel = hasCustomFolder && permission.contains("needs")
            ? "Status: Reselect folder"
            : "Status: Optimized";
        binding.storageGaugeView.setFreePercent(freePercent);
        binding.storageFreeValueText.setText(freeValue);
        binding.storageFreeLabelText.setText(freeUnit + " (" + freePercent + "%)");
        binding.storageHealthText.setText(optimizedLabel);
    }

    private boolean hasPersistedWritePermission(String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                if (permission.isWritePermission() && permission.getUri().equals(uri)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private boolean shouldShowHeaderStatus(String action, String message) {
        if (message == null || message.trim().isEmpty()) {
            return false;
        }

        if (LiveMonitorActions.ACTION_LOG_UPDATED.equals(action) || "MONITOR_LOG".equals(action)) {
            return false;
        }

        return true;
    }

    private String formatHeaderStatus(String message) {
        String trimmed = message == null ? "" : message.trim();

        if (trimmed.length() <= 24) {
            return trimmed;
        }

        return trimmed.substring(0, 21) + "...";
    }

    private void fetchRemoteConfigOnStart() {
        RemoteConfigFetcher fetcher = new RemoteConfigFetcher(this);
        fetcher.fetchAsync((config, fromNetwork, message) -> runOnUiThread(() -> {
            storage.appendLog(new LogItem(
                fromNetwork ? LogItem.LEVEL_SUCCESS : LogItem.LEVEL_INFO,
                LogItem.SOURCE_REMOTE_CONFIG,
                "",
                "",
                "",
                "",
                message,
                config == null ? "" : config.buildDebugSummary()
            ));

            Intent updateIntent = new Intent(LiveMonitorActions.ACTION_REMOTE_CONFIG_UPDATED);
            updateIntent.putExtra(LiveMonitorActions.EXTRA_MESSAGE, "Remote config ready");
            LocalBroadcastManager.getInstance(this).sendBroadcast(updateIntent);
        }));
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }

        if (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        ActivityCompat.requestPermissions(
            this,
            new String[] { Manifest.permission.POST_NOTIFICATIONS },
            1
        );
    }

    private static String cleanUrl(String value) {
        if (value == null) {
            return "";
        }

        return value
            .trim()
            .replaceAll("\\s+", "")
            .replaceAll("/+$", "");
    }

    private int getColorCompat(String color) {
        return android.graphics.Color.parseColor(color);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
