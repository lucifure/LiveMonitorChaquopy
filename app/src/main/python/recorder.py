import threading
import sys
import os
import traceback
from datetime import datetime

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
        filename = os.path.basename(d.get("filename", ""))
        _log(f"Segment done: {filename}", "success")

    elif status == "error":
        _log(f"yt-dlp error: {d.get('error', 'unknown')}", "error")


def _postprocessor_hook(d):
    if d.get("status") == "started":
        pp = d.get("postprocessor", "")
        _log(f"Post-processing: {pp}", "info")
    elif d.get("status") == "finished":
        _log("Post-processing complete.", "success")


def _do_record(watch_url, out_path):
    try:
        import yt_dlp

        _log("yt-dlp version: " + yt_dlp.version.__version__, "info")
        _log("Starting download: " + watch_url, "info")
        _log("Output: " + out_path, "info")

        os.makedirs(os.path.dirname(out_path), exist_ok=True)

        ydl_opts = {
            "outtmpl": out_path,
            "format": "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best[height<=720]/best",
            "merge_output_format": "mp4",
            "live_from_start": False,
            "wait_for_video": (5, 300),
            "retries": 100,
            "fragment_retries": 100,
            "skip_unavailable_fragments": True,
            "keepvideo": False,
            "concurrent_fragment_downloads": 4,
            "no_warnings": False,
            "quiet": False,
            "verbose": False,
            "progress_hooks": [_progress_hook],
            "postprocessor_hooks": [_postprocessor_hook],
            "http_headers": {
                "User-Agent": "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
            },
            "paths": {
                "temp": os.path.dirname(out_path),
            },
            "writeinfojson": False,
            "writethumbnail": False,
            "writesubtitles": False,
            "writedescription": False,
            "socket_timeout": 60,
            "prefer_ffmpeg": False,
            "ffmpeg_location": None,
            "abort_download_if": lambda _: _stop_event.is_set(),
        }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ret = ydl.download([watch_url])

        if _stop_event.is_set():
            _log("Recording stopped by user.", "warning")
            _finished(False, "stopped")
        elif ret == 0:
            _log("Recording finished successfully!", "success")
            _finished(True, "complete")
        else:
            _log(f"yt-dlp returned exit code {ret}", "error")
            _finished(False, f"exit_code_{ret}")

    except Exception as e:
        tb = traceback.format_exc()
        _log(f"Recording exception: {e}", "error")
        _log(tb, "error")
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
    _log("Recorder thread started.", "success")
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
