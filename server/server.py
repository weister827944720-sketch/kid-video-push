from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse
import datetime
import json
import re


DATA_FILE = Path(__file__).with_name("videos.json")
PORT = 3000


class Handler(BaseHTTPRequestHandler):
    def do_OPTIONS(self):
        self.send_response(204)
        self.send_cors_headers()
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/api/videos":
            self.send_json(200, read_videos())
            return
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path != "/api/videos":
            self.send_json(404, {"error": "not found"})
            return

        body = self.read_json_body()
        share_url = str(body.get("shareUrl", "")).strip()
        title = str(body.get("title", "视频分享链接")).strip()

        if not re.match(r"^https?://", share_url, re.I):
            self.send_json(400, {"error": "shareUrl must be an http(s) url"})
            return

        videos = read_videos()
        for item in videos:
            if item.get("shareUrl") == share_url:
                self.send_json(200, item)
                return

        now = datetime.datetime.utcnow().replace(microsecond=0).isoformat() + "Z"
        item = {
            "id": re.sub(r"\D", "", now),
            "source": detect_source(share_url),
            "title": title,
            "shareUrl": share_url,
            "status": "link-only",
            "videoUrl": None,
            "createdAt": now,
        }
        videos.insert(0, item)
        write_videos(videos)
        self.send_json(201, item)

    def do_DELETE(self):
        parsed = urlparse(self.path)
        prefix = "/api/videos/"
        if not parsed.path.startswith(prefix):
            self.send_json(404, {"error": "not found"})
            return

        video_id = unquote(parsed.path[len(prefix):])
        videos = read_videos()
        next_videos = [item for item in videos if item.get("id") != video_id]
        write_videos(next_videos)
        self.send_json(200, {"deleted": len(videos) - len(next_videos)})

    def read_json_body(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length == 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def send_json(self, status, value):
        payload = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_cors_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")


def read_videos():
    if not DATA_FILE.exists():
        return []
    return json.loads(DATA_FILE.read_text("utf-8"))


def write_videos(videos):
    DATA_FILE.write_text(json.dumps(videos, ensure_ascii=False, indent=2) + "\n", "utf-8")


def detect_source(url):
    value = str(url or "").lower()
    if "douyin.com" in value:
        return "douyin"
    if "bilibili.com" in value or "b23.tv" in value:
        return "bilibili"
    return "unknown"


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"Kid video push server listening on http://0.0.0.0:{PORT}")
    server.serve_forever()
