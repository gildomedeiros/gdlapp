import json,zipfile,datetime
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip')
cases=[('yesterday',open('C:/Users/gildo/temp/3.8/cam3_full_2026-10-03_094458_725_8c2c3251.jsonl',encoding='utf-8-sig'),'2026-10-03T10:07:21.428+10:00'),('today',z.open(next(n for n in z.namelist() if '_091937_' in n)),'2026-10-04T09:30:17.712+10:00')]
for label,f,stamp in cases:
 t=datetime.datetime.fromisoformat(stamp).timestamp()*1000;best={};packets=[];gimbals=[]
 for l in f:
  r=json.loads(l);e=r.get('event');dt=r.get('epochMs',0)-t
  if e in ['aiming_cycle','movement_cycle','gimbal_pitch_cycle'] and (e not in best or abs(dt)<best[e][0]):best[e]=(abs(dt),r)
  if abs(dt)<3000 and e=='lora_packet':packets.append({k:r.get(k) for k in ['timestamp','sequence','latitude','longitude','accepted','hdop','satellites']})
  if -15000<dt<3000 and e=='gimbal_pitch_cycle':
   val=(r.get('activeBand'),r.get('targetPitchDeg'))
   if not gimbals or val!=gimbals[-1][0]:gimbals.append((val,{k:r.get(k) for k in ['timestamp','distanceM','actualPitchDeg','targetPitchDeg','activeBand','trigger']}))
 print(label)
 for e,(d,r) in best.items():print(e,d,json.dumps({k:r.get(k) for k in ['timestamp','distanceM','targetAgeMs','aircraftAgeMs','aircraftHeadingDeg','subjectBearingDeg','relativeBearingDeg','targetLatitude','targetLongitude','aircraftLatitude','aircraftLongitude','targetPitchDeg','actualPitchDeg','activeBand','phase','reason','submittedForwardMps','submittedRightMps','retreatActive']}))
 print('PACKETS',json.dumps(packets));print('GIMBAL_CHANGES',json.dumps(gimbals))
