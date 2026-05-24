import threading
import sys
import os
import stat
import traceback

_download_thread = None
_stop_event = threading.Event()
_callback = None
_ffmpeg_path = None


def _log(msg, msg_type="info"):
    if _callback is not None:
        try:
            _callback.onLog(str(msg), str(msg_type))
        except Exception:
            pass
    print("[recorder/" + msg_type + "] " + str(msg), file=sys.stderr)


def _finished(success, reason=""):
    if _callback is not None:
        try:
            _callback.onFinished(bool(success), str(reason))
        except Exception:
            pass


def _progress_hook(d):
    status = d.get("status", "")
    if status == "downloading":
        filename = os.path.basename(d.get("filename", ""))
        downloaded = d.get("downloaded_bytes", 0)
        speed = d.get("speed") or 0
        eta = d.get("eta")
        mb = downloaded / (1024 * 1024)
        kbs = speed / 1024 if speed else 0
        eta_str = " ETA " + str(eta) + "s" if eta else ""
        _log("down " + str(round(mb, 1)) + "MB " + str(round(kbs, 0)) + "KB/s" + eta_str, "download")
    elif status == "finished":
        _log("Segment done: " + os.path.basename(d.get("filename", "")), "success")
    elif status == "error":
        _log("yt-dlp error: " + str(d.get("error", "unknown")), "error")


def _postprocessor_hook(d):
    if d.get("status") == "started":
        _log("Post-processing: " + str(d.get("postprocessor", "")), "info")
    elif d.get("status") == "finished":
        _log("Post-processing complete.", "success")


def _do_record(watch_url, out_path):
    try:
        import yt_dlp

        _log("yt-dlp version: " + yt_dlp.version.__version__, "info")
        _log("Starting: " + watch_url, "info")

        os.makedirs(os.path.dirname(out_path), exist_ok=True)

        ffmpeg = _ffmpeg_path
        _log("ffmpeg path: " + str(ffmpeg), "info")

        if ffmpeg and os.path.exists(ffmpeg):
            size = os.path.getsize(ffmpeg)
            _log("ffmpeg found! Size: " + str(size) + " bytes", "success")
            try:
                os.chmod(ffmpeg, stat.S_IRWXU)
            except Exception as e:
                _log("chmod warning: " + str(e), "warning")
        else:
            _log("ffmpeg not found at: " + str(ffmpeg), "error")
            _finished(False, "ffmpeg not found")
            return

        ydl_opts = {
            "outtmpl": out_path,
            "format": "best[height<=720]/best",
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
            "prefer_ffmpeg": True,
            "ffmpeg_location": ffmpeg,
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
            _log("yt-dlp exit code: " + str(ret), "error")
            _finished(False, "exit_code_" + str(ret))

    except Exception as e:
        _log("Exception: " + str(e), "error")
        _log(traceback.format_exc(), "error")
        _finished(False, str(e))


def start(watch_url, out_path, java_callback, ffmpeg_path):
    global _download_thread, _callback, _ffmpeg_path
    _stop_event.clear()
    _callback = java_callback
    _ffmpeg_path = str(ffmpeg_path) if ffmpeg_path else ""
    _log("ffmpeg path received: " + _ffmpeg_path, "info")
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
        return "error: " + str(e)
