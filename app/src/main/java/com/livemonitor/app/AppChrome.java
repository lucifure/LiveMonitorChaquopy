package com.livemonitor.app;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.util.TypedValue;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/** Shared navigation and screen chrome for the app's primary sections. */
public final class AppChrome {
    private AppChrome() {}

    public static LinearLayout wrapWithBottomNav(Activity activity, View content, int selectedItemId) {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.lm_screen_background);
        // Like Home, this view uses the window decor's default system bar fitting.
        root.addView(content, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        BottomNavigationView nav = (BottomNavigationView) LayoutInflater.from(activity)
            .inflate(R.layout.view_bottom_nav, root, false);
        root.addView(nav, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setupNav(activity, nav, selectedItemId);

        return root;
    }

    public static void setupNav(Activity current, BottomNavigationView nav, int selectedItemId) {
        nav.setSelectedItemId(selectedItemId);
        nav.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == selectedItemId ||
                (current instanceof ServerLogActivity && itemId == R.id.nav_logs)) {
                return true;
            }

            Class<?> target;
            if (itemId == R.id.nav_monitoring) target = MainActivity.class;
            else if (itemId == R.id.nav_server) target = ServerRecordingsActivity.class;
            else if (itemId == R.id.nav_files) target = DownloadedFilesActivity.class;
            else if (itemId == R.id.nav_logs) target = LogActivity.class;
            else return false;

            Intent intent = new Intent(current, target);
            if (target == MainActivity.class) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            } else {
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            }
            current.startActivity(intent);
            current.overridePendingTransition(0, 0);
            return false;
        });
    }

    /** Reassert this screen's tab after REORDER_TO_FRONT without firing navigation. */
    public static void selectCurrentTab(Activity activity, int itemId) {
        View navView = activity.findViewById(R.id.bottomNavigation);
        if (!(navView instanceof BottomNavigationView)) return;
        BottomNavigationView nav = (BottomNavigationView) navView;
        nav.setOnItemSelectedListener(null);
        nav.setSelectedItemId(itemId);
        setupNav(activity, nav, itemId);
    }

    public static ImageButton settingsButton(Activity activity) {
        ImageButton button = new ImageButton(activity);
        int size = Math.round(48 * activity.getResources().getDisplayMetrics().density);
        button.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        button.setPadding(Math.round(9 * activity.getResources().getDisplayMetrics().density),
            Math.round(9 * activity.getResources().getDisplayMetrics().density),
            Math.round(9 * activity.getResources().getDisplayMetrics().density),
            Math.round(9 * activity.getResources().getDisplayMetrics().density));
        button.setImageResource(R.drawable.ic_settings);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(0xffAEB7C2));
        TypedValue ripple = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        button.setBackgroundResource(ripple.resourceId);
        button.setContentDescription("Settings");
        button.setOnClickListener(v -> activity.startActivity(new Intent(activity, SettingsActivity.class)));
        return button;
    }

    public static void handleBack(Activity activity) {
        Intent intent = new Intent(activity, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
        activity.finish();
    }
}
