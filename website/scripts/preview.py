"""Local preview of dist/ that behaves like Cloudflare Pages for this site.

- binds 127.0.0.1 only (port 8789, or the first argument)
- applies the rules of dist/_headers (CSP, Content-Type of /agent.md and /install.sh, cache)
- serves /path/ as /path/index.html and unknown paths as 404.html with status 404

Run after `pnpm build`:  python3 scripts/preview.py
"""
import fnmatch
import functools
import http.server
import mimetypes
import socketserver
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "dist"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8789

mimetypes.add_type("image/webp", ".webp")
mimetypes.add_type("image/svg+xml", ".svg")
mimetypes.add_type("text/javascript", ".js")
mimetypes.add_type("text/css", ".css")
mimetypes.add_type("video/mp4", ".mp4")


def load_rules():
    rules, current = [], None
    for line in (ROOT / "_headers").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        if not line[0].isspace():
            current = (line.strip(), [])
            rules.append(current)
        elif current and ":" in line:
            name, value = line.strip().split(":", 1)
            current[1].append((name.strip(), value.strip()))
    return rules


class Handler(http.server.SimpleHTTPRequestHandler):
    rules = load_rules()

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def _headers_for(self, path):
        out = {}
        for pattern, headers in self.rules:
            if fnmatch.fnmatch(path, pattern):
                for name, value in headers:
                    out[name] = value
        return out

    def send_head(self):
        url_path = self.path.split("?", 1)[0]
        fs_path = Path(self.translate_path(url_path))
        if fs_path.is_dir():
            fs_path = fs_path / "index.html"
            if not url_path.endswith("/"):
                self.send_response(301)
                self.send_header("Location", url_path + "/")
                self.end_headers()
                return None
        status = 200
        if not fs_path.exists():
            status, fs_path = 404, ROOT / "404.html"
        served = "/" + str(fs_path.relative_to(ROOT))
        extra = self._headers_for(served)
        ctype = extra.pop("Content-Type", None) or mimetypes.guess_type(str(fs_path))[0] or "application/octet-stream"
        if ctype.startswith("text/") and "charset" not in ctype:
            ctype += "; charset=utf-8"
        data = fs_path.read_bytes()
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        for name, value in extra.items():
            self.send_header(name, value)
        self.end_headers()
        import io
        return io.BytesIO(data)

    def log_message(self, fmt, *args):
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    with Server(("127.0.0.1", PORT), functools.partial(Handler)) as httpd:
        print(f"preview: http://127.0.0.1:{PORT}/  (root={ROOT})", flush=True)
        httpd.serve_forever()
