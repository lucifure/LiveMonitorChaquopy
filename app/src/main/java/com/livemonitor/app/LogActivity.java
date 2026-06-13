package com.livemonitor.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Global log screen.
 *
 * Shows logs from all channels and app components.
 */
public class LogActivity extends AppCompatActivity {

    private static final int CLIPBOARD_SAFE_CHUNK_CHARS = 90_000;

    private AppStorage storage;
    private LogAdapter adapter;
    private ListView listView;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        storage = new AppStorage(this);
        adapter = new LogAdapter(this);

        setTitle("Global Log");
        setContentView(buildContentView());

        refreshLogs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLogs();
    }

    private LinearLayout buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(Color.rgb(15, 15, 15));

        TextView title = new TextView(this);
        title.setText("Global Log");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(8));
        title.setTextColor(Color.WHITE);
        root.addView(
            title,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.END);

        TextView filters = new TextView(this);
        filters.setText("All   Errors   Warnings   Success");
        filters.setTextColor(Color.rgb(190, 190, 190));
        filters.setPadding(0, 0, 0, dp(8));
        root.addView(filters);

        Button copyButton = new Button(this);
        copyButton.setAllCaps(false);
        copyButton.setText("Copy Log");
        copyButton.setOnClickListener(v -> copyLog());

        Button clearButton = new Button(this);
        clearButton.setAllCaps(false);
        clearButton.setText("Clear Log");
        clearButton.setOnClickListener(v -> clearLog());

        buttonRow.addView(copyButton);

        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        clearParams.leftMargin = dp(8);
        buttonRow.addView(clearButton, clearParams);

        root.addView(
            buttonRow,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        emptyView = new TextView(this);
        emptyView.setText("No log entries yet.");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextSize(15);
        emptyView.setTextColor(Color.rgb(102, 102, 102));

        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);
        listView.setBackgroundColor(Color.rgb(15, 15, 15));

        root.addView(
            emptyView,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );

        root.addView(
            listView,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        );

        return root;
    }

    private void refreshLogs() {
        adapter.setLogs(storage.loadLogs());
    }

    private void copyLog() {
        String text = storage.buildCopyTextForAllLogs();

        if (text.trim().isEmpty()) {
            Toast.makeText(this, "Log is empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (text.length() <= CLIPBOARD_SAFE_CHUNK_CHARS) {
            copyTextToClipboard("LiveMonitor Global Log", text);
            Toast.makeText(this, "Full log copied.", Toast.LENGTH_SHORT).show();
            return;
        }

        showChunkedCopyDialog(splitLogForClipboard(text));
    }

    private void showChunkedCopyDialog(String[] chunks) {
        if (chunks == null || chunks.length == 0) {
            Toast.makeText(this, "Log is empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), 0);

        TextView helpText = new TextView(this);
        helpText.setText("Choose Copy to place a safe-sized part on the clipboard, or View/Select to select only the lines you need.");
        helpText.setPadding(0, 0, 0, dp(8));
        content.addView(helpText);

        for (int i = 0; i < chunks.length; i++) {
            final int partIndex = i;

            TextView partLabel = new TextView(this);
            partLabel.setText("Part " + (i + 1) + " of " + chunks.length + " (" + chunks[i].length() + " chars)");
            partLabel.setPadding(0, dp(8), 0, dp(4));
            content.addView(partLabel);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            Button copyPartButton = new Button(this);
            copyPartButton.setAllCaps(false);
            copyPartButton.setText("Copy");
            copyPartButton.setOnClickListener(v -> {
                copyTextToClipboard("LiveMonitor Global Log part " + (partIndex + 1) + " of " + chunks.length, chunks[partIndex]);
                Toast.makeText(this, "Copied log part " + (partIndex + 1) + " of " + chunks.length + ".", Toast.LENGTH_SHORT).show();
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
            .setTitle("Log split into " + chunks.length + " parts")
            .setView(scrollView)
            .setNegativeButton("Close", null)
            .show();
    }

    private void showSelectableGlobalLogPart(String[] chunks, int partIndex) {
        TextView logTextView = new TextView(this);
        logTextView.setText(chunks[partIndex]);
        logTextView.setTextIsSelectable(true);
        logTextView.setTextSize(12);
        logTextView.setPadding(dp(12), dp(12), dp(12), dp(12));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(logTextView);

        new AlertDialog.Builder(this)
            .setTitle("Log part " + (partIndex + 1) + " of " + chunks.length)
            .setView(scrollView)
            .setPositiveButton("Copy this part", (dialog, which) -> {
                copyTextToClipboard("LiveMonitor Global Log part " + (partIndex + 1) + " of " + chunks.length, chunks[partIndex]);
                Toast.makeText(this, "Copied log part " + (partIndex + 1) + ".", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private String[] splitLogForClipboard(String text) {
        int safeChunkCount = Math.max(2, (int) Math.ceil(text.length() / (double) CLIPBOARD_SAFE_CHUNK_CHARS));
        int targetChunkLength = (int) Math.ceil(text.length() / (double) safeChunkCount);
        String[] chunks = new String[safeChunkCount];

        int start = 0;
        for (int i = 0; i < safeChunkCount; i++) {
            int end;
            if (i == safeChunkCount - 1) {
                end = text.length();
            } else {
                end = findChunkEnd(text, start, Math.min(text.length(), start + targetChunkLength));
            }

            chunks[i] = text.substring(start, end).trim();
            start = end;
        }

        return chunks;
    }

    private int findChunkEnd(String text, int start, int preferredEnd) {
        int minEnd = Math.min(text.length(), start + Math.max(1, CLIPBOARD_SAFE_CHUNK_CHARS / 2));
        int newline = text.lastIndexOf('\n', preferredEnd);

        if (newline >= minEnd) {
            return newline + 1;
        }

        return preferredEnd;
    }

    private void copyTextToClipboard(String label, String text) {
        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
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
