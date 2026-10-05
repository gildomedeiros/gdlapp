import zipfile,json,datetime,math
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_091937_' in n);ts=['09:30:17.712','09:30:30.256'];targets={s:datetime.datetime.fromisoformat('2026-10-04T'+s+'+10:00').timestamp()*1000 for s in ts};best={};packets={};start=None
for l in z.open(n):
 r=json.loads(l);e=r['event']
 if e=='lora_packet':packets[r.get('sequence')]=r['timestamp'][11:23]
 if e=='aiming_cycle' and r.get('session')==1 and r.get('state')=='AIMING' and r.get('targetLatitude') is not None and start is None:start=r
 if e in ['aiming_cycle','movement_cycle','retreat_cycle']:
  for s,t in targets.items():
   k=(s,e);d=abs(r['epochMs']-t)
   if k not in best or d<best[k][0]:best[k]=(d,r)
def dist(a,b,c,d):
 p,q=map(math.radians,[a,c]);x=math.sin((q-p)/2)**2+math.cos(p)*math.cos(q)*math.sin(math.radians(d-b)/2)**2;return 6371000*2*math.atan2(math.sqrt(x),math.sqrt(1-x))
print('START',start['timestamp'],start['targetLatitude'],start['targetLongitude'])
for s in ts:
 a=best[s,'aiming_cycle'][1];m=best[s,'movement_cycle'][1];r=best[s,'retreat_cycle'][1]
 print(s,json.dumps({'gps':packets.get(a.get('targetSequence')),'calculation':m['timestamp'][11:23],'age':a.get('targetAgeMs'),'direct':m.get('surferDistanceM'),'startDistance':dist(start['targetLatitude'],start['targetLongitude'],a['aircraftLatitude'],a['aircraftLongitude']),'filming':m.get('filmingDistanceM'),'retreatSetting':r.get('minimumDistanceM'),'retreatActive':m.get('retreatActive')}))
