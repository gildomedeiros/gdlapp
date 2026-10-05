import zipfile,json,datetime
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_095812_' in n);t=datetime.datetime.fromisoformat('2026-10-04T10:04:16.487+10:00').timestamp()*1000;best={};events=[]
for l in z.open(n):
 r=json.loads(l);e=r['event'];d=abs(r['epochMs']-t)
 if e in ['aiming_cycle','movement_cycle','gimbal_pitch_cycle'] and (e not in best or d<best[e][0]):best[e]=(d,r)
 if abs(r['epochMs']-t)<4000 and e=='movement_cycle':
  v=(r.get('phase'),r.get('reason'),r.get('yawPurpose'))
  if not events or events[-1][0]!=v:events.append((v,r['timestamp']))
for e,(d,r) in best.items():print(e,d,json.dumps(r))
print('CHANGES',events)
