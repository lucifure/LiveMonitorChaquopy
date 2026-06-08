package com.livemonitor.app;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Visible, user-approved YouTube sign-in flow for personal-use yt-dlp cookies.
 */
public class YouTubeSignInActivity extends AppCompatActivity {
    private static final String START_URL = "https://www.youtube.com/";
    private static final String[] COOKIE_URLS = new String[] {
        "https://www.youtube.com/",
        "https://m.youtube.com/",
        "https://youtube.com/",
        "https://accounts.google.com/"
    };
    private static final String COOKIE_FILE_NAME = "youtube-cookies.txt";

    private AppStorage storage;
    private WebView webView;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        storage = new AppStorage(this);
        setTitle("YouTube WebView Sign-In");
        setContentView(buildContentView());
        configureWebView();
        webView.loadUrl(START_URL);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }

        super.onDestroy();
    }

    private LinearLayout buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(Color.rgb(15, 15, 15));

        TextView title = new TextView(this);
        title.setText("Sign in to YouTube in this visible WebView, then tap Save YouTube session.");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        statusText = new TextView(this);
        statusText.setText("No session saved yet.");
        statusText.setTextColor(Color.rgb(190, 190, 190));
        statusText.setTextSize(13);
        statusText.setPadding(0, 0, 0, dp(8));
        root.addView(statusText);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);

        Button saveButton = new Button(this);
        saveButton.setAllCaps(false);
        saveButton.setText("Save YouTube session");
        saveButton.setOnClickListener(v -> saveYouTubeSession());
        buttonRow.addView(saveButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button reloadButton = new Button(this);
        reloadButton.setAllCaps(false);
        reloadButton.setText("Open YouTube");
        reloadButton.setOnClickListener(v -> webView.loadUrl(START_URL));
        buttonRow.addView(reloadButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttonRow);

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));

        return root;
    }

    private void configureWebView() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(settings.getUserAgentString());

        webView.setWebViewClient(new WebViewClient());
    }

    private void saveYouTubeSession() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.flush();

        Map<String, CookieEntry> cookies = collectCookies(cookieManager);

        if (cookies.isEmpty()) {
            Toast.makeText(this, "No YouTube cookies found yet. Sign in first, then save.", Toast.LENGTH_LONG).show();
            return;
        }

        String cookieHeader = buildCookieHeader(cookies);
        File cookieFile = new File(getFilesDir(), COOKIE_FILE_NAME);

        try {
            writeNetscapeCookieFile(cookieFile, cookies);

            AppSettings appSettings = storage.loadSettings();
            appSettings.setYtDlpCookieHeader(cookieHeader);
            appSettings.setYtDlpCookiesPath(cookieFile.getAbsolutePath());
            storage.saveSettings(appSettings);
            storage.appendLog(LogItem.info(
                LogItem.SOURCE_UI,
                "Saved YouTube WebView session cookies for yt-dlp."
            ));

            String message = "Saved " + cookies.size() + " cookies to Settings and " + cookieFile.getName() + ".";
            statusText.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Unable to write cookies.txt: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private Map<String, CookieEntry> collectCookies(CookieManager cookieManager) {
        Map<String, CookieEntry> cookies = new LinkedHashMap<>();

        for (String url : COOKIE_URLS) {
            String header = cookieManager.getCookie(url);

            if (isBlank(header)) {
                continue;
            }

            String domain = cookieDomainForUrl(url);

            for (String part : header.split(";")) {
                String cookie = part.trim();
                int equalsIndex = cookie.indexOf('=');

                if (equalsIndex <= 0) {
                    continue;
                }

                String name = cookie.substring(0, equalsIndex).trim();
                String value = cookie.substring(equalsIndex + 1).trim();

                if (!isBlank(name)) {
                    cookies.put(name, new CookieEntry(domain, name, value));
                }
            }
        }

        return cookies;
    }

    private String buildCookieHeader(Map<String, CookieEntry> cookies) {
        StringBuilder builder = new StringBuilder();

        for (CookieEntry cookie : cookies.values()) {
            if (builder.length() > 0) {
                builder.append("; ");
            }

            builder.append(cookie.name).append('=').append(cookie.value);
        }

        return builder.toString();
    }

    private void writeNetscapeCookieFile(File file, Map<String, CookieEntry> cookies) throws IOException {
        try (FileWriter writer = new FileWriter(file, false)) {
            writer.write("# Netscape HTTP Cookie File\n");
            writer.write("# Generated from the app's user-approved YouTube WebView session.\n");

            for (CookieEntry cookie : cookies.values()) {
                writer.write(cookie.domain);
                writer.write("\tTRUE\t/\tTRUE\t2147483647\t");
                writer.write(cookie.name);
                writer.write('\t');
                writer.write(cookie.value);
                writer.write('\n');
            }
        }
    }

    private String cookieDomainForUrl(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);

        if (lower.contains("accounts.google.com")) {
            return ".accounts.google.com";
        }

        if (lower.contains("m.youtube.com")) {
            return ".m.youtube.com";
        }

        return ".youtube.com";
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class CookieEntry {
        final String domain;
        final String name;
        final String value;

        CookieEntry(String domain, String name, String value) {
            this.domain = domain;
            this.name = name;
            this.value = value;
        }
    }
}
