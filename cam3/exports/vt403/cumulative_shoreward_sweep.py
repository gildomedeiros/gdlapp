import zipfile,json,math,collections
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');ang=math.radians(88.4188024134645);triggers={v:[] for v in [1,2,3,5]};rides=[]
def sea(a,b):return math.radians(b[0]-a[0])*6371000*math.cos(ang)+math.radians(b[1]-a[1])*6371000*math.cos(math.radians(b[0]))*math.sin(ang)
for n in z.namelist():
 if not n.endswith('.jsonl'):continue
 aim=None;prev=None;progress=0;fired=set()
 for l in z.open(n):
  r=json.loads(l)
  if r['event']=='aiming_cycle':aim=r
  if r['event']!='movement_cycle':continue
  if r.get('rideEvent')=='ride_started':rides.append((n,r['epochMs']))
  if r.get('phase')=='OFF' or not aim or aim.get('state')!='AIMING':prev=None;progress=0;fired=set();continue
  if not r.get('newRidePacket') or aim.get('targetLatitude') is None:continue
  c=(aim['targetLatitude'],aim['targetLongitude'])
  if prev is None:prev=c;continue
  delta=sea(prev,c);prev=c
  if delta>1e-6:progress=0;fired=set();continue
  progress-=delta
  signed=sea((aim['aircraftLatitude'],aim['aircraftLongitude']),c)
  for v in triggers:
   if progress>=v and v not in fired:
    fired.add(v)
    if signed>0:triggers[v].append((n,r['epochMs'],r['timestamp'][11:23]))
for v,es in triggers.items():
 matched=[e for e in es if any(n==e[0] and e[1]<=t<=e[1]+20000 for n,t in rides)];covered=sum(any(e[0]==n and t-20000<=e[1]<=t for e in es) for n,t in rides)
 print('NET_SHOREWARD',v,'total',len(es),'matched',len(matched),'unmatched',len(es)-len(matched),'ridesCovered',covered)
 print('MATCHTIMES',[e[2] for e in matched])
