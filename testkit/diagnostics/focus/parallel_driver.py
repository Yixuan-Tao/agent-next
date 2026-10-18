"""Test-only foreground driver. The production secondary probe remains unchanged."""

import argparse
import base64
import datetime
import json
import pathlib
import re
import shlex
import subprocess
import threading
import time

PACKAGE = 'agent.foreground.test'


class Cancelled(RuntimeError):
    pass


def guard_state(state, session, expected_text, pid=None):
    for key, value in [('package', PACKAGE), ('display', 0), ('session', session),
                       ('resumed', True), ('text', expected_text)]:
        if state.get(key) != value:
            raise RuntimeError(f'Foreground {key} changed: {state.get(key)!r}')
    if pid is not None and state.get('pid') != pid:
        raise RuntimeError('Foreground process changed')
    heartbeat, now = state.get('heartbeat'), state.get('now')
    if not isinstance(heartbeat, (int, float)) or not isinstance(now, (int, float)):
        raise RuntimeError('Missing foreground heartbeat')
    if not 0 <= now - heartbeat <= 1200:
        raise RuntimeError('Foreground UI heartbeat is stale')


def verify_transition(state, *, text, clicks=None):
    if state.get('text') != text:
        raise RuntimeError(f'Exact text mismatch: {state.get("text")!r} != {text!r}')
    if clicks is not None and state.get('clicks') != clicks:
        raise RuntimeError('Click count mismatch')


def guard_clicks(actual, expected, pending=False):
    allowed = (expected, expected + 1) if pending else (expected,)
    if actual not in allowed:
        raise RuntimeError('Unrequested or extra foreground button click')


def check_sequence(previous, snapshot, events):
    for event in events:
        if previous is not None and event['seq'] != previous + 1:
            raise RuntimeError('Foreground event sequence has a gap or reset')
        previous = event['seq']
    if previous is not None and previous != snapshot:
        raise RuntimeError('Foreground event sequence has a gap or reset')
    return snapshot


def check_text_events(events, expected, pending, pending_seen):
    for event in events:
        if event['kind'] != 'text':
            continue
        text = event['text']
        if pending is not None and text == pending:
            pending_seen = True
        elif text != expected or pending_seen:
            raise RuntimeError('Unexpected foreground text event: ' + repr(text))
    return pending_seen


def stop_worker(worker, stop, failure, timeout=20):
    stop.set()
    if worker is not None:
        worker.join(timeout=timeout)
        if worker.is_alive():
            raise RuntimeError('Secondary worker did not stop')
    if failure():
        raise RuntimeError(failure())


def guard_windows(dump, display, package):
    found = False
    for block in re.split(r'(?m)^  Window #', dump)[1:]:
        if not re.search(r'\bmDisplayId=' + str(display) + r'\b', block):
            continue
        if not re.search(r'\bisOnScreen=true\b', block):
            continue
        owner = re.search(r'\bpkg\s*=\s*(\S+)', block)
        if owner and owner[1] == package:
            found = True
            continue
        # System bar windows are outside the fixture's interior touch coordinates.
        if owner and owner[1] == 'com.android.systemui' and re.search(r'\bty=(?:STATUS_BAR|NAVIGATION_BAR)\b', block):
            continue
        raise RuntimeError('Unexpected visible touch window on test display')
    if not found:
        raise RuntimeError('Expected test window is not visible on selected display')


