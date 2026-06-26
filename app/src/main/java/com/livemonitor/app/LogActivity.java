package com.livemonitor.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Global log screen.
 *
 * Shows logs from all channels and app components.
 */
public class LogActivity extends AppCompatActivity {

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
        root.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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

        Button saveButton = new Button(this);
        saveButton.setAllCaps(false);
        saveButton.setText("Save Log");
        saveButton.setOnClickListener(v -> saveLog());
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        saveParams.leftMargin = dp(8);
        buttonRow.addView(saveButton, saveParams);

        Button viewSelectButton = new Button(this);
        viewSelectButton.setAllCaps(false);
        viewSelectButton.setText("View/Select & Copy");
        viewSelectButton.setOnClickListener(v -> viewSelectFullLog());
        LinearLayout.LayoutParams viewSelectParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        viewSelectParams.leftMargin = dp(8);
        buttonRow.addView(viewSelectButton, viewSelectParams);

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

        listView = new ListView(this);
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

        return root;
    }

    private void refreshLogs() {
        List<LogItem> logs = storage.loadLogs();
        adapter.setLogs(logs);
        boolean isEmpty = logs == null || logs.isEmpty();
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        listView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void copyLog() {
        String text = storage.buildCopyTextForAllLogs();

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
        String text = storage.buildCopyTextForAllLogs();

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
