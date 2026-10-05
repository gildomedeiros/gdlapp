import zipfile,json,datetime
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_091937_' in n)
t=datetime.datetime.fromisoformat('2026-10-04T09:30:09.471+10:00').timestamp()*1000
best={};events=[];near=[]
for l in z.open(n):
 r=json.loads(l);e=r['event'];delta=abs(r['epochMs']-t)
 if e in ['movement_cycle','aiming_cycle','retreat_cycle','gimbal_pitch_cycle']:
  if e not in best or delta<best[e][0]:best[e]=(delta,r)
 if t-10000<=r['epochMs']<=t+10000 and e in ['movement_transition','retreat_started','retreat_finished','gimbal_pitch_command','gimbal_band_switch','pause','stop']:events.append(r)
for e,(d,r) in best.items():print(e,'delta',d,json.dumps(r))
print('EVENTS',json.dumps(events))
