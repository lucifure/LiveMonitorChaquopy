package com.livemonitor.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.view.Gravity;

/** Shared card and action-button styling used by Home and server cards. */
public final class AppCardStyle {
    private AppCardStyle() {}

    public static void applyHomeCard(Context context, View outer, View card) {
        int density = Math.round(4 * context.getResources().getDisplayMetrics().density);
        outer.setPadding(0, density, 0, density);
        int horizontal = Math.round(16 * context.getResources().getDisplayMetrics().density);
        int vertical = Math.round(12 * context.getResources().getDisplayMetrics().density);
        card.setPadding(horizontal, vertical, horizontal, vertical);
        card.setBackgroundResource(R.drawable.lm_card_background);
        card.setElevation(Math.round(8 * context.getResources().getDisplayMetrics().density));
    }

    public static void styleHomeActionButton(Context context, Button button) {
        float density = context.getResources().getDisplayMetrics().density;
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(Math.round(8 * density), 0, Math.round(8 * density), 0);
        button.setBackgroundResource(R.drawable.lm_action_button_background);
    }

    public static TextView createHomeStat(Context context) {
        TextView stat = new TextView(context);
        stat.setTextColor(Color.rgb(194, 202, 211));
        stat.setTextSize(12);
        stat.setGravity(Gravity.CENTER_VERTICAL);
        stat.setSingleLine(true);
        return stat;
    }

    public static void applyRecordingBadge(Context context, TextView badge) {
        badge.setText("● REC");
        badge.setTextSize(10);
        float density = context.getResources().getDisplayMetrics().density;
        badge.setPadding(Math.round(6 * density), Math.round(2 * density), Math.round(6 * density), Math.round(2 * density));
        badge.setBackgroundResource(R.drawable.lm_status_recording_background);
        badge.setTextColor(context.getResources().getColor(R.color.lm_status_recording_text));
        badge.setVisibility(View.VISIBLE);
    }
}
