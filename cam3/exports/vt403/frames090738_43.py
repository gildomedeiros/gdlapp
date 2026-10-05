import zipfile,json,datetime
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_083821_' in n)
ts=['09:07:38.905','09:07:42.375','09:07:43.609']; targets={s:datetime.datetime.fromisoformat('2026-10-04T'+s+'+10:00').timestamp()*1000 for s in ts};best={};packets={}
for l in z.open(n):
 r=json.loads(l);e=r['event']
 if e=='lora_packet':packets[r.get('sequence')]=r['timestamp'][11:23]
 if e in ['aiming_cycle','movement_cycle']:
  for s,t in targets.items():
   key=(s,e);d=abs(r['epochMs']-t)
   if key not in best or d<best[key][0]:best[key]=(d,r)
for s in ts:
 a=best[s,'aiming_cycle'][1];m=best[s,'movement_cycle'][1]
 print(s,json.dumps({'gps':packets.get(a.get('targetSequence')),'calculation':m['timestamp'][11:23],'age':a.get('targetAgeMs'),'projected':m.get('projectedSeparationM'),'direct':m.get('surferDistanceM'),'alignment':m.get('comeToMeAlignmentErrorM'),'retreat':m.get('retreatActive')}))
