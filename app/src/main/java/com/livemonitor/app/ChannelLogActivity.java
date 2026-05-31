package com.livemonitor.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Per-channel log screen.
 *
 * Opened by tapping a channel in Monitoring tab.
 */
public class ChannelLogActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID = LiveMonitorActions.EXTRA_CHANNEL_ID;
    public static final String EXTRA_CHANNEL_TITLE = LiveMonitorActions.EXTRA_CHANNEL_TITLE;

    private AppStorage storage;
    private LogAdapter adapter;
    private String channelId;
    private String channelTitle;
    private TextView emptyView;
    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        storage = new AppStorage(this);
        adapter = new LogAdapter(this);

        channelId = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelTitle = getIntent().getStringExtra(EXTRA_CHANNEL_TITLE);

        if (channelTitle == null || channelTitle.trim().isEmpty()) {
            ChannelItem channel = storage.findChannelById(channelId);
            channelTitle = channel == null ? "Channel Log" : channel.getDisplayTitle();
        }

        setTitle(channelTitle);
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

        TextView title = new TextView(this);
        title.setText(channelTitle);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(8));

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
        emptyView.setText("No log entries for this channel yet.");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setTextSize(15);

        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);

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
        adapter.setLogs(storage.loadLogsForChannel(channelId));
    }

    private void copyLog() {
        String text = storage.buildCopyTextForChannel(channelId);

        if (text.trim().isEmpty()) {
            Toast.makeText(this, "Channel log is empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard =
            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);

        clipboard.setPrimaryClip(
            ClipData.newPlainText("LiveMonitor Channel Log - " + channelTitle, text)
        );

        Toast.makeText(this, "Channel log copied.", Toast.LENGTH_SHORT).show();
    }

    private void clearLog() {
        storage.clearLogsForChannel(channelId);
        refreshLogs();

        storage.appendLog(new LogItem(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_UI,
            channelId,
            channelTitle,
            "",
            "",
            "Channel log cleared.",
            ""
        ));
        refreshLogs();

        Toast.makeText(this, "Channel log cleared.", Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
