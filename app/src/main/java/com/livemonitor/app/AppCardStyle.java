package com.livemonitor.app;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.graphics.Typeface;

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

    public static void applyChannelCard(Context context, View card) {
        float density = context.getResources().getDisplayMetrics().density;
        card.setPadding(Math.round(14 * density), Math.round(14 * density), Math.round(14 * density), Math.round(12 * density));
        card.setBackgroundResource(R.drawable.lm_card_background);
        card.setElevation(Math.round(8 * density));
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

}
