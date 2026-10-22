package com.livemonitor.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter for Monitoring tab channel list.
 *
 * Shows:
 * - channel title
 * - channel URL
 * - current status
 * - retry info
 * - pause/resume button
 * - stop/remove button
 *
 * Row tap opens per-channel log.
 */
public class ChannelAdapter extends BaseAdapter {

    public interface Listener {
        void onChannelClicked(ChannelItem channel);

        void onPauseResumeClicked(ChannelItem channel);

        void onStopClicked(ChannelItem channel);

        void onDeleteClicked(ChannelItem channel);

        void onRecordingPauseResumeClicked(RecordingItem recording);

        void onRecordingStopClicked(RecordingItem recording);
    }

    private final Context context;
    private final List<ChannelItem> channels;
    private final AppStorage storage;
    private Listener listener;
    private final Map<String, RecordingItem> homeRecordings = new HashMap<>();

    public ChannelAdapter(Context context) {
        this.context = context;
        this.channels = new ArrayList<>();
        this.storage = new AppStorage(context);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setChannels(List<ChannelItem> newChannels) {
        channels.clear();

        if (newChannels != null) {
            channels.addAll(newChannels);
        }

        notifyDataSetChanged();
    }

    public void setHomeRecordings(List<RecordingItem> recordings) {
        homeRecordings.clear();
        if (recordings != null) {
            for (RecordingItem recording : recordings) {
                if (recording != null && recording.isActive() && recording.getChannelId() != null
                    && !recording.getChannelId().trim().isEmpty()) {
                    RecordingItem existing = homeRecordings.get(recording.getChannelId());
                    if (existing == null || recording.getStartedAt() > existing.getStartedAt()) {
                        homeRecordings.put(recording.getChannelId(), recording);
                    }
                }
            }
        }
        notifyDataSetChanged();
    }

    public View getHomeView(ChannelItem channel, View convertView, ViewGroup parent) {
        ChannelViewHolder holder;
        if (convertView == null || !(convertView.getTag() instanceof ChannelViewHolder)) {
            holder = createViewHolder();
            convertView = holder.root;
            convertView.setTag(holder);
        } else {
            holder = (ChannelViewHolder) convertView.getTag();
        }
        bind(holder, channel, channel == null ? null : homeRecordings.get(channel.getId()));
        return convertView;
    }

    public void addOrUpdate(ChannelItem channel) {
        if (channel == null) {
            return;
        }

        for (int i = 0; i < channels.size(); i++) {
            ChannelItem existing = channels.get(i);

            if (existing != null && existing.getId().equals(channel.getId())) {
                channels.set(i, channel);
                notifyDataSetChanged();
                return;
            }
        }

        channels.add(channel);
        notifyDataSetChanged();
    }

    public void removeById(String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) {
            return;
        }

        for (int i = channels.size() - 1; i >= 0; i--) {
            ChannelItem channel = channels.get(i);

            if (channel != null && channelId.equals(channel.getId())) {
                channels.remove(i);
            }
        }

        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return channels.size();
    }

    @Override
    public ChannelItem getItem(int position) {
        return channels.get(position);
    }

    @Override
    public long getItemId(int position) {
        ChannelItem channel = getItem(position);
        return channel == null ? position : channel.getNotificationId();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ChannelViewHolder holder;

        if (convertView == null) {
            holder = createViewHolder();
            convertView = holder.root;
            convertView.setTag(holder);
        } else {
            holder = (ChannelViewHolder) convertView.getTag();
        }

        ChannelItem channel = getItem(position);
        bind(holder, channel, null);

        return convertView;
    }

    private ChannelViewHolder createViewHolder() {
        FrameLayout itemRoot = new FrameLayout(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        AppCardStyle.applyHomeCard(context, itemRoot, root);
        itemRoot.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT);
        title.setSingleLine(false);

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        );
        topRow.addView(title, titleParams);

        TextView statusBadge = new TextView(context);
        statusBadge.setTextColor(Color.WHITE);
        statusBadge.setTextSize(12);
        statusBadge.setGravity(Gravity.CENTER);
        statusBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        topRow.addView(statusBadge);

        TextView url = new TextView(context);
        url.setTextColor(Color.rgb(194, 202, 211));
        url.setTextSize(12);
        url.setSingleLine(false);
        url.setPadding(0, dp(4), 0, 0);

        TextView details = new TextView(context);
        details.setTextColor(Color.rgb(194, 202, 211));
        details.setTextSize(11);
        details.setSingleLine(false);
        details.setPadding(0, dp(4), 0, 0);

        TextView videoId = new TextView(context);
        videoId.setTextColor(Color.rgb(194, 202, 211));
        videoId.setTextSize(12);
        videoId.setSingleLine(true);
        videoId.setPadding(0, dp(4), 0, 0);

