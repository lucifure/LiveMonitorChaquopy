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
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

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
    }

    private final Context context;
    private final List<ChannelItem> channels;
    private Listener listener;

    public ChannelAdapter(Context context) {
        this.context = context;
        this.channels = new ArrayList<>();
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
        bind(holder, channel);

        return convertView;
    }

    private ChannelViewHolder createViewHolder() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundResource(R.drawable.lm_card_background);
        root.setElevation(dp(8));

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

        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.END);
        buttonRow.setPadding(0, dp(10), 0, 0);

        Button pauseResume = new Button(context);
        pauseResume.setAllCaps(false);
        pauseResume.setTextColor(Color.rgb(214, 220, 228));
        pauseResume.setTextSize(14);
        pauseResume.setBackgroundResource(R.drawable.lm_button_neutral_background);

        Button stop = new Button(context);
        stop.setAllCaps(false);
        stop.setText("■  Stop");
        stop.setTextColor(Color.rgb(214, 220, 228));
        stop.setTextSize(14);
        stop.setBackgroundResource(R.drawable.lm_button_delete_background);

        Button delete = new Button(context);
        delete.setAllCaps(false);
        delete.setText("Delete");
        delete.setTextColor(Color.rgb(214, 220, 228));
        delete.setTextSize(14);
        delete.setBackgroundResource(R.drawable.lm_button_delete_background);

        buttonRow.addView(
            pauseResume,
            new LinearLayout.LayoutParams(
                0,
                dp(36),
                1f
            )
        );

        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
            0,
            dp(36),
            1f
        );
        stopParams.leftMargin = dp(8);
        buttonRow.addView(stop, stopParams);

        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
            0,
            dp(36),
            1f
        );
        deleteParams.leftMargin = dp(8);
        buttonRow.addView(delete, deleteParams);

        root.addView(topRow);
        root.addView(url);
        root.addView(details);
        root.addView(buttonRow);

        ChannelViewHolder holder = new ChannelViewHolder();
        holder.root = root;
        holder.title = title;
        holder.url = url;
        holder.statusBadge = statusBadge;
        holder.details = details;
        holder.pauseResume = pauseResume;
        holder.stop = stop;
        holder.delete = delete;

        return holder;
    }

    private void bind(ChannelViewHolder holder, ChannelItem channel) {
        if (channel == null) {
            holder.title.setText("Unknown channel");
            holder.url.setText("");
            holder.statusBadge.setText("Unknown");
            holder.details.setText("");
            return;
        }

        holder.title.setText(channel.getDisplayTitle());
        holder.url.setText(channel.getUrl());
        holder.statusBadge.setText(formatStatus(channel.getStatus()));
        applyStatusBadge(holder.statusBadge, channel.getStatus());
        holder.details.setText(buildDetails(channel));
        holder.pauseResume.setText(channel.shouldMonitor() ? "Ⅱ  Pause" : "▶  Resume");

        holder.root.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChannelClicked(channel);
            }
        });

        holder.pauseResume.setOnClickListener(v -> {
            if (listener != null) {
                listener.onPauseResumeClicked(channel);
            }
        });

        holder.stop.setOnClickListener(v -> {
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

        if (channel.isRecording()) {
            builder.append("Recording");
        } else if (channel.shouldMonitor()) {
            builder.append("Monitoring enabled");
        } else {
            builder.append("Monitoring paused");
        }

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

        if (channel.getLastError() != null && !channel.getLastError().trim().isEmpty()) {
            builder.append("\n");
            builder.append(channel.getLastError());
        }

        return builder.toString();
    }

    private String formatStatus(String status) {
        if (ChannelItem.STATUS_WAITING_FOR_LIVE.equals(status)) {
            return "Waiting";
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
        LinearLayout root;
        TextView title;
        TextView url;
        TextView statusBadge;
        TextView details;
        Button pauseResume;
        Button stop;
        Button delete;
    }
}
