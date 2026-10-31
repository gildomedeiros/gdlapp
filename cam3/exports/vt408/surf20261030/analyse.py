import json,collections,math
from pathlib import Path
out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408/surf20261030');results=[]
for p in sorted(Path('C:/Users/gildo/temp/311026').glob('*.jsonl')):
 events=collections.Counter();states=collections.Counter();reasons=collections.Counter();purpose=collections.Counter();routes={};outcomes=[];special=[];configs=[];errors=[];ranges={};runs=[];lastkey=None;nonzero=0;rideflags=collections.Counter();aim={};fail=[];last=None
 def metric(k,v):
  if isinstance(v,(int,float)) and math.isfinite(v):
   if k not in ranges:ranges[k]=[v,v]
   else:ranges[k]=[min(ranges[k][0],v),max(ranges[k][1],v)]
 for i,l in enumerate(p.open(encoding='utf-8-sig'),1):
  try:x=json.loads(l)
  except Exception as e:errors.append([i,str(e)]);continue
  last=x;e=x.get('event','');events[e]+=1
  if i==1:first=x
  if e in ['vt40_configuration','retreat_settings_config','rotation_speed_config','gimbal_band_config']:configs.append(x)
  if e in ['journey_outcome']:outcomes.append(x)
  if e in ['user_start','state','stop','pause','resumed','retreat_started','retreat_finished','sdk_callback','disable_request','disable_result','authority_reason','configuration_start_blocked','lora_connection_error'] or ('ride' in e and e!='movement_cycle'):special.append(x)
  if e=='aiming_cycle':
   aim[x['cycleId']]=x
   if len(aim)>5:aim.pop(next(iter(aim)))
   states[x.get('state')]+=1
  if e!='movement_cycle':continue
  reasons[x.get('reason')]+=1; rideflags[str((x.get('riding'),x.get('rideAccepted'),x.get('rideEvent'),x.get('speedEvidence')))]+=1
  f=x.get('submittedForwardMps') or 0;t=x.get('submittedRightMps') or 0;v=math.hypot(f,t);active=v>1e-6
  if active:
   nonzero+=1;purpose[x.get('translationPurpose')]+=1
   for k in ['surferDistanceM','centralDistanceM','currentBoundaryDistanceM']:metric('moving_'+k,x.get(k))
   metric('speed',v)
   if x.get('translationPurpose') in ['approach','return'] and v>x['maxMovementSpeedMps']+1e-6:fail.append([x['timestamp'],'speed',v])
   a=aim.get(x['cycleId']);
   if a and x.get('currentBoundaryDistanceM') is not None and x['currentBoundaryDistanceM']< -1e-6:
    h=math.radians(a['aircraftHeadingDeg']);sea=math.radians(x['seawardBearingDeg']);normal=f*math.cos(h-sea)+t*math.sin(sea-h)
    if normal<=0:fail.append([x['timestamp'],'beachward command',normal])
   if a and x.get('translationPurpose')=='approach' and x.get('positioningMode')=='diagonal':
    sy=x['routeSurferLatitude'];sx=x['routeSurferLongitude'];fac=math.cos(math.radians(sy));R=6371000
    def xy(la,lo):return(math.radians(la-sy)*R,math.radians(lo-sx)*R*fac)
    y,z=xy(a['aircraftLatitude'],a['aircraftLongitude']);u,w=xy(x['routeWaypointLatitude'],x['routeWaypointLongitude']);dy,dz=u-y,w-z;den=dy*dy+dz*dz;q=max(0,min(1,-(y*dy+z*dz)/den)) if den else 0;c=math.hypot(y+q*dy,z+q*dz);metric('S0clearance',c)
    if not x.get('routeEscapeLeg') and c+1e-4<x['requiredPathClearanceMetres']:fail.append([x['timestamp'],'S0clearance',c])
  key=(x.get('phase'),x.get('reason'),x.get('journeyId'),x.get('routeWaypointIndex'),x.get('riding'),x.get('rideEvent'),x.get('returnReason'))
  if key!=lastkey:
   brief={k:x.get(k) for k in ['timestamp','phase','reason','journeyId','routeWaypointIndex','routeKind','routeDirection','riding','rideEvent','rideAccepted','speedKmh','headingErrorDeg','surferDistanceM','currentBoundaryDistanceM','approachRemainingM','returnTargetRemainingM','returnReason','pathBlockReason','routeRecoveryStatus','inactivityElapsedMs','failedSegmentBoundaryM','failedSegmentClearanceM','routeSurferLatitude','routeSurferLongitude','approachTargetLatitude','approachTargetLongitude','returnTargetLatitude','returnTargetLongitude']};brief.update({'end':x['timestamp'],'cycles':1});runs.append(brief);lastkey=key
  else:runs[-1]['end']=x['timestamp'];runs[-1]['cycles']+=1
  j=x.get('journeyId')
  if j and x.get('phase')=='APPROACHING':
   z=routes.setdefault(str(j),{'start':x['timestamp'],'kind':x.get('routeKind'),'waypoints':len(x.get('routeWaypoints') or []),'length':x.get('plannedTravelM'),'commands':0,'speedSum':0,'end':x['timestamp']});z['end']=x['timestamp'];z['commands']+=active;z['speedSum']+=v
 result=dict(file=p.name,first=first,last=last,events=dict(events),states=dict(states),reasons=dict(reasons),commands=dict(purpose),nonzero=nonzero,rideFlags=dict(rideflags),routes=routes,outcomes=outcomes,special=special,configs=configs,ranges=ranges,runs=runs,parseErrors=errors,failures=fail);results.append(result)
 print(json.dumps({k:result[k] for k in ['file','states','reasons','commands','ranges','parseErrors','failures']})[:7000],flush=True)
(out/'analysis.json').write_text(json.dumps(results,indent=2));print('DONE',flush=True)
