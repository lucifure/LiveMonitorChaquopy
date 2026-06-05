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
        root.setBackgroundColor(Color.rgb(15, 15, 15));

        TextView header = new TextView(context);
        header.setTextSize(12);
        header.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        TextView message = new TextView(context);
        message.setTextSize(14);
        message.setTextColor(Color.WHITE);
        message.setTypeface(Typeface.MONOSPACE);
        message.setPadding(0, dp(3), 0, 0);
        message.setSingleLine(false);

        TextView details = new TextView(context);
        details.setTextSize(12);
        details.setTextColor(Color.rgb(160, 160, 160));
        details.setTypeface(Typeface.MONOSPACE);
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
                + levelIcon(log.getLevel())
                + "  "
                + log.getSource()
                + "  →"
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

    private String levelIcon(String level) {
        if (LogItem.LEVEL_ERROR.equals(level)) return "🔴";
        if (LogItem.LEVEL_WARNING.equals(level)) return "🟡";
        if (LogItem.LEVEL_SUCCESS.equals(level)) return "🟢";
        if (LogItem.LEVEL_DEBUG.equals(level)) return "⚪";
        return "🔵";
    }

    private int levelColor(String level) {
        if (LogItem.LEVEL_ERROR.equals(level)) {
            return Color.rgb(255, 107, 107);
        }

        if (LogItem.LEVEL_WARNING.equals(level)) {
            return Color.rgb(255, 210, 64);
        }

        if (LogItem.LEVEL_SUCCESS.equals(level)) {
            return Color.rgb(22, 199, 132);
        }

        if (LogItem.LEVEL_DEBUG.equals(level)) {
            return Color.rgb(150, 150, 150);
        }

        return Color.rgb(64, 156, 255);
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
