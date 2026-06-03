# Bundling yt-dlp for Android

The app cannot reliably execute Termux's `yt-dlp` or a binary stored on shared
storage. Android runs this app in its own sandbox, and shared storage is commonly
mounted without execute permission.

## Preferred layout

Provide an ABI-specific executable named `libyt-dlp.so` under `app/src/main/jniLibs`:

```text
app/src/main/jniLibs/arm64-v8a/libyt-dlp.so
app/src/main/jniLibs/x86_64/libyt-dlp.so
```

Despite the `.so` filename, this file may be an executable native binary. The
name is intentional: Android extracts packaged native libraries into the app's
`nativeLibraryDir`, which is an app-owned executable location. On startup,
`YtDlpEnvironment` looks for `libyt-dlp.so` there and rewrites the in-memory
`ytDlpExecutable` path to that extracted file.

## Remote config fallback

If you do not bundle `libyt-dlp.so`, set an absolute app-accessible executable
path in remote config:

```json
{
  "youtubeExtractorMode": "yt-dlp-first",
  "ytDlpExecutable": "/absolute/path/owned/by/this/app/yt-dlp",
  "javaHlsFallbackEnabled": true
}
```

Do not point this value at Termux private files or `/storage/emulated/0`.
