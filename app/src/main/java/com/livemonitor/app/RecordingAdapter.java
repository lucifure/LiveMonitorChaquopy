package com.livemonitor.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

        ImageView thumbnail = new ImageView(context);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.setBackground(rounded(Color.rgb(18, 24, 32), dp(8), Color.rgb(48, 56, 68)));
        LinearLayout.LayoutParams thumbnailParams = new LinearLayout.LayoutParams(dp(116), dp(66));
        thumbnailParams.rightMargin = dp(12);
        topRow.addView(thumbnail, thumbnailParams);

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
        holder.thumbnail.setVisibility(downloadedMode ? View.VISIBLE : View.GONE);
        if (downloadedMode) {
            bindThumbnail(holder.thumbnail, recording);
        } else {
            holder.thumbnail.setImageDrawable(null);
        }
        holder.title.setTextSize(downloadedMode ? 16 : 15);
        holder.subtitle.setText(recording.getDisplaySubtitle());
    }

    private void bindThumbnail(ImageView thumbnail, RecordingItem recording) {
        if (thumbnail == null || recording == null) return;
        String path = recording.getBestPlayablePath();
        if (path == null || path.trim().isEmpty() || !new File(path).exists()) {
            thumbnail.setImageDrawable(null);
            return;
        }
        try {
            Bitmap bitmap = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND);
            thumbnail.setImageBitmap(bitmap);
        } catch (Exception ignored) {
            thumbnail.setImageDrawable(null);
        }
    }

    private String buildDownloadedSubtitle(RecordingItem recording) {
        if (recording == null) return "";
        String duration = RecordingProgressTracker.formatDuration(Math.max(0L, estimateDisplayedRecordedMs(recording, System.currentTimeMillis()) / 1_000L));
        String size = RecordingProgressTracker.formatBytes(recording.getBytesRecorded());
        return duration + " • " + size + " • " + recording.getQuality();
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

    private int statusColor(String status) {
        if (RecordingItem.STATUS_RECORDING.equals(status)) {
            return Color.rgb(220, 0, 0);
        }

        if (RecordingItem.STATUS_CONVERTING.equals(status) || RecordingItem.STATUS_COPYING.equals(status)) {
            return Color.rgb(255, 184, 77);
        }

        if (RecordingItem.STATUS_PAUSED_BY_USER.equals(status)) {
            return Color.rgb(80, 120, 220);
        }

        if (RecordingItem.STATUS_COMPLETED.equals(status)) {
            return Color.rgb(22, 199, 132);
        }

        if (RecordingItem.STATUS_RECOVERABLE.equals(status)) {
            return Color.rgb(80, 120, 220);
        }

        if (RecordingItem.STATUS_FAILED.equals(status)) {
            return Color.rgb(180, 0, 0);
        }

        return Color.rgb(100, 100, 100);
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
        ImageView thumbnail;
    }
}
