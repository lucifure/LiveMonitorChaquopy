package com.livemonitor.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Minimal authenticated client for the Oracle Control API. Never follows a redirect. */
public final class OracleControlClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final String baseUrl;
    private final String token;
    private final OkHttpClient http = new OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build();

    public OracleControlClient(Context context) {
        baseUrl = OracleServerSettings.normalizeHttpsBaseUrl(OracleServerSettings.getBaseUrl(context));
        token = OracleServerSettings.getDeviceToken(context);
        if (token.isEmpty()) throw new IllegalStateException("Oracle server pairing is not configured.");
    }

    public JSONObject get(String path) throws IOException { return request("GET", path, null); }
    public JSONObject post(String path, JSONObject body) throws IOException { return request("POST", path, body); }
    public JSONObject delete(String path) throws IOException { return request("DELETE", path, null); }

    public void download(String name, File target) throws IOException {
        String path = "/recordings/" + android.net.Uri.encode(name);
        Request request = new Request.Builder().url(baseUrl + path).header("Authorization", "Bearer " + token).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new ApiException(response.code(), "Download failed (HTTP " + response.code() + ").");
            if (response.body() == null) throw new IOException("Empty download response.");
            try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[32 * 1024]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        }
    }

    private JSONObject request(String method, String path, JSONObject body) throws IOException {
        if (path == null || !path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("Invalid API path.");
        RequestBody requestBody = body == null ? null : RequestBody.create(body.toString(), JSON);
        Request request = new Request.Builder().url(baseUrl + path).method(method, requestBody)
            .header("Authorization", "Bearer " + token).header("Accept", "application/json").build();
        try (Response response = http.newCall(request).execute()) {
            String payload = response.body() == null ? "" : response.body().string();
            if (payload.length() > MAX_RESPONSE_BYTES) throw new IOException("Server response is too large.");
            if (!response.isSuccessful()) throw new ApiException(response.code(), "Server request failed (HTTP " + response.code() + ").");
            return new JSONObject(payload);
        } catch (org.json.JSONException error) {
            throw new IOException("Server returned invalid JSON.", error);
        }
    }

    public static final class ApiException extends IOException {
        private final int statusCode;
        ApiException(int statusCode, String message) { super(message); this.statusCode = statusCode; }
        public int getStatusCode() { return statusCode; }
    }
}
