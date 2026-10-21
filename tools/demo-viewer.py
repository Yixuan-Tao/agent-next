#!/usr/bin/env python3
"""Read the app's private demo snapshot; never capture or control the device.

Usage: python3 tools/demo-viewer.py --serial HOST:PORT [--adb /path/to/adb]
Requires a debuggable agent.next.app and an already connected ADB device.
Only the fixed app-owned JSON is read. Frames stay in memory until exit.
"""

import argparse
import base64
import binascii
import json
import queue
import shutil
import socketserver
import struct
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import zlib


MAX_PNG_BYTES = 9 * 1024 * 1024
MAX_JSON_BYTES = 9 * 1024 * 1024
MAX_PIXELS = 16_000_000
POLL_SECONDS = 0.8
ADB_TIMEOUT_SECONDS = 4.0
STALE_MS = 5000

MESSAGES = {
    "waiting": "等待应用发布画面",
    "live": "画面正常更新（只读）",
    "stopped": "已结束 / 停止；显示最后画面",
    "stale": "画面已过期；显示最后画面，并非实时",
    "disconnected": "ADB 已断开或设备不可访问；保留最后画面，并非实时",
    "unavailable": "应用尚未发布画面、文件缺失或 run-as 不可用；保留最后画面",
    "invalid": "收到无效或过大的画面数据；保留最后画面",
    "timeout": "ADB 读取超时；保留最后画面，并非实时",
    "clock_skew": "设备与电脑时间不一致，无法确认画面时效；并非实时",
}


class ReadError(Exception):
    def __init__(self, status):
        self.status = status
        super().__init__(MESSAGES[status])


def validate_png(png):
    """Check bounded dimensions/chunks/CRC without decompressing image data."""
    if not 57 <= len(png) <= MAX_PNG_BYTES or not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("invalid PNG")
    offset, chunks, has_data = 8, 0, False
    while offset + 12 <= len(png):
        length = struct.unpack_from(">I", png, offset)[0]
        kind = png[offset + 4:offset + 8]
        end = offset + 12 + length
        if end > len(png) or chunks > 4096:
            raise ValueError("invalid PNG chunks")
        chunk_data = png[offset + 8:end - 4]
        expected = struct.unpack_from(">I", png, end - 4)[0]
        if zlib.crc32(kind + chunk_data) != expected:
            raise ValueError("invalid PNG checksum")
        if chunks == 0:
            if kind != b"IHDR" or length != 13:
                raise ValueError("missing PNG header")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", chunk_data)
            depths = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}
            if (not 0 < width <= 8192 or not 0 < height <= 8192
                    or width * height > MAX_PIXELS or depth not in depths.get(color, ())
                    or compression != 0 or filtering != 0 or interlace not in (0, 1)):
                raise ValueError("unsupported PNG header")
        elif kind == b"IHDR":
            raise ValueError("duplicate PNG header")
        if kind == b"IDAT":
            has_data = True
        if kind == b"IEND":
            if length != 0 or not has_data or end != len(png):
                raise ValueError("invalid PNG end")
            return
        chunks += 1
        offset = end
    raise ValueError("truncated PNG")


def parse_frame(raw):
    if not raw or len(raw) > MAX_JSON_BYTES:
        raise ValueError("frame exceeds JSON limit")
    try:
        data = json.loads(raw)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise ValueError("invalid frame JSON") from error
    if not isinstance(data, dict):
        raise ValueError("frame must be an object")
    for key in ("sequence", "captured_at_ms", "published_at_ms"):
        value = data.get(key)
        if type(value) is not int or not 0 <= value < 2**63:
            raise ValueError("invalid frame number")
    if data["captured_at_ms"] > data["published_at_ms"] or type(data.get("active")) is not bool:
        raise ValueError("invalid frame metadata")
    for key, limit in (("state", 256), ("detail", 4096)):
        if not isinstance(data.get(key), str) or len(data[key]) > limit:
            raise ValueError("invalid frame text")
    encoded = data.get("png")
    if not isinstance(encoded, str) or len(encoded) > 4 * ((MAX_PNG_BYTES + 2) // 3):
        raise ValueError("invalid PNG size")
    try:
        png = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error) as error:
        raise ValueError("invalid base64") from error
    if not (encoded == "" and data["sequence"] == 0 and data["captured_at_ms"] == 0):
        validate_png(png)
    return {key: data[key] for key in ("sequence", "captured_at_ms", "published_at_ms",
                                     "active", "state", "detail", "png")}


