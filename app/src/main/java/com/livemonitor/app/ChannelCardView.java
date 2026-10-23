package com.livemonitor.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared fixed-height channel card used by Home and Server monitoring. */
public final class ChannelCardView extends LinearLayout {
    public static final int BADGE_MONITORING = 0;
    public static final int BADGE_RECORDING = 1;
    public static final int BADGE_PAUSED = 2;
    public static final int BADGE_FINALIZING = 3;

    public static final class Model {
        public String title = "";
        public String badgeLabel = "Monitoring";
        public int badgeStyle = BADGE_MONITORING;
        public String subLine = "—";
        public boolean subLineError;
        public String stat1 = "—", stat2 = "—", stat3 = "—";
        public String button1 = "Start", button2 = "Stop", button3 = "Delete";
        public boolean button1Enabled = true, button2Enabled = true, button3Enabled = true;
        public View.OnClickListener button1Click, button2Click, button3Click, cardClick;
    }

    public ChannelCardView(Context context, Model model) {
        super(context);
        setOrientation(VERTICAL);
        AppCardStyle.applyChannelCard(context, this);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(model.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(title, new LayoutParams(0, dp(28), 1f));

        TextView badge = new TextView(context);
        badge.setText(model.badgeLabel);
        badge.setTextSize(12);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setTextColor(context.getResources().getColor(model.badgeStyle == BADGE_RECORDING || model.badgeStyle == BADGE_FINALIZING
            ? R.color.lm_status_recording_text
            : model.badgeStyle == BADGE_PAUSED ? R.color.lm_status_paused_text : R.color.lm_accent_blue_glow));
        badge.setBackgroundResource(model.badgeStyle == BADGE_RECORDING || model.badgeStyle == BADGE_FINALIZING
            ? R.drawable.lm_status_recording_background
            : model.badgeStyle == BADGE_PAUSED ? R.drawable.lm_status_paused_background : R.drawable.lm_status_paused_background);
        titleRow.addView(badge, new LayoutParams(dp(100), dp(26)));
        addView(titleRow, new LayoutParams(LayoutParams.MATCH_PARENT, dp(28)));

        TextView sub = new TextView(context);
        sub.setText(model.subLine == null ? "—" : model.subLine);
        sub.setTextSize(13);
        sub.setTextColor(context.getResources().getColor(model.subLineError ? R.color.danger : R.color.lm_text_secondary));
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams subParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(20));
        subParams.topMargin = dp(2);
        addView(sub, subParams);

        LinearLayout stats = new LinearLayout(context);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        addStat(context, stats, model.stat1, false);
        addStat(context, stats, model.stat2, true);
        addStat(context, stats, model.stat3, true);
        LayoutParams statsParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(36));
        statsParams.topMargin = dp(6);
        addView(stats, statsParams);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        addButton(context, buttons, model.button1, model.button1Enabled, model.button1Click, false);
        addButton(context, buttons, model.button2, model.button2Enabled, model.button2Click, true);
        addButton(context, buttons, model.button3, model.button3Enabled, model.button3Click, true);
        LayoutParams buttonParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(44));
        buttonParams.topMargin = dp(8);
        addView(buttons, buttonParams);
        setOnClickListener(model.cardClick);
    }

    private void addStat(Context context, LinearLayout row, String value, boolean gap) {
        TextView stat = new TextView(context);
        stat.setText(value == null || value.isEmpty() ? "—" : value);
        stat.setTextColor(context.getResources().getColor(R.color.lm_text_secondary));
        stat.setTextSize(13);
        stat.setGravity(Gravity.CENTER);
        stat.setSingleLine(true);
        stat.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams params = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        if (gap) params.leftMargin = dp(8);
        row.addView(stat, params);
    }

    private void addButton(Context context, LinearLayout row, String label, boolean enabled, View.OnClickListener click, boolean gap) {
        Button button = new Button(context);
        button.setText(label);
        AppCardStyle.styleHomeActionButton(context, button);
        button.setEnabled(enabled);
        if ("Delete".equals(label)) {
            button.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_delete_24, 0, 0, 0);
            button.setCompoundDrawablePadding(dp(4));
        }
        button.setOnClickListener(click);
        LayoutParams params = new LayoutParams(0, dp(44), 1f);
        if (gap) params.leftMargin = dp(8);
        row.addView(button, params);
    }

    public static String formatTimeAgo(long timestampMillis) {
        if (timestampMillis <= 0L) return "—";
        long seconds = Math.max(0L, (System.currentTimeMillis() - timestampMillis) / 1000L);
        if (seconds < 60L) return "just now";
        if (seconds < 3600L) return (seconds / 60L) + "m";
        if (seconds < 86400L) return (seconds / 3600L) + "h";
        return (seconds / 86400L) + "d";
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
