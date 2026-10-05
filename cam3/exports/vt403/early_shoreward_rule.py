import zipfile,json,math
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip')
for n in sorted(z.namelist()):
 if not n.endswith('.jsonl'):continue
 packets=[];rides=[];retreats=[]
 for l in z.open(n):
  r=json.loads(l)
  if r['event']=='movement_cycle' and r.get('newRidePacket'):packets.append(r)
  if r['event']=='movement_cycle' and r.get('rideEvent')=='ride_started':rides.append(r)
  if r['event']=='retreat_started':retreats.append(r)
 # coordinate fields are absent on movement cycles: attach latest aiming cycle per cycleId
 aims={}
 for l in z.open(n):
  r=json.loads(l)
  if r['event']=='aiming_cycle':aims[r.get('cycleId')]=r
 prev=None;qualified=[];chain=0
 for r in packets:
  a=aims.get(r.get('cycleId'),{});lat=a.get('targetLatitude');lon=a.get('targetLongitude')
  if lat is None or lon is None:chain=0;continue
  shoreward=False
  if prev:
   north=math.radians(lat-prev[0])*6371000;east=math.radians(lon-prev[1])*6371000*math.cos(math.radians(lat));b=math.radians(88.4188024134645);shoreward=north*math.cos(b)+east*math.sin(b)<-0.01
  speed=r.get('speedKmh');ok=speed is not None and speed>=4 and shoreward and not r.get('speedJumpRejected')
  chain=chain+1 if ok else 0
  if chain==3:qualified.append(r)
  prev=(lat,lon)
 for ride in rides:
  t=ride['epochMs'];q=next((r for r in qualified if t-15000<=r['epochMs']<=t),None)
  actual=next((r for r in retreats if (q['epochMs'] if q else t-5000)<=r['epochMs']<=t+25000),None)
  # if candidate already retreating there is no additional head start
  print(json.dumps({'ride':ride['timestamp'][11:23],'candidate':q['timestamp'][11:23] if q else None,'distance':q.get('surferDistanceM') if q else None,'active':q.get('retreatActive') if q else None,'actual':actual['timestamp'][11:23] if actual else None,'headstart':(actual['epochMs']-q['epochMs'])/1000 if q and actual and not q.get('retreatActive') else 0 if q and q.get('retreatActive') else None}))
