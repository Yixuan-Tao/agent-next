"""Run the guarded browser/foreground test on an Android 16 focus-safe VD."""
import argparse
import base64
import json
import pathlib
import re
import select
import shlex
import socket
import subprocess
import sys
import time
import uuid

ROOT = pathlib.Path(os.environ.get('AGENT_OLD_ROOT', ''))
WORK = pathlib.Path(os.environ.get('FOCUS_WORK', 'work/focus-20261016'))
sys.path.insert(0, str(WORK))
sys.path.insert(0, str(ROOT / 'outputs/android-foreground-test'))
from experiment import ADB, SERIAL, KEYS, shell, save_system, wait_for_main_window
from parallel_driver import Runner
from calibrate import calibrate

PACKAGE = 'agent.foreground.test'
PROBE_JAR = pathlib.Path(os.environ.get('PROBE_JAR', 'android-input-probe.jar'))
REMOTE_JAR = '/data/local/tmp/codex-android-input-probe.jar'
HELPER_JAR = pathlib.Path(os.environ.get('HELPER_JAR', 'focus-vd.jar'))
REMOTE_HELPER = '/data/local/tmp/codex-focus-vd.jar'
PYTHON = os.environ.get('PYTHON', 'python3')


def free_port():
    with socket.socket() as server_socket:
        server_socket.bind(('127.0.0.1', 0))
        return server_socket.getsockname()[1]


def start_server(case, port):
    receipts = case / 'receipts.jsonl'
    log = (case / 'fixture-server.log').open('w')
    process = subprocess.Popen(
        [PYTHON, str(ROOT / 'outputs/android-foreground-test/fixture_server.py'),
         '--receipts', str(receipts), '--port', str(port)],
        stdout=log, stderr=subprocess.STDOUT,
    )
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Fixture server exited during startup')
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=.2):
                return process, log, receipts
        except OSError:
            time.sleep(.1)
    raise RuntimeError('Fixture server did not become ready')


def new_display():
    dump = shell(['dumpsys', 'display'])
    matches = {(int(match[1]), match[2]) for match in re.finditer(
        r'DisplayInfo\{"scrcpy", displayId (\d+),[^\n]*?uniqueId "([^"]+)"', dump)}
    if len(matches) != 1:
        raise RuntimeError('Expected exactly one new scrcpy display')
    return matches.pop()


def start_focus_guard_display(case):
    if not HELPER_JAR.is_file():
        raise RuntimeError('Focus-safe virtual display helper is missing')
    remote = subprocess.run([ADB, '-s', SERIAL, 'shell', 'md5sum', REMOTE_HELPER],
                            capture_output=True, text=True, timeout=15)
    if remote.returncode == 0 and remote.stdout.strip():
        raise RuntimeError('Focus helper destination already exists; refusing to overwrite it')
    subprocess.run([ADB, '-s', SERIAL, 'push', str(HELPER_JAR), REMOTE_HELPER],
                   capture_output=True, text=True, timeout=30, check=True)
    log = (case / 'focus-vd-helper.log').open('w')
    command = f'CLASSPATH={REMOTE_HELPER} app_process / agent.vd.FocusVirtualDisplay 720 1280 240'
    process = subprocess.Popen([ADB, '-s', SERIAL, 'shell', command], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, bufsize=1, start_new_session=True)
    try:
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('Focus helper exited before creating the display')
            ready, _, _ = select.select([process.stdout], [], [], .25)
            if ready:
                line = process.stdout.readline()
                log.write(line); log.flush()
                if line.startswith('created display='):
                    return process, log, line.strip()
                if line:
                    raise RuntimeError('Focus helper failed: ' + line.strip())
        raise RuntimeError('Focus helper did not report display creation')
    except Exception:
        process.terminate()
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill(); process.wait(timeout=3)
        process.stdout.close(); process.stdin.close(); log.close()
        subprocess.run([ADB, '-s', SERIAL, 'shell', 'rm', '-f', REMOTE_HELPER],
                       capture_output=True, text=True, timeout=15)
        raise


def stop_focus_guard_display(process, log):
    if process.poll() is None:
        try:
            process.stdin.write('stop\n')
            process.stdin.flush()
            process.stdin.close()
            process.wait(timeout=8)
        except (BrokenPipeError, subprocess.TimeoutExpired):
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
    remaining = process.stdout.read() if process.stdout else ''
    if log:
        log.write(remaining); log.close()
    if process.stdout:
        process.stdout.close()
    if process.stdin and not process.stdin.closed:
        process.stdin.close()


def read_current_state(session):
    raw = shell(['content', 'query', '--uri', 'content://agent.foreground.test.state/state'])
    match = re.search(r'payload=([A-Za-z0-9+/=]+)', raw)
    if not match:
        raise RuntimeError('Foreground test state is unavailable')
    state = json.loads(base64.b64decode(match[1]))
    if state.get('package') != PACKAGE or state.get('session') != session or state.get('display') != 0:
        raise RuntimeError('Foreground test identity changed during cleanup')
    return state


