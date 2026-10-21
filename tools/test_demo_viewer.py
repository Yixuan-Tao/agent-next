"""Standard-library checks: python3 -m unittest discover -s tools -p test_demo_viewer.py."""

import base64
import contextlib
import http.client
import importlib.util
import io
import json
from pathlib import Path
import struct
import sys
import threading
import unittest
from unittest import mock
import zlib


SPEC = importlib.util.spec_from_file_location("demo_viewer", Path(__file__).with_name("demo-viewer.py"))
viewer = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = viewer
SPEC.loader.exec_module(viewer)


def png_chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


PNG = (b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0))
       + png_chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00")) + png_chunk(b"IEND", b""))


def payload(**changes):
    data = dict(sequence=1, captured_at_ms=10000, published_at_ms=10100,
                active=True, state="executing", detail="中文 <script>示例</script>",
                png=base64.b64encode(PNG).decode("ascii"))
    data.update(changes)
    return json.dumps(data, ensure_ascii=False).encode("utf-8")


class ParseTests(unittest.TestCase):
    def test_initial_empty_frame_is_waiting_without_fake_image(self):
        initial = payload(sequence=0, captured_at_ms=0, png="", state="AWAITING_PLAN")
        result = viewer.parse_frame(initial)
        self.assertEqual(result["png"], "")
        store = viewer.FrameStore(wall_ms=lambda: 10200)
        store.accept(initial)
        self.assertEqual(store.snapshot()["status"], "waiting")
        for change in (dict(sequence=1), dict(captured_at_ms=10000)):
            with self.assertRaises(ValueError):
                viewer.parse_frame(payload(sequence=change.get("sequence", 0),
                                           captured_at_ms=change.get("captured_at_ms", 0), png=""))

    def test_valid_frame_preserves_unicode_and_png(self):
        result = viewer.parse_frame(payload(sequence=2**60))
        self.assertEqual(result["sequence"], 2**60)
        self.assertEqual(base64.b64decode(result["png"]), PNG)
        self.assertEqual(result["detail"], "中文 <script>示例</script>")

    def test_rejects_invalid_fields_and_excessive_data(self):
        for change in [dict(sequence=True), dict(sequence=-1), dict(sequence=2**63),
                       dict(captured_at_ms="10000"), dict(published_at_ms=9999),
                       dict(active=1), dict(state=None), dict(detail="x" * 5000),
                       dict(png="data:image/png;base64,AA=="), dict(png="!"),
                       dict(png=base64.b64encode(b"not a PNG").decode())]:
            with self.subTest(change=list(change)), self.assertRaises(ValueError):
                viewer.parse_frame(payload(**change))
        for raw in [b"[]", b"{}", b"null", b"\xff", b"{" * 1100,
                    b" " * (viewer.MAX_JSON_BYTES + 1)]:
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                viewer.parse_frame(raw)

    def test_rejects_truncated_corrupt_or_huge_dimension_png(self):
        huge = b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", struct.pack(">IIBBBBB", 50000, 50000, 8, 2, 0, 0, 0))
        for raw in [PNG[:-2], PNG[:35] + b"bad", PNG.replace(b"IDAT", b"IDAX"), huge]:
            with self.subTest(size=len(raw)), self.assertRaises(ValueError):
                viewer.parse_frame(payload(png=base64.b64encode(raw).decode()))


class MonitorTests(unittest.TestCase):
    def setUp(self):
        self.clock = 20.0
        self.wall = 10200
        self.store = viewer.FrameStore(clock=lambda: self.clock, wall_ms=lambda: self.wall)

    def test_disconnect_keeps_last_frame_but_never_calls_it_live(self):
        self.store.accept(payload())
        self.assertEqual(self.store.snapshot()["status"], "live")
        self.store.fail("disconnected")
        snapshot = self.store.snapshot()
        self.assertEqual(snapshot["status"], "disconnected")
        self.assertEqual(snapshot["frame"]["sequence"], "1")
        self.assertEqual(snapshot["frame"]["png"], base64.b64encode(PNG).decode())
        self.store.accept(payload(sequence=2, captured_at_ms=10100))
        self.assertEqual(self.store.snapshot()["status"], "live")

    def test_missing_or_invalid_frame_keeps_previous_image(self):
        self.store.accept(payload())
        for status in ["unavailable", "invalid", "timeout"]:
            self.store.fail(status)
            self.assertEqual(self.store.snapshot()["status"], status)
            self.assertEqual(self.store.snapshot()["frame"]["sequence"], "1")

    def test_repeat_does_not_refresh_age_and_old_capture_is_stale(self):
        self.store.accept(payload())
        self.clock += 6
        self.store.accept(payload())
        self.assertEqual(self.store.snapshot()["status"], "stale")
        self.wall = 20000
        self.store.accept(payload(sequence=2))
        self.assertEqual(self.store.snapshot()["status"], "stale")

    def test_stopped_and_future_clock_are_never_live(self):
        self.store.accept(payload(active=False))
        self.assertEqual(self.store.snapshot()["status"], "stopped")
        self.store.accept(payload(sequence=2, captured_at_ms=20000, published_at_ms=20100))
        self.assertEqual(self.store.snapshot()["status"], "clock_skew")