def read_process(command, timeout=ADB_TIMEOUT_SECONDS, max_output=MAX_JSON_BYTES):
    """Drain both pipes with bounded buffers; never spool private data to disk."""
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, shell=False)
    except OSError as error:
        raise ReadError("disconnected") from error
    events = queue.Queue(maxsize=4)
    cancelled = threading.Event()

    def drain(name, stream):
        try:
            while not cancelled.is_set():
                chunk = stream.read1(65536)
                while not cancelled.is_set():
                    try:
                        events.put((name, chunk), timeout=0.1)
                        break
                    except queue.Full:
                        pass
                if not chunk:
                    break
        finally:
            stream.close()

    readers = [threading.Thread(target=drain, args=(name, stream), daemon=True)
               for name, stream in (("out", process.stdout), ("err", process.stderr))]
    for reader in readers:
        reader.start()
    buffers = {"out": bytearray(), "err": bytearray()}
    deadline = time.monotonic() + timeout
    finished = 0
    try:
        while finished < 2:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ReadError("timeout")
            try:
                name, chunk = events.get(timeout=remaining)
            except queue.Empty as error:
                raise ReadError("timeout") from error
            if not chunk:
                finished += 1
                continue
            limit = max_output if name == "out" else 8192
            if len(buffers[name]) + len(chunk) > limit:
                raise ReadError("invalid")
            buffers[name].extend(chunk)
        try:
            code = process.wait(timeout=max(0.001, deadline - time.monotonic()))
        except subprocess.TimeoutExpired as error:
            raise ReadError("timeout") from error
        return code, bytes(buffers["out"]), bytes(buffers["err"])
    finally:
        cancelled.set()
        if process.poll() is None:
            process.kill()
        process.wait(timeout=1)
        for reader in readers:
            reader.join(timeout=1)


class AdbReader:
    def __init__(self, adb, serial):
        self.command = [adb, "-s", serial, "exec-out", "run-as", "agent.next.app.debug",
                        "cat", "cache/demo-monitor/latest.json"]

    def read(self):
        code, stdout, stderr = read_process(self.command)
        if code:
            status = "unavailable" if any(token in stderr.lower() for token in
                     (b"no such file", b"run-as:", b"not debuggable", b"permission denied")) else "disconnected"
            raise ReadError(status)
        try:
            return parse_frame(stdout)
        except ValueError as error:
            raise ReadError("invalid") from error


class FrameStore:
    def __init__(self, clock=time.monotonic, wall_ms=lambda: int(time.time() * 1000)):
        self.clock = clock
        self.wall_ms = wall_ms
        self.lock = threading.Lock()
        self.frame = None
        self.observed_at = None
        self.error = "waiting"

    def accept(self, frame):
        if isinstance(frame, bytes):
            frame = parse_frame(frame)
        with self.lock:
            identity = ("sequence", "captured_at_ms", "published_at_ms")
            if self.frame is None or any(self.frame[key] != frame[key] for key in identity):
                self.observed_at = self.clock()
            self.frame = frame
            self.error = None

    def fail(self, status):
        with self.lock:
            self.error = status

    def snapshot(self):
        with self.lock:
            frame = dict(self.frame) if self.frame is not None else None
            age = None if frame is None else max(0, self.wall_ms() - frame["captured_at_ms"],
                                                int((self.clock() - self.observed_at) * 1000))
            stale = age is not None and age > STALE_MS
            if self.error:
                status = self.error
            elif not frame["active"]:
                status = "stopped"
            elif not frame["png"]:
                status = "waiting"
            elif frame["published_at_ms"] > self.wall_ms() + STALE_MS:
                status = "clock_skew"
            else:
                status = "stale" if stale else "live"
            if frame:
                # Java long values must not lose precision in JavaScript numbers.
                frame["sequence"] = str(frame["sequence"])
            return dict(status=status, message=MESSAGES[status], frame=frame,
                        frame_age_ms=age, frame_stale=stale)


