import os
"""Compare virtual display decorations with the main soft keyboard already active.

Keep size, IME policy, control mode, audio and clipboard configuration fixed.
Only test data is used. Observe startup first; never repair focus inside a case.
"""
import argparse
import base64
import datetime
import json
import pathlib
import re
import shlex
import subprocess
import sys
import time
import uuid

ROOT = pathlib.Path('/Users/tommy/Documents/Codex/2026-10-15/xia')
sys.path.insert(0, str(ROOT / 'outputs/android-foreground-test'))
from driver import Runner, guard_state
from calibrate import calibrate

ADB = str(ROOT / 'work/tools/platform-tools/adb')
SCRCPY = str(ROOT / 'work/tools/scrcpy-macos-aarch64-v5.0.1/scrcpy')
SERIAL = os.environ.get('ADB_SERIAL', '')
KEYS = str(ROOT / 'outputs/android-foreground-test/keyboard.redmi-pad-pro.json')


def shell(args, check=True):
    result = subprocess.run([ADB, '-s', SERIAL, 'shell', shlex.join(args)],
                            capture_output=True, text=True, timeout=15)
    if check and result.returncode:
        raise RuntimeError((result.stdout + result.stderr)[-800:])
    return result.stdout


def read_state(session, after=0):
    raw = shell(['content', 'query', '--uri', 'content://agent.foreground.test.state/state?after=' + str(after)])
    match = re.search(r'payload=([A-Za-z0-9+/=]+)', raw)
    if not match:
        raise RuntimeError('Fixture state missing: ' + raw[-300:])
    state = json.loads(base64.b64decode(match[1]))
    guard_state(state, session, '')
    return state


def snapshot(case, session, after, label):
    state = read_state(session, after)
    row = dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
               monotonic=time.monotonic(), label=label, state=state)
    with (case / 'observations.jsonl').open('a') as file:
        file.write(json.dumps(row, ensure_ascii=False) + '\n')
    return state


def save_system(case, label):
    for name, command in [('windows', ['dumpsys', 'window', 'windows']),
                          ('ime', ['dumpsys', 'input_method']),
                          ('activities', ['dumpsys', 'activity', 'activities']),
                          ('display', ['dumpsys', 'display'])]:
        (case / (label + '-' + name + '.txt')).write_text(shell(command))


def wait_for_main_window(runner, timeout=15):
    """Wait passively for ActivityManager/window focus before calibration taps."""
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = runner.context(text='', require_focus=False)
        bounds = last.get('editorBounds')
        if last.get('windowFocus') and bounds and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
            return last
        time.sleep(.2)
    raise RuntimeError('Main test window did not become ready before calibration: ' +
                       json.dumps({k: last.get(k) for k in ('resumed', 'windowFocus', 'editorFocus', 'ime', 'imeBottom')},
                                  ensure_ascii=False))


