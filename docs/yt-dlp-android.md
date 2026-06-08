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

## What the bundled file must be

`libyt-dlp.so` must be a real Android executable for the target ABI, not just the
regular desktop `yt-dlp` Python script renamed to `.so`. In practice it must:

- be built for Android/Linux and the ABI folder it is placed in (`arm64-v8a` or
  `x86_64` in this app);
- include or be able to load everything it needs at runtime, including Python and
  required Python modules if the build is Python-based;
- be executable by the app after Android extracts it from `jniLibs`;
- accept normal `yt-dlp` command-line arguments, because `RecorderCommandBuilder`
  invokes it as an external process.

Do not place a host-machine Linux binary, Windows executable, Termux-private
script, or plain `yt-dlp` Python entrypoint in `jniLibs`; those will usually fail
on-device even if the filename is correct.

## Bundling steps

1. Build or obtain a self-contained Android executable for each ABI you ship.
   This project currently ships `arm64-v8a` and `x86_64`.
2. Copy each executable into the matching `jniLibs` directory and name it
   `libyt-dlp.so`:

   ```sh
   mkdir -p app/src/main/jniLibs/arm64-v8a app/src/main/jniLibs/x86_64
   cp /path/to/android-arm64-yt-dlp app/src/main/jniLibs/arm64-v8a/libyt-dlp.so
   cp /path/to/android-x86_64-yt-dlp app/src/main/jniLibs/x86_64/libyt-dlp.so
   chmod 755 app/src/main/jniLibs/arm64-v8a/libyt-dlp.so \
     app/src/main/jniLibs/x86_64/libyt-dlp.so
   ```

3. Build and install the APK. Android extracts `libyt-dlp.so` into the app's
   native library directory because the file is packaged as a JNI library.
4. Start monitoring and confirm these logs appear:

   ```text
   yt-dlp executable ready.
   Playable stream URL found. ... source=yt-dlp
   ```

If the service still logs `yt-dlp executable needs setup`, the bundled file was
not found or was not executable after extraction. If it logs that yt-dlp failed
after startup, the executable launched but its embedded runtime/dependencies or
YouTube extraction behavior need to be fixed.

Before building the APK, run the bundle validation helper:

```sh
./scripts/validate-yt-dlp-bundle.sh
```

The helper checks that both ABI files exist, are executable ELF files, and match
the expected CPU architecture. It cannot prove that every embedded Python module
or dynamic dependency works on Android, so still install the APK and run an
on-device resolver test after the script passes.

## Will it work?

The app side is ready for this packaging approach: it searches the extracted
native library directory for `libyt-dlp.so`, verifies that the file can execute,
and rewrites the runtime `ytDlpExecutable` value to that app-owned path. However,
it will only work on a real device if the bundled file is actually compatible
with that device ABI and can run `yt-dlp` without relying on Termux, shared
storage, or host-only dynamic libraries.

## Personal testing shortcut: youtubedl-android

For private builds, the app can use `youtubedl-android` instead of a manually
bundled `libyt-dlp.so`. This dependency packages an Android Python runtime and
`yt-dlp`, so the monitor service can initialize it and use it as the `yt-dlp`
resolver before falling back to Java HLS.

This is intended as a practical personal-testing path. If you later distribute
the APK to other people, review the dependency licenses first; for private
on-device testing, the important checks are simply that initialization logs
`youtubedl-android ready.` with `yt-dlp and ffmpeg runtimes initialized` and stream resolution logs `source=yt-dlp`.


## YouTube cookies and GVS PO tokens

Settings now has separate fields for the GVS PO token client and token value in
addition to the raw extractor-args override. Paste either the bare token value or
a `client.gvs+TOKEN` value. The recorder builds the matching yt-dlp extractor
argument as:

```text
youtube:player_client=<client>;po_token=<client>.gvs+<TOKEN>
```

A real token attempt is tried before yt-dlp defaults, remote-config clients, and
the generic Android/iOS/MWeb/Web fallback clients. Recorder logs include the
extractor-attempt list with the token value redacted so you can verify that the
correct client is attached without leaking the token. Placeholder values such as
`TOKEN` or `...` are ignored.

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

## Secure resolver logging

Stream resolver logs should not expose complete Google video manifest URLs. Those
URLs can include the viewer IP address and short-lived signed playback parameters
such as `sig`, `spc`, `bui`, `ei`, and `expire`. Keep the real URL only in memory
for FFmpeg/proxy input, and log only a redacted description.

The monitor service redacts known sensitive path and query values before writing
manifest/input URLs to app logs. A healthy resolver log should still show the
scheme, host, and non-sensitive route information, but sensitive values should be
printed as `<redacted>`.
