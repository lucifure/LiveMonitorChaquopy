package com.livemonitor.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

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

    public interface Listener {
        void onRecordingClicked(RecordingItem recording);

        void onOpenFileClicked(RecordingItem recording);

        void onRecoverClicked(RecordingItem recording);
    }

    private final Context context;
    private final List<RecordingItem> recordings;
    private Listener listener;

    public RecordingAdapter(Context context) {
        this.context = context;
        this.recordings = new ArrayList<>();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setRecordings(List<RecordingItem> newRecordings) {
        recordings.clear();

        if (newRecordings != null) {
            recordings.addAll(newRecordings);
        }

        notifyDataSetChanged();
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
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.setBackgroundColor(Color.WHITE);

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setTextColor(Color.rgb(25, 25, 25));
        title.setTextSize(16);
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
        subtitle.setTextColor(Color.rgb(90, 90, 90));
        subtitle.setTextSize(13);
        subtitle.setPadding(0, dp(4), 0, 0);

        TextView details = new TextView(context);
        details.setTextColor(Color.rgb(110, 110, 110));
        details.setTextSize(12);
        details.setPadding(0, dp(4), 0, 0);

        ProgressBar progressBar = new ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        );
        progressBar.setMax(100);
        progressBar.setPadding(0, dp(8), 0, 0);

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.END);
        buttonRow.setPadding(0, dp(8), 0, 0);

        Button openButton = new Button(context);
        openButton.setAllCaps(false);
        openButton.setText("Open");

        Button recoverButton = new Button(context);
        recoverButton.setAllCaps(false);
        recoverButton.setText("Recover");

        buttonRow.addView(openButton);

        LinearLayout.LayoutParams recoverParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        recoverParams.leftMargin = dp(8);
        buttonRow.addView(recoverButton, recoverParams);

        root.addView(topRow);
        root.addView(subtitle);
        root.addView(details);
        root.addView(progressBar);
        root.addView(buttonRow);

        RecordingViewHolder holder = new RecordingViewHolder();
        holder.root = root;
        holder.title = title;
        holder.statusBadge = statusBadge;
        holder.subtitle = subtitle;
        holder.details = details;
        holder.progressBar = progressBar;
        holder.openButton = openButton;
        holder.recoverButton = recoverButton;

        return holder;
    }

    private void bind(RecordingViewHolder holder, RecordingItem recording) {
        if (recording == null) {
            holder.title.setText("Unknown recording");
            holder.subtitle.setText("");
            holder.details.setText("");
            holder.statusBadge.setText("Unknown");
            holder.progressBar.setProgress(0);
            holder.openButton.setVisibility(View.GONE);
            holder.recoverButton.setVisibility(View.GONE);
            return;
        }

        holder.title.setText(recording.getDisplayTitle());
        holder.subtitle.setText(recording.getDisplaySubtitle());
        holder.details.setText(buildDetails(recording));
        holder.statusBadge.setText(formatStatus(recording.getStatus()));
        holder.statusBadge.setBackgroundColor(statusColor(recording.getStatus()));
        holder.progressBar.setProgress(recording.getProgressPercent());

        holder.openButton.setVisibility(recording.hasExistingFinalMp4File() ? View.VISIBLE : View.GONE);
        holder.recoverButton.setVisibility(recording.isRecoverable() ? View.VISIBLE : View.GONE);

        holder.root.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRecordingClicked(recording);
            }
        });

        holder.openButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onOpenFileClicked(recording);
            }
        });

        holder.recoverButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onRecoverClicked(recording);
            }
        });
    }

    private String buildDetails(RecordingItem recording) {
        StringBuilder builder = new StringBuilder();

        builder.append("Duration ");
        builder.append(RecordingProgressTracker.formatDuration(recording.getDurationSeconds()));

        builder.append(" • Size ");
        builder.append(RecordingProgressTracker.formatBytes(recording.getBytesRecorded()));

        if (recording.getVideoId() != null && !recording.getVideoId().trim().isEmpty()) {
            builder.append(" • videoId=");
            builder.append(recording.getVideoId());
        }

        if (recording.getErrorMessage() != null && !recording.getErrorMessage().trim().isEmpty()) {
            builder.append("\n");
            builder.append(recording.getErrorMessage());
        }

        return builder.toString();
    }

    private String formatStatus(String status) {
        if (RecordingItem.STATUS_WAITING_FOR_LIVE.equals(status)) {
            return "Waiting";
        }

        if (RecordingItem.STATUS_RECORDING.equals(status)) {
            return "Recording";
        }

        if (RecordingItem.STATUS_PAUSED_NETWORK.equals(status)) {
            return "Network";
        }

        if (RecordingItem.STATUS_CONVERTING.equals(status)) {
            return "Converting";
        }

        if (RecordingItem.STATUS_COMPLETED.equals(status)) {
            return "Done";
        }

        if (RecordingItem.STATUS_FAILED.equals(status)) {
            return "Failed";
        }

        if (RecordingItem.STATUS_STOPPED_BY_USER.equals(status)) {
            return "Stopped";
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

        if (RecordingItem.STATUS_CONVERTING.equals(status)) {
            return Color.rgb(255, 128, 0);
        }

        if (RecordingItem.STATUS_COMPLETED.equals(status)) {
            return Color.rgb(0, 168, 132);
        }

        if (RecordingItem.STATUS_RECOVERABLE.equals(status)) {
            return Color.rgb(80, 120, 220);
        }

        if (RecordingItem.STATUS_FAILED.equals(status)) {
            return Color.rgb(180, 0, 0);
        }

        return Color.rgb(100, 100, 100);
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static class RecordingViewHolder {
        LinearLayout root;
        TextView title;
        TextView statusBadge;
        TextView subtitle;
        TextView details;
        ProgressBar progressBar;
        Button openButton;
        Button recoverButton;
    }
}
