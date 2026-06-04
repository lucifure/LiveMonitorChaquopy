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

## Service warning: `yt-dlp executable needs setup`

When the monitor service logs `yt-dlp executable needs setup`, it has refused to
launch a bare `yt-dlp` command from `PATH`. This prevents the Android background
service from repeatedly hitting failures such as
`Cannot run program "yt-dlp": error=13, Permission denied` when the only working
copy is a Termux command or a file on shared storage.

Fix the warning by doing one of the following before starting monitoring:

1. Bundle an Android-compatible executable at
   `app/src/main/jniLibs/<abi>/libyt-dlp.so` for every supported ABI. The app is
   configured to extract JNI libraries, so `YtDlpEnvironment` can rewrite the
   in-memory `ytDlpExecutable` value to the extracted app-owned path.
2. Set `ytDlpExecutable` in remote config to an absolute path that
   `com.livemonitor.app` owns and can execute. Do not use Termux-private paths,
   `/storage/emulated/0`, or a plain `yt-dlp` command name.

After setup, the service should log `yt-dlp executable ready.` and successful
stream resolution should show `source=yt-dlp` in the `Playable stream URL found.`
log entry.
