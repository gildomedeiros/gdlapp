import zipfile,json,math,pathlib
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');out=[]
for n in sorted(z.namelist()):
 if not n.endswith('.jsonl'):continue
 aims=[];rides=[];mov=[]
 for l in z.open(n):
  r=json.loads(l)
  if r['event']=='aiming_cycle' and r.get('targetLatitude') is not None and r.get('aircraftLatitude') is not None:aims.append(r)
  if r['event']=='movement_cycle':
   if r.get('rideEvent')=='ride_started':rides.append(r)
   if r.get('rideEvent')=='ride_expired':mov.append(r)
 def signed(r):
  north=math.radians(r['targetLatitude']-r['aircraftLatitude'])*6371000
  east=math.radians(r['targetLongitude']-r['aircraftLongitude'])*6371000*math.cos(math.radians((r['targetLatitude']+r['aircraftLatitude'])/2))
  b=math.radians(88.4188024134645);return north*math.cos(b)+east*math.sin(b)
 for start in rides:
  t=start['epochMs'];end=t+20000;er=min(aims,key=lambda r:abs(r['epochMs']-end));sr=min(aims,key=lambda r:abs(r['epochMs']-t));rr=[r for r in aims if t<=r['epochMs']<=end];valid=[r for r in rr if (r.get('targetAgeMs') or 0)<=1500];neg=[r for r in valid if signed(r)<-5];first=neg[0]['timestamp'][11:23] if neg else None
  row={'log':n,'mode':start.get('positioningMode'),'start':start['timestamp'][11:23],'timerEnd':er['timestamp'][11:23],'startSignedM':signed(sr),'endSignedM':signed(er),'endDirectM':er.get('distanceM'),'endGpsAgeMs':er.get('targetAgeMs'),'minSignedM':min(signed(r) for r in rr),'firstBelowMinus5Fresh':first,'distinctNegativeFixes':len(set(r.get('targetFixMs') for r in neg)),'endState':er.get('state'),'endYawError':er.get('relativeBearingDeg')};out.append(row);print(json.dumps(row))
pathlib.Path('exports/vt403/water_ride_shore_crossings.json').write_text(json.dumps(out,indent=2),encoding='utf-8')
