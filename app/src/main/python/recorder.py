"""
Live Monitor - Python recorder using yt-dlp
Runs natively on Android via Chaquopy
"""
import yt_dlp
import os
import threading
from datetime import datetime


# Callback to send logs back to Java
_log_callback = None
_stop_flag = threading.Event()


def set_log_callback(callback):
    global _log_callback
    _log_callback = callback


def log(msg):
    if _log_callback:
        _log_callback(msg)


def stop():
    _stop_flag.set()


def reset():
    _stop_flag.clear()


def is_stopped():
    return _stop_flag.is_set()


def check_live(url):
    """Check if a YouTube channel/video is currently live."""
    try:
        live_url = _get_live_url(url)
        ydl_opts = {
            'quiet': True,
            'no_warnings': True,
            'skip_download': True,
            'noplaylist': True,
            'socket_timeout': 20,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(live_url, download=False)
            if info is None:
                return False, None, None
            is_live = bool(
                info.get('is_live') or
                info.get('live_status') == 'is_live'
            )
            title = info.get('title', '')
            video_id = info.get('id', '')
            return is_live, title, video_id
    except Exception as e:
        log("Check error: " + str(e))
        return False, None, None


def record(url, output_dir):
    """Record a live stream using yt-dlp."""
    try:
        live_url = _get_live_url(url)
        os.makedirs(output_dir, exist_ok=True)

        ts = datetime.now().strftime('%d%m%Y_%H%M')
        out_template = os.path.join(
            output_dir,
            '%(title)s_' + ts + '.%(ext)s'
        )

        ydl_opts = {
            'outtmpl': out_template,
            'quiet': False,
            'no_warnings': True,
            'noplaylist': True,
            'merge_output_format': 'mp4',
            'restrictfilenames': True,
            'nopart': True,
            'socket_timeout': 90,
            'retries': 50,
            'fragment_retries': 50,
            'format': 'bestvideo[height<=720]+bestaudio/best[height<=720]',
            'live_from_start': False,
            'logger': _YtdlpLogger(),
            'progress_hooks': [_progress_hook],
        }

        log("Starting yt-dlp recording...")
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([live_url])

        log("Recording finished!")
        return True

    except Exception as e:
        if not is_stopped():
            log("Recording error: " + str(e))
        return False


def _get_live_url(url):
    url = url.strip().rstrip('/')
    import re
    if re.search(r'youtube\.com/(@|channel/|c/|user/)', url):
        if not url.endswith('/live'):
            return url + '/live'
    return url


def _progress_hook(d):
    status = d.get('status', '')
    if status == 'downloading':
        speed = d.get('_speed_str', '').strip()
        eta = d.get('_eta_str', '').strip()
        if speed:
            log("Downloading... " + speed + " ETA: " + eta)
    elif status == 'finished':
        fname = os.path.basename(d.get('filename', ''))
        log("Saved: " + fname)


class _YtdlpLogger:
    def debug(self, msg):
        if not msg.startswith('[debug]'):
            log(msg)
    def info(self, msg):
        log(msg)
    def warning(self, msg):
        log("[WARN] " + msg)
    def error(self, msg):
        log("[ERR] " + msg)
