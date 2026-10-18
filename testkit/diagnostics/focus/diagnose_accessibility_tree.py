import os
"""Run one bounded accessibility-tree diagnostic on the synthetic browser page."""
import base64
import json
import pathlib
import re
import shlex
import socket
import subprocess
import sys
import time
import uuid

ROOT = pathlib.Path(os.environ.get('AGENT_OLD_ROOT', ''))
WORK = pathlib.Path(os.environ.get('FOCUS_WORK', 'work/focus-20261016'))
PYTHON = os.environ.get('PYTHON', 'python3')
ADB = str(ROOT / 'work/tools/platform-tools/adb')
SERIAL = os.environ.get('ADB_SERIAL', '')
REMOTE_JAR = '/data/local/tmp/codex-accessibility-tree-diagnostic.jar'
LOCAL_JAR = WORK / 'build-tree-diagnostic/accessibility-diagnostic.jar'
PACKAGE = 'agent.foreground.test'

sys.path.insert(0, str(WORK))
sys.path.insert(0, str(ROOT / 'outputs/android-foreground-test'))
import parallel_case as case_tools
from experiment import KEYS, shell, wait_for_main_window
from driver import Runner
from calibrate import calibrate


def free_port():
    with socket.socket() as server:
        server.bind(('127.0.0.1', 0))
        return server.getsockname()[1]


