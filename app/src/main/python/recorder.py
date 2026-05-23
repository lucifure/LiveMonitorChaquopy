import threading
import sys
import os
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


def _patch_yt_dlp_for_no_ffmpeg():
    """
    Monkey-patch yt-dlp to never call ffmpeg.
    Forces native HLS downloader always.
    """
    try:
        from yt_dlp.downloader import external
        original = external.ExternalFD._call_downloader

        def patched_call(self, tmpfilename, info_dict):
            raise Exception("ffmpeg disabled - using native downloader")

        external.ExternalFD._call_downloader = patched_call
        _log("yt-dlp patched to use native downloader", "success")
    except Exception as e:
        _log(f"Patch warning: {e}", "warning")


def _do_record(watch_url, out_path):
    try:
        import yt_dlp
        from yt_dlp.utils import DownloadError

        _log("yt-dlp version: " + yt_dlp.version.__version__, "info")
        _log("Starting: " + watch_url, "info")

        os.makedirs(os.path.dirname(out_path), exist_ok=True)

        ydl_opts = {
            "outtmpl": out_path,

            # Force formats that don't need ffmpeg
            # mp4 with combined video+audio in a single file
            "format": (
                "best[ext=mp4][protocol!=m3u8][protocol!=m3u8_native]"
                "/best[ext=mp4]"
                "/best[protocol!=m3u8][protocol!=m3u8_native]"
                "/best"
            ),

            "live_from_start": False,
            "wait_for_video": (5, 300),
            "retries": 100,
            "fragment_retries": 100,
            "skip_unavailable_fragments": True,
            "keepvideo": False,
            "concurrent_fragment_downloads": 1,
            "no_warnings": False,
            "quiet": False,
            "verbose": True,
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

            # Disable ffmpeg completely
            "prefer_ffmpeg": False,
            "hls_prefer_native": True,
            "hls_use_mpegts": True,
            "ffmpeg_location": "/nonexistent",

            # No post processing that needs ffmpeg
            "postprocessors": [],

            "abort_download_if": lambda _: _stop_event.is_set(),
        }

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            # First extract info to see what formats are available
            _log("Checking available formats...", "info")
            try:
                info = ydl.extract_info(watch_url, download=False)
                formats = info.get("formats", [])
                _log(f"Found {len(formats)} formats", "info")
                for f in formats[:5]:
                    ext = f.get("ext", "?")
                    proto = f.get("protocol", "?")
                    height = f.get("height", "?")
                    _log(f"  Format: {ext} proto={proto} height={height}", "info")
            except Exception as e:
                _log(f"Format check error: {e}", "warning")

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
