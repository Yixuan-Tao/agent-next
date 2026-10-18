import re, subprocess, time, sys
ADB='work/android-sdk/platform-tools/adb'
def adb(*args): return subprocess.run([ADB]+list(args),capture_output=True,text=True).stdout
def tap(x,y): adb('shell','input','-d','0','tap',str(x),str(y)); time.sleep(0.8)
def swipe(): adb('shell','input','-d','0','swipe','1700','1400','1700','600','300'); time.sleep(0.6)
def dump():
    adb('shell','rm','-f','/sdcard/s.xml'); adb('shell','uiautomator','dump','/sdcard/s.xml')
    xml=adb('shell','cat','/sdcard/s.xml'); out=[]
    for m in re.finditer(r'<node [^>]*>',xml):
        t=m.group(0); a=lambda k:(re.search(k+r'="([^"]*)"',t) or [None,''])[1]
        b=a('bounds'); mm=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b or '')
        if mm: out.append((a('resource-id'),a('text'),tuple(map(int,mm.groups()))))
    return out
def find(suffix):
    for rid,text,b in dump():
        if rid.endswith(suffix): return b,text
    return None,None
def center(b): return ((b[0]+b[2])//2,(b[1]+b[3])//2)
def wait(suffix,tries=8):
    for _ in range(tries):
        b,t=find(suffix)
        if b: return b,t
        time.sleep(1)
    return None,None
def select_preset(label):
    b,t=wait('preset_picker')
    if not b: return False
    tap(*center(b)); time.sleep(1)
    for rid,text,b in dump():
        if label in text:
            tap(*center(b)); time.sleep(1); return True
    return False
for pkg in ('agent.phone','com.jingdong.app.mall','com.quark.browser'):
    adb('shell','am','force-stop',pkg)
for pid in adb('shell','ps','-A').splitlines():
    if 'agent.phone:agent_shell' in pid:
        parts=pid.split()
        if len(parts)>1: adb('shell','kill','-9',parts[1])
time.sleep(2)
adb('shell','am','start','-n','agent.phone/.ui.MainActivity','--es','target_package','com.jingdong.app.mall')
time.sleep(3)
b,t=wait('connect_button'); print('connect',b)
if not b: sys.exit('no connect')
tap(*center(b)); time.sleep(2)
b,t=wait('create_display_button'); print('create',b)
if b: tap(*center(b)); time.sleep(3)
for _ in range(5):
    b,t=find('bind_target_button')
    if b: break
    swipe()
print('bind',b)
if not b: sys.exit('no bind')
tap(*center(b)); time.sleep(3)
for _ in range(7):
    b,t=find('preset_picker')
    if b: break
    swipe()
print('preset',b)
print('select add',select_preset('京东：加购并重开核验'))
for _ in range(6):
    b,t=find('start_task_button')
    if b: break
    swipe()
print('start',b)
if not b: sys.exit('no start')
tap(*center(b)); print('STARTED')
