import re, subprocess, time, sys
ADB='work/android-sdk/platform-tools/adb'
def adb(*args):
    return subprocess.run([ADB]+list(args),capture_output=True,text=True).stdout
def tap(x,y):
    adb('shell','input','tap',str(x),str(y)); time.sleep(0.8)
def dump():
    adb('shell','rm','-f','/sdcard/s.xml')
    adb('shell','uiautomator','dump','/sdcard/s.xml')
    xml=adb('shell','cat','/sdcard/s.xml')
    nodes=[]
    for m in re.finditer(r'<node [^>]*>',xml):
        t=m.group(0); a=lambda k:(re.search(k+r'="([^"]*)"',t) or [None,''])[1]
        b=a('bounds'); mm=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b or '')
        if mm:
            nodes.append((a('resource-id'),a('text'),a('content-desc'),tuple(map(int,mm.groups()))))
    return nodes
def find(suffix):
    for rid,text,desc,b in dump():
        if rid.endswith(suffix): return b
    return None
def wait_find(suffix, tries=8):
    for _ in range(tries):
        b=find(suffix)
        if b: return b
        time.sleep(1)
    return None
def center(b): return ((b[0]+b[2])//2,(b[1]+b[3])//2)

b=wait_find('connect_button')
if not b: print('NO_CONNECT'); sys.exit(1)
tap(*center(b)); print('connect tapped',center(b)); time.sleep(2)
b=wait_find('create_display_button')
print('create',b)
if b: tap(*center(b)); time.sleep(3)
# scroll target section into view
for i in range(4):
    b=find('bind_target_button')
    if b: break
    adb('shell','input','swipe','1700','1400','1700','500','300'); time.sleep(0.8)
    print('scroll',i)
b=wait_find('bind_target_button')
print('bind',b)
if b: tap(*center(b)); time.sleep(3)
# print state around target/status
for n in dump():
    rid,text,desc,b=n
    if rid and rid.endswith(('target_package_input','bind_target_button','task_status','start_task_button')):
        print('STATE',rid,text,b)
