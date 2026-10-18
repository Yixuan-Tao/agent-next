"""Local synthetic page and browser-generated readiness/scroll receipts."""
import argparse
import http.server
import json
import pathlib
import threading
import time
import urllib.parse


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--receipts', required=True)
    parser.add_argument('--port', type=int, default=38768)
    args = parser.parse_args()
    lock = threading.Lock()
    directory = str(pathlib.Path(__file__).parent / 'fixture')
    class Handler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=directory, **kw)
        def log_message(self, *a):
            pass
        def do_GET(self):
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path == '/telemetry':
                query = urllib.parse.parse_qs(parsed.query)
                row = {k: query.get(k, [''])[0] for k in ('run', 'round', 'event', 'y', 'path')}
                row['monotonic'] = time.monotonic()
                with lock, pathlib.Path(args.receipts).open('a') as file:
                    file.write(json.dumps(row) + '\n')
                self.send_response(204); self.end_headers()
            else:
                super().do_GET()
    server = http.server.ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    print('fixture_server_ready', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