class Runner:
    def __init__(self, args):
        self.args = args
        self.directory = pathlib.Path(args.run_dir)
        self.directory.mkdir(parents=True, exist_ok=False)
        self.lock = threading.RLock()
        self.stop = threading.Event()
        self.finish_requested = threading.Event()
        self.failure = None
        self.final_read = False
        self.seq = None
        self.pid = None
        self.ime_expected = None
        self.expected_clicks = None
        self.click_pending = False
        self.operations = 0
        self.secondary_rounds = 0
        self.command_number = 0
        self.expected_text = args.expected_text
        self.pending_text = None
        self.pending_seen = False
        self.keys = json.loads(pathlib.Path(args.keys).read_text())

    def record(self, kind, **data):
        row = dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                   monotonic=time.monotonic(), kind=kind, **data)
        with self.lock:
            with (self.directory / 'events.jsonl').open('a') as file:
                file.write(json.dumps(row, ensure_ascii=False) + '\n')
        if kind in ('started', 'progress', 'halt', 'completed', 'secondary_round'):
            print(json.dumps(row, ensure_ascii=False), flush=True)

    def shell(self, args, *, check_stop=True):
        if check_stop and self.stop.is_set() and not (self.final_read and args[0] in ('content', 'dumpsys')):
            raise Cancelled(self.failure or 'Stopped')
        tracked = args[0] in ('input', 'am') or args[0].startswith('CLASSPATH=')
        command_id = None
        if tracked:
            with self.lock:
                self.command_number += 1
                command_id = self.command_number
            self.record('command_started', command_id=command_id, args=args,
                        worker=threading.current_thread().name)
        result = subprocess.run([self.args.adb, '-s', self.args.serial, 'shell', shlex.join(args)],
                                capture_output=True, text=True, timeout=15)
        if tracked:
            self.record('command_finished', command_id=command_id, returncode=result.returncode,
                        worker=threading.current_thread().name)
        if result.returncode:
            raise RuntimeError('Device command failed: ' + (result.stdout + result.stderr)[-700:])
        return result.stdout

    def state(self):
        with self.lock:
            suffix = '' if self.seq is None else '?after=' + str(self.seq)
            raw = self.shell(['content', 'query', '--uri',
                              'content://agent.foreground.test.state/state' + suffix])
            match = re.search(r'payload=([A-Za-z0-9+/=]+)', raw)
            if not match:
                raise RuntimeError('Test state missing: ' + raw[-400:])
            state = json.loads(base64.b64decode(match[1]))
            events = state.pop('events', [])
            next_seq = check_sequence(self.seq, state['seq'], events)
            if self.pid is not None:
                self.pending_seen = check_text_events(events, self.expected_text, self.pending_text, self.pending_seen)
            for event in events:
                self.record('app_event', event=event)
                if self.pid is not None and event['kind'] in ('pause', 'destroy'):
                    raise RuntimeError('Test activity paused or destroyed')
                if self.pid is not None and event['kind'] == 'window_focus' and not event['windowFocus']:
                    raise RuntimeError('Main test window lost focus during the observation interval')
                if self.ime_expected is True and event.get('ime') is False:
                    raise RuntimeError('IME became hidden during continuous typing')
            self.seq = next_seq
            self.record('observation', state=state)
            if self.expected_clicks is not None:
                guard_clicks(state['clicks'], self.expected_clicks, self.click_pending)
            return state

    def context(self, *, text=None, require_focus=True):
        state = self.state()
        guard_state(state, self.args.session, state['text'] if text is None else text, self.pid)
        activities = self.shell(['dumpsys', 'activity', 'activities'])
        main = re.search(r'(?ms)^Display #0\b.*?(?=^Display #|\Z)', activities)
        top = re.search(r'topResumedActivity=(.*)', main[0]) if main else None
        if not top or PACKAGE + '/' not in top[1]:
            raise RuntimeError('Test app is no longer foreground on display 0')
        policy = self.shell(['dumpsys', 'window', 'policy'])
        if not re.search(r'^\s*screenState=SCREEN_STATE_ON\s*$', policy, re.M):
            raise RuntimeError('Screen is not on')
        if not re.search(r'^\s*mIsShowing=false\s*$', policy, re.M):
            raise RuntimeError('Device is locked')
        if state['rotation'] != self.keys['rotation']:
            raise RuntimeError('Display rotation changed; keyboard calibration invalid')
        if self.ime_expected and not state['ime']:
            raise RuntimeError('Expected main-screen IME is hidden')
        if require_focus and not state['windowFocus']:
            raise RuntimeError('Main test window lost focus; refusing further foreground injection')
        return state

    def secondary_window(self):
        activities = self.shell(['dumpsys', 'activity', 'activities'])
        display = self.args.display
        selected = re.search(r'(?ms)^Display #' + str(display) + r'\b.*?(?=^Display #|\Z)', activities)
        top = re.search(r'topResumedActivity=(.*)', selected[0]) if selected else None
        if not top or 'com.android.browser/' not in top[1]:
            raise RuntimeError('Secondary test browser is not the foreground activity')
        guard_windows(self.shell(['dumpsys', 'window', 'windows']), display, 'com.android.browser')

    def secondary_target(self):
        self.secondary_window()
        self.probe('inspect')

    def wait_state(self, predicate, *, timeout=3):
        deadline = time.monotonic() + timeout
        while True:
            state = self.state()
            guard_state(state, self.args.session, state['text'], self.pid)
            if predicate(state):
                return state
            if time.monotonic() >= deadline:
                raise RuntimeError('Expected UI change timed out')
            self.stop.wait(.1)

    def tap(self, xy):
        self.shell(['input', '-d', '0', 'tap', str(xy[0]), str(xy[1])])

    def open_keyboard(self):
        state = self.context(text=self.expected_text)
        box = state['editorBounds']
        self.tap([(box[0] + box[2]) // 2, (box[1] + box[3]) // 2])
        state = self.wait_state(lambda s: s['ime'] and s['editorFocus'])
        if state['imeBottom'] != self.keys['imeBottom']:
            raise RuntimeError('IME geometry changed; recalibration required')
        verify_transition(state, text=self.expected_text)
        self.ime_expected = True
        self.record('keyboard_opened')

    def type_key(self, key):
        self.context(text=self.expected_text)
        before = time.monotonic()
        old = self.expected_text
        expected = old[:-1] if key == 'backspace' else old + ('\n' if key == 'enter' else key)
        with self.lock:
            self.pending_text = expected
            self.pending_seen = False
        self.tap(self.keys['keys'][key])
        # No click on the editor here: losing focus must be exposed by the result.
        state = self.wait_state(lambda s: s['text'] != old)
        verify_transition(state, text=expected)
        with self.lock:
            self.expected_text = expected
            self.pending_text = None
            self.pending_seen = False
        self.operations += 1
        self.record('key_verified', key=key, text=expected, confirmation_seconds=time.monotonic() - before)

    def controls(self):
        state = self.context(text=self.expected_text)
        self.ime_expected = None
        box = state['hideBounds']
        self.tap([(box[0] + box[2]) // 2, (box[1] + box[3]) // 2])
        state = self.wait_state(lambda s: not s['ime'])
        self.record('keyboard_hidden')
        state = self.context(text=self.expected_text)
        clicks = state['clicks']
        box = state['counterBounds']
        self.click_pending = True
        self.tap([(box[0] + box[2]) // 2, (box[1] + box[3]) // 2])
        state = self.wait_state(lambda s: s['clicks'] != clicks)
        verify_transition(state, text=self.expected_text, clicks=clicks + 1)
        self.expected_clicks = clicks + 1
        self.click_pending = False
        self.record('click_verified', clicks=state['clicks'])
        state = self.context(text=self.expected_text)
        x1, y1, x2, y2 = state['scrollBounds']
        if y2 - y1 < 150:
            raise RuntimeError('Scroll fixture is not visible')
        old_y = state['scrollY']
        x = (x1 + x2) // 2
        start, end = (y2 - 80, y1 + 80) if old_y < 100 else (y1 + 80, y2 - 80)
        self.shell(['input', '-d', '0', 'swipe', str(x), str(start), str(x), str(end), '400'])
        state = self.wait_state(lambda s: s['scrollY'] != old_y)
        verify_transition(state, text=self.expected_text)
        if (old_y < 100 and state['scrollY'] <= old_y) or (old_y >= 100 and state['scrollY'] >= old_y):
            raise RuntimeError('Scroll moved in the wrong direction')
        self.record('scroll_verified', before=old_y, after=state['scrollY'])
        self.open_keyboard()
        if self.keys.get('resetLanguageOnReopen', False):
            self.context(text=self.expected_text)
            self.tap(self.keys['keys']['language'])
            self.record('language_switch_requested', target='english', reason='calibrated IME reset on deliberate reopen')

    def probe(self, command, payload=None):
        args = ['CLASSPATH=/data/local/tmp/codex-android-input-probe.jar', 'app_process', '/',
                'agent.probe.AndroidInputProbe', str(self.args.display), self.args.unique_id, command]
        if payload is not None:
            args.append(payload)
        out = self.shell(args)
        if 'status=ok' not in out:
            raise RuntimeError('Secondary probe did not confirm success')
        return dict(line.split('=', 1) for line in out.splitlines() if '=' in line)

    def receipt(self, number, event, after, predicate=lambda row: True):
        deadline = time.monotonic() + 8
        path = pathlib.Path(self.args.receipts)
        while time.monotonic() < deadline:
            if self.stop.is_set():
                raise Cancelled('Stopping secondary receipt wait')
            rows = path.read_text().splitlines() if path.exists() else []
            for line in reversed(rows):
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if (row['run'] == self.args.session and row['round'] == str(number)
                        and row['event'] == event and row['path'] == '/round.html'
                        and row['monotonic'] >= after and predicate(row)):
                    return row
            self.stop.wait(.1)
        raise RuntimeError('Missing browser-generated ' + event + ' receipt')

    def secondary(self):
        try:
            number = 0
            page_round = 1
            while not self.stop.is_set() and not self.finish_requested.is_set():
                number += 1
                self.context()
                if self.probe('ime-get').get('ime_policy') != '2':
                    raise RuntimeError('Secondary IME policy changed')
                if number == 1:
                    before = time.monotonic()
                    out = self.shell(['am', 'start', '--display', str(self.args.display), '-a',
                                      'android.intent.action.VIEW', '-d', self.args.fixture_url + '?run=' + self.args.session + '&round=' + str(page_round),
                                      '-p', 'com.android.browser'])
                    if 'Error:' in out:
                        raise RuntimeError('Secondary navigation rejected')
                    self.receipt(page_round, 'ready', before)
                    self.record('secondary_navigation_verified', round=number)
                else:
                    # Reuse one verified synthetic page throughout the soak to avoid tab churn.
                    self.secondary_target()
                    self.record('secondary_page_reused', round=number)
                for text in [f'SECONDARY_{number}', f'独立画面第{number}轮', '独立 English 🙂', '第一行\n第二行 🙂', '']:
                    self.context()
                    encoded = base64.b64encode(text.encode()).decode()
                    result = self.probe('set-text', encoded)
                    if result.get('verified_text_base64') != encoded:
                        raise RuntimeError('Secondary exact text mismatch')
                    self.record('secondary_text_verified', round=number, text=text)
                for start, end in [(1000, 400), (400, 1100)]:
                    self.probe('ime-get')
                    if start > end:
                        self.secondary_target()
                    else:
                        # The exact test field is intentionally required visible before editing.
                        # After scrolling down it is offscreen, so only verify the browser window
                        # before the reverse swipe that brings the fixture back to the top.
                        self.secondary_window()
                    before = time.monotonic()
                    self.shell(['input', '-d', str(self.args.display), 'swipe', '360', str(start), '360', str(end), '400'])
                    row = self.receipt(page_round, 'scroll', before,
                                       (lambda r: float(r['y']) > 20) if start > end else (lambda r: float(r['y']) <= 1))
                    self.record('secondary_scroll_verified', round=number, y=float(row['y']))
                self.probe('inspect')
                self.secondary_rounds = number
                self.record('secondary_round', round=number)
                self.finish_requested.wait(20)
        except Cancelled:
            pass
        except Exception as error:
            self.failure = 'Secondary: ' + str(error)
            self.stop.set()

    def run(self):
        thread = None
        started = None
        try:
            state = self.context(text=self.expected_text)
            self.pid = state['pid']
            self.expected_clicks = state['clicks']
            self.open_keyboard()
            started = time.monotonic()
            self.record('started', phase=self.args.phase, duration=self.args.duration,
                        session=self.args.session, pid=self.pid)
            if self.args.phase == 'parallel':
                if not self.args.display or self.args.display <= 0 or not self.args.unique_id:
                    raise RuntimeError('Parallel mode requires a secondary display and exact unique ID')
                thread = threading.Thread(target=self.secondary, name='SecondaryWorkload', daemon=True)
                thread.start()
            index, next_controls, next_progress = 0, started + 20, started + 30
            sequence = ['a', 's', 'd', 'f', 'backspace', 'enter']
            while time.monotonic() - started < self.args.duration:
                if self.stop.is_set() or (self.directory / 'stop.txt').exists():
                    raise RuntimeError(self.failure or 'Stop requested')
                self.type_key(sequence[index % len(sequence)])
                index += 1
                now = time.monotonic()
                if now >= next_controls:
                    self.controls(); next_controls = now + 20
                if now >= next_progress:
                    self.record('progress', elapsed_seconds=now - started, keys=self.operations,
                                secondary_rounds=self.secondary_rounds)
                    next_progress = now + 30
                self.stop.wait(.25)
            self.context(text=self.expected_text)
            # Normal end finishes the in-flight round and its receipts. Only faults cancel commands.
            stop_worker(thread, self.finish_requested, lambda: self.failure, timeout=60)
            self.stop.set()
            self.final_read = True
            self.context(text=self.expected_text)
            if self.args.phase == 'parallel' and self.secondary_rounds == 0:
                raise RuntimeError('No complete secondary workload round')
            result = dict(status='completed', elapsed_seconds=time.monotonic() - started,
                          keys=self.operations, secondary_rounds=self.secondary_rounds, final_text=self.expected_text)
            self.record('completed', **result)
        except Exception as error:
            self.failure = self.failure or str(error)
            result = dict(status='halted', reason=self.failure, keys=self.operations,
                          secondary_rounds=self.secondary_rounds,
                          elapsed_seconds=None if started is None else time.monotonic() - started)
            self.record('halt', **result)
        finally:
            self.stop.set()
            self.finish_requested.set()
            if thread:
                thread.join(timeout=20)
            (self.directory / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result['status'] == 'completed' else 1


def main():
    parser = argparse.ArgumentParser()
    for name in ('adb', 'serial', 'session', 'keys', 'run-dir'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--phase', choices=['baseline', 'parallel'], required=True)
    parser.add_argument('--duration', type=float, default=60)
    parser.add_argument('--expected-text', default='')
    parser.add_argument('--display', type=int)
    parser.add_argument('--unique-id')
    parser.add_argument('--fixture-url', default='http://127.0.0.1:38768/round.html')
    parser.add_argument('--receipts')
    args = parser.parse_args()
    if args.duration <= 0:
        parser.error('duration must be positive')
    if args.phase == 'parallel' and (not args.display or args.display <= 0 or not args.unique_id or not args.receipts):
        parser.error('parallel phase requires positive display, exact unique-id and receipts file')
    return Runner(args).run()


if __name__ == '__main__':
    raise SystemExit(main())
