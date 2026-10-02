import zipfile,json,collections,statistics,math,base64
from pathlib import Path
OUT=Path(r'C:/Users/gildo/gdlapp/cam3/exports')
def st(v):
 v=sorted(x for x in v if isinstance(x,(int,float)) and math.isfinite(x))
 return dict(min=round(v[0],2),median=round(statistics.median(v),2),p90=round(v[min(len(v)-1,math.ceil(.9*len(v))-1)],2),max=round(v[-1],2)) if v else {}
def tm(x):return x['timestamp'][11:23]
def dist(a,b):
 lat,lon=a;la,lo=b
 return 6371000*2*math.asin(min(1,math.sqrt(math.sin(math.radians(la-lat)/2)**2+math.cos(math.radians(lat))*math.cos(math.radians(la))*math.sin(math.radians(lo-lon)/2)**2)))
def pos(x):return (x['aircraftLatitude'],x['aircraftLongitude'])
def span(v,k,end):
 c=collections.Counter()
 for x,y in zip(v,v[1:]+[{'epochMs':end}]):c[str(x.get(k))]+=(y['epochMs']-x['epochMs'])/1000
 return {k:round(v,2) for k,v in c.items()}
results=[]
with zipfile.ZipFile(r'C:/Users/gildo/temp/3.5.zip') as z:
 for n in z.namelist():
  if not n.endswith('.jsonl'):continue
  rr=[json.loads(l) for l in z.read(n).decode('utf-8-sig').splitlines() if l.strip()]
  start=next((x for x in rr if x['event']=='user_start'),None)
  if not start:continue
  stop=next(x for x in rr if x['event']=='stop' and x['epochMs']>start['epochMs'])
  r=[x for x in rr if start['epochMs']<=x['epochMs']<stop['epochMs']]
  ev=lambda e:[x for x in r if x['event']==e]
  a=ev('aiming_cycle');m=ev('movement_cycle');rt=ev('retreat_cycle');o=ev('orientation_cycle');g=ev('gimbal_pitch_cycle');p=[x for x in ev('lora_packet') if x['accepted']]
  settings={k:m[-1][k] for k in ['filmingDistanceM','reapproachMarginM','maxMovementSpeedMps','movementAccelerationMps2','lineupWidthM','rideStartKmh','rideDurationMs','noRideTimeoutMs','qualificationRequiredMs']}
  runs=[];cur=None
  for x in r:
   if x['event']=='retreat_started':cur=x
   if x['event'] in ['retreat_finished','retreat_cancelled'] and cur:
    rows=[q for q in rt if cur['epochMs']<=q['epochMs']<=x['epochMs']]
    aa=[q for q in a if cur['epochMs']<=q['epochMs']<=x['epochMs']]
    path=[pos(cur)]+[pos(q) for q in rows]+[pos(x)]
    runs.append(dict(start=tm(cur),end=tm(x),seconds=round((x['epochMs']-cur['epochMs'])/1000,3),periods=x['period'],startDistance=cur['distanceM'],endDistance=x['distanceM'],minDistance=min(q['distanceM'] for q in rows),aircraftPathM=sum(dist(u,v) for u,v in zip(path,path[1:])),aircraftDisplacementM=dist(pos(cur),pos(x)),surferDisplacementM=dist((cur['targetLatitude'],cur['targetLongitude']),(x['targetLatitude'],x['targetLongitude'])),speed=st([q['horizontalSpeedMps'] for q in aa]),yawError=st([abs(q['relativeBearingDeg']) for q in aa if q.get('relativeBearingDeg') is not None]),submitted=dict(collections.Counter(str(q['submittedForwardMps']) for q in rows)),retained=sum(q['retainedTarget'] for q in rows),riding=sum(q['riding'] for q in rows)))
    cur=None
  active=[q for q in a if q['state']=='AIMING']
  approaches=[];cur=None
  for q in m:
   if cur and q['phase']!='APPROACHING':
    approaches.append(dict(start=tm(cur),end=tm(q),seconds=(q['epochMs']-cur['epochMs'])/1000,endReason=q['reason'],startDistance=cur['surferDistanceM'],endDistance=q['surferDistanceM']));cur=None
   if q['phase']=='APPROACHING' and not cur:cur=q
  gaps=[(q['receivedAtMs']-v['receivedAtMs'],tm(q)) for v,q in zip(p,p[1:])]
  rides=[];prev=False
  for q in m:
   if q['riding'] and not prev:rides.append(dict(start=tm(q),speed=q['speedKmh']))
   prev=q['riding']
  res=dict(file=n,start=tm(start),end=tm(stop),seconds=(stop['epochMs']-start['epochMs'])/1000,settings=settings,retreatSettings={k:ev('retreat_settings')[0][k] for k in ['minimumDistanceM','durationMs','speedMps','cooldownMs']},yawSettings=sorted(set((q['maxYawRate'],q['maxYawAcceleration']) for q in a)),height=st([q['heightAboveTakeoffM'] for q in o if q['heightFresh']]),distance=st([q['distanceM'] for q in active]),yawError=st([abs(q['relativeBearingDeg']) for q in active if q['relativeBearingDeg'] is not None]),yawOver15=sum(abs(q.get('relativeBearingDeg') or 0)>15 for q in active)/max(1,len(active)),stateSeconds=span(a,'state',stop['epochMs']),phaseSeconds=span(m,'phase',stop['epochMs']),retreats=runs,approaches=approaches,approachOutcomes=dict(collections.Counter(q['endReason'] for q in approaches)),rides=rides,pause=ev('pause'),retreatCounts={k:len(ev(k)) for k in ['retreat_started','retreat_repeated','retreat_finished','retreat_cancelled','retreat_approach_replaced']},pitchCommands=len(ev('gimbal_pitch_command')),pitchReached=len(ev('gimbal_pitch_reached')),pitchReachMs=st([q['elapsedMs'] for q in ev('gimbal_pitch_reached')]),gimbalConfigs=ev('gimbal_band_config'),packetGapsMs=st([q[0] for q in gaps]),gapsOver3=[q for q in gaps if q[0]>3000],missing=sum(q['missingBefore'] for q in p[1:]),packets=len(p),retainedSeconds=span(a,'retainedTargetAiming',stop['epochMs']),nofix=sum('NOFIX' in base64.b64decode(q['rawBase64']).decode(errors='replace') for q in ev('lora_datagram')),transitions=ev('movement_transition'))
  results.append(res)
OUT.joinpath('VT35_2026-10-02_analysis.json').write_text(json.dumps(results,indent=2))
for r in results:
 print(json.dumps({k:v for k,v in r.items() if k not in ['retreats','approaches','transitions','gimbalConfigs']}))
 for q in r['retreats']:print('RETREAT',json.dumps(q))
