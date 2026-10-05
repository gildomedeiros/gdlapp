import zipfile,json,datetime,collections
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip'); n=next(n for n in z.namelist() if '_083821_' in n); t=datetime.datetime.fromisoformat('2026-10-04T09:05:01.964+10:00').timestamp()*1000; best={}; rides=[]; rows=[]
for l in z.open(n):
 r=json.loads(l);e=r['event'];d=abs(r['epochMs']-t)
 if e in ['movement_cycle','aiming_cycle','gimbal_pitch_cycle'] and (e not in best or d<best[e][0]):best[e]=(d,r)
 if e=='movement_cycle' and r.get('rideEvent')=='ride_started':rides.append(r['timestamp'])
 if e=='aiming_cycle' and r.get('riding'):rows.append(r)
for e,(d,r) in best.items():print(e,d,json.dumps(r))
print('RIDES',rides)