HTML = """<!doctype html>
<html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Agent Next · 只读监看</title>
<style>
:root{color-scheme:dark;font:16px system-ui,sans-serif;background:#11151c;color:#edf2fa}
[hidden]{display:none!important}
body{margin:0;padding:24px}main{max-width:1100px;margin:auto}h1{font-size:23px;margin:0 0 8px}
.hint{color:#aebacc;font-size:14px}#status{padding:14px 16px;border:1px solid #bb9545;border-radius:8px;background:#352d1d;margin:20px 0}
#status[data-status="live"]{background:#19362e;border-color:#4aa58a}dl{display:grid;grid-template-columns:100px 1fr;gap:10px;font-size:14px}
dt{color:#aebacc}dd{margin:0;overflow-wrap:anywhere;white-space:pre-wrap}figure{margin:20px 0;text-align:center;background:#080b10;padding:12px;border-radius:8px}
img{display:block;max-width:100%;max-height:75vh;height:auto;margin:auto}#empty{padding:60px 12px;color:#aebacc}figcaption{margin-top:12px;font-size:13px;color:#aebacc}
</style>
<main><h1>Agent Next · 只读监看</h1><p class="hint">只显示应用发布的画面与执行状态 · 关闭本页不会停止 Agent</p>
<div id="status" role="status" aria-live="polite" data-status="waiting">等待应用发布画面</div>
<dl><dt>帧序号</dt><dd id="sequence">—</dd><dt>采集时间</dt><dd id="captured">—</dd>
<dt>画面年龄</dt><dd id="age">—</dd><dt>任务状态</dt><dd id="state">—</dd><dt>状态详情</dt><dd id="detail">—</dd></dl>
<figure><div id="empty">暂无画面</div><img id="frame" alt="应用发布的最后一帧" hidden><figcaption id="caption">暂无画面</figcaption></figure></main>
<script>
const el = id => document.getElementById(id);
let imageKey = '', lastResponseAt = 0, currentStatus = 'waiting', imageFailed = false;
function mark(status, message) {
  currentStatus = status;
  el('status').dataset.status = status;
  el('status').textContent = message;
  el('caption').textContent = status === 'live' ? '最近采集的画面（仅供观察）'
    : status === 'waiting' ? '等待首帧' : '最后可用画面 · 并非实时';
}
async function refresh() {
  const abort = new AbortController();
  const timer = setTimeout(() => abort.abort(), 3000);
  try {
    const response = await fetch('/api/frame', {cache:'no-store', signal:abort.signal});
    if (!response.ok) throw new Error('unavailable');
    const data = await response.json();
    lastResponseAt = Date.now();
    mark(data.status, data.message);
    if (data.frame) {
      const f = data.frame;
      el('sequence').textContent = f.sequence;
      el('captured').textContent = f.png ? new Date(f.captured_at_ms).toLocaleString() + ' (' + f.captured_at_ms + ' ms)' : '尚未采集';
      el('age').textContent = f.png ? (data.frame_age_ms / 1000).toFixed(1) + ' 秒' + (data.frame_stale ? ' · 已过期' : '') : '—';
      el('state').textContent = f.state + (f.active ? '' : ' · 已结束 / 停止');
      el('detail').textContent = f.detail;
      if (!f.png) {
        imageKey = '';
        imageFailed = false;
        el('frame').hidden = true;
        el('empty').hidden = false;
        return;
      }
      const key = [f.sequence, f.captured_at_ms, f.published_at_ms].join(':');
      if (key === imageKey && imageFailed) {
        mark('invalid', '新画面无法解码；保留最后可用画面，并非实时');
      } else if (key !== imageKey) {
        imageKey = key;
        imageFailed = false;
        const candidate = new Image();
        candidate.onload = () => {
          if (imageKey !== key) return;
          el('frame').src = candidate.src;
          el('frame').hidden = false;
          el('empty').hidden = true;
        };
        candidate.onerror = () => {
          if (imageKey !== key) return;
          imageFailed = true;
          mark('invalid', '新画面无法解码；保留最后可用画面，并非实时');
        };
        candidate.src = 'data:image/png;base64,' + f.png;
      }
    }
  } catch (_) {
    mark('disconnected', '本地查看器连接中断；保留最后画面，并非实时');
  } finally {
    clearTimeout(timer);
    setTimeout(refresh, 800);
  }
}
setInterval(() => {
  if (currentStatus === 'live' && Date.now() - lastResponseAt > 3000)
    mark('stale', '本地查看器未更新；保留最后画面，并非实时');
}, 800);
refresh();
</script></html>""".encode("utf-8")