def run(case_name, duration):
    case = (WORK / case_name).resolve()
    case.mkdir(parents=True, exist_ok=False)
    result = {'status': 'preparing', 'case': case.name, 'duration': duration}
    server = server_log = helper_log = None
    helper = None
    reverse_port = None
    pushed_jar = False
    pushed_helper = False
    runner = None
    session = None
    try:
        if 'DisplayDeviceInfo{"scrcpy"' in shell(['dumpsys', 'display']):
            raise RuntimeError('A pre-existing scrcpy display exists')
        if not PROBE_JAR.is_file():
            raise RuntimeError('AndroidInputProbe JAR is missing')
        remote = subprocess.run([ADB, '-s', SERIAL, 'shell', 'md5sum', REMOTE_JAR],
                                capture_output=True, text=True, timeout=15)
        if remote.returncode == 0 and remote.stdout.strip():
            raise RuntimeError('Probe destination already exists; refusing to overwrite it')

        reverse_port = free_port()
        server, server_log, receipts = start_server(case, reverse_port)
        subprocess.run([ADB, '-s', SERIAL, 'reverse',
                        f'tcp:{reverse_port}', f'tcp:{reverse_port}'],
                       capture_output=True, text=True, timeout=15, check=True)
        subprocess.run([ADB, '-s', SERIAL, 'push', str(PROBE_JAR), REMOTE_JAR],
                       capture_output=True, text=True, timeout=30, check=True)
        pushed_jar = True

        session = uuid.uuid4().hex
        app_start = shell(['am', 'force-stop', PACKAGE])
        app_start += shell(['am', 'start', '--display', '0', '-n',
                            PACKAGE + '/.TestActivity', '--es', 'session', session])
        if 'Error:' in app_start:
            raise RuntimeError('Could not start the foreground fixture: ' + app_start)

        url = f'http://127.0.0.1:{reverse_port}/round.html'
        args = argparse.Namespace(
            adb=ADB, serial=SERIAL, session=session, keys=KEYS,
            run_dir=str(case / 'parallel'), expected_text='', phase='parallel',
            duration=duration, display=1, unique_id='pending', fixture_url=url,
            receipts=str(receipts),
        )
        runner = Runner(args)
        ready = wait_for_main_window(runner)
        result['ready_state'] = {key: ready.get(key) for key in
                                 ('resumed', 'windowFocus', 'editorFocus', 'ime', 'imeBottom')}
        calibrate(runner)
        before = runner.context(text='')
        result['main_before_display'] = {key: before.get(key) for key in
                                         ('windowFocus', 'editorFocus', 'ime', 'imeBottom', 'rotation')}
        save_system(case, 'before')

        helper, helper_log, helper_message = start_focus_guard_display(case)
        pushed_helper = True
        flags_match = re.search(r'flags=(\d+)', helper_message)
        if not flags_match or not (int(flags_match[1]) & (1 << 16)):
            raise RuntimeError('The helper did not set STEAL_TOP_FOCUS_DISABLED')
        display_id, unique_id = new_display()
        if display_id <= 0 or 'scrcpy' not in unique_id:
            raise RuntimeError('Secondary display identity is invalid')
        runner.args.display = display_id
        runner.args.unique_id = unique_id
        result['display'] = {'id': display_id, 'unique_id': unique_id}
        result['steal_top_focus_disabled'] = True
        active_dump = shell(['dumpsys', 'display'])
        result['flag_visible_in_system_dump'] = 'FLAG_STEAL_TOP_FOCUS_DISABLED' in active_dump
        save_system(case, 'display_ready')

        code = runner.run()
        result['driver_exit_code'] = code
        result['driver'] = json.loads((case / 'parallel/result.json').read_text())
        result['status'] = 'parallel_passed' if code == 0 else 'parallel_stopped'
        if code != 0:
            save_system(case, 'stopped_active')
    except Exception as error:
        result['status'] = 'setup_or_cleanup_error'
        result['reason'] = str(error)
    finally:
        if helper is not None:
            stop_focus_guard_display(helper, helper_log)
            deadline = time.monotonic() + 15
            while 'DisplayDeviceInfo{"scrcpy"' in shell(['dumpsys', 'display']):
                if time.monotonic() >= deadline:
                    result['display_released'] = False
                    break
                time.sleep(.25)
            else:
                result['display_released'] = True
        if pushed_helper:
            try:
                shell(['rm', '-f', REMOTE_HELPER])
                result['focus_helper_removed'] = True
            except Exception as error:
                result['focus_helper_removed'] = False
                result['focus_helper_cleanup_error'] = str(error)
        if reverse_port is not None:
            removed = subprocess.run([ADB, '-s', SERIAL, 'reverse', '--remove',
                                      f'tcp:{reverse_port}'], capture_output=True, text=True, timeout=15)
            result['reverse_removed'] = removed.returncode == 0
        if pushed_jar:
            try:
                shell(['rm', '-f', REMOTE_JAR])
                result['probe_jar_removed'] = True
            except Exception as error:
                result['probe_jar_removed'] = False
                result['probe_cleanup_error'] = str(error)
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=3)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait(timeout=3)
            server_log.close()
        if runner is not None:
            try:
                state = read_current_state(session)
                result['final_foreground'] = {key: state.get(key) for key in
                                               ('resumed', 'windowFocus', 'ime', 'imeBottom', 'text')}
                result['keyboard_recovered'] = bool(state.get('windowFocus') and state.get('ime'))
            except Exception as error:
                result['keyboard_recovered'] = False
                result['final_state_error'] = str(error)
        (case / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print('case_result=' + json.dumps(result, ensure_ascii=False), flush=True)
    return 0 if result.get('status') == 'parallel_passed' and result.get('display_released') else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', default='parallel-focus-guard-a9')
    parser.add_argument('--duration', type=float, default=60)
    options = parser.parse_args()
    raise SystemExit(run(options.case, options.duration))