def run(args):
    case = pathlib.Path(args.case).resolve()
    case.mkdir(parents=True, exist_ok=False)
    session = uuid.uuid4().hex
    process = None
    runner = None
    result = dict(session=session, decorations=args.decorations, ime_policy=args.ime_policy,
                  status='preparing', forced_client_kill=False)
    try:
        if 'DisplayDeviceInfo{"scrcpy"' in shell(['dumpsys', 'display']):
            raise RuntimeError('A pre-existing test display exists')
        shell(['am', 'force-stop', 'agent.foreground.test'])
        output = shell(['am', 'start', '--display', '0', '-n', 'agent.foreground.test/.TestActivity', '--es', 'session', session])
        if 'Error:' in output:
            raise RuntimeError(output)
        settings = argparse.Namespace(adb=ADB, serial=SERIAL, session=session, keys=KEYS,
                                      run_dir=str(case / 'foreground'), expected_text='')
        runner = Runner(settings)
        ready = wait_for_main_window(runner)
        result['ready_state'] = {k: ready.get(k) for k in ('resumed', 'windowFocus', 'editorFocus', 'ime', 'imeBottom')}
        calibrate(runner)
        before = snapshot(case, session, runner.seq, 'before_creation')
        start_seq = before['seq']
        save_system(case, 'before')
        cmd = [SCRCPY, '--serial=' + SERIAL, '--new-display=720x1280/240',
               '--no-clipboard-autosync', '--no-audio', '--keyboard=sdk', '--mouse=sdk',
               '--window-title=Focus experiment ' + case.name]
        if args.ime_policy != 'default':
            cmd.append('--display-ime-policy=' + args.ime_policy)
        if args.decorations == 'off':
            cmd.append('--no-vd-system-decorations')
        env = dict(__import__('os').environ, ADB=ADB)
        log = (case / 'scrcpy.log').open('w')
        process = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, env=env, start_new_session=True)
        result['client_started_monotonic'] = time.monotonic()
        interference = []
        seq = start_seq
        until = time.monotonic() + 12
        while time.monotonic() < until:
            state = snapshot(case, session, seq, 'creation_observation')
            seq = state['seq']
            for event in state.get('events', []):
                if event['kind'] == 'window_focus' and not event['windowFocus']:
                    interference.append('window_focus_lost')
                if event.get('ime') is False:
                    interference.append('ime_became_hidden')
            if not state['windowFocus']:
                interference.append('window_focus_lost')
            if not state['ime']:
                interference.append('ime_became_hidden')
            time.sleep(.25)
        displays = shell(['dumpsys', 'display'])
        devices = re.findall(r'DisplayDeviceInfo\{"scrcpy": uniqueId="([^"]+)"', displays)
        if len(devices) != 1:
            raise RuntimeError('Expected exactly one new scrcpy display')
        result['display_unique_id'] = devices[0]
        save_system(case, 'active')
        result['startup_interference'] = sorted(set(interference))
        if interference:
            result['status'] = 'startup_interference'
        else:
            runner.type_key('a')
            runner.type_key('backspace')
            result['status'] = 'startup_and_real_keys_passed'
        print(json.dumps({k: v for k, v in result.items() if k != 'session'}, ensure_ascii=False), flush=True)
    except Exception as error:
        result['status'] = 'halted'
        result['reason'] = str(error)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)
                result['forced_client_kill'] = True
            deadline = time.monotonic() + 10
            while 'DisplayDeviceInfo{"scrcpy"' in shell(['dumpsys', 'display']):
                if time.monotonic() >= deadline:
                    result['cleanup_error'] = 'Virtual display not released'
                    break
                time.sleep(.25)
            result['display_released'] = 'DisplayDeviceInfo{"scrcpy"' not in shell(['dumpsys', 'display'])
            log.close()
        if runner is not None:
            # This is outside the measured case and is logged as explicit recovery.
            try:
                state = read_state(session)
                for name in ['hideBounds', 'editorBounds']:
                    state = read_state(session)
                    activities = shell(['dumpsys', 'activity', 'activities'])
                    main = re.search(r'(?ms)^Display #0\b.*?(?=^Display #|\Z)', activities)
                    top = re.search(r'topResumedActivity=(.*)', main[0]) if main else None
                    if not top or 'agent.foreground.test/' not in top[1]:
                        raise RuntimeError('Main fixture left foreground during cleanup')
                    policy = shell(['dumpsys', 'window', 'policy'])
                    if not re.search(r'^\s*mIsShowing=false\s*$', policy, re.M) or not re.search(r'^\s*screenState=SCREEN_STATE_ON\s*$', policy, re.M):
                        raise RuntimeError('Device is locked or screen is off during cleanup')
                    box = state[name]
                    shell(['input', '-d', '0', 'tap', str((box[0] + box[2]) // 2), str((box[1] + box[3]) // 2)])
                    time.sleep(.3)
                recovery = Runner(argparse.Namespace(adb=ADB, serial=SERIAL, session=session, keys=KEYS,
                                  run_dir=str(case / 'recovery'), expected_text=''))
                calibrate(recovery)
                result['keyboard_recovered'] = True
            except Exception as error:
                result['keyboard_recovered'] = False
                result['recovery_error'] = str(error)
        (case / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        print('case_result=' + json.dumps(result, ensure_ascii=False), flush=True)
    return 0 if result['status'] in ('startup_interference', 'startup_and_real_keys_passed') and result.get('display_released') else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--case', required=True)
    parser.add_argument('--decorations', choices=['on', 'off'], required=True)
    parser.add_argument('--ime-policy', choices=['hide', 'local', 'default'], default='hide')
    raise SystemExit(run(parser.parse_args()))
