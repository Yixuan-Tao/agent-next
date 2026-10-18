import unittest
import threading
import tempfile
import pathlib
import argparse
import json

from driver import (guard_state, verify_transition, guard_clicks,
                    check_sequence, check_text_events, stop_worker, Runner)
from driver import guard_windows


class GuardsTest(unittest.TestCase):
    def setUp(self):
        self.state = dict(package='agent.foreground.test', session='a' * 32,
                          display=0, resumed=True, text='abc', clicks=2,
                          heartbeat=10000, now=10100, pid=42, revision=3)

    def test_accepts_fresh_expected_fixture(self):
        guard_state(self.state, 'a' * 32, 'abc', pid=42)

    def test_rejects_wrong_context_and_stale_or_changed_state(self):
        for changes in [dict(package='other.app'), dict(display=1),
                        dict(session='b' * 32), dict(resumed=False),
                        dict(heartbeat=8000), dict(text='abcAGENT'), dict(pid=43)]:
            with self.subTest(changes=changes), self.assertRaises(RuntimeError):
                guard_state(dict(self.state, **changes), 'a' * 32, 'abc', pid=42)

    def test_rejects_omitted_heartbeat_and_negative_age(self):
        for changes in [dict(heartbeat=None), dict(now=9999)]:
            with self.subTest(changes=changes), self.assertRaises(RuntimeError):
                guard_state(dict(self.state, **changes), 'a' * 32, 'abc')

    def test_detects_inserted_text_and_extra_clicks(self):
        with self.assertRaises(RuntimeError):
            verify_transition(dict(self.state, text='abcAGENT'), text='abc')
        with self.assertRaises(RuntimeError):
            verify_transition(dict(self.state, clicks=4), text='abc', clicks=3)

    def test_exact_empty_and_multiline_text(self):
        for text in ['', '中文\n🙂']:
            verify_transition(dict(self.state, text=text), text=text)

    def test_unrequested_or_extra_click_is_not_accepted_as_new_baseline(self):
        for actual, expected, pending in [(3, 2, False), (4, 2, True), (1, 2, True)]:
            with self.subTest(actual=actual), self.assertRaises(RuntimeError):
                guard_clicks(actual, expected, pending)
        guard_clicks(2, 2, True)
        guard_clicks(3, 2, True)

    def test_rejects_visible_foreign_touch_windows_on_selected_display(self):
        own = '  Window #1 Window{a u0 agent.foreground.test/x}:\n    mDisplayId=0\n    mAttrs={pkg = agent.foreground.test ty=BASE_APPLICATION}\n    isOnScreen=true\n'
        foreign = '  Window #2 Window{b u0 other.app/x}:\n    mDisplayId=0\n    mAttrs={pkg = other.app ty=APPLICATION_OVERLAY}\n    isOnScreen=true\n'
        guard_windows(own, 0, 'agent.foreground.test')
        with self.assertRaises(RuntimeError):
            guard_windows(own + foreign, 0, 'agent.foreground.test')
        guard_windows(own + foreign.replace('mDisplayId=0', 'mDisplayId=11'), 0, 'agent.foreground.test')
        guard_windows(own + foreign.replace('isOnScreen=true', 'isOnScreen=false'), 0, 'agent.foreground.test')

    def test_detects_event_gap_and_sequence_reset(self):
        for previous, snapshot, events in [(100, 2, []), (100, 102, [{'seq': 102}]), (100, 101, [])]:
            with self.subTest(snapshot=snapshot), self.assertRaises(RuntimeError):
                check_sequence(previous, snapshot, events)
        self.assertEqual(check_sequence(100, 102, [{'seq': 101}, {'seq': 102}]), 102)

    def test_detects_temporary_text_corruption_and_reversion(self):
        with self.assertRaises(RuntimeError):
            check_text_events([dict(kind='text', text='abcAGENT'), dict(kind='text', text='abc')], 'abc', None, False)
        with self.assertRaises(RuntimeError):
            check_text_events([dict(kind='text', text='abca'), dict(kind='text', text='abc')], 'abc', 'abca', False)
        self.assertTrue(check_text_events([dict(kind='text', text='abca')], 'abc', 'abca', False))

    def test_late_worker_failure_prevents_completion(self):
        stop, errors = threading.Event(), []
        def worker():
            stop.wait(); errors.append('late device failure')
        thread = threading.Thread(target=worker); thread.start()
        with self.assertRaisesRegex(RuntimeError, 'late device failure'):
            stop_worker(thread, stop, lambda: errors[0] if errors else None)

    def test_live_worker_prevents_completion(self):
        stop, release = threading.Event(), threading.Event()
        thread = threading.Thread(target=release.wait); thread.start()
        try:
            with self.assertRaisesRegex(RuntimeError, 'did not stop'):
                stop_worker(thread, stop, lambda: None, timeout=.01)
        finally:
            release.set(); thread.join()

    def test_final_observation_after_worker_stop_prevents_false_completion(self):
        class LateCorruptionRunner(Runner):
            def context(self, **kwargs):
                if self.stop.is_set():
                    raise RuntimeError('late foreground corruption')
                return dict(pid=42, clicks=0)
            def open_keyboard(self):
                pass
        with tempfile.TemporaryDirectory() as directory:
            base = pathlib.Path(directory)
            (base / 'keys.json').write_text('{}')
            args = argparse.Namespace(run_dir=str(base / 'run'), expected_text='',
                keys=str(base / 'keys.json'), session='a' * 32, phase='baseline', duration=0)
            runner = LateCorruptionRunner(args)
            self.assertEqual(runner.run(), 1)
            result = json.loads((base / 'run/result.json').read_text())
            self.assertEqual(result['status'], 'halted')
            self.assertIn('late foreground corruption', result['reason'])


if __name__ == '__main__':
    unittest.main()
