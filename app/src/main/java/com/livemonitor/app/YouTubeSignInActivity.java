package com.livemonitor.app;

import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Visible, user-approved YouTube session and PO-token setup flow for personal-use yt-dlp.
 */
public class YouTubeSignInActivity extends AppCompatActivity {
    private static final String DEFAULT_TOKEN_VIDEO_ID = "dQw4w9WgXcQ";
    private static final String START_URL = "https://m.youtube.com/watch?v=" + DEFAULT_TOKEN_VIDEO_ID;
    private static final String[] COOKIE_URLS = new String[] {
        "https://www.youtube.com/",
        "https://m.youtube.com/",
        "https://youtube.com/",
        "https://accounts.google.com/"
    };
    private static final String COOKIE_FILE_NAME = "youtube-cookies.txt";
    private static final String TOKEN_TYPE_GVS = "gvs";
    private static final String PO_TOKEN_SCRIPT = "(function(){"
        + "function readCfg(name){try{return window.ytcfg&&window.ytcfg.get?window.ytcfg.get(name):null;}catch(e){return null;}}"
        + "function isTokenKey(key){return /po[_-]?token|potoken/i.test(String(key||''));}"
        + "function validToken(value){return typeof value==='string'&&value.length>10&&value.indexOf('TOKEN')<0&&value.indexOf('...')<0;}"
        + "var found={token:'',source:''};"
        + "function remember(token,source){if(!found.token&&validToken(token)){found.token=token;found.source=source;}}"
        + "function readPotFromUrl(value,path){if(found.token||typeof value!=='string'||value.indexOf('pot=')<0){return;}"
        + "try{remember(new URL(value,location.href).searchParams.get('pot'),path+'.url.pot');}catch(e){"
        + "var match=value.match(/[?&]pot=([^&#]+)/);if(match){remember(decodeURIComponent(match[1].replace(/\\+/g,'%20')),path+'.url.pot');}}}"
        + "function walk(value,path,depth){"
        + "if(found.token||!value||depth>8){return;}"
        + "readPotFromUrl(value,path);if(found.token){return;}"
        + "if(Array.isArray(value)){for(var i=0;i<value.length&&!found.token;i++){walk(value[i],path+'['+i+']',depth+1);}return;}"
        + "if(typeof value==='object'){var keys=Object.keys(value);for(var k=0;k<keys.length&&!found.token;k++){"
        + "var key=keys[k];var next=value[key];var nextPath=path+'.'+key;"
        + "if(isTokenKey(key)&&validToken(next)){remember(next,nextPath);return;}"
        + "walk(next,nextPath,depth+1);}}}"
        + "try{walk(readCfg('WEB_PLAYER_CONTEXT_CONFIGS'),'ytcfg.WEB_PLAYER_CONTEXT_CONFIGS',0);}catch(e){}"
        + "try{walk(window.ytInitialPlayerResponse,'ytInitialPlayerResponse',0);}catch(e){}"
        + "try{walk(readCfg('PLAYER_VARS'),'ytcfg.PLAYER_VARS',0);}catch(e){}"
        + "try{walk(readCfg('PLAYER_CONFIG'),'ytcfg.PLAYER_CONFIG',0);}catch(e){}"
        + "try{var entries=performance&&performance.getEntriesByType?performance.getEntriesByType('resource'):[];"
        + "for(var i=entries.length-1;i>=0&&!found.token&&i>entries.length-80;i--){readPotFromUrl(entries[i].name,'performance.resource['+i+']');}}catch(e){}"
        + "var clientName=readCfg('INNERTUBE_CONTEXT_CLIENT_NAME')||readCfg('INNERTUBE_CLIENT_NAME')||'MWEB';"
        + "var clientVersion=readCfg('INNERTUBE_CONTEXT_CLIENT_VERSION')||readCfg('INNERTUBE_CLIENT_VERSION')||'';"
        + "var visitorData=readCfg('VISITOR_DATA')||'';"
        + "var videoId=(window.ytInitialPlayerResponse&&window.ytInitialPlayerResponse.videoDetails&&window.ytInitialPlayerResponse.videoDetails.videoId)||'';"
        + "if(!videoId){try{videoId=new URL(location.href).searchParams.get('v')||'';}catch(e){}}"
        + "return JSON.stringify({token:found.token,tokenType:'gvs',source:found.source,clientName:clientName,clientVersion:clientVersion,visitorData:visitorData,videoId:videoId,playerUrl:location.href});"
        + "})()";

    private AppStorage storage;
    private WebView webView;
    private TextView statusText;
    private EditText playerUrlInput;
    private String lastObservedPoToken = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        storage = new AppStorage(this);
        setTitle("YouTube PO Token Setup");
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
        title.setText(
            "Use this visible WebView to sign in, load a real YouTube player, then tap Generate/Refresh PO token. "
                + "The app does not run hidden token scraping."
        );
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        statusText = new TextView(this);
        statusText.setText(buildInitialStatusText());
        statusText.setTextColor(Color.rgb(190, 190, 190));
        statusText.setTextSize(13);
        statusText.setPadding(0, 0, 0, dp(8));
        root.addView(statusText);

