import zipfile,json,statistics,collections,math
from pathlib import Path
def tm(x):return x['timestamp'][11:23]
def stats(v):
 v=sorted(x for x in v if x is not None)
 return {'min':round(v[0],3),'median':round(statistics.median(v),3),'p90':round(v[min(len(v)-1,math.ceil(.9*len(v))-1)],3),'max':round(v[-1],3)} if v else {}
def spans(v,k,end):
 c=collections.Counter()
 for i,x in enumerate(v): c[str(x.get(k))]+=( (v[i+1]['epochMs'] if i+1<len(v) else end)-x['epochMs'])/1000
 return {k:round(v,2) for k,v in c.items()}
def dist(a,b):
 la,lo=a;lb,lob=b
 return 6371000*2*math.asin(min(1,math.sqrt(math.sin(math.radians(lb-la)/2)**2+math.cos(math.radians(la))*math.cos(math.radians(lb))*math.sin(math.radians(lob-lo)/2)**2)))
out=[]
with zipfile.ZipFile(r'C:/Users/gildo/temp/vt 3.4 beach test.zip') as z:
 for n in z.namelist():
  if not n.endswith('.jsonl'):continue
  rr=[json.loads(l) for l in z.read(n).decode('utf-8-sig').splitlines() if l.strip()]
  start=next((x for x in rr if x['event']=='user_start'),None)
  if start is None:continue
  stop=next(x for x in rr if x['event']=='stop' and x['epochMs']>start['epochMs']);r=[x for x in rr if start['epochMs']<=x['epochMs']<stop['epochMs']]
  ev=lambda e:[x for x in r if x['event']==e]
  a=ev('aiming_cycle');m=ev('movement_cycle');o=ev('orientation_cycle');g=ev('gimbal_pitch_cycle');p=[x for x in ev('lora_packet') if x.get('accepted')];secs=(stop['epochMs']-start['epochMs'])/1000
  active=next(x for x in m if x['phase']!='OFF');gaps=[(y['receivedAtMs']-x['receivedAtMs'],tm(y)) for x,y in zip(p,p[1:])]
  app=[];cur=None
  for x in m:
   if cur and x['phase']!='APPROACHING':
    cur.update(end=tm(x),endPhase=x['phase'],endReason=x['reason'],duration=round((x['epochMs']-cur.pop('epoch'))/1000,2));app.append(cur);cur=None
   if x['phase']=='APPROACHING' and cur is None:cur={'start':tm(x),'epoch':x['epochMs'],'travel':x['plannedTravelM'],'distance':x['surferDistanceM'],'moving':None}
   if cur and cur['moving'] is None and (x.get('submittedForwardMps') or 0)>0:cur['moving']=tm(x);cur['alignSeconds']=round((x['epochMs']-cur['epoch'])/1000,2)
  if cur:cur.pop('epoch');cur['endReason']='run_stopped';app.append(cur)
  origin=(p[0]['latitude'],p[0]['longitude']);ranges=[dist(origin,(x['latitude'],x['longitude'])) for x in p]
  bands={}
  for pitch in sorted(set(x['targetPitchDeg'] for x in g)):
   rows=[x for x in g if x['targetPitchDeg']==pitch];bands[str(pitch)]={'samples':len(rows),'first':tm(rows[0]),'distance':stats([x['distanceM'] for x in rows])}
  rides=[];prev=False
  for x in m:
   if x['riding'] and not prev:rides.append({'start':tm(x),'speed':x['speedKmh']})
   if not x['riding'] and prev:rides[-1]['end']=tm(x)
   prev=x['riding']
  res={'file':n,'start':tm(start),'stop':tm(stop),'seconds':secs,'settings':{k:active.get(k) for k in ['filmingDistanceM','reapproachMarginM','lineupWidthM','rideStartKmh','rideDurationMs','qualificationRequiredMs','noRideTimeoutMs','maxMovementSpeedMps','movementAccelerationMps2']},'yaw':sorted(set((x['maxYawRate'],x['maxYawAcceleration']) for x in a)),'height':stats([x['heightAboveTakeoffM'] for x in o if x['heightFresh']]),'distance':stats([x['distanceM'] for x in a]),'phaseSeconds':spans(m,'phase',stop['epochMs']),'stateSeconds':spans(a,'state',stop['epochMs']),'ridingSeconds':spans(m,'riding',stop['epochMs']),'approaches':app,'approachOutcomes':dict(collections.Counter(x['endReason'] for x in app)),'rides':rides,'pitchCommands':len(ev('gimbal_pitch_command')),'pitchReached':len(ev('gimbal_pitch_reached')),'pitchReachMs':stats([x['elapsedMs'] for x in ev('gimbal_pitch_reached')]),'pitchBands':bands,'packets':len(p),'pps':len(p)/secs,'packetGapsMs':stats([x[0] for x in gaps]),'gapsOver3':sum(x[0]>3000 for x in gaps),'gapsOver5':[x for x in gaps if x[0]>5000],'rssi':stats([x['rssi'] for x in p]),'snr':stats([x['snr'] for x in p]),'missing':sum(x['missingBefore'] for x in p[1:]),'maxRangeFromStart':max(ranges),'pauses':ev('pause'),'returnEvents':[x for x in ev('movement_transition') if 'RETURN' in x['detail']],'retainedSeconds':spans(a,'retainedTargetAiming',stop['epochMs']),'end':stop}
  out.append(res)
Path(r'C:/Users/gildo/gdlapp/cam3/exports/VT34_beach_2026-10-02.json').write_text(json.dumps(out,indent=2))
for x in out:
 print(json.dumps({k:v for k,v in x.items() if k not in ['approaches','pitchBands','pauses','end']}))
 print('BANDS',json.dumps(x['pitchBands']))