def run():
    output_dir = WORK / 'probe-repeatability-a16'
    output_dir.mkdir(parents=True, exist_ok=False)
    if not LOCAL_JAR.is_file():
        raise RuntimeError('Diagnostic jar is missing')
    if 'DisplayDeviceInfo{"scrcpy"' in shell(['dumpsys', 'display']):
        raise RuntimeError('A pre-existing test display exists; refusing to continue')
    remote_check = subprocess.run([ADB, '-s', SERIAL, 'shell', 'md5sum', REMOTE_JAR],
                                  capture_output=True, text=True, timeout=15)
    if remote_check.returncode == 0 and remote_check.stdout.strip():
        raise RuntimeError('Diagnostic jar destination already exists; refusing to overwrite it')

    port = free_port()
    receipts = output_dir / 'receipts.jsonl'
    server_log = (output_dir / 'fixture-server.log').open('w')
    server = subprocess.Popen(
        [PYTHON, str(ROOT / 'outputs/android-foreground-test/fixture_server.py'),
         '--receipts', str(receipts), '--port', str(port)],
        stdout=server_log, stderr=subprocess.STDOUT)
    helper = helper_log = runner = None
    reverse = pushed = probe_pushed = helper_pushed = False
    result = {'status': 'preparing'}
    session = uuid.uuid4().hex
    try:
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=.2):
                    break
            except OSError:
                time.sleep(.1)
        else:
            raise RuntimeError('Synthetic fixture server did not start')

        subprocess.run([ADB, '-s', SERIAL, 'reverse', f'tcp:{port}', f'tcp:{port}'],
                       capture_output=True, text=True, timeout=15, check=True)
        reverse = True
        subprocess.run([ADB, '-s', SERIAL, 'push', str(LOCAL_JAR), REMOTE_JAR],
                       capture_output=True, text=True, timeout=30, check=True)
        pushed = True
        remote_probe_check = subprocess.run([ADB, '-s', SERIAL, 'shell', 'md5sum', case_tools.REMOTE_JAR],
                                            capture_output=True, text=True, timeout=15)
        if remote_probe_check.returncode == 0 and remote_probe_check.stdout.strip():
            raise RuntimeError('Probe jar destination already exists; refusing to overwrite it')
        subprocess.run([ADB, '-s', SERIAL, 'push', str(case_tools.PROBE_JAR), case_tools.REMOTE_JAR],
                       capture_output=True, text=True, timeout=30, check=True)
        probe_pushed = True

        start_output = shell(['am', 'force-stop', PACKAGE])
        start_output += shell(['am', 'start', '--display', '0', '-n', PACKAGE + '/.TestActivity',
                               '--es', 'session', session])
        if 'Error:' in start_output:
            raise RuntimeError('Could not start synthetic foreground fixture')
        args = type('Args', (), {})()
        args.adb, args.serial, args.session = ADB, SERIAL, session
        args.keys, args.run_dir, args.expected_text = str(KEYS), str(output_dir / 'runner'), ''
        args.phase, args.duration = 'parallel', 1
        args.display, args.unique_id = 1, 'pending'
        args.fixture_url = f'http://127.0.0.1:{port}/round.html'
        args.receipts = str(receipts)
        runner = Runner(args)
        ready = wait_for_main_window(runner)
        result['main_ready'] = {k: ready.get(k) for k in ('resumed', 'windowFocus', 'editorFocus', 'ime', 'imeBottom')}
        calibrate(runner)
        result['main_before_display'] = {k: runner.context(text='').get(k) for k in ('windowFocus', 'ime', 'imeBottom')}

        helper, helper_log, message = case_tools.start_focus_guard_display(output_dir)
        helper_pushed = True
        match = re.search(r'flags=(\d+)', message)
        if not match or not (int(match[1]) & (1 << 16)):
            raise RuntimeError('Focus-safe flag was not set')
        display_match = re.search(r'display=(\d+)', message)
        display_id = int(display_match[1]) if display_match else case_tools.new_display()[0]
        display_id, unique_id = case_tools.new_display()
        args.display, args.unique_id = display_id, unique_id
        diagnostics = []
        for number in range(1, 20):
            url = args.fixture_url + '?run=' + session + '&round=' + str(number)
            launch = shell(['am', 'start', '--display', str(display_id), '-a',
                            'android.intent.action.VIEW', '-d', url, '-p', 'com.android.browser'])
            if 'Error:' in launch:
                raise RuntimeError('Browser navigation did not start')
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                if receipts.exists():
                    ready_rows = [json.loads(line) for line in receipts.read_text().splitlines() if line]
                    if any(row.get('run') == session and row.get('round') == str(number)
                           and row.get('event') == 'ready' and row.get('path') == '/round.html'
                           for row in ready_rows):
                        break
                time.sleep(.1)
            else:
                raise RuntimeError(f'Synthetic page round {number} did not report ready')
            state = runner.context(text='', require_focus=True)
            if not state.get('ime') or state.get('imeBottom') != 43:
                raise RuntimeError(f'Main keyboard changed during browser navigation round {number}')
            if number in (18, 19):
                command = f'CLASSPATH={REMOTE_JAR} app_process / agent.diagnostics.AccessibilityTreeDiagnostic {display_id}'
                diagnostic = subprocess.run([ADB, '-s', SERIAL, 'shell', command],
                                            capture_output=True, text=True, timeout=30)
                diagnostics.append({'round': number, 'exit_code': diagnostic.returncode,
                                    'stdout': diagnostic.stdout, 'stderr': diagnostic.stderr})
        result['tree_diagnostics'] = diagnostics
        probe_command = (f'CLASSPATH={case_tools.REMOTE_JAR} app_process / agent.probe.AndroidInputProbe '
                         f'{display_id} {unique_id} inspect')
        successful_probes = 0
        for attempt in range(1, 151):
            probe_result = subprocess.run([ADB, '-s', SERIAL, 'shell', probe_command],
                                          capture_output=True, text=True, timeout=20)
            if probe_result.returncode != 0 or 'status=ok' not in probe_result.stdout:
                result['probe_failure'] = {'attempt': attempt, 'exit_code': probe_result.returncode,
                                           'stdout': probe_result.stdout, 'stderr': probe_result.stderr}
                break
            successful_probes += 1
        result['successful_probe_count'] = successful_probes
        result['probe_attempt_limit'] = 150
        command = f'CLASSPATH={REMOTE_JAR} app_process / agent.diagnostics.AccessibilityTreeDiagnostic {display_id}'
        final_tree = subprocess.run([ADB, '-s', SERIAL, 'shell', command],
                                    capture_output=True, text=True, timeout=30)
        result['tree_after_probe_loop'] = {'exit_code': final_tree.returncode,
                                           'stdout': final_tree.stdout, 'stderr': final_tree.stderr}
        result['display_id'] = display_id
        result['unique_id'] = unique_id
        result['status'] = 'probe_loop_completed' if successful_probes == 150 and final_tree.returncode == 0 else 'probe_loop_failed'
        (output_dir / 'diagnostic.txt').write_text('\n'.join(
            f"round={item['round']}\n{item['stdout']}\n{item['stderr']}" for item in diagnostics)
            + '\npost-loop tree\n' + final_tree.stdout + '\n' + final_tree.stderr)
        result['receipts'] = receipts.read_text() if receipts.exists() else ''
        state_after_diagnostic = runner.context(text='', require_focus=True)
        result['main_window_focus_still_present'] = bool(state_after_diagnostic.get('windowFocus'))
        state_raw = shell(['content', 'query', '--uri', 'content://agent.foreground.test.state/state'])
        payload = re.search(r'payload=([A-Za-z0-9+/=]+)', state_raw)
        if payload:
            state = json.loads(base64.b64decode(payload[1]))
            result['main_state'] = {k: state.get(k) for k in ('resumed', 'windowFocus', 'ime', 'imeBottom', 'text')}
    finally:
        if helper is not None:
            case_tools.stop_focus_guard_display(helper, helper_log)
            deadline = time.monotonic() + 15
            while 'DisplayDeviceInfo{"scrcpy"' in shell(['dumpsys', 'display']):
                if time.monotonic() >= deadline:
                    result['display_released'] = False
                    break
                time.sleep(.25)
            else:
                result['display_released'] = True
        if helper_pushed:
            try:
                shell(['rm', '-f', case_tools.REMOTE_HELPER])
                result['focus_helper_removed'] = True
            except Exception as error:
                result['focus_helper_removed'] = False
                result['focus_helper_cleanup_error'] = str(error)
        if pushed:
            try:
                shell(['rm', '-f', REMOTE_JAR])
                result['diagnostic_jar_removed'] = True
            except Exception as error:
                result['diagnostic_jar_removed'] = False
                result['diagnostic_jar_cleanup_error'] = str(error)
        if probe_pushed:
            try:
                shell(['rm', '-f', case_tools.REMOTE_JAR])
                result['probe_jar_removed'] = True
            except Exception as error:
                result['probe_jar_removed'] = False
                result['probe_jar_cleanup_error'] = str(error)
        if reverse:
            removed = subprocess.run([ADB, '-s', SERIAL, 'reverse', '--remove', f'tcp:{port}'],
                                      capture_output=True, text=True, timeout=15)
            result['reverse_removed'] = removed.returncode == 0
        server.terminate()
        try:
            server.wait(timeout=3)
        except subprocess.TimeoutExpired:
            server.kill(); server.wait(timeout=3)
        server_log.close()
        if runner is not None:
            try:
                state = runner.context(text='', require_focus=False)
                box = state['editorBounds']
                shell(['input', '-d', '0', 'tap', str((box[0] + box[2]) // 2), str((box[1] + box[3]) // 2)])
                runner.wait_state(lambda row: row.get('ime') and row.get('editorFocus'), timeout=4)
                result['keyboard_restored'] = True
            except Exception as error:
                result['keyboard_restored'] = False
                result['keyboard_restore_error'] = str(error)
        (output_dir / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print('case_result=' + json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    run()
