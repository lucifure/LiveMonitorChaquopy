package com.livemonitor.app;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Size;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapter for Downloads tab.
 *
 * Shows:
 * - active recordings
 * - completed recordings
 * - recoverable TS files
 * - duration
 * - recorded size
 * - status
 */
public class RecordingAdapter extends BaseAdapter {

    public enum Mode {
        DOWNLOADING,
        DOWNLOADED
    }

    public interface Listener {
        void onRecordingClicked(RecordingItem recording);

        void onOpenFileClicked(RecordingItem recording);

        void onPauseResumeClicked(RecordingItem recording);

        void onDeleteClicked(RecordingItem recording);

        void onSelectionChanged(int selectedCount);
    }

    private final Context context;
    private final List<RecordingItem> recordings;
    private Listener listener;
    private Mode mode;
    private final Set<String> selectedRecordingIds = new HashSet<>();
    private final Map<String, Long> mediaDurationCacheMs = new HashMap<>();
    private final Map<String, Bitmap> thumbnailCache = new HashMap<>();
    private final Set<String> pendingThumbnailLoads = new HashSet<>();
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public RecordingAdapter(Context context) {
        this.context = context;
        this.recordings = new ArrayList<>();
        this.mode = Mode.DOWNLOADING;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DOWNLOADING : mode;
        notifyDataSetChanged();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setRecordings(List<RecordingItem> newRecordings) {
        recordings.clear();
        selectedRecordingIds.clear();
        mediaDurationCacheMs.clear();
        thumbnailCache.clear();
        pendingThumbnailLoads.clear();

        if (newRecordings != null) {
            recordings.addAll(newRecordings);
        }

        notifySelectionChanged();
        notifyDataSetChanged();
    }

    public List<RecordingItem> getSelectedRecordings() {
        List<RecordingItem> selected = new ArrayList<>();
        for (RecordingItem recording : recordings) {
            if (recording != null && selectedRecordingIds.contains(recording.getId())) {
                selected.add(recording);
            }
        }
        return selected;
    }

    public void clearSelection() {
        if (selectedRecordingIds.isEmpty()) {
            return;
        }
        selectedRecordingIds.clear();
        notifySelectionChanged();
        notifyDataSetChanged();
    }

    public void selectAll() {
        selectedRecordingIds.clear();
        for (RecordingItem recording : recordings) {
            if (recording != null && recording.getId() != null) {
                selectedRecordingIds.add(recording.getId());
            }
        }
        notifySelectionChanged();
        notifyDataSetChanged();
    }

    public boolean hasSelection() {
        return !selectedRecordingIds.isEmpty();
    }

    private void toggleSelection(RecordingItem recording) {
        if (recording == null || recording.getId() == null) {
            return;
        }
        if (selectedRecordingIds.contains(recording.getId())) {
            selectedRecordingIds.remove(recording.getId());
        } else {
            selectedRecordingIds.add(recording.getId());
        }
        notifySelectionChanged();
        notifyDataSetChanged();
    }

    private void notifySelectionChanged() {
        if (listener != null) {
            listener.onSelectionChanged(selectedRecordingIds.size());
        }
    }

    public void addOrUpdate(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        for (int i = 0; i < recordings.size(); i++) {
            RecordingItem existing = recordings.get(i);

            if (existing != null && existing.getId().equals(recording.getId())) {
                recordings.set(i, recording);
                notifyDataSetChanged();
                return;
            }
        }

        recordings.add(recording);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return recordings.size();
    }

    @Override
    public RecordingItem getItem(int position) {
        return recordings.get(position);
    }

    @Override
    public long getItemId(int position) {
        RecordingItem recording = getItem(position);

        if (recording == null || recording.getId() == null) {
            return position;
        }

        return recording.getId().hashCode();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        RecordingViewHolder holder;

        if (convertView == null) {
            holder = createViewHolder();
            convertView = holder.root;
            convertView.setTag(holder);
        } else {
            holder = (RecordingViewHolder) convertView.getTag();
        }

        RecordingItem recording = getItem(position);
        bind(holder, recording);

        return convertView;
    }

    private RecordingViewHolder createViewHolder() {
        FrameLayout itemRoot = new FrameLayout(context);
        itemRoot.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setBackgroundResource(R.drawable.lm_card_background);
        root.setElevation(dp(6));
        itemRoot.addView(
            root,
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout thumbnailFrame = new FrameLayout(context);
        LinearLayout.LayoutParams thumbnailParams = new LinearLayout.LayoutParams(dp(116), dp(66));
        thumbnailParams.rightMargin = dp(12);

        ImageView thumbnail = new ImageView(context);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackground(rounded(Color.rgb(18, 24, 32), dp(8), Color.rgb(48, 56, 68)));
        thumbnailFrame.addView(thumbnail, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView thumbnailDuration = new TextView(context);
        thumbnailDuration.setTextColor(Color.WHITE);
        thumbnailDuration.setTextSize(12);
        thumbnailDuration.setTypeface(Typeface.DEFAULT_BOLD);
        thumbnailDuration.setGravity(Gravity.CENTER);
        thumbnailDuration.setPadding(dp(6), dp(2), dp(6), dp(2));
        thumbnailDuration.setBackground(rounded(Color.argb(190, 0, 0, 0), dp(3), Color.TRANSPARENT));
        FrameLayout.LayoutParams durationParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM);
        durationParams.setMargins(0, 0, dp(6), dp(5));
        thumbnailFrame.addView(thumbnailDuration, durationParams);
        topRow.addView(thumbnailFrame, thumbnailParams);

        TextView title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(false);

        topRow.addView(
            title,
            new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        );

        TextView statusBadge = new TextView(context);
        statusBadge.setTextColor(Color.WHITE);
        statusBadge.setTextSize(12);
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
        topRow.addView(statusBadge);

        TextView subtitle = new TextView(context);
        subtitle.setTextColor(Color.rgb(190, 190, 190));
        subtitle.setTextSize(12);
        subtitle.setPadding(0, dp(4), 0, 0);

        TextView details = new TextView(context);
        details.setTextColor(Color.rgb(160, 160, 160));
        details.setTextSize(11);
        details.setPadding(0, dp(4), 0, 0);

        RecordingLagProgressView progressBar = new RecordingLagProgressView(context);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(8)
        );
        progressParams.topMargin = dp(8);
        progressBar.setLayoutParams(progressParams);

        TextView progressLabel = new TextView(context);
        progressLabel.setTextColor(Color.rgb(150, 160, 168));
        progressLabel.setTextSize(11);
        progressLabel.setPadding(0, dp(4), 0, 0);

        LinearLayout statsRow = new LinearLayout(context);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setPadding(0, dp(8), 0, 0);

        TextView durationStat = createStatBox();
        TextView sizeStat = createStatBox();
        TextView qualityStat = createStatBox();
        statsRow.addView(durationStat, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams statParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statParams.leftMargin = dp(8);
        statsRow.addView(sizeStat, statParams);
        LinearLayout.LayoutParams qualityParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qualityParams.leftMargin = dp(8);
        statsRow.addView(qualityStat, qualityParams);

        TextView savedTo = new TextView(context);
        savedTo.setTextColor(Color.rgb(22, 199, 132));
        savedTo.setTextSize(12);
        savedTo.setPadding(0, dp(6), 0, 0);

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.END);
        buttonRow.setPadding(0, dp(8), 0, 0);

        Button openButton = new Button(context);
        openButton.setAllCaps(false);
        openButton.setText("▶  Open");
        styleCardActionButton(openButton);

        Button pauseResumeButton = new Button(context);
        pauseResumeButton.setAllCaps(false);
        pauseResumeButton.setText("Pause");
        styleCardActionButton(pauseResumeButton);

        Button deleteButton = new Button(context);
        deleteButton.setAllCaps(false);
        deleteButton.setText("Delete");
        styleCardActionButton(deleteButton);
        deleteButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete_24, 0, 0, 0);
        deleteButton.setCompoundDrawablePadding(dp(4));

        buttonRow.addView(openButton, new LinearLayout.LayoutParams(0, dp(40), 1f));

        LinearLayout.LayoutParams pauseParams = new LinearLayout.LayoutParams(
            0,
            dp(40),
            1f
        );
        pauseParams.leftMargin = dp(8);
        buttonRow.addView(pauseResumeButton, pauseParams);

        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
            0,
            dp(40),
            1f
        );
        deleteParams.leftMargin = dp(8);
        buttonRow.addView(deleteButton, deleteParams);

