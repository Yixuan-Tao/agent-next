import os
import argparse,base64,datetime,json,pathlib,re,shlex,subprocess,time
parser=argparse.ArgumentParser();parser.add_argument('phase',choices=['A','B']);phase=parser.parse_args().phase
base=pathlib.Path('work/clipboard-20261016')
adb='/Users/tommy/Documents/Codex/2026-10-15/xia/work/tools/platform-tools/adb'
serial=os.environ.get('ADB_SERIAL','');display=os.environ.get('DISPLAY_ID','');identity=os.environ.get('DISPLAY_IDENTITY','')
def record(kind,**data):
    row={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'phase':phase,'kind':kind,**data}
    with (base/'events.jsonl').open('a') as f:f.write(json.dumps(row,ensure_ascii=False)+'\n')
    print(json.dumps(row,ensure_ascii=False),flush=True)
def shell(args):
    result=subprocess.run([adb,'-s',serial,'shell',shlex.join(args)],capture_output=True,text=True,timeout=20)
    if result.returncode:raise RuntimeError(result.stdout[-800:]+result.stderr[-800:])
    return result.stdout
def guard(require_browser=True):
    policy=shell(['dumpsys','window','policy'])
    if not re.search(r'^\s*screenState=SCREEN_STATE_ON\s*$',policy,re.M):raise RuntimeError('Screen is not on')
    if not re.search(r'^\s*mIsShowing=false\s*$',policy,re.M):raise RuntimeError('Lock state is not verified unlocked')
    activities=shell(['dumpsys','activity','activities'])
    parts=re.split(r'(?m)^Display #(\d+)[^\n]*\n',activities);selected={}
    for i in range(1,len(parts),2):
        if parts[i] not in ('0',display):continue
        match=re.search(r'topResumedActivity=(ActivityRecord\{[^\n]+)',parts[i+1])
        selected[parts[i]]=match.group(1) if match else None
    if selected.get('0') is None:raise RuntimeError('Main activity is missing')
    if require_browser and 'com.android.browser/' not in (selected.get(display) or ''):raise RuntimeError('Target browser missing')
    if selected.get('0')==selected.get(display):raise RuntimeError('Target activity appeared on main display')
    return selected
def probe(cmd,payload=None):
    args=['CLASSPATH=/data/local/tmp/codex-android-input-probe.jar','app_process','/','agent.probe.AndroidInputProbe',display,identity,cmd]
    if payload is not None:args.append(payload)
    out=shell(args)
    if 'status=ok' not in out:raise RuntimeError('Probe did not verify success')
    return dict(line.split('=',1) for line in out.splitlines() if '=' in line)
def wait_fixture():
    deadline=time.monotonic()+5
    while True:
        try:return probe('inspect')
        except RuntimeError as error:
            if 'Not the browser test fixture' not in str(error) or time.monotonic()>=deadline:raise
            guard();time.sleep(.15)
try:
    before=guard(False)
    if probe('ime-get')['ime_policy']!='2':raise RuntimeError('Unexpected IME policy')
    if phase=='A':
        shell(['am','start','--display',display,'-a','android.intent.action.VIEW','-d','http://127.0.0.1:38767/?phase='+phase,'-p','com.android.browser'])
    wait_fixture()
    record('batch_started',displays=before)
    cases=['SECONDARY_'+phase+'_ASCII_1016','独立画面 '+phase+' 中文文本','SECONDARY '+phase+' 中英混合 🙂','独立画面 '+phase+' 第一行\n第二行 🙂','http://example.com/CLIPBOARD_TEST_'+phase+'?n=5']
    for index,text in enumerate(cases,1):
        for action,value in [('set',text),('clear','')]:
            current=guard()
            encoded=base64.b64encode(value.encode()).decode()
            result=probe('set-text',encoded)
            if result.get('verified_text_base64')!=encoded:raise RuntimeError('Exact text verification failed')
            record('text_verified',index=index,action=action,exact=True)
    record('batch_completed',operations=10,displays=guard())
except Exception as error:
    record('halt',reason=str(error));raise SystemExit(1)
