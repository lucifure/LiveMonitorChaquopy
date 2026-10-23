package com.livemonitor.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Adapter for the Home monitoring channel list. */
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
    private final AppStorage storage;
    private final List<ChannelItem> channels = new ArrayList<>();
    private Listener listener;
    private final Map<String, RecordingItem> homeRecordings = new HashMap<>();

    public ChannelAdapter(Context context) { this.context = context; this.storage = new AppStorage(context); }
    public void setListener(Listener listener) { this.listener = listener; }

    public void setChannels(List<ChannelItem> newChannels) {
        channels.clear();
        if (newChannels != null) channels.addAll(newChannels);
        notifyDataSetChanged();
    }

    public void setHomeRecordings(List<RecordingItem> recordings) {
        homeRecordings.clear();
        if (recordings != null) for (RecordingItem recording : recordings) {
            if (recording != null && recording.isActive() && recording.getChannelId() != null
                && !recording.getChannelId().trim().isEmpty()) {
                RecordingItem existing = homeRecordings.get(recording.getChannelId());
                if (existing == null || recording.getStartedAt() > existing.getStartedAt()) homeRecordings.put(recording.getChannelId(), recording);
            }
        }
        notifyDataSetChanged();
    }

    public View getHomeView(ChannelItem channel, View convertView, ViewGroup parent) {
        return createCard(channel, channel == null ? null : homeRecordings.get(channel.getId()));
    }

    public void addOrUpdate(ChannelItem channel) {
        if (channel == null) return;
        for (int i = 0; i < channels.size(); i++) {
            ChannelItem existing = channels.get(i);
            if (existing != null && existing.getId().equals(channel.getId())) {
                channels.set(i, channel); notifyDataSetChanged(); return;
            }
        }
        channels.add(channel); notifyDataSetChanged();
    }

    public void removeById(String channelId) {
        if (channelId == null || channelId.trim().isEmpty()) return;
        for (int i = channels.size() - 1; i >= 0; i--) {
            ChannelItem channel = channels.get(i);
            if (channel != null && channelId.equals(channel.getId())) channels.remove(i);
        }
        notifyDataSetChanged();
    }

    @Override public int getCount() { return channels.size(); }
    @Override public ChannelItem getItem(int position) { return channels.get(position); }
    @Override public long getItemId(int position) { ChannelItem channel = getItem(position); return channel == null ? position : channel.getNotificationId(); }
    @Override public View getView(int position, View convertView, ViewGroup parent) { return createCard(getItem(position), null); }

    private View createCard(ChannelItem channel, RecordingItem recording) {
        ChannelCardView.Model model = new ChannelCardView.Model();
        if (channel == null) {
            model.title = "Unknown channel";
            model.subLine = "—";
            model.stat1 = model.stat2 = model.stat3 = "—";
            return new ChannelCardView(context, model);
        }
        boolean recordingCard = channel.isRecording() || (recording != null && recording.isActive());
        model.title = homeTitle(channel);
        model.badgeStyle = recordingCard ? ChannelCardView.BADGE_RECORDING
            : channel.shouldMonitor() ? ChannelCardView.BADGE_MONITORING : ChannelCardView.BADGE_PAUSED;
        model.badgeLabel = recordingCard ? "Recording" : channel.shouldMonitor() ? "Monitoring" : "Paused";
        String error = channel.getLastError();
        model.subLineError = error != null && !error.trim().isEmpty();
        if (model.subLineError) model.subLine = "Error: " + error.trim();
        else if (recordingCard) model.subLine = recording != null && recording.isActive()
            ? nullToDash(recording.getVideoId()) : nullToDash(channel.getCurrentVideoId());
        else model.subLine = channel.shouldMonitor() ? "Waiting for stream" : "Monitoring paused";

        if (recordingCard) {
            if (recording != null && recording.isActive()) {
                long elapsed = recording.getStartedAt() > 0L
                    ? Math.max(0L, (System.currentTimeMillis() - recording.getStartedAt()) / 1000L) : 0L;
                model.stat1 = "⏱ " + RecordingProgressTracker.formatDuration(elapsed);
                model.stat2 = "📺 " + nullToDash(recording.getQuality());
                boolean starting = recording.getBytesRecorded() <= 0L && elapsed < 10L;
                model.stat3 = "💾 " + (starting ? "Starting…" : RecordingProgressTracker.formatBytes(recording.getBytesRecorded()));
            } else {
                model.stat1 = "⏱ —"; model.stat2 = "📺 —"; model.stat3 = "💾 —";
            }
        } else {
            model.stat1 = "🔄 Checked " + ChannelCardView.formatTimeAgo(channel.getLastCheckAt());
            String quality = storage.loadSettings().getDownloadQuality();
            model.stat2 = "📺 " + (quality == null || quality.trim().isEmpty() ? "—" : quality.trim());
            String live = ChannelCardView.formatTimeAgo(channel.getLastLiveAt());
            model.stat3 = "📡 " + ("—".equals(live) ? "None yet" : "Live " + live + ("just now".equals(live) ? "" : " ago"));
        }

        model.button1 = recordingCard
            ? recording != null && recording.isActive()
                ? (recording.isPausedByUser() ? "▶  Resume" : "Ⅱ  Pause")
                : (channel.shouldMonitor() ? "Ⅱ  Pause" : "▶  Resume")
            : (channel.shouldMonitor() ? "Ⅱ  Pause" : "▶  Resume");
        model.button2 = "■  Stop";
        model.button3 = "Delete";
        model.button1Click = v -> {
            if (listener != null) {
                if (recordingCard && recording != null && recording.isActive()) listener.onRecordingPauseResumeClicked(recording);
                else listener.onPauseResumeClicked(channel);
            }
        };
        model.button2Click = v -> {
            if (recordingCard && recording != null && recording.isActive()) {
                if (listener != null) listener.onRecordingStopClicked(recording);
            } else if (listener != null) listener.onStopClicked(channel);
        };
        model.button3Click = v -> { if (listener != null) listener.onDeleteClicked(channel); };
        model.cardClick = v -> { if (listener != null) listener.onChannelClicked(channel); };
        return new ChannelCardView(context, model);
    }

    private String homeTitle(ChannelItem channel) {
        String title = channel.getTitle();
        if (title != null && !title.trim().isEmpty()
            && !title.trim().toLowerCase(java.util.Locale.US).startsWith("http")) return title.trim();
        String url = channel.getUrl();
        int at = url == null ? -1 : url.indexOf("/@");
        if (at >= 0) {
            int end = url.indexOf('/', at + 2);
            return end < 0 ? url.substring(at + 1) : url.substring(at + 1, end);
        }
        if (url != null) {
            int end = url.indexOf('?', 0);
            String clean = end < 0 ? url : url.substring(0, end);
            int slash = clean.lastIndexOf('/');
            String handle = slash >= 0 ? clean.substring(slash + 1) : clean;
            while (handle.startsWith("@")) handle = handle.substring(1);
            if (!handle.isEmpty()) return "@" + handle;
        }
        return "@channel";
    }

    private static String nullToDash(String value) { return value == null || value.trim().isEmpty() ? "—" : value.trim(); }
}
