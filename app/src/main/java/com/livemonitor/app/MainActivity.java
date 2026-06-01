package com.livemonitor.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
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
 * - Downloads tab
 * - 3-dot menu for logs, downloaded files, settings
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private AppStorage storage;
    private ChannelAdapter channelAdapter;
    private RecordingAdapter recordingAdapter;
    private BroadcastReceiver updateReceiver;

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
            public void onRecoverClicked(RecordingItem recording) {
                storage.appendLog(LogItem.recording(
                    LogItem.LEVEL_INFO,
                    LogItem.SOURCE_UI,
                    recording,
                    "Recover selected from Downloads tab."
                ));
                Toast.makeText(
                    MainActivity.this,
                    "Recovery will run after recorder integration.",
                    Toast.LENGTH_SHORT
                ).show();
            }

            @Override
            public void onDeleteClicked(RecordingItem recording) {
                confirmStopDownload(recording);
            }
        });

        binding.channelListView.setAdapter(channelAdapter);
        binding.channelListView.setEmptyView(binding.emptyMonitoringText);

        binding.recordingListView.setAdapter(recordingAdapter);
        binding.recordingListView.setEmptyView(binding.emptyDownloadsText);
    }

    private void setupClickListeners() {
        binding.btnAddChannel.setOnClickListener(v -> addChannelFromInput());

        binding.navMonitoring.setOnClickListener(v -> showMonitoringTab());
        binding.navDownloads.setOnClickListener(v -> showDownloadsTab());

        binding.btnMenu.setOnClickListener(v -> showOverflowMenu());
    }

    private void setupUpdateReceiver() {
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }

                refreshAll();

                String message = intent.getStringExtra(LiveMonitorActions.EXTRA_MESSAGE);

                if (message != null && !message.trim().isEmpty()) {
                    binding.statusText.setText(message);
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

    private void addChannelFromInput() {
        String url = cleanUrl(binding.urlInput.getText().toString());

        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter a YouTube channel URL.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (YouTubeUrlUtils.isDirectVideoUrl(url)) {
            confirmDownloadVideoUrl(url);
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
        storage.removeChannel(channel.getId());
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

        new AlertDialog.Builder(this)
            .setTitle("Stop download?")
            .setMessage("Stop further downloading and keep the file saved so far?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Stop", (dialog, which) -> stopDownload(recording))
            .show();
    }

    private void stopDownload(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(LiveMonitorActions.ACTION_STOP_RECORDING);
        intent.putExtra(LiveMonitorActions.EXTRA_RECORDING_ID, recording.getId());
        intent.putExtra(LiveMonitorActions.EXTRA_CHANNEL_ID, recording.getChannelId());
        startServiceCompat(intent);

        recording.markStoppedByUser();
        storage.upsertRecording(recording);

        if (recording.getChannelId() != null && !recording.getChannelId().trim().isEmpty()) {
            storage.removeChannel(recording.getChannelId());
        }

        refreshAll();
        Toast.makeText(this, "Download stopped. Saved file kept.", Toast.LENGTH_SHORT).show();
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

        binding.navMonitoringText.setTextColor(getColorCompat("#00A884"));
        binding.navMonitoringIcon.setTextColor(getColorCompat("#00A884"));
        binding.navDownloadsText.setTextColor(getColorCompat("#667781"));
        binding.navDownloadsIcon.setTextColor(getColorCompat("#667781"));

        refreshAll();
    }

    private void showDownloadsTab() {
        showingMonitoring = false;

        binding.monitoringPanel.setVisibility(View.GONE);
        binding.downloadsPanel.setVisibility(View.VISIBLE);

        binding.navMonitoringText.setTextColor(getColorCompat("#667781"));
        binding.navMonitoringIcon.setTextColor(getColorCompat("#667781"));
        binding.navDownloadsText.setTextColor(getColorCompat("#00A884"));
        binding.navDownloadsIcon.setTextColor(getColorCompat("#00A884"));

        refreshAll();
    }

    private void refreshAll() {
        List<ChannelItem> channels = storage.loadChannels();
        channelAdapter.setChannels(channels);

        List<RecordingItem> recordings = new ArrayList<>();
        recordings.addAll(storage.loadActiveRecordings());
        recordings.addAll(storage.loadCompletedRecordings());
        recordingAdapter.setRecordings(recordings);

        int monitoringCount = 0;

        for (ChannelItem channel : channels) {
            if (channel != null && channel.shouldMonitor()) {
                monitoringCount++;
            }
        }

        if (monitoringCount > 0) {
            binding.statusText.setText("Monitoring " + monitoringCount);
        } else {
            binding.statusText.setText("Ready");
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
}
