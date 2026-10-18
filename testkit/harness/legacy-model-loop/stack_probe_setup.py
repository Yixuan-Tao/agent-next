import re,subprocess,time,sys
ADB='work/android-sdk/platform-tools/adb'
def adb(*a): return subprocess.run([ADB]+list(a),capture_output=True,text=True).stdout
def tap(x,y): adb('shell','input','-d','0','tap',str(x),str(y)); time.sleep(.8)
def swipe(): adb('shell','input','-d','0','swipe','1700','1400','1700','600','300'); time.sleep(.6)
def dump():
 adb('shell','rm','-f','/sdcard/s.xml'); adb('shell','uiautomator','dump','/sdcard/s.xml')
 xml=adb('shell','cat','/sdcard/s.xml'); out=[]
 for m in re.finditer(r'<node [^>]*>',xml):
  t=m.group(0); a=lambda k:(re.search(k+r'="([^"]*)"',t) or [None,''])[1]
  b=a('bounds'); mm=re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',b or '')
  if mm: out.append((a('resource-id'),a('text'),tuple(map(int,mm.groups()))))
 return out
def find(suf):
 for rid,text,b in dump():
  if rid.endswith(suf): return b,text
 return None,None
def wait(suf,t=8):
 for _ in range(t):
  b,x=find(suf)
  if b: return b,x
  time.sleep(1)
 return None,None
def c(b): return ((b[0]+b[2])//2,(b[1]+b[3])//2)
for p in ('agent.phone','com.jingdong.app.mall','com.quark.browser'): adb('shell','am','force-stop',p)
for line in adb('shell','ps','-A').splitlines():
 if 'agent.phone:agent_shell' in line:
  parts=line.split()
  if len(parts)>1: adb('shell','kill','-9',parts[1])
time.sleep(2)
adb('shell','am','start','-n','agent.phone/.ui.MainActivity','--es','target_package','com.jingdong.app.mall')
time.sleep(3)
b,_=wait('connect_button'); print('connect',b); tap(*c(b)); time.sleep(2)
b,_=wait('create_display_button'); print('create',b); tap(*c(b)); time.sleep(3)
for _ in range(5):
 b,_=find('bind_target_button')
 if b: break
 swipe()
print('bind',b); tap(*c(b)); time.sleep(3)
for _ in range(5):
 b,_=find('launch_target_button')
 if b: break
 swipe()
print('launch',b)
if not b: sys.exit('no launch')
tap(*c(b)); time.sleep(4)
print('display line:')
print(adb('shell','dumpsys','window','displays').split('Display: mDisplayId=')[-1][:30])
print('STACK LIST:')
print(adb('shell','am','stack','list'))
print('TASKS:')
print(adb('shell','dumpsys','activity','activities').split('ACTIVITY MANAGER ACTIVITIES')[1][:3000] if 'ACTIVITY MANAGER ACTIVITIES' in adb('shell','dumpsys','activity','activities') else '')