        LinearLayout statsRow = new LinearLayout(context);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setPadding(0, dp(8), 0, 0);
        TextView timerStat = AppCardStyle.createHomeStat(context);
        TextView qualityStat = AppCardStyle.createHomeStat(context);
        TextView sizeStat = AppCardStyle.createHomeStat(context);
        statsRow.addView(timerStat, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statsRow.addView(qualityStat, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statsRow.addView(sizeStat, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.END);
        buttonRow.setPadding(0, dp(10), 0, 0);

        Button pauseResume = new Button(context);
        pauseResume.setAllCaps(false);
        styleCardActionButton(pauseResume);

        Button stop = new Button(context);
        stop.setAllCaps(false);
        stop.setText("■  Stop");
        styleCardActionButton(stop);

        Button delete = new Button(context);
        delete.setAllCaps(false);
        delete.setText("Delete");
        styleCardActionButton(delete);
        delete.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete_24, 0, 0, 0);
        delete.setCompoundDrawablePadding(dp(4));

        buttonRow.addView(
            pauseResume,
            new LinearLayout.LayoutParams(
                0,
                dp(40),
                1f
            )
        );

        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
            0,
            dp(40),
            1f
        );
        stopParams.leftMargin = dp(8);
        buttonRow.addView(stop, stopParams);

        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
            0,
            dp(40),
            1f
        );
        deleteParams.leftMargin = dp(8);
        buttonRow.addView(delete, deleteParams);

        root.addView(topRow);
        root.addView(url);
        root.addView(details);
        root.addView(videoId);
        root.addView(statsRow);
        root.addView(buttonRow);

        ChannelViewHolder holder = new ChannelViewHolder();
        holder.root = itemRoot;
        holder.title = title;
        holder.url = url;
        holder.statusBadge = statusBadge;
        holder.details = details;
        holder.videoId = videoId;
        holder.statsRow = statsRow;
        holder.timerStat = timerStat;
        holder.qualityStat = qualityStat;
        holder.sizeStat = sizeStat;
        holder.pauseResume = pauseResume;
        holder.stop = stop;
        holder.delete = delete;

        return holder;
    }

    private void bind(ChannelViewHolder holder, ChannelItem channel, RecordingItem recording) {
        if (channel == null) {
            holder.title.setText("Unknown channel");
            holder.url.setText("");
            holder.statusBadge.setText("Unknown");
            holder.details.setText("");
            holder.videoId.setVisibility(View.GONE);
            holder.statsRow.setVisibility(View.GONE);
            return;
        }

        boolean recordingCard = recording != null && recording.isActive();
        holder.url.setVisibility(recordingCard ? View.GONE : View.VISIBLE);
        holder.details.setVisibility(recordingCard ? View.GONE : View.VISIBLE);
        holder.videoId.setVisibility(recordingCard ? View.VISIBLE : View.GONE);
        holder.statsRow.setVisibility(recordingCard ? View.VISIBLE : View.GONE);
        holder.title.setTypeface(recordingCard ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

        holder.title.setText(channel.getDisplayTitle());
        holder.url.setText(channel.getUrl());
        holder.statusBadge.setText(formatStatus(channel.getStatus()));
        if (!recordingCard) {
            holder.statusBadge.setTextSize(12);
            holder.statusBadge.setPadding(dp(10), dp(4), dp(10), dp(4));
        }
        applyStatusBadge(holder.statusBadge, channel.getStatus());
        holder.details.setText(buildDetails(channel));
        if (recordingCard) {
            AppCardStyle.applyRecordingBadge(context, holder.statusBadge);
            holder.videoId.setText(recording.getVideoId() == null ? "" : recording.getVideoId());
            long elapsed = recording.getStartedAt() > 0L
                ? Math.max(0L, (System.currentTimeMillis() - recording.getStartedAt()) / 1000L) : 0L;
            holder.timerStat.setText("⏱ " + RecordingProgressTracker.formatDuration(elapsed));
            holder.qualityStat.setText("📺 " + recording.getQuality());
            boolean starting = recording.getBytesRecorded() <= 0L && elapsed < 10L;
            holder.sizeStat.setText("💾 " + (starting ? "Starting…" : RecordingProgressTracker.formatBytes(recording.getBytesRecorded())));
        }
        holder.pauseResume.setText(recordingCard
            ? (recording.isPausedByUser() ? "▶  Resume" : "Ⅱ  Pause")
            : (channel.shouldMonitor() ? "Ⅱ  Pause" : "▶  Resume"));
        holder.stop.setText("■  Stop");
        holder.stop.setEnabled(true);
        holder.stop.setAlpha(1f);

        holder.root.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChannelClicked(channel);
            }
        });

        holder.pauseResume.setOnClickListener(v -> {
            if (listener != null) {
                if (recordingCard) listener.onRecordingPauseResumeClicked(recording);
                else listener.onPauseResumeClicked(channel);
            }
        });

        holder.stop.setOnClickListener(v -> {
            if (recordingCard) {
                if (listener != null) listener.onRecordingStopClicked(recording);
                return;
            }
            holder.stop.setEnabled(false);
            holder.stop.setAlpha(0.65f);
            holder.stop.setText("Stopping…");
            if (listener != null) {
                listener.onStopClicked(channel);
            }
        });

        holder.delete.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteClicked(channel);
            }
        });
    }

    private String buildDetails(ChannelItem channel) {
        StringBuilder builder = new StringBuilder();

        builder.append(getSubtitleForState(channel));

        if (channel.getRetryCount() > 0) {
            builder.append(" • Retry ");
            builder.append(channel.getRetryCount());
            builder.append("/");
            builder.append(channel.getMaxRetries());
        }

        if (channel.hasCurrentVideoId()) {
            builder.append(" • videoId=");
            builder.append(channel.getCurrentVideoId());
        }

        int missedCount = storage.countMissedStreamRecords(channel.getId());
        if (missedCount > 0) {
            builder.append("\n⚠ ");
            builder.append(missedCount);
            builder.append(missedCount == 1 ? " missed stream during outage" : " missed streams during outage");
        }

        if (channel.getLastError() != null && !channel.getLastError().trim().isEmpty()) {
            builder.append("\n");
            builder.append(channel.getLastError());
        }

        return builder.toString();
    }

    private String getSubtitleForState(ChannelItem channel) {
        if (channel == null) {
            return "";
        }
        String status = channel.getStatus();
        if (ChannelItem.STATUS_STOPPED.equals(status)) return "Monitoring stopped";
        if (ChannelItem.STATUS_WAITING_FOR_LIVE.equals(status) || ChannelItem.STATUS_RETRYING.equals(status)) return "Monitoring active · waiting for stream";
        if (ChannelItem.STATUS_RECORDING.equals(status) || ChannelItem.STATUS_LIVE_DETECTED.equals(status)) return "Recording in progress";
        if (ChannelItem.STATUS_PAUSED_BY_USER.equals(status) || ChannelItem.STATUS_PAUSED_NETWORK.equals(status)) return "Monitoring paused";
        if (channel.isRecording()) return "Recording in progress";
        if (channel.shouldMonitor()) return "Monitoring active · waiting for stream";
        return "Monitoring paused";
    }

    private void styleCardActionButton(Button button) {
        AppCardStyle.styleHomeActionButton(context, button);
    }

    private String formatStatus(String status) {
        if (ChannelItem.STATUS_WAITING_FOR_LIVE.equals(status)) {
            return "Monitoring";
        }

        if (ChannelItem.STATUS_LIVE_DETECTED.equals(status)) {
            return "Live";
        }

        if (ChannelItem.STATUS_RECORDING.equals(status)) {
            return "RECORDING";
        }

        if (ChannelItem.STATUS_PAUSED_BY_USER.equals(status)) {
            return "PAUSED";
        }

        if (ChannelItem.STATUS_PAUSED_NETWORK.equals(status)) {
            return "Network";
        }

        if (ChannelItem.STATUS_RETRYING.equals(status)) {
            return "Retrying";
        }

        if (ChannelItem.STATUS_FAILED.equals(status)) {
            return "Failed";
        }

        if (ChannelItem.STATUS_STOPPED.equals(status)) {
            return "STOPPED";
        }

        return "Idle";
    }

    private void applyStatusBadge(TextView badge, String status) {
        if (ChannelItem.STATUS_RECORDING.equals(status)
            || ChannelItem.STATUS_LIVE_DETECTED.equals(status)) {
            badge.setBackgroundResource(R.drawable.lm_status_recording_background);
            badge.setTextColor(context.getResources().getColor(R.color.lm_status_recording_text));
            return;
        }

        if (ChannelItem.STATUS_PAUSED_BY_USER.equals(status)
            || ChannelItem.STATUS_PAUSED_NETWORK.equals(status)) {
            badge.setBackgroundResource(R.drawable.lm_status_paused_background);
            badge.setTextColor(context.getResources().getColor(R.color.lm_status_paused_text));
            return;
        }

        if (ChannelItem.STATUS_STOPPED.equals(status)
            || ChannelItem.STATUS_FAILED.equals(status)) {
            badge.setBackgroundResource(R.drawable.lm_status_stopped_background);
            badge.setTextColor(context.getResources().getColor(R.color.lm_status_stopped_text));
            return;
        }

        badge.setBackgroundResource(R.drawable.lm_status_paused_background);
        badge.setTextColor(context.getResources().getColor(R.color.lm_accent_blue_glow));
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static class ChannelViewHolder {
        FrameLayout root;
        TextView title;
        TextView url;
        TextView statusBadge;
        TextView details;
        TextView videoId;
        LinearLayout statsRow;
        TextView timerStat;
        TextView qualityStat;
        TextView sizeStat;
        Button pauseResume;
        Button stop;
        Button delete;
    }
}