        playerUrlInput = new EditText(this);
        playerUrlInput.setSingleLine(true);
        playerUrlInput.setTextColor(Color.WHITE);
        playerUrlInput.setHintTextColor(Color.rgb(140, 140, 140));
        playerUrlInput.setText(START_URL);
        playerUrlInput.setHint("YouTube watch URL or video ID for token context");
        root.addView(playerUrlInput);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);

        Button saveButton = new Button(this);
        saveButton.setAllCaps(false);
        saveButton.setText("Save session");
        saveButton.setOnClickListener(v -> saveYouTubeSession());
        buttonRow.addView(saveButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button openPlayerButton = new Button(this);
        openPlayerButton.setAllCaps(false);
        openPlayerButton.setText("Open player");
        openPlayerButton.setOnClickListener(v -> openPlayerContext());
        buttonRow.addView(openPlayerButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(buttonRow);

        Button refreshTokenButton = new Button(this);
        refreshTokenButton.setAllCaps(false);
        refreshTokenButton.setText("Generate/Refresh PO token");
        refreshTokenButton.setOnClickListener(v -> generatePoTokenFromVisiblePlayer());
        root.addView(refreshTokenButton);

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));

        return root;
    }

    private String buildInitialStatusText() {
        AppSettings settings = storage == null ? new AppSettings() : storage.loadSettings();

        if (!settings.hasYtDlpPoToken()) {
            return "No PO token cached yet. Load a player page and tap Generate/Refresh PO token.";
        }

        String refreshState = settings.isYtDlpPoTokenRefreshNeeded(System.currentTimeMillis())
            ? "refresh recommended"
            : "fresh";
        return "Cached PO token: client="
            + settings.getYtDlpPoTokenClient()
            + ", type="
            + settings.getYtDlpPoTokenType()
            + ", video="
            + settings.getYtDlpPoTokenVideoId()
            + ", "
            + refreshState
            + ".";
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

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                statusText.setText("Loading visible YouTube context: " + safeUrlForStatus(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                statusText.setText("Loaded player context: " + safeUrlForStatus(url));
                scheduleVisiblePlayerTokenScan(view, url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                WebView view,
                WebResourceRequest request
            ) {
                observePotentialPoTokenUrl(request == null ? null : request.getUrl());
                return super.shouldInterceptRequest(view, request);
            }
        });
    }

    private void scheduleVisiblePlayerTokenScan(WebView view, String url) {
        if (view == null || !looksLikePlayerUrl(url)) {
            return;
        }

        view.postDelayed(() -> {
            if (webView == null || !looksLikePlayerUrl(webView.getUrl())) {
                return;
            }

            webView.evaluateJavascript(PO_TOKEN_SCRIPT, this::handlePoTokenScriptResult);
        }, 1500L);
    }

    private void observePotentialPoTokenUrl(Uri uri) {
        if (uri == null) {
            return;
        }

        String token = uri.getQueryParameter("pot");

        if (isBlank(token)) {
            token = uri.getQueryParameter("po_token");
        }

        if (!isValidPoTokenCandidate(token) || token.equals(lastObservedPoToken)) {
            return;
        }

        lastObservedPoToken = token;
        runOnUiThread(() -> saveObservedPoToken(
            token,
            "visible-player:network-url-pot",
            webView == null ? "" : webView.getUrl(),
            true
        ));
    }

    private void openPlayerContext() {
        String playerUrl = normalizePlayerUrl(playerUrlInput.getText().toString());
        playerUrlInput.setText(playerUrl);
        statusText.setText("Loading visible YouTube player context...");
        webView.loadUrl(playerUrl);
    }

    private void generatePoTokenFromVisiblePlayer() {
        String currentUrl = webView.getUrl();

        if (!looksLikePlayerUrl(currentUrl)) {
            Toast.makeText(this, "Load a real YouTube player page first.", Toast.LENGTH_LONG).show();
            openPlayerContext();
            return;
        }

        statusText.setText("Checking visible player context for an observable GVS PO token...");
        webView.evaluateJavascript(PO_TOKEN_SCRIPT, this::handlePoTokenScriptResult);
    }

    private void handlePoTokenScriptResult(String value) {
        try {
            Object unwrapped = new JSONTokener(value == null ? "null" : value).nextValue();
            String jsonText = unwrapped instanceof String ? (String) unwrapped : String.valueOf(unwrapped);
            JSONObject json = new JSONObject(jsonText);
            String token = json.optString("token", "").trim();

            if (isBlank(token)) {
                String message = "No GVS PO token was observable on this loaded player page. Try playing the video, sign in, or open another video.";
                statusText.setText(message);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                return;
            }

            saveGeneratedPoToken(json, token);
        } catch (Exception e) {
            String message = "Unable to read PO-token data from the visible player: " + e.getMessage();
            statusText.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void saveGeneratedPoToken(JSONObject json, String token) {
        String source = "visible-player:" + json.optString("source", "player-context");
        String playerUrl = json.optString("playerUrl", webView == null ? "" : webView.getUrl());
        saveObservedPoToken(token, source, playerUrl, true);
    }

    private void saveObservedPoToken(
        String token,
        String source,
        String playerUrl,
        boolean showToast
    ) {
        String normalizedToken = token == null ? "" : token.trim();

        if (!isValidPoTokenCandidate(normalizedToken)) {
            return;
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.flush();

        Map<String, CookieEntry> cookies = collectCookies(cookieManager);
        String cookieHeader = buildCookieHeader(cookies);
        String sessionBinding = sha256Prefix(cookieHeader, 16);
        String videoId = extractVideoId(playerUrl);

        if (isBlank(videoId)) {
            videoId = extractVideoId(webView == null ? "" : webView.getUrl());
        }

        AppSettings appSettings = storage.loadSettings();
        appSettings.setYtDlpPoTokenClient("mweb");
        appSettings.setYtDlpPoTokenValue(normalizedToken);
        appSettings.setYtDlpPoTokenMetadata(
            TOKEN_TYPE_GVS,
            System.currentTimeMillis(),
            source,
            sessionBinding,
            videoId,
            isBlank(playerUrl) ? START_URL : playerUrl
        );
        storage.saveSettings(appSettings);
        storage.appendLog(LogItem.info(
            LogItem.SOURCE_UI,
            "Cached YouTube GVS PO token from visible WebView player context. client=mweb"
                + ", type="
                + TOKEN_TYPE_GVS
                + ", videoId="
                + videoId
                + ", source="
                + source
                + ", session="
                + sessionBinding
        ));

        String message = "Cached GVS PO token for client=mweb, video="
            + (isBlank(videoId) ? "unknown" : videoId)
            + ". Token value is hidden from logs.";
        statusText.setText(message);

        if (showToast) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
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
                "Saved YouTube WebView session cookies for yt-dlp. session="
                    + sha256Prefix(cookieHeader, 16)
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
                String cookieValue = cookie.substring(equalsIndex + 1).trim();

                if (!isBlank(name)) {
                    cookies.put(name, new CookieEntry(domain, name, cookieValue));
                }
            }
        }

        return cookies;
    }

    private String buildCookieHeader(Map<String, CookieEntry> cookies) {
        StringBuilder builder = new StringBuilder();

        if (cookies == null) {
            return "";
        }

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

    private boolean isValidPoTokenCandidate(String value) {
        if (isBlank(value)) {
            return false;
        }

        String trimmed = value.trim();
        return trimmed.length() > 10
            && !trimmed.equalsIgnoreCase("TOKEN")
            && !trimmed.contains("...");
    }

    private String normalizePlayerUrl(String value) {
        String trimmed = value == null ? "" : value.trim();

        if (trimmed.matches("^[A-Za-z0-9_-]{11}$")) {
            return "https://m.youtube.com/watch?v=" + trimmed;
        }

        if (!isBlank(extractVideoId(trimmed))) {
            return trimmed;
        }

        return START_URL;
    }

    private boolean looksLikePlayerUrl(String url) {
        return !isBlank(extractVideoId(url));
    }

    private String extractVideoId(String url) {
        if (isBlank(url)) {
            return "";
        }

        try {
            Uri uri = Uri.parse(url.trim());
            String queryVideoId = uri.getQueryParameter("v");

            if (!isBlank(queryVideoId)) {
                return queryVideoId.trim();
            }

            String lastPathSegment = uri.getLastPathSegment();

            if (!isBlank(lastPathSegment) && lastPathSegment.matches("^[A-Za-z0-9_-]{11}$")) {
                return lastPathSegment;
            }
        } catch (RuntimeException ignored) {
            // Fall back to an empty video ID below.
        }

        return "";
    }

    private String normalizeClientForYtDlp(String clientName) {
        String normalized = clientName == null ? "" : clientName.trim().toLowerCase(Locale.US);

        if (normalized.contains("mweb")) {
            return "mweb";
        }

        if (normalized.contains("web")) {
            return "web";
        }

        if (normalized.contains("android")) {
            return "android";
        }

        if (normalized.contains("ios")) {
            return "ios";
        }

        return "mweb";
    }

    private String safeUrlForStatus(String url) {
        String videoId = extractVideoId(url);
        return isBlank(videoId) ? "non-player page" : "videoId=" + videoId;
    }

    private String sha256Prefix(String value, int length) {
        if (isBlank(value)) {
            return "no-session";
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();

            for (byte b : hash) {
                builder.append(String.format(Locale.US, "%02x", b));
            }

            return builder.substring(0, Math.min(Math.max(1, length), builder.length()));
        } catch (Exception e) {
            return "session-hash-error";
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
