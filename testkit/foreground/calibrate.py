"""One preflight calibration on a fresh synthetic Activity, before measured tests."""
import argparse
import json
from driver import Runner, verify_transition, guard_windows


def main():
    parser = argparse.ArgumentParser()
    for field in ('adb', 'serial', 'session', 'keys', 'run-dir'):
        parser.add_argument('--' + field, required=True)
    args = parser.parse_args()
    args.expected_text = ''
    runner = Runner(args)
    try:
        calibrate(runner)
    except Exception as error:
        result = dict(status='calibration_failed', reason=str(error))
        runner.record('halt', **result)
        (runner.directory / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        return 1
    result = dict(status='calibrated', session=args.session, text='')
    (runner.directory / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result), flush=True)
    return 0


def calibrate(runner):
    state = runner.context(text='', require_focus=False)
    if not state['windowFocus']:
        guard_windows(runner.shell(['dumpsys', 'window', 'windows']), 0, 'agent.foreground.test')
        box = state['editorBounds']
        runner.tap([(box[0] + box[2]) // 2, (box[1] + box[3]) // 2])
        runner.context(text='')
        runner.record('preflight_main_focus_restored')
    runner.pid = state['pid']; runner.expected_clicks = state['clicks']
    runner.open_keyboard()
    runner.context(text='')
    runner.pending_text = 'a'
    runner.tap(runner.keys['keys']['a'])
    state = runner.state()
    if state['text'] == 'a':
        runner.expected_text = 'a'; runner.pending_text = None; runner.pending_seen = False
        runner.record('preflight_mode', mode='english')
    else:
        verify_transition(state, text='')
        runner.pending_text = None
        runner.context(text='')
        runner.tap(runner.keys['keys']['backspace'])
        runner.context(text='')
        runner.tap(runner.keys['keys']['language'])
        runner.type_key('a')
        runner.record('preflight_mode', mode='switched_to_english')
    runner.type_key('backspace')
    runner.context(text='')
    runner.record('calibration_completed', text='')


if __name__ == '__main__':
    raise SystemExit(main())
