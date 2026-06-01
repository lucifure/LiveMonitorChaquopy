package com.livemonitor.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.List;

/**
 * Downloaded files screen from 3-dot menu.
 *
 * Shows completed and recoverable recordings.
 */
public class DownloadedFilesActivity extends AppCompatActivity {

    private AppStorage storage;
    private RecordingAdapter adapter;
    private TextView emptyView;
    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        storage = new AppStorage(this);
        adapter = new RecordingAdapter(this);
        adapter.setListener(new RecordingAdapter.Listener() {
            @Override
            public void onRecordingClicked(RecordingItem recording) {
                openRecording(recording);
            }

            @Override
            public void onOpenFileClicked(RecordingItem recording) {
                openRecording(recording);
            }

            @Override
            public void onRecoverClicked(RecordingItem recording) {
                recoverRecording(recording);
            }

            @Override
            public void onDeleteClicked(RecordingItem recording) {
                confirmStopDownload(recording);
            }
        });

        setTitle("Downloaded Files");
        setContentView(buildContentView());

        refreshFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFiles();
    }

    private LinearLayout buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("Downloaded Files");
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

        Button refreshButton = new Button(this);
        refreshButton.setAllCaps(false);
        refreshButton.setText("Refresh");
        refreshButton.setOnClickListener(v -> refreshFiles());

        root.addView(
            refreshButton,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        emptyView = new TextView(this);
        emptyView.setText("No downloaded files yet.");
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

    private void refreshFiles() {
        RecordingFileManager fileManager = new RecordingFileManager(this);
        fileManager.registerRecoverableTsFilesInStorage();

        List<RecordingItem> completed = storage.loadCompletedRecordings();
        adapter.setRecordings(completed);
    }

    private void openRecording(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        String path = recording.getBestPlayablePath();

        if (path.trim().isEmpty()) {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        File file = new File(path);

        if (!file.exists()) {
            Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Uri uri = FileProvider.getUriForFile(
                this,
                getPackageName() + ".fileprovider",
                file
            );

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, path.endsWith(".ts") ? "video/mp2t" : "video/mp4");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(intent, "Open recording"));
        } catch (Exception e) {
            Toast.makeText(
                this,
                "Unable to open file: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }


    private void confirmStopDownload(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("Stop download?")
            .setMessage("Stop further downloading and keep the file saved so far?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Stop", (dialog, which) -> stopDownload(recording))
            .show();
    }

    private void stopDownload(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(LiveMonitorActions.ACTION_STOP_RECORDING);
        intent.putExtra(LiveMonitorActions.EXTRA_RECORDING_ID, recording.getId());
        intent.putExtra(LiveMonitorActions.EXTRA_CHANNEL_ID, recording.getChannelId());

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        recording.markStoppedByUser();
        storage.upsertRecording(recording);

        if (recording.getChannelId() != null && !recording.getChannelId().trim().isEmpty()) {
            storage.removeChannel(recording.getChannelId());
        }

        refreshFiles();
        Toast.makeText(this, "Download stopped. Saved file kept.", Toast.LENGTH_SHORT).show();
    }

    private void recoverRecording(RecordingItem recording) {
        if (recording == null) {
            return;
        }

        /*
         * Actual TS -> MP4 conversion will be wired to FFmpegRunner later.
         * For now this screen marks that the file was selected for recovery.
         */
        storage.appendLog(LogItem.recording(
            LogItem.LEVEL_INFO,
            LogItem.SOURCE_UI,
            recording,
            "Recover selected for TS file."
        ));

        Toast.makeText(
            this,
            "Recovery will be handled by recorder integration.",
            Toast.LENGTH_SHORT
        ).show();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
