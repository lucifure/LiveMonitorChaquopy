package com.livemonitor.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.ArrayList;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Global log screen.
 *
 * Shows logs from all channels and app components.
 */
public class LogActivity extends AppCompatActivity {

    public static final String EXTRA_VIEW_SELECT_ON_OPEN = "com.livemonitor.app.extra.VIEW_SELECT_ON_OPEN";

    private AppStorage storage;
    private LogAdapter adapter;
    private ListView listView;
    private TextView emptyView;
    private TextView subtitle;
    private final List<LogItem> allLogs = new ArrayList<>();
    private final List<Button> filterButtons = new ArrayList<>();
    private String selectedFilter = "ALL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        storage = new AppStorage(this);
        adapter = new LogAdapter(this);

        setTitle("Global Log");
        setContentView(AppChrome.wrapWithBottomNav(this, buildContentView(), R.id.nav_logs));

        refreshLogs();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_VIEW_SELECT_ON_OPEN, false)) {
            listView.post(this::viewSelectFullLog);
        }
    }

    @Override
    public void onBackPressed() {
        AppChrome.handleBack(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppChrome.selectCurrentTab(this, R.id.nav_logs);
        refreshLogs();
    }

    private LinearLayout buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(Color.rgb(15, 15, 15));
        root.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Global Log");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTextColor(Color.WHITE);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(AppChrome.settingsButton(this), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dp(8);
        root.addView(titleRow, titleParams);

        addLogModeToggle(root, true);
        subtitle = new TextView(this);
        subtitle.setTextColor(Color.rgb(190, 190, 190));
        subtitle.setTextSize(12);
        subtitle.setPadding(0, 0, 0, dp(8));
        root.addView(subtitle);

        LinearLayout filters = new LinearLayout(this);
        filters.setGravity(Gravity.CENTER);
        addFilter(filters, "All", "ALL");
        addFilter(filters, "Errors", "ERRORS");
        addFilter(filters, "Warnings", "WARNINGS");
        addFilter(filters, "Success", "SUCCESS");
        root.addView(filters);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        addAction(actions, "Refresh", v -> refreshLogs());
        addAction(actions, "Copy Log", v -> copyLog());
        addAction(actions, "Save Log", v -> saveLog());
        addAction(actions, "Clear Log", v -> confirmClear());
        root.addView(actions);

        listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        listView.setFastScrollAlwaysVisible(true);
        listView.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        listView.setStackFromBottom(false);
        listView.setAdapter(adapter);
        listView.setBackgroundColor(Color.rgb(15, 15, 15));

        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        );
        listParams.topMargin = dp(8);
        root.addView(listView, listParams);

        emptyView = new TextView(this);
        emptyView.setText("No log entries yet.");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextSize(15);
        emptyView.setTextColor(Color.rgb(102, 102, 102));
        emptyView.setVisibility(View.GONE);

        LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        emptyParams.topMargin = dp(8);
        root.addView(emptyView, emptyParams);

        Button close = new Button(this);
        close.setText("Close");
        close.setAllCaps(false);
        close.setOnClickListener(v -> AppChrome.handleBack(this));
        root.addView(close);

        updateFilterButtons();

        return root;
    }

    private void addLogModeToggle(LinearLayout parent, boolean appSelected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button app = new Button(this); app.setText("App Log"); app.setAllCaps(false);
        Button server = new Button(this); server.setText("Server Log"); server.setAllCaps(false);
        styleLogModeButton(app, appSelected); styleLogModeButton(server, !appSelected);
        app.setOnClickListener(v -> {});
        server.setOnClickListener(v -> { startActivity(new Intent(this, ServerLogActivity.class)); overridePendingTransition(0, 0); finish(); });
        row.addView(app, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams serverParams = new LinearLayout.LayoutParams(0, dp(40), 1f); serverParams.leftMargin = dp(6); row.addView(server, serverParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); rowParams.bottomMargin = dp(8); parent.addView(row, rowParams);
    }

    private void styleLogModeButton(Button button, boolean selected) {
        button.setTextColor(selected ? Color.WHITE : Color.rgb(190, 190, 190));
        button.setBackgroundResource(selected ? R.drawable.lm_blue_add_pill_background : R.drawable.lm_glass_button_background);
    }

    private void refreshLogs() {
        List<LogItem> logs = storage.loadLogs();
        allLogs.clear();
        if (logs != null) allLogs.addAll(logs);
        renderLogs();
    }

    private void renderLogs() {
        List<LogItem> visible = new ArrayList<>();
        for (LogItem log : allLogs) {
            if (log != null && ServerLogFormatter.matchesFilter(log.getLevel(), selectedFilter)) {
                visible.add(log);
            }
        }
        adapter.setLogs(visible);
        boolean isEmpty = visible.isEmpty();
        subtitle.setText(visible.size() + " entries shown · times shown in your phone's time zone");
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        listView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void addFilter(LinearLayout row, String label, String filter) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> {
            selectedFilter = filter;
            updateFilterButtons();
            renderLogs();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        row.addView(button, params);
        filterButtons.add(button);
    }

    private void updateFilterButtons() {
        String[] filters = {"ALL", "ERRORS", "WARNINGS", "SUCCESS"};
        for (int i = 0; i < filterButtons.size(); i++) {
            boolean selected = filters[i].equals(selectedFilter);
            filterButtons.get(i).setTextColor(selected ? Color.WHITE : Color.rgb(190, 190, 190));
            filterButtons.get(i).setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                selected ? 0xff455a64 : 0xff242424
            ));
        }
    }

    private void addAction(LinearLayout row, String label, View.OnClickListener click) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(click);
        button.setTextSize(12);
        row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private String visibleText() {
        StringBuilder text = new StringBuilder();
        for (LogItem log : allLogs) {
            if (log == null || !log.isCopyable()
                || !ServerLogFormatter.matchesFilter(log.getLevel(), selectedFilter)) {
                continue;
            }
            if (text.length() > 0) text.append("\n");
            text.append(log.toCopyLine());
        }
        return text.toString();
    }

    private void copyLog() {
        String text = visibleText();

        if (text.trim().isEmpty()) {
            Toast.makeText(this, "Log is empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (text.length() <= ClipboardLogSplitter.MAX_CLIPBOARD_CHARS_PER_PART) {
            copyTextToClipboard("LiveMonitor Global Log", text);
            Toast.makeText(this, "Full log copied.", Toast.LENGTH_SHORT).show();
            return;
        }

        showChunkedCopyDialog(ClipboardLogSplitter.split(text));
    }

    private void saveLog() {
        String text = visibleText();

        if (text.trim().isEmpty()) {
            Toast.makeText(this, "Log is empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String fileName = LogFileSaver.save(this, storage, text);
            Toast.makeText(this, "Log saved: " + fileName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save log: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void viewSelectFullLog() {
        String text = storage.buildCopyTextForAllLogs();

        if (text.trim().isEmpty()) {
            Toast.makeText(this, "Log is empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        showSelectableText("Global Log", text, "LiveMonitor Global Log");
    }

    private void showSelectableText(String title, String text, String clipboardLabel) {
        TextView logTextView = new TextView(this);
        logTextView.setText(text);
        logTextView.setTextIsSelectable(true);
        logTextView.setTextSize(12);
        logTextView.setPadding(dp(12), dp(12), dp(12), dp(12));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(logTextView);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(title + " · " + ClipboardLogSplitter.countLines(text) + " lines")
            .setMessage("Drag-select any range, or use Select All from Android's text-selection toolbar for manual copy control.")
            .setView(scrollView)
            .setNegativeButton("Close", null);

        if (text.length() <= ClipboardLogSplitter.MAX_CLIPBOARD_CHARS_PER_PART) {
            builder.setPositiveButton("Copy all", (dialog, which) -> {
                copyTextToClipboard(clipboardLabel, text);
                Toast.makeText(this, "Full visible log copied.", Toast.LENGTH_SHORT).show();
            });
        } else {
            builder.setNeutralButton("Split copy", (dialog, which) -> showChunkedCopyDialog(ClipboardLogSplitter.split(text)));
        }

        builder.show();
    }

    private void showChunkedCopyDialog(List<ClipboardLogSplitter.Part> chunks) {
        if (chunks == null || chunks.size() == 0) {
            Toast.makeText(this, "Log is empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), 0);

        TextView helpText = new TextView(this);
        int totalLines = chunks.get(chunks.size() - 1).getEndLine();
        helpText.setText("Total lines: " + totalLines + ". Choose Copy to place one safe-sized part on the clipboard, or View/Select & Copy to manually select exact lines.");
        helpText.setPadding(0, 0, 0, dp(8));
        content.addView(helpText);

        for (int i = 0; i < chunks.size(); i++) {
            final int partIndex = i;

            TextView partLabel = new TextView(this);
            ClipboardLogSplitter.Part part = chunks.get(i);
            partLabel.setText(
                "Part " + (i + 1) + " of " + chunks.size()
                    + ": lines " + part.getStartLine() + "–" + part.getEndLine()
                    + " (" + part.length() + " chars)"
            );
            partLabel.setPadding(0, dp(8), 0, dp(4));
            content.addView(partLabel);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            Button copyPartButton = new Button(this);
            copyPartButton.setAllCaps(false);
            copyPartButton.setText("Copy");
            copyPartButton.setOnClickListener(v -> {
                copyTextToClipboard("LiveMonitor Global Log part " + (partIndex + 1) + " of " + chunks.size(), chunks.get(partIndex).getText());
                Toast.makeText(this, "Copied log part " + (partIndex + 1) + " of " + chunks.size() + ".", Toast.LENGTH_SHORT).show();
            });
            row.addView(copyPartButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button viewPartButton = new Button(this);
            viewPartButton.setAllCaps(false);
            viewPartButton.setText("View/Select");
            viewPartButton.setOnClickListener(v -> showSelectableGlobalLogPart(chunks, partIndex));
            LinearLayout.LayoutParams viewParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            viewParams.leftMargin = dp(8);
            row.addView(viewPartButton, viewParams);

            content.addView(row);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content);

        new AlertDialog.Builder(this)
            .setTitle("Log split into " + chunks.size() + " parts")
            .setView(scrollView)
            .setNegativeButton("Close", null)
            .show();
    }

    private void showSelectableGlobalLogPart(List<ClipboardLogSplitter.Part> chunks, int partIndex) {
        TextView logTextView = new TextView(this);
        logTextView.setText(chunks.get(partIndex).getText());
        logTextView.setTextIsSelectable(true);
        logTextView.setTextSize(12);
        logTextView.setPadding(dp(12), dp(12), dp(12), dp(12));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(logTextView);

        new AlertDialog.Builder(this)
            .setTitle("Log part " + (partIndex + 1) + " of " + chunks.size())
            .setView(scrollView)
            .setPositiveButton("Copy this part", (dialog, which) -> {
                copyTextToClipboard("LiveMonitor Global Log part " + (partIndex + 1) + " of " + chunks.size(), chunks.get(partIndex).getText());
                Toast.makeText(this, "Copied log part " + (partIndex + 1) + ".", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void copyTextToClipboard(String label, String text) {
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
            .setTitle("Clear global log?")
            .setMessage("This clears the app log. Recordings are not affected.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear", (dialog, which) -> clearLog())
            .show();
    }

    private void clearLog() {
        storage.clearAllLogs();
        refreshLogs();

        storage.appendLog(LogItem.info(LogItem.SOURCE_UI, "Global log cleared."));
        refreshLogs();

        Toast.makeText(this, "Log cleared.", Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
