import json,zipfile,datetime,math
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip')
cases=[('yesterday',open('C:/Users/gildo/temp/3.8/cam3_full_2026-10-03_094458_725_8c2c3251.jsonl',encoding='utf-8-sig'),'2026-10-03',['10:07:21.428','10:07:23.597']),('today',z.open(next(n for n in z.namelist() if '_091937_' in n)),'2026-10-04',['09:30:17.712','09:30:30.256'])]
for label,f,date,frames in cases:
 targets={s:datetime.datetime.fromisoformat(date+'T'+s+'+10:00').timestamp()*1000 for s in frames};lo=min(targets.values())-15000;hi=max(targets.values())+20000;best={};lastcoord=None;changed=None;gval=None;phase=None;sec=None;out=[]
 for l in f:
  r=json.loads(l);e=r.get('event');tm=r.get('epochMs',0)
  if e=='lora_packet' and r.get('accepted'):
   coord=(r.get('latitude'),r.get('longitude'))
   if coord!=lastcoord:changed=tm;lastcoord=coord
  if e in ['aiming_cycle','movement_cycle','gimbal_pitch_cycle','retreat_cycle']:
   for s,t in targets.items():
    k=(s,e);d=abs(tm-t)
    if k not in best or d<best[k][0]:best[k]=(d,r,changed)
  if not lo<=tm<=hi:continue
  if e=='gimbal_pitch_cycle':
   v=(r.get('activeBand'),r.get('targetPitchDeg'))
   if v!=gval:out.append(('PITCH', {k:r.get(k) for k in ['timestamp','distanceM','targetPitchDeg','actualPitchDeg']}));gval=v
  if e in ['retreat_started','retreat_repeated','retreat_finished']:out.append((e,{k:r.get(k) for k in ['timestamp','distanceM','headingDeg','boundaryStatus']}))
  if e=='movement_cycle' and r.get('rideEvent')=='ride_started':out.append(('RIDE',{k:r.get(k) for k in ['timestamp','speedKmh','phase']}))
  if e=='aiming_cycle' and int(tm/1000)!=sec:
   sec=int(tm/1000);out.append(('SECOND',{k:r.get(k) for k in ['timestamp','distanceM','subjectBearingDeg','relativeBearingDeg','targetAgeMs','submittedYawRate','decision']}))
 print('\n',label)
 for s in frames:
  a=best[s,'aiming_cycle'][1];m=best[s,'movement_cycle'][1];g=best[s,'gimbal_pitch_cycle'][1];r=best[s,'retreat_cycle'][1];changed=best[s,'aiming_cycle'][2]
  print('FRAME',s,json.dumps({'calculation':a['timestamp'],'direct':a.get('distanceM'),'gpsAge':a.get('targetAgeMs'),'coordinateUnchangedMs':a['epochMs']-changed if changed else None,'yawError':a.get('relativeBearingDeg'),'yawCommand':a.get('submittedYawRate'),'pitchActual':g.get('actualPitchDeg'),'pitchTarget':g.get('targetPitchDeg'),'gimbalTime':g['timestamp'],'retreat':m.get('retreatActive'),'cooldown':m.get('retreatCooldownRemainingMs'),'forward':m.get('submittedForwardMps'),'phase':m.get('phase'),'reason':m.get('reason'),'ride':m.get('riding')}))
 for e,r in out:print(e,json.dumps(r))
