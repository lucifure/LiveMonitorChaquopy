import threading
import sys
import os
import stat
import traceback

_download_thread = None
_stop_event = threading.Event()
_callback = None


def _log(msg, msg_type="info"):
    if _callback is not None:
        try:
            _callback.onLog(str(msg), str(msg_type))
        except Exception:
            pass
    print(f"[recorder/{msg_type}] {msg}", file=sys.stderr)


def _finished(success, reason=""):
    if _callback is not None:
        try:
            _callback.onFinished(bool(success), str(reason))
        except Exception:
            pass


def _get_ffmpeg_path():
    try:
        files_dir = "/data/data/com.livemonitor.app/files"
        ffmpeg_dest = os.path.join(files_dir, "ffmpeg")
        os.makedirs(files_dir, exist_ok=True)

        if os.path.exists(ffmpeg_dest) and os.path.getsize(ffmpeg_dest) > 100000:
            os.chmod(ffmpeg_dest, stat.S_IRWXU)
            _log("ffmpeg already exists: " + ffmpeg_dest, "success")
            return ffmpeg_dest

        _log("Looking for ffmpeg in assets...", "info")
        possible_assets = [
            "/data/app/com.livemonitor.app-1/base.apk",
            "/data/app/com.livemonitor.app-2/base.apk",
        ]

        import zipfile
        for apk_path in possible_assets:
            if os.path.exists(apk_path):
                _log(f"Found APK: {apk_path}", "info")
                try:
                    with zipfile.ZipFile(apk_path, 'r') as apk:
                        if 'assets/ffmpeg' in apk.namelist():
                            _log("Extracting ffmpeg from APK...", "info")
                            with apk.open('assets/ffmpeg') as src:
                                with open(ffmpeg_dest, 'wb') as dst:
                                    dst.write(src.read())
                            os.chmod(ffmpeg_dest, stat.S_IRWXU)
                            size = os.path.getsize(ffmpeg_dest)
                            _log(f"ffmpeg extracted! Size: {size} bytes", "success")
                            return ffmpeg_dest
                except Exception as e:
                    _log(f"APK extract error: {e}", "error")

        _log("ffmpeg not found in APK assets", "error")
        return None
    except Exception as e:
        _log(f"ffmpeg setup error: {e}", "error")
        return None


def _progress_hook(d):
    status = d.get("status", "")
    if status == "downloading":
        filename = os.path.basename(d.get("filename", ""))
        downloaded = d.get("downloaded_bytes", 0)
        speed = d.get("speed") or 0
        eta = d.get("eta")
        mb = downloaded / (1024 * 1024)
        kbs = speed / 1024 if speed else 0
        eta_str = f" ETA {eta}s" if eta else ""
        _log(f"↓ {mb:.1f} MB  {kbs:.0f} KB/s{eta_str}  [{filename[:40]}]", "download")
    elif status == "finished":
        _log(f"Segment done: {os.path.basename(d.get('filename', ''))}", "success")
    elif status == "error":
        _log(f"yt-dlp error: {d.get('error', 'unknown')}", "error")


def _postprocessor_hook(d):
    if d.get("status") == "started":
        _log(f"Post-processing: {d.get('postprocessor', '')}", "info")
    elif d.get("status") == "finished":
        _log("Post-processing complete.", "success")


def _do_record(watch_url, out_path):
    try:
        import yt_dlp

        _log("yt-dlp version: " + yt_dlp.version.__version__, "info")
        _log("Starting: " + watch_url, "info")

        os.makedirs(os.path.dirname(out_path), exist_ok=True)

        ffmpeg_path = _get_ffmpeg_path()
        if ffmpeg_path:
            _log("ffmpeg ready - full recording enabled", "success")
        else:
            _log("No ffmpeg - will try native downloader", "warning")

        ydl_opts = {
            "outtmpl": out_path,
            "format": "best[ext=mp4]/best",
            "merge_output_format": "mp4",
            "live_from_start": False,
            "wait_for_video": (5, 300),
            "retries": 100,
            "fragment_retries": 100,
            "skip_unavailable_fragments": True,
            "keepvideo": False,
            "concurrent_fragment_downloads": 1,
            "no_warnings": False,
            "quiet": False,
            "verbose": False,
            "progress_hooks": [_progress_hook],
            "postprocessor_hooks": [_postprocessor_hook],
            "http_headers": {
                "User-Agent": "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
            },
            "paths": {"temp": os.path.dirname(out_path)},
            "writeinfojson": False,
            "writethumbnail": False,
            "writesubtitles": False,
            "writedescription": False,
            "socket_timeout": 60,
            "prefer_ffmpeg": bool(ffmpeg_path),
            "hls_prefer_native": not bool(ffmpeg_path),
            "ffmpeg_location": ffmpeg_path,
            "abort_download_if": lambda _: _stop_event.is_set(),
        }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ret = ydl.download([watch_url])

        if _stop_event.is_set():
            _log("Stopped by user.", "warning")
            _finished(False, "stopped")
        elif ret == 0:
            _log("Recording finished!", "success")
            _finished(True, "complete")
        else:
            _log(f"yt-dlp exit code: {ret}", "error")
            _finished(False, f"exit_code_{ret}")

    except Exception as e:
        _log(f"Exception: {e}", "error")
        _log(traceback.format_exc(), "error")
        _finished(False, str(e))


def start(watch_url, out_path, java_callback):
    global _download_thread, _callback
    _stop_event.clear()
    _callback = java_callback
    _download_thread = threading.Thread(
        target=_do_record,
        args=(watch_url, out_path),
        name="yt-dlp-recorder",
        daemon=True
    )
    _download_thread.start()
    _log("Recorder started.", "success")
    return True


def stop():
    _log("Stop signal received.", "warning")
    _stop_event.set()
    return True


def is_running():
    return _download_thread is not None and _download_thread.is_alive()


def get_yt_dlp_version():
    try:
        import yt_dlp
        return yt_dlp.version.__version__
    except Exception as e:
        return f"error: {e}"
