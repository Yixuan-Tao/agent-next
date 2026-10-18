import re,subprocess,time
ADB='work/android-sdk/platform-tools/adb'
def adb(*a): return subprocess.run([ADB]+list(a),capture_output=True,text=True).stdout
def tap(x,y): adb('shell','input','-d','0','tap',str(x),str(y)); time.sleep(.8)
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
  if rid.endswith(suf): return b
 return None
def wait(suf,t=8):
 for _ in range(t):
  b=find(suf)
  if b: return b
  time.sleep(1)
 return None
def c(b): return ((b[0]+b[2])//2,(b[1]+b[3])//2)
adb('shell','am','force-stop','agent.phone'); adb('shell','am','force-stop','com.jingdong.app.mall')
for line in adb('shell','ps','-A').splitlines():
 if 'agent.phone:agent_shell' in line:
  p=line.split()
  if len(p)>1: adb('shell','kill','-9',p[1])
time.sleep(2)
adb('shell','am','start','-n','agent.phone/.ui.MainActivity'); time.sleep(3)
b=wait('connect_button'); print('connect',b); tap(*c(b)); time.sleep(2)
b=wait('create_display_button'); print('create',b); tap(*c(b)); time.sleep(3)
print('DISPLAYS:')
print(adb('shell','dumpsys','window','displays').split('Display: mDisplayId=')[1][:20] if 'Display: mDisplayId=' in adb('shell','dumpsys','window','displays') else 'none')
print(adb('shell','dumpsys','window','displays').split('Display: mDisplayId=')[1][:120] if 'Display: mDisplayId=' in adb('shell','dumpsys','window','displays') else '')