class AdbTests(unittest.TestCase):
    def test_only_fixed_read_command_no_shell_interpolation(self):
        serial = "host:5555;echo forbidden"
        with mock.patch.object(viewer, "read_process", return_value=(0, payload(), b"")) as read:
            self.assertEqual(viewer.AdbReader("/path to/adb", serial).read()["sequence"], 1)
        self.assertEqual(read.call_args.args[0], ["/path to/adb", "-s", serial, "exec-out",
                                               "run-as", "agent.next.app.debug", "cat",
                                               "cache/demo-monitor/latest.json"])

    def test_missing_and_disconnected_errors_are_sanitized(self):
        for stderr, expected in [(b"cat: latest.json: No such file or directory", "unavailable"),
                                 (b"error: device offline: PRIVATE USER CONTENT", "disconnected")]:
            with mock.patch.object(viewer, "read_process", return_value=(1, b"", stderr)):
                with self.assertRaises(viewer.ReadError) as caught:
                    viewer.AdbReader("adb", "device").read()
            self.assertEqual(caught.exception.status, expected)
            self.assertNotIn("PRIVATE", str(caught.exception))

    def test_process_timeout_and_output_limit(self):
        with self.assertRaises(viewer.ReadError) as caught:
            viewer.read_process([sys.executable, "-c", "import time; time.sleep(5)"], timeout=0.1)
        self.assertEqual(caught.exception.status, "timeout")
        with self.assertRaises(viewer.ReadError) as caught:
            viewer.read_process([sys.executable, "-c", "import sys; sys.stdout.buffer.write(b'x'*100000)"],
                                max_output=1024)
        self.assertEqual(caught.exception.status, "invalid")

    def test_missing_adb_has_friendly_error(self):
        stderr = io.StringIO()
        with mock.patch.object(viewer.shutil, "which", return_value=None), contextlib.redirect_stderr(stderr):
            self.assertEqual(viewer.main(["--serial", "host:5555"]), 2)
        self.assertIn("--adb", stderr.getvalue())
        self.assertNotIn("Traceback", stderr.getvalue())


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.store = viewer.FrameStore(wall_ms=lambda: 10200)
        self.store.accept(payload(sequence=2**60))
        self.server = viewer.make_server(self.store, 0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        try:
            conn.request(method, path, body=body, headers=headers or {})
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            conn.close()

    def test_local_only_api_is_uncached_and_long_sequence_is_lossless(self):
        self.assertEqual(self.server.server_address[0], "127.0.0.1")
        status, headers, body = self.request("GET", "/api/frame")
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(json.loads(body)["frame"]["sequence"], str(2**60))
        self.assertNotIn("Access-Control-Allow-Origin", headers)

    def test_no_control_methods_or_arbitrary_file_routes(self):
        for method in ["POST", "PUT", "DELETE", "PATCH", "CONNECT", "OPTIONS", "TRACE"]:
            with self.subTest(method=method):
                status, headers, _ = self.request(method, "/api/frame", "ignored")
                self.assertEqual(status, 405)
                self.assertEqual(headers["Cache-Control"], "no-store")
        for path in ["/etc/passwd", "/api/tap", "/api/frame?command=stop", "/../README.md"]:
            self.assertEqual(self.request("GET", path)[0], 404)
        self.assertEqual(self.store.snapshot()["frame"]["sequence"], str(2**60))

    def test_html_is_self_contained_and_uses_text_content(self):
        status, headers, body = self.request("GET", "/")
        self.assertEqual(status, 200)
        self.assertIn("no-store", headers["Cache-Control"])
        self.assertIn(b"textContent", body)
        self.assertNotIn(b"innerHTML", body)
        self.assertNotIn(b"https://", body)
        self.assertNotIn(b"<input", body)

    def test_rejects_foreign_hosts_and_unknown_methods_without_caching(self):
        status, headers, _ = self.request("GET", "/api/frame", headers={"Host": "foreign.example"})
        self.assertEqual(status, 403)
        self.assertEqual(headers["Cache-Control"], "no-store")
        status, headers, _ = self.request("CONTROL", "/api/frame")
        self.assertEqual(status, 405)
        self.assertEqual(headers["Cache-Control"], "no-store")


if __name__ == "__main__":
    unittest.main()