def make_server(store, port):
    class LocalServer(ThreadingHTTPServer):
        def server_bind(self):
            # HTTPServer normally reverse-resolves its own IP; this viewer needs no DNS.
            socketserver.TCPServer.server_bind(self)
            self.server_name, self.server_port = self.server_address

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass  # Request paths/status text can contain private content.

        def respond(self, status, body, content_type):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; "
                             "style-src 'unsafe-inline'; img-src data:; connect-src 'self'; frame-ancestors 'none'")
            self.end_headers()
            if self.command != "HEAD":
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        def do_GET(self):
            if self.headers.get("Host") not in (f"127.0.0.1:{self.server.server_port}", "127.0.0.1"):
                self.respond(403, b"Local host required", "text/plain; charset=utf-8")
                return
            if self.path == "/":
                self.respond(200, HTML, "text/html; charset=utf-8")
            elif self.path == "/api/frame":
                body = json.dumps(store.snapshot(), ensure_ascii=True).encode("utf-8")
                self.respond(200, body, "application/json; charset=utf-8")
            elif self.path == "/favicon.ico":
                self.respond(204, b"", "image/x-icon")
            else:
                self.respond(404, b"Not found", "text/plain; charset=utf-8")

        do_HEAD = do_GET

        def reject(self):
            self.close_connection = True
            self.respond(405, b"Read-only viewer: GET and HEAD only", "text/plain; charset=utf-8")

        do_POST = do_PUT = do_DELETE = do_PATCH = do_CONNECT = do_OPTIONS = do_TRACE = reject

        def send_error(self, code, message=None, explain=None):
            self.close_connection = True
            self.respond(405 if code == 501 else code, b"Request rejected", "text/plain; charset=utf-8")

    return LocalServer(("127.0.0.1", port), Handler)


def poll(reader, store, stop):
    while not stop.is_set():
        start = time.monotonic()
        try:
            store.accept(reader.read())
        except ReadError as error:
            store.fail(error.status)
        stop.wait(max(0.0, POLL_SECONDS - (time.monotonic() - start)))


def main(argv=None):
    parser = argparse.ArgumentParser(description="Agent Next 电脑端只读监看：仅读取应用私有快照。")
    parser.add_argument("--adb", help="adb 可执行文件路径；默认从 PATH 查找")
    parser.add_argument("--serial", required=True, help="已连接设备的 ADB serial，例如 HOST:PORT")
    parser.add_argument("--port", type=int, default=8765, help="本机 HTTP 端口（默认 8765）")
    args = parser.parse_args(argv)
    adb = shutil.which(args.adb or "adb")
    if not adb:
        print("找不到 adb。请安装 Android platform-tools，或使用 --adb /path/to/adb 指定路径。", file=sys.stderr)
        return 2
    if not args.serial.strip() or not 1 <= args.port <= 65535:
        print("请提供有效的 --serial 和 1–65535 范围内的 --port。", file=sys.stderr)
        return 2
    store = FrameStore()
    try:
        server = make_server(store, args.port)
    except OSError:
        print("无法启动本机查看器；请检查端口是否已占用，或使用 --port 指定其他端口。", file=sys.stderr)
        return 2
    stop = threading.Event()
    worker = threading.Thread(target=poll, args=(AdbReader(adb, args.serial), store, stop), daemon=True)
    worker.start()
    print(f"只读监看：http://127.0.0.1:{args.port}/ （Ctrl+C 关闭查看器）")
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
        worker.join(timeout=ADB_TIMEOUT_SECONDS + 3)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
