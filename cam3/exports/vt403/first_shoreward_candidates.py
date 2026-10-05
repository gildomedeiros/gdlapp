import zipfile,json,math
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');angle=math.radians(88.4188024134645)
def sea(a,b):
 north=math.radians(b[0]-a[0])*6371000;east=math.radians(b[1]-a[1])*6371000*math.cos(math.radians(b[0]));return north*math.cos(angle)+east*math.sin(angle)
for n in sorted(z.namelist()):
 if not n.endswith('.jsonl'):continue
 ps=[];rides=[];aims=[];rets=[]
 for l in z.open(n):
  r=json.loads(l)
  if r['event']=='lora_packet' and r.get('accepted'):ps.append(r)
  if r['event']=='movement_cycle' and r.get('rideEvent')=='ride_started':rides.append(r)
  if r['event']=='aiming_cycle' and r.get('targetLatitude') is not None:aims.append(r)
  if r['event']=='retreat_started':rets.append(r)
 for ride in rides:
  t=ride['epochMs'];w=[p for p in ps if t-20000<=p['epochMs']<=t];run=[];prev=None;gaps=[]
  for p in w:
   if prev:
    ds=sea((prev['latitude'],prev['longitude']),(p['latitude'],p['longitude']))
    if ds< -1e-6:
     run.append(p)
    elif ds>1e-6:run=[]
   prev=p
  p=run[0] if run else None;a=next((r for r in aims if r['epochMs']>=p['epochMs']),None) if p else None
  actual=next((r for r in rets if (p['epochMs'] if p else t-5000)<=r['epochMs']<=t+25000),None)
  signed=sea((a['aircraftLatitude'],a['aircraftLongitude']),(a['targetLatitude'],a['targetLongitude'])) if a else None
  print(json.dumps({'ride':ride['timestamp'][11:23],'candidate':p['timestamp'][11:23] if p else None,'calc':a['timestamp'][11:23] if a else None,'distance':a.get('distanceM') if a else None,'signed':signed,'actual':actual['timestamp'][11:23] if actual else None,'headstart':(actual['epochMs']-p['epochMs'])/1000 if p and actual else None}))
