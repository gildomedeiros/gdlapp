import json, glob, collections, statistics, math, base64
from pathlib import Path
def tm(r): return r['timestamp'][11:23]
def stats(v):
 v=sorted(x for x in v if isinstance(x,(int,float)) and math.isfinite(x))
 return dict(min=round(v[0],2),median=round(statistics.median(v),2),p90=round(v[min(len(v)-1,math.ceil(len(v)*.9)-1)],2),max=round(v[-1],2)) if v else {}
def pick(r,ks): return {k:r.get(k) for k in ks.split()}
def distance(a,b):
 la,lo=a;lb,ln=b
 return 6371000*2*math.asin(min(1,math.sqrt(math.sin(math.radians(lb-la)/2)**2+math.cos(math.radians(la))*math.cos(math.radians(lb))*math.sin(math.radians(ln-lo)/2)**2)))
def pos(r,p='aircraft'):return r[p+'Latitude'],r[p+'Longitude']
out=[]
for p in sorted(glob.glob('C:/Users/gildo/temp/cam3_full_2026-10-02_2*.jsonl')):
 rr=[json.loads(l) for l in open(p,encoding='utf-8-sig') if l.strip()]
 starts=[r for r in rr if r['event']=='user_start']; start=starts[0] if starts else rr[0]
 stop=next((r for r in rr if r['event']=='stop' and r['epochMs']>start['epochMs']),rr[-1])
 rows=[r for r in rr if start['epochMs']<=r['epochMs']<=stop['epochMs']]
 ev=lambda e:[r for r in rows if r['event']==e]
 a=ev('aiming_cycle'); m=ev('movement_cycle'); active=[r for r in a if r['state']=='AIMING']; rt=ev('retreat_cycle')
 res=dict(file=p,start=tm(start),stop=tm(stop),end=tm(rr[-1]),started=bool(starts),settings=pick(m[0],'enabled filmingDistanceM reapproachMarginM maxMovementSpeedMps movementAccelerationMps2 movementAccelerationRampEnabled lineupWidthM rideStartKmh rideDurationMs noRideTimeoutMs qualificationRequiredMs'),yawSettings=list({(r['maxYawRate'],r['maxYawAcceleration']) for r in a}),configs=[r for r in rows if r['event'] in ['rotation_speed_config','gimbal_band_config'] and 'source' in r],retreatSettings=[pick(r,'minimumDistanceM durationMs speedMps cooldownMs') for r in ev('retreat_settings')],events=[pick(r,'timestamp event detail') for r in rows if r['event'] in ['pause','resumed','stop','recovery_cancelled']],states=dict(collections.Counter(r['state'] for r in a)),phaseCounts=dict(collections.Counter(r['phase'] for r in m)))
 res['stateSeconds']=dict(collections.Counter())
 for r,n in zip(a,a[1:]): res['stateSeconds'][r['state']]=res['stateSeconds'].get(r['state'],0)+(n['monoMs']-r['monoMs'])/1000
 res['activeStats']={k:stats([abs(r[k]) if k=='relativeBearingDeg' and r.get(k)!=None else r.get(k) for r in active]) for k in ['distanceM','relativeBearingDeg','horizontalSpeedMps','targetAgeMs','desiredYawRate','requestedYawRate','submittedYawRate']}
 res['height']=stats([r.get('heightAboveTakeoffM') for r in ev('orientation_cycle') if r.get('heightFresh')])
 res['yawGroups']={}
 for purpose in sorted({r.get('yawPurpose','unknown') for r in m}):
  ids={r['cycleId'] for r in m if r.get('yawPurpose')==purpose}; ar=[r for r in active if r['cycleId'] in ids]
  res['yawGroups'][purpose]=dict(count=len(ar),error=stats([abs(r['relativeBearingDeg']) for r in ar if r.get('relativeBearingDeg')!=None]),reverseBlocked=sum(bool(r.get('reverseBlocked')) for r in ar),over15=sum(abs(r.get('relativeBearingDeg') or 0)>15 for r in ar))
 res['rotationSources']=dict(collections.Counter(str(r.get('rotationConfigSource')) for r in active));res['curves']=dict(collections.Counter(str(r.get('rotationCurve')) for r in active))
 res['retreats']=[]
 for s in ev('retreat_started'):
  e=next(r for r in rows if r['epochMs']>s['epochMs'] and r['event'] in ['retreat_finished','retreat_cancelled'])
  ar=[r for r in active if s['epochMs']<=r['epochMs']<=e['epochMs']]; tr=[r for r in rt if s['epochMs']<=r['epochMs']<e['epochMs']]
  res['retreats'].append(dict(start=tm(s),end=tm(e),seconds=round((e['monoMs']-s['monoMs'])/1000,3),periods=e['period'],startDistance=s['distanceM'],endDistance=e['distanceM'],minDistance=min(r['distanceM'] for r in tr),droneDisplacement=distance(pos(s),pos(e)),targetDisplacement=distance(pos(s,'target'),pos(e,'target')),speed=stats([r.get('horizontalSpeedMps') for r in ar]),submitted=dict(collections.Counter(str(r.get('submittedForwardMps')) for r in tr)),retained=sum(r['retainedTarget'] for r in tr),repeats=[dict(time=tm(r),distance=r['distanceM']) for r in ev('retreat_repeated') if s['epochMs']<r['epochMs']<e['epochMs']]))
 res['transitions']=[pick(r,'timestamp detail') for r in ev('movement_transition')]
 res['approaches']=[];cur=None
 for r in m:
  if cur and r['phase']!='APPROACHING':
   seg=[q for q in m if cur['monoMs']<=q['monoMs']<r['monoMs']];last=seg[-1]
   res['approaches'].append(dict(start=tm(cur),end=tm(r),startDistance=cur['surferDistanceM'],endDistance=r['surferDistanceM'],planned=cur['plannedTravelM'],lastRemaining=last['approachRemainingM'],lastProgress=last['forwardProgressM'],reason=r['reason'],phase=r['phase'],requested=stats([q['requestedForwardMps'] for q in seg]),submitted=stats([q['submittedForwardMps'] for q in seg])));cur=None
  if r['phase']=='APPROACHING' and cur is None:cur=r
 if cur:
  last=m[-1]
  res['approaches'].append(dict(start=tm(cur),end=None,observedUntil=tm(last),startDistance=cur['surferDistanceM'],lastDistance=last['surferDistanceM'],planned=cur['plannedTravelM'],lastRemaining=last['approachRemainingM'],paused=last['paused'],outcome='unfinished_before_stop'))
 res['rides']=[pick(r,'timestamp rideEvent speedKmh rideRemainingMs') for r in m if r.get('rideEvent') not in [None,'none']]
 res['rejectedSpeedJumps']=max([r.get('rejectedSpeedJumps',0) for r in m],default=0)
 res['gimbalCommands']=[pick(r,'timestamp targetPitchDeg actualPitchDeg distanceM trigger') for r in ev('gimbal_pitch_command')]
 res['gimbalReached']=len(ev('gimbal_pitch_reached'));res['gimbalReachMs']=stats([r.get('elapsedMs') for r in ev('gimbal_pitch_reached')]);res['gimbalResults']=ev('gimbal_pitch_result')
 packets=ev('lora_packet');res['packetCount']=len(packets);res['acceptedPackets']=sum(r.get('accepted',False) for r in packets)
 res['packetGaps']=stats([y['monoMs']-x['monoMs'] for x,y in zip(packets,packets[1:])]);res['gaps']=ev('lora_gap')
 res['nofix']=sum(b'NOFIX' in base64.b64decode(r.get('rawBase64','')) for r in ev('lora_datagram'))
 res['retainedAimingCycles']=sum(r.get('retainedTargetAiming',False) for r in active)
 res['speedKmh']=stats([r.get('speedKmh') for r in m]);res['retreatIdleSettings']=pick(rt[0],'enabled minimumDistanceM durationMs speedMps cooldownMs') if rt else {}
 res['worstYaw']=sorted([pick(r,'timestamp relativeBearingDeg distanceM requiredDirection permittedDirection reverseBlocked submittedYawRate rotationCurve') for r in active if r.get('relativeBearingDeg')!=None],key=lambda r:abs(r['relativeBearingDeg']),reverse=True)[:5]
 out.append(res)
Path('C:/Users/gildo/gdlapp/cam3/exports/VT37_evening_2026-10-02_analysis.json').write_text(json.dumps(out,indent=2),encoding='utf-8')
for r in out:
 print(json.dumps({k:v for k,v in r.items() if k not in ['configs','transitions','gimbalCommands','gimbalResults','events']},separators=(',',':')))