        root.addView(topRow);
        root.addView(subtitle);
        root.addView(details);
        root.addView(statsRow);
        root.addView(savedTo);
        root.addView(progressBar);
        root.addView(progressLabel);
        root.addView(buttonRow);

        RecordingViewHolder holder = new RecordingViewHolder();
        holder.root = itemRoot;
        holder.title = title;
        holder.statusBadge = statusBadge;
        holder.subtitle = subtitle;
        holder.details = details;
        holder.durationStat = durationStat;
        holder.sizeStat = sizeStat;
        holder.qualityStat = qualityStat;
        holder.statsRow = statsRow;
        holder.savedTo = savedTo;
        holder.progressBar = progressBar;
        holder.progressLabel = progressLabel;
        holder.openButton = openButton;
        holder.pauseResumeButton = pauseResumeButton;
        holder.deleteButton = deleteButton;
        holder.thumbnail = thumbnail;
        holder.thumbnailFrame = thumbnailFrame;
        holder.thumbnailDuration = thumbnailDuration;

        return holder;
    }

    private void bind(RecordingViewHolder holder, RecordingItem recording) {
        if (recording == null) {
            holder.title.setText("Unknown recording");
            holder.subtitle.setText("");
            holder.details.setText("");
            holder.statusBadge.setText("Unknown");
            holder.progressBar.setProgressFraction(0f);
            holder.progressLabel.setText("");
            holder.savedTo.setVisibility(View.GONE);
            holder.openButton.setVisibility(View.GONE);
            holder.pauseResumeButton.setVisibility(View.GONE);
            holder.deleteButton.setVisibility(View.GONE);
            if (holder.thumbnailDuration != null) holder.thumbnailDuration.setVisibility(View.GONE);
            return;
        }

        holder.title.setText(recording.getDisplayTitle());
        holder.subtitle.setText(recording.getDisplaySubtitle());
        holder.details.setText(buildDetails(recording));
        long bytesRec = recording.getBytesRecorded();
        long totalMs = estimateDisplayedRecordedMs(recording, System.currentTimeMillis());
        long sessionDurationSeconds = recording.isActive() && recording.getStartedAt() > 0L
            ? Math.max(0L, (System.currentTimeMillis() - recording.getStartedAt()) / 1_000L)
            : Math.max(0L, totalMs / 1_000L);
        holder.durationStat.setText("⏱ " + RecordingProgressTracker.formatDuration(sessionDurationSeconds));
        boolean showStartingSize = bytesRec <= 0L && recording.isActive() && sessionDurationSeconds < 10L;
        holder.sizeStat.setText(showStartingSize ? "💾 Starting…" : "💾 " + RecordingProgressTracker.formatBytes(bytesRec));
        holder.qualityStat.setText("📺 " + recording.getQuality());
        String savedToDisplay = recording.getSavedToDisplay();
        holder.savedTo.setText(savedToDisplay.trim().isEmpty() ? "" : "Saved to: " + savedToDisplay);
        holder.savedTo.setVisibility(savedToDisplay.trim().isEmpty() ? View.GONE : View.VISIBLE);
        holder.statusBadge.setText(formatStatus(recording.getStatus()));
        applyStatusBadge(holder.statusBadge, recording.getStatus());
        bindProgress(holder, recording);

        boolean downloadedMode = mode == Mode.DOWNLOADED;
        boolean activeDownload = !downloadedMode && recording.isActive();

        boolean hasPlayableFile = !recording.getBestPlayablePath().trim().isEmpty();
        boolean canDelete = activeDownload || (downloadedMode && hasPlayableFile);

        holder.openButton.setVisibility(hasPlayableFile ? View.VISIBLE : View.GONE);
        holder.pauseResumeButton.setVisibility(activeDownload ? View.VISIBLE : View.GONE);
        holder.pauseResumeButton.setEnabled(true);
        holder.pauseResumeButton.setAlpha(1f);
        holder.pauseResumeButton.setText(recording.isPausedByUser() ? "▶  Resume" : "Ⅱ  Pause");
        holder.deleteButton.setVisibility(canDelete && !downloadedMode ? View.VISIBLE : View.GONE);
        holder.deleteButton.setEnabled(true);
        holder.deleteButton.setAlpha(1f);
        applyDownloadedCompactLayout(holder, recording, downloadedMode);

        if (downloadedMode) {
            holder.deleteButton.setText("Delete");
            styleCardActionButton(holder.deleteButton);
            holder.deleteButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete_24, 0, 0, 0);
            holder.deleteButton.setCompoundDrawablePadding(dp(4));
        } else {
            holder.deleteButton.setText("■  Cancel");
            styleCardActionButton(holder.deleteButton);
            holder.deleteButton.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
        }

        holder.root.setOnClickListener(v -> {
            if (downloadedMode && hasSelection()) {
                toggleSelection(recording);
                return;
            }
            if (listener != null) {
                listener.onRecordingClicked(recording);
            }
        });

        holder.root.setOnLongClickListener(v -> {
            if (downloadedMode) {
                toggleSelection(recording);
                return true;
            }
            return false;
        });

        holder.openButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onOpenFileClicked(recording);
            }
        });

        holder.pauseResumeButton.setOnClickListener(v -> {
            boolean wasPaused = recording.isPausedByUser();
            holder.pauseResumeButton.setText(wasPaused ? "Ⅱ  Pause" : "▶  Resume");
            setActionButtonsEnabled(holder, false);
            holder.pauseResumeButton.postDelayed(() -> setActionButtonsEnabled(holder, true), 2_000L);
            if (listener != null) {
                listener.onPauseResumeClicked(recording);
            }
        });

        holder.deleteButton.setOnClickListener(v -> {
            setActionButtonsEnabled(holder, false);
            if (activeDownload) {
                holder.deleteButton.setText("Stopping…");
            }
            if (listener != null) {
                listener.onDeleteClicked(recording);
            }
        });
    }

    private void setActionButtonsEnabled(RecordingViewHolder holder, boolean enabled) {
        holder.pauseResumeButton.setEnabled(enabled);
        holder.pauseResumeButton.setAlpha(enabled ? 1f : 0.65f);
        holder.deleteButton.setEnabled(enabled);
        holder.deleteButton.setAlpha(enabled ? 1f : 0.65f);
    }

    private String buildDetails(RecordingItem recording) {
        StringBuilder builder = new StringBuilder();

        builder.append(diagnosticLabel(recording));

        if (recording.getVideoId() != null && !recording.getVideoId().trim().isEmpty()) {
            builder.append(" • videoId=");
            builder.append(recording.getVideoId());
        }

        if (mode != Mode.DOWNLOADED && recording.getErrorMessage() != null && !recording.getErrorMessage().trim().isEmpty()) {
            builder.append("\n");
            builder.append(compactErrorMessage(recording.getErrorMessage()));
        }

        return builder.toString();
    }

    private String compactErrorMessage(String message) {
        if (message == null) return "";
        String[] lines = message.split("\\r?\\n");
        StringBuilder builder = new StringBuilder();
        int added = 0;
        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("Retrying (") || trimmed.contains("Video is no longer live")) {
                continue;
            }
            if (added > 0) builder.append("\n");
            builder.append(trimmed);
            added++;
            if (added >= 2) break;
        }
        return builder.length() == 0 ? "See Logs for recorder retry details." : builder.toString();
    }

    private void applyDownloadedCompactLayout(RecordingViewHolder holder, RecordingItem recording, boolean downloadedMode) {
        boolean selected = downloadedMode && recording != null && selectedRecordingIds.contains(recording.getId());
        holder.root.setForeground(selected ? rounded(Color.argb(75, 93, 216, 232), dp(12), Color.rgb(93, 216, 232)) : null);
        holder.statusBadge.setVisibility(downloadedMode ? View.GONE : View.VISIBLE);
        holder.details.setVisibility(downloadedMode ? View.GONE : View.VISIBLE);
        holder.subtitle.setVisibility(downloadedMode ? View.GONE : View.VISIBLE);
        holder.statsRow.setVisibility(downloadedMode ? View.GONE : View.VISIBLE);
        holder.savedTo.setVisibility(downloadedMode ? View.GONE : holder.savedTo.getVisibility());
        holder.progressBar.setVisibility(downloadedMode ? View.GONE : View.VISIBLE);
        holder.progressLabel.setVisibility(downloadedMode ? View.GONE : View.VISIBLE);
        holder.openButton.setVisibility(downloadedMode ? View.GONE : holder.openButton.getVisibility());
        holder.pauseResumeButton.setVisibility(downloadedMode ? View.GONE : holder.pauseResumeButton.getVisibility());
        holder.deleteButton.setVisibility(downloadedMode ? View.GONE : holder.deleteButton.getVisibility());
        holder.thumbnailFrame.setVisibility(downloadedMode ? View.VISIBLE : View.GONE);
        holder.thumbnail.setVisibility(downloadedMode ? View.VISIBLE : View.GONE);
        if (downloadedMode) {
            bindThumbnail(holder.thumbnail, recording);
            bindThumbnailDuration(holder.thumbnailDuration, recording);
        } else {
            holder.thumbnail.setImageDrawable(null);
            holder.thumbnailDuration.setVisibility(View.GONE);
        }
        holder.title.setTextSize(downloadedMode ? 16 : 15);
        holder.subtitle.setText(recording.getDisplaySubtitle());
    }

    private void bindThumbnail(ImageView thumbnail, RecordingItem recording) {
        if (thumbnail == null || recording == null) return;

        String thumbnailKey = buildThumbnailKey(recording);
        if (thumbnailKey.isEmpty()) {
            thumbnail.setTag(null);
            thumbnail.setImageDrawable(null);
            return;
        }

        thumbnail.setTag(thumbnailKey);
        Bitmap cached = thumbnailCache.get(thumbnailKey);
        if (cached != null && !cached.isRecycled()) {
            thumbnail.setImageBitmap(cached);
            return;
        }

        thumbnail.setImageDrawable(null);
        loadThumbnailAsync(thumbnail, recording, thumbnailKey);
    }

    private String buildThumbnailKey(RecordingItem recording) {
        if (recording == null) {
            return "";
        }
        String videoId = resolveVideoId(recording);
        if (!videoId.isEmpty()) {
            return "youtube:" + videoId;
        }
        String path = recording.getBestPlayablePath();
        if (path != null && !path.trim().isEmpty()) {
            String normalizedPath = path.trim();
            if (isContentUri(normalizedPath) || new File(normalizedPath).exists()) {
                return "file:" + normalizedPath;
            }
        }
        return "";
    }

    private String resolveVideoId(RecordingItem recording) {
        if (recording == null) {
            return "";
        }
        String videoId = recording.getVideoId();
        if (videoId != null && !videoId.trim().isEmpty()) {
            return videoId.trim();
        }
        return extractVideoIdFromUrl(recording.getVideoUrl());
    }

    private String extractVideoIdFromUrl(String videoUrl) {
        if (videoUrl == null || videoUrl.trim().isEmpty()) {
            return "";
        }

        String trimmedUrl = videoUrl.trim();
        int watchIndex = trimmedUrl.indexOf("v=");
        if (watchIndex >= 0) {
            return trimVideoIdAtSeparator(trimmedUrl.substring(watchIndex + 2));
        }

        int shortIndex = trimmedUrl.indexOf("youtu.be/");
        if (shortIndex >= 0) {
            return trimVideoIdAtSeparator(trimmedUrl.substring(shortIndex + "youtu.be/".length()));
        }

        int shortsIndex = trimmedUrl.indexOf("/shorts/");
        if (shortsIndex >= 0) {
            return trimVideoIdAtSeparator(trimmedUrl.substring(shortsIndex + "/shorts/".length()));
        }

        return "";
    }

    private String trimVideoIdAtSeparator(String candidate) {
        if (candidate == null) {
            return "";
        }
        int end = candidate.length();
        char[] separators = new char[] {'&', '?', '#', '/'};
        for (char separator : separators) {
            int separatorIndex = candidate.indexOf(separator);
            if (separatorIndex >= 0) {
                end = Math.min(end, separatorIndex);
            }
        }
        return candidate.substring(0, end).trim();
    }

    private void loadThumbnailAsync(ImageView thumbnail, RecordingItem recording, String thumbnailKey) {
        if (pendingThumbnailLoads.contains(thumbnailKey)) {
            return;
        }

        pendingThumbnailLoads.add(thumbnailKey);
        String videoId = resolveVideoId(recording);
        String path = recording.getBestPlayablePath();
        thumbnailExecutor.execute(() -> {
            Bitmap bitmap = createRemoteVideoThumbnail(videoId);
            if (bitmap == null && path != null && !path.trim().isEmpty() && isReadableVideoSource(path.trim())) {
                bitmap = createLocalVideoThumbnail(path.trim());
            }

            Bitmap loadedBitmap = bitmap;
            mainHandler.post(() -> {
                pendingThumbnailLoads.remove(thumbnailKey);
                if (loadedBitmap != null) {
                    thumbnailCache.put(thumbnailKey, loadedBitmap);
                    if (thumbnailKey.equals(thumbnail.getTag())) {
                        thumbnail.setImageBitmap(loadedBitmap);
                    }
                    notifyDataSetChanged();
                }
            });
        });
    }

    private Bitmap createRemoteVideoThumbnail(String videoId) {
        if (videoId == null || videoId.trim().isEmpty()) {
            return null;
        }

        String normalizedVideoId = videoId.trim();
        String[] thumbnailUrls = new String[] {
            "https://i.ytimg.com/vi/" + normalizedVideoId + "/maxresdefault.jpg",
            "https://i.ytimg.com/vi/" + normalizedVideoId + "/hqdefault.jpg",
            "https://i.ytimg.com/vi/" + normalizedVideoId + "/mqdefault.jpg"
        };

        for (String thumbnailUrl : thumbnailUrls) {
            Bitmap bitmap = downloadBitmap(thumbnailUrl);
            if (bitmap != null) {
                return bitmap;
            }
        }
        return null;
    }

    private Bitmap downloadBitmap(String thumbnailUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(thumbnailUrl).openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            connection.setInstanceFollowRedirects(true);
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                return null;
            }
            try (InputStream inputStream = connection.getInputStream()) {
                return BitmapFactory.decodeStream(inputStream);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Bitmap createLocalVideoThumbnail(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }

        String normalizedPath = path.trim();
        Bitmap bitmap = isContentUri(normalizedPath) ? null : createPlatformLocalVideoThumbnail(normalizedPath);
        if (bitmap != null) {
            return bitmap;
        }

        return createRetrieverLocalVideoThumbnail(normalizedPath);
    }

    private Bitmap createPlatformLocalVideoThumbnail(String path) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return ThumbnailUtils.createVideoThumbnail(new File(path), new Size(dp(232), dp(132)), null);
            }
            return ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Bitmap createRetrieverLocalVideoThumbnail(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            setRetrieverDataSource(retriever, path);
            long durationUs = Math.max(0L, readMediaDurationMs(path) * 1_000L);
            long preferredFrameUs = durationUs > 0L ? Math.min(durationUs / 10L, 30_000_000L) : 1_000_000L;
            long[] frameTimesUs = new long[] {preferredFrameUs, 1_000_000L, 5_000_000L, 0L};
            for (long frameTimeUs : frameTimesUs) {
                Bitmap frame = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (frame != null) {
                    return frame;
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Ignore cleanup failures.
            }
        }
    }

    private void bindThumbnailDuration(TextView durationView, RecordingItem recording) {
        if (durationView == null || recording == null) return;
        long seconds = getPlayableFileDurationSeconds(recording);
        durationView.setText(RecordingProgressTracker.formatDuration(seconds));
        durationView.setVisibility(seconds > 0L ? View.VISIBLE : View.GONE);
    }

    private long getPlayableFileDurationSeconds(RecordingItem recording) {
        if (recording == null) {
            return 0L;
        }
        long mediaDurationMs = readMediaDurationMs(recording.getBestPlayablePath());
        if (mediaDurationMs > 0L) {
            return Math.max(1L, mediaDurationMs / 1_000L);
        }
        return Math.max(0L, recording.getDurationSeconds());
    }

    private long readMediaDurationMs(String path) {
        if (path == null || path.trim().isEmpty()) {
            return 0L;
        }

        String normalizedPath = path.trim();
        if (!isReadableVideoSource(normalizedPath)) {
            return 0L;
        }
        Long cachedDuration = mediaDurationCacheMs.get(normalizedPath);
        if (cachedDuration != null) {
            return cachedDuration;
        }

        long durationMs = readExtractorDurationMs(normalizedPath);
        if (durationMs <= 0L) {
            durationMs = readMetadataRetrieverDurationMs(normalizedPath);
        }
        mediaDurationCacheMs.put(normalizedPath, durationMs);
        return durationMs;
    }

    private long readExtractorDurationMs(String path) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            if (isContentUri(path)) {
                extractor.setDataSource(context, Uri.parse(path), null);
            } else {
                extractor.setDataSource(path);
            }
            long maxDurationUs = 0L;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    maxDurationUs = Math.max(maxDurationUs, format.getLong(MediaFormat.KEY_DURATION));
                }
            }
            return maxDurationUs > 0L ? maxDurationUs / 1_000L : 0L;
        } catch (Exception ignored) {
            return 0L;
        } finally {
            extractor.release();
        }
    }

    private long readMetadataRetrieverDurationMs(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            setRetrieverDataSource(retriever, path);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration == null || duration.trim().isEmpty()) {
                return 0L;
            }
            return Math.max(0L, Long.parseLong(duration.trim()));
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Ignore cleanup failures.
            }
        }
    }

    private boolean isReadableVideoSource(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        String normalizedPath = path.trim();
        if (isContentUri(normalizedPath)) {
            return true;
        }
        return new File(normalizedPath).exists();
    }

    private boolean isContentUri(String path) {
        return path != null && path.trim().toLowerCase(java.util.Locale.US).startsWith("content://");
    }

    private void setRetrieverDataSource(MediaMetadataRetriever retriever, String path) {
        if (isContentUri(path)) {
            retriever.setDataSource(context, Uri.parse(path));
        } else {
            retriever.setDataSource(path);
        }
    }

    private String formatStatus(String status) {
        if (RecordingItem.STATUS_WAITING_FOR_LIVE.equals(status)) {
            return "Waiting";
        }

        if (RecordingItem.STATUS_RECORDING.equals(status)) {
            return "● REC";
        }

        if (RecordingItem.STATUS_PAUSED_NETWORK.equals(status)) {
            return "Network";
        }

        if (RecordingItem.STATUS_PAUSED_BY_USER.equals(status)) {
            return "Paused";
        }

        if (RecordingItem.STATUS_CONVERTING.equals(status)) {
            return "🔄 Converting";
        }

        if (RecordingItem.STATUS_COPYING.equals(status)) {
            return "Copying";
        }

        if (RecordingItem.STATUS_COMPLETED.equals(status)) {
            return "✅ Completed";
        }

        if (RecordingItem.STATUS_FAILED.equals(status)) {
            return "❌ Failed";
        }

        if (RecordingItem.STATUS_STOPPED_BY_USER.equals(status)) {
            return "⏹ Stopped";
        }

        if (RecordingItem.STATUS_STOPPED_BY_SYSTEM.equals(status)) {
            return "⚠️ Stopped";
        }

        if (RecordingItem.STATUS_RECOVERABLE.equals(status)) {
            return "Recover";
        }

        return "Unknown";
    }

    private void applyStatusBadge(TextView badge, String status) {
        if (RecordingItem.STATUS_RECORDING.equals(status)) {
            badge.setBackgroundResource(R.drawable.lm_status_recording_background);
            badge.setTextColor(context.getResources().getColor(R.color.lm_status_recording_text));
            return;
        }
        if (RecordingItem.STATUS_COMPLETED.equals(status)) {
            badge.setBackgroundResource(R.drawable.lm_status_recording_background);
            badge.setTextColor(context.getResources().getColor(R.color.lm_status_recording_text));
            return;
        }
        if (RecordingItem.STATUS_PAUSED_BY_USER.equals(status) || RecordingItem.STATUS_PAUSED_NETWORK.equals(status)) {
            badge.setBackgroundResource(R.drawable.lm_status_paused_background);
            badge.setTextColor(context.getResources().getColor(R.color.lm_status_paused_text));
            return;
        }
        badge.setBackgroundResource(R.drawable.lm_status_stopped_background);
        badge.setTextColor(context.getResources().getColor(R.color.lm_status_stopped_text));
    }

    private void bindProgress(RecordingViewHolder holder, RecordingItem recording) {
        long now = System.currentTimeMillis();
        long totalRecordedMs = estimateDisplayedRecordedMs(recording, now);
        long totalRecordedSeconds = totalRecordedMs / 1_000L;

        if (!recording.isActive() || RecordingItem.STATUS_COMPLETED.equals(recording.getStatus()) || recording.isFinished()) {
            holder.progressBar.setProgressFraction(1f);
            holder.progressLabel.setText("🎬 Recorded: " + RecordingProgressTracker.formatDuration(totalRecordedSeconds));
            return;
        }

        long streamStartedAt = recording.getStreamStartedAt();
        long streamAgeMs = streamStartedAt > 0L ? Math.max(0L, now - streamStartedAt) : 0L;
        if (streamAgeMs > 0L) {
            holder.progressBar.setProgressFraction(Math.min(1f, (float) totalRecordedMs / (float) streamAgeMs));
        } else {
            holder.progressBar.setProgressFraction(recording.getProgressPercent() / 100f);
        }

        long behindMs = estimateBehindLiveMs(recording, now, streamAgeMs);
        String behindLabel = behindMs < 60_000L
            ? "📡 ~" + Math.max(0L, behindMs / 1_000L) + "s behind (near live)"
            : "📡 Behind: " + RecordingProgressTracker.formatDuration(behindMs / 1_000L);
        holder.progressLabel.setText(behindLabel);
    }

    private long estimateBehindLiveMs(RecordingItem recording, long now, long streamAgeMs) {
        if (recording == null || streamAgeMs <= 0L) {
            return 0L;
        }

        long recordedMs = Math.max(0L, recording.getCombinedTotalRecordedDurationMs());
        if (recordedMs <= 0L && recording.getStartedAt() > 0L) {
            recordedMs = Math.max(0L, now - recording.getStartedAt());
        }

        return Math.max(0L, streamAgeMs - recordedMs);
    }

    private long estimateDisplayedRecordedMs(RecordingItem recording, long now) {
        if (recording == null) {
            return 0L;
        }

        long recordedMs = recording.getCombinedTotalRecordedDurationMs();
        long streamStartedAt = recording.getStreamStartedAt();
        if (recording.isActive() && streamStartedAt > 0L) {
            recordedMs = Math.max(recordedMs, Math.max(0L, now - streamStartedAt));
        }

        return Math.max(0L, recordedMs);
    }

    private void styleCardActionButton(Button button) {
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundResource(R.drawable.lm_action_button_background);
    }

    private TextView createStatBox() {
        TextView textView = new TextView(context);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(12);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(dp(6), dp(6), dp(6), dp(6));
        textView.setSingleLine(true);
        textView.setBackground(rounded(Color.rgb(36, 36, 36), dp(10), Color.rgb(48, 48, 48)));
        return textView;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private String diagnosticLabel(RecordingItem recording) {
        String status = recording.getStatus();
        if (RecordingItem.STATUS_WAITING_FOR_LIVE.equals(status)) return "Resolving stream";
        if (RecordingItem.STATUS_RECORDING.equals(status)) return "Recording";
        if (RecordingItem.STATUS_CONVERTING.equals(status)) return "Converting";
        if (RecordingItem.STATUS_COPYING.equals(status)) return "Copying to folder";
        if (RecordingItem.STATUS_COMPLETED.equals(status)) return "Saved";
        if (RecordingItem.STATUS_FAILED.equals(status)) return "Failed";
        if (RecordingItem.STATUS_STOPPED_BY_USER.equals(status)) return "Stopped by user";
        if (RecordingItem.STATUS_STOPPED_BY_SYSTEM.equals(status)) return "Stopped by system";
        if (RecordingItem.STATUS_RECOVERABLE.equals(status)) return "Stalled / recoverable";
        return "Status update";
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static class RecordingViewHolder {
        FrameLayout root;
        TextView title;
        TextView statusBadge;
        TextView subtitle;
        TextView details;
        TextView durationStat;
        TextView sizeStat;
        TextView qualityStat;
        LinearLayout statsRow;
        TextView savedTo;
        RecordingLagProgressView progressBar;
        TextView progressLabel;
        Button openButton;
        Button pauseResumeButton;
        Button deleteButton;
        FrameLayout thumbnailFrame;
        ImageView thumbnail;
        TextView thumbnailDuration;
    }
}
