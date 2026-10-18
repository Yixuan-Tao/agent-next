import re, subprocess, time, sys
ADB='work/android-sdk/platform-tools/adb'
def adb(*args):
    return subprocess.run([ADB]+list(args),capture_output=True,text=True).stdout
def tap(x,y):
    adb('shell','input','-d','0','tap',str(x),str(y)); time.sleep(0.8)
def swipe_up():
    adb('shell','input','-d','0','swipe','1700','1400','1700','600','300'); time.sleep(0.6)
def swipe_down():
    adb('shell','input','-d','0','swipe','1700','600','1700','1400','300'); time.sleep(0.6)
def dump():
    adb('shell','rm','-f','/sdcard/s.xml')
    adb('shell','uiautomator','dump','/sdcard/s.xml')
    xml=adb('shell','cat','/sdcard/s.xml')
    nodes=[]
    for m in re.finditer(r'<node [^>]*>',xml):
        t=m.group(0); a=lambda k:(re.search(k+r'="([^"]*)"',t) or [None,''])[1]
        b=a('bounds'); mm=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b or '')
        if mm:
            nodes.append((a('resource-id'),a('text'),tuple(map(int,mm.groups()))))
    return nodes
def find_rid(suffix):
    for rid,text,b in dump():
        if rid.endswith(suffix): return b,text
    return None,None
def find_text(sub):
    for rid,text,b in dump():
        if sub in text: return b,text
    return None,None
def center(b): return ((b[0]+b[2])//2,(b[1]+b[3])//2)
def wait_rid(suffix,tries=8):
    for i in range(tries):
        b,t=find_rid(suffix)
        if b: return b,t
        time.sleep(1)
    return None,None

# launch
adb('shell','am','force-stop','agent.phone')
adb('shell','am','force-stop','com.quark.browser')
adb('shell','am','force-stop','com.jingdong.app.mall')
for pid in adb('shell','ps','-A').splitlines():
    if 'agent.phone:agent_shell' in pid:
        parts=pid.split()
        if len(parts)>1: adb('shell','kill','-9',parts[1])
time.sleep(2)
adb('shell','am','start','-n','agent.phone/.ui.MainActivity','--es','target_package','com.quark.browser')
time.sleep(3)
b,t=wait_rid('connect_button')
print('connect',b)
if not b: sys.exit('no connect')
tap(*center(b)); time.sleep(2)
b,t=wait_rid('create_display_button')
print('create',b)
if b: tap(*center(b)); time.sleep(3)
# scroll to bind
for i in range(5):
    b,t=find_rid('bind_target_button')
    if b: break
    swipe_up(); print('scroll',i)
print('bind',b)
if not b: sys.exit('no bind')
tap(*center(b)); time.sleep(3)
# verify target detail
for i in range(4):
    nodes=dump()
    detail=[text for rid,text,b in nodes if rid.endswith('target_detail')]
    print('detail',detail)
    if any('已绑定' in text for text in detail): break
    time.sleep(1)
# scroll to preset
for i in range(5):
    b,t=find_rid('preset_picker')
    if b: break
    swipe_up(); print('scroll preset',i)
print('preset',b)
if not b: sys.exit('no preset')
tap(*center(b)); time.sleep(1)
items=dump()
print('dropdown',[(text,b) for rid,text,b in items if 'Quark' in text or '京东' in text])
target=None
for rid,text,b in items:
    if text.startswith('Quark→京东'):
        target=b; break
if not target:
    sys.exit('no chain item')
tap(*center(target)); time.sleep(1)
# scroll to start
for i in range(6):
    b,t=find_rid('start_task_button')
    if b: break
    swipe_up(); print('scroll start',i)
print('start',b)
if not b: sys.exit('no start')
tap(*center(b)); print('STARTED')
