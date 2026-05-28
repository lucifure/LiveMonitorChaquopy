package com.livemonitor.app;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public class NewPipeDownloader extends Downloader {

    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36";

    private static NewPipeDownloader instance;
    private final OkHttpClient client;

    private NewPipeDownloader() {
        client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    public static synchronized NewPipeDownloader getInstance() {
        if (instance == null) instance = new NewPipeDownloader();
        return instance;
    }

    @Override
    public Response execute(@NonNull Request request) throws IOException, ReCaptchaException {
        String httpMethod = request.httpMethod();
        String url       = request.url();
        Map<String, List<String>> headers = request.headers();
        byte[] dataToSend = request.dataToSend();

        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);
        builder.addHeader("User-Agent", USER_AGENT);

        // Add all request headers
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                for (String val : entry.getValue()) {
                    builder.addHeader(entry.getKey(), val);
                }
            }
        }

        RequestBody body = null;
        if (dataToSend != null) {
            body = RequestBody.create(dataToSend);
        }

        switch (httpMethod) {
            case "GET":    builder.get(); break;
            case "POST":   builder.post(body != null ? body : RequestBody.create(new byte[0])); break;
            case "DELETE": builder.delete(body); break;
            case "HEAD":   builder.head(); break;
            default:       builder.method(httpMethod, body); break;
        }

        okhttp3.Response response = client.newCall(builder.build()).execute();

        if (response.code() == 429) {
            throw new ReCaptchaException("Rate-limited by YouTube", url);
        }

        Map<String, List<String>> responseHeaders = new HashMap<>(response.headers().toMultimap());
        ResponseBody responseBody = response.body();
        String responseBodyText = responseBody != null ? responseBody.string() : "";

        return new Response(
            response.code(),
            response.message(),
            responseHeaders,
            responseBodyText,
            response.request().url().toString()
        );
    }
}
