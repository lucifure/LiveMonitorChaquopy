package com.livemonitor.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for global and per-channel log screens.
 */
public class LogAdapter extends BaseAdapter {

    private final Context context;
    private final List<LogItem> logs;

    public LogAdapter(Context context) {
        this.context = context;
        this.logs = new ArrayList<>();
    }

    public void setLogs(List<LogItem> newLogs) {
        logs.clear();

        if (newLogs != null) {
            logs.addAll(newLogs);
        }

        notifyDataSetChanged();
    }

    public void addLog(LogItem log) {
        if (log == null) {
            return;
        }

        logs.add(log);
        notifyDataSetChanged();
    }

    public void clear() {
        logs.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return logs.size();
    }

    @Override
    public LogItem getItem(int position) {
        return logs.get(position);
    }

    @Override
    public long getItemId(int position) {
        LogItem item = getItem(position);

        if (item == null || item.getId() == null) {
            return position;
        }

        return item.getId().hashCode();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LogViewHolder holder;

        if (convertView == null) {
            holder = createViewHolder();
            convertView = holder.root;
            convertView.setTag(holder);
        } else {
            holder = (LogViewHolder) convertView.getTag();
        }

        bind(holder, getItem(position));

        return convertView;
    }

    private LogViewHolder createViewHolder() {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setBackgroundColor(Color.rgb(250, 250, 250));

        TextView header = new TextView(context);
        header.setTextSize(12);
        header.setTypeface(Typeface.DEFAULT_BOLD);

        TextView message = new TextView(context);
        message.setTextSize(14);
        message.setTextColor(Color.rgb(25, 25, 25));
        message.setPadding(0, dp(3), 0, 0);
        message.setSingleLine(false);

        TextView details = new TextView(context);
        details.setTextSize(12);
        details.setTextColor(Color.rgb(100, 100, 100));
        details.setPadding(0, dp(3), 0, 0);
        details.setSingleLine(false);

        root.addView(header);
        root.addView(message);
        root.addView(details);

        LogViewHolder holder = new LogViewHolder();
        holder.root = root;
        holder.header = header;
        holder.message = message;
        holder.details = details;

        return holder;
    }

    private void bind(LogViewHolder holder, LogItem log) {
        if (log == null) {
            holder.header.setText("Unknown");
            holder.message.setText("");
            holder.details.setText("");
            return;
        }

        holder.header.setText(
            log.getFormattedTime()
                + "  "
                + log.getLevel()
                + "  "
                + log.getSource()
                + "  "
                + log.getTag()
        );

        holder.header.setTextColor(levelColor(log.getLevel()));
        holder.message.setText(log.getMessage());

        String details = buildDetails(log);
        holder.details.setText(details);
        holder.details.setVisibility(details.trim().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String buildDetails(LogItem log) {
        StringBuilder builder = new StringBuilder();

        if (log.getVideoId() != null && !log.getVideoId().trim().isEmpty()) {
            builder.append("videoId=");
            builder.append(log.getVideoId());
        }

        if (log.getRecordingId() != null && !log.getRecordingId().trim().isEmpty()) {
            if (builder.length() > 0) {
                builder.append(" • ");
            }

            builder.append("recordingId=");
            builder.append(log.getRecordingId());
        }

        if (log.getDetails() != null && !log.getDetails().trim().isEmpty()) {
            if (builder.length() > 0) {
                builder.append("\n");
            }

            builder.append(log.getDetails());
        }

        return builder.toString();
    }

    private int levelColor(String level) {
        if (LogItem.LEVEL_ERROR.equals(level)) {
            return Color.rgb(190, 0, 0);
        }

        if (LogItem.LEVEL_WARNING.equals(level)) {
            return Color.rgb(210, 130, 0);
        }

        if (LogItem.LEVEL_SUCCESS.equals(level)) {
            return Color.rgb(0, 150, 100);
        }

        if (LogItem.LEVEL_DEBUG.equals(level)) {
            return Color.rgb(100, 100, 100);
        }

        return Color.rgb(40, 90, 180);
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static class LogViewHolder {
        LinearLayout root;
        TextView header;
        TextView message;
        TextView details;
    }
}
