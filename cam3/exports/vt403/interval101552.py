import zipfile,json,datetime,collections
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_095812_' in n);ts=[datetime.datetime.fromisoformat('2026-10-04T'+s+'+10:00').timestamp()*1000 for s in ['10:15:52.678','10:15:57.682']];best={};rows=collections.defaultdict(list)
for l in z.open(n):
 r=json.loads(l);e=r['event'];t=r['epochMs']
 if e in ['aiming_cycle','movement_cycle','gimbal_pitch_cycle']:
  for i,x in enumerate(ts):
   k=(i,e);d=abs(t-x)
   if k not in best or d<best[k][0]:best[k]=(d,r)
 if ts[0]-1500<=t<=ts[1]+1000:rows[e].append(r)
for k,(d,r) in best.items():print('FRAME',k,d,json.dumps({a:r.get(a) for a in ['timestamp','distanceM','aircraftHeadingDeg','subjectBearingDeg','relativeBearingDeg','targetAgeMs','decision','submittedYawRate','targetLatitude','targetLongitude','aircraftLatitude','aircraftLongitude','phase','reason','submittedForwardMps','submittedRightMps','riding','speedKmh','retreatActive','yawPurpose','actualPitchDeg','targetPitchDeg']}))
for e in ['movement_cycle','aiming_cycle','lora_packet']:
 rs=[r for r in rows[e] if ts[0]<=r['epochMs']<=ts[1]]
 print('SUMMARY',e,len(rs))
 if e=='movement_cycle':print('PHASES',dict(collections.Counter((r.get('phase'),r.get('reason')) for r in rs)));print('MAXMOVE',max(abs(r.get('submittedForwardMps') or 0)+abs(r.get('submittedRightMps') or 0) for r in rs))
 if e=='aiming_cycle':print('MAXYAW',max(abs(r.get('submittedYawRate') or 0) for r in rs),'MAXERROR',max(abs(r.get('relativeBearingDeg') or 0) for r in rs))
 if e=='lora_packet':
  for r in rs:print('GPS',json.dumps({a:r.get(a) for a in ['timestamp','latitude','longitude','accepted','sequence']}))
