import zipfile,json,math,collections,csv
from pathlib import Path
Z=Path('exports/vt406/flight-2026-10-07.zip');out=Path('exports/vt406/flight-2026-10-07');out.mkdir(exist_ok=True)
with zipfile.ZipFile(Z,'w',compression=zipfile.ZIP_DEFLATED) as staging:
 for input_name in ['cam3_full_2026-10-07_080514_499_042b2a4f.jsonl','cam3_full_2026-10-07_090028_041_a2dcd7d5.jsonl']:
  staging.write(Path('C:/Users/gildo/temp')/input_name,input_name)
R=6371000;counts=collections.Counter();fails=[];summaries=[];routes=[];episodes=[];outcomes=[]
def offset(p,c):return math.radians(p[0]-c[0])*R,math.radians((p[1]-c[1]+180)%360-180)*R*math.cos(math.radians(c[0]))
def dist(p,c):
 a,b=map(math.radians,p);x,y=map(math.radians,c);return 2*R*math.asin(min(1,math.sqrt(math.sin((a-x)/2)**2+math.cos(a)*math.cos(x)*math.sin((b-y)/2)**2)))
def seg(a,b,c):
 n,e=offset(a,c);bn,be=offset(b,c);dn,de=bn-n,be-e;den=dn*dn+de*de;t=max(0,min(1,-(n*dn+e*de)/den)) if den else 0;return math.hypot(n+t*dn,e+t*de)
def boundary(p,anchor,bearing):
 n,e=offset(p,anchor);a=math.radians(bearing);return n*math.cos(a)+e*math.sin(a)
def test(name,ok,detail):
 counts[name]+=1
 if not ok:fails.append({'check':name,**detail})
with zipfile.ZipFile(Z) as z:
 for filename in sorted(z.namelist()):
  if not filename.endswith('.jsonl'):continue
  rows=[]
  for i,line in enumerate(z.read(filename).splitlines(),1):
   if not line.strip():continue
   try:rows.append(json.loads(line));counts['valid_json']+=1
   except Exception as e:fails.append({'check':'valid_json','file':filename,'line':i,'error':str(e)})
  for r in rows:
   if r['event']=='session_start':test('runtime_version',r.get('version')=='4.0.6',{'file':filename})
  aims={(r.get('session'),r.get('cycleId')):r for r in rows if r['event']=='aiming_cycle'}
  moves=[r for r in rows if r['event']=='movement_cycle' and r.get('positioningMode')=='diagonal'];configs=[r for r in rows if r['event']=='vt40_configuration'];cfg=json.loads(configs[-1]['effectiveSettingsJson']) if configs else None
  retreatcfg=[r for r in rows if r['event']=='retreat_settings_config'];retreatcycles=[r for r in rows if r['event']=='retreat_cycle'];threshold=next((r.get('minimumDistanceM') for r in retreatcycles if r.get('enabled')),None)
  first={};plans=0;repairs=0;nonzero=0;duration=collections.Counter();maxattempt=0
  os=[r for r in rows if r['event']=='journey_outcome'];outcomes.extend({'file':filename,**r} for r in os)
  seen=set()
  for o in os:
   key=(o['session'],o['journeyId']);test('one_outcome_per_journey',key not in seen,{'file':filename,'time':o['timestamp']});seen.add(key)
  for r in retreatcycles:
   if r.get('active') and r.get('submittedForwardMps') is not None:
    f=r['submittedForwardMps'];test('retreat_command_envelope',f<=1e-6 and abs(f)<=r['speedMps']+1e-6 and (not r.get('boundaryBlocked') or abs(f)<1e-6),{'file':filename,'time':r['timestamp']})
  for i,m in enumerate(moves):
   detail={'file':filename,'time':m['timestamp'],'cycle':m['cycleId']};a=aims.get((m['session'],m['cycleId']))
   dt=(moves[i+1]['epochMs']-m['epochMs'])/1000 if i+1<len(moves) else 0
   if 0<=dt<=1 and not m.get('paused'):duration[m['reason']]+=dt
   test('planning_allowance_config',(cfg is None or m['routePlanningAllowanceMetres']==cfg.get('routePlanningAllowanceMetres',2)) and m['requiredPlanningClearanceMetres']==(m['requiredPathClearanceMetres']+m['routePlanningAllowanceMetres'] if m['requiredPathClearanceMetres']>0 else 0),detail)
   if m['reason']=='moving_saved_route_aiming_surfer':test('resumed_status_cleared',m['routeRecoveryStatus']=='none' and m['failedSegmentIndex']==-1 and m['failedSegmentBoundaryM'] is None,detail)
   test('diagonal_fields',m['projectedSeparationM'] is None and m['comeToMeAlignmentErrorM'] is None and m['distanceMeaning']=='direct_horizontal',detail)
   test('zero_extra_buffer',m['extraPathClearanceMetres']==0 and (threshold is None or m['requiredPathClearanceMetres']==threshold),detail)
   f=m.get('submittedForwardMps');rr=m.get('submittedRightMps');speed=math.hypot(f,rr) if f is not None and rr is not None else 0
   if m['translationPurpose']!='retreat':test('positioning_speed_cap',speed<=(cfg['maxMovementSpeedMetresPerSecond'] if cfg else 4)+1e-6,detail)
   if m['routeStatus'] in ['blocked','replanned'] and m['translationPurpose']!='retreat':test('blocked_or_replan_neutral',speed<1e-8,detail)
   if m.get('routeWaypoints') and m['fixedDestinationFixTime']>0:
    key=(m['session'],m['journeyId']);frozen=(m['routeSurferLatitude'],m['routeSurferLongitude'],m['approachTargetLatitude'],m['approachTargetLongitude'],m['fixedDestinationFixTime'])
    if key in first:test('snapshot_destination_frozen',first[key]==frozen,detail)
    else:first[key]=frozen
   if m.get('routeSurferLatitude') is None or not a:continue
   P=(a['aircraftLatitude'],a['aircraftLongitude']);C=(m['routeSurferLatitude'],m['routeSurferLongitude']);E=(m['approachTargetLatitude'],m['approachTargetLongitude']);anchor=(m['retreatBoundaryLatitude'],m['retreatBoundaryLongitude']);central=(m['centralLatitude'],m['centralLongitude']);bearing=m['seawardBearingDeg'];radius=m['requiredPathClearanceMetres']
   if m.get('capturedSurferDirectDistanceM') is not None:test('captured_distance_label',abs(dist(P,C)-m['capturedSurferDirectDistanceM'])<.02,detail)
   if m['plannerEvent'] in ['fixed_route_planned','fixed_route_replanned']:
    initial=m['plannerEvent']=='fixed_route_planned';plans+=initial;repairs+=not initial
    if initial:
     targetradius=m['filmingDistanceM'] if m['journeyKind']=='direct_distance_reapproach' else m['surferDistanceM'];test('endpoint_radius',abs(dist(E,C)-targetradius)<.03,detail)
     n,e=offset(E,C);b=math.radians(bearing);angle=math.degrees(math.atan2(-n*math.sin(b)+e*math.cos(b),-n*math.cos(b)-e*math.sin(b)));test('endpoint_angle',abs((angle-m['positioningAngleDegrees']+180)%360-180)<.01,detail)
    minimum=1e9;bminimum=1e9;exmax=0
    for j,wp in enumerate(m['routeWaypoints']):
     wp=tuple(wp);cl=seg(P,wp,C);bd=min(boundary(P,anchor,bearing),boundary(wp,anchor,bearing));ex=max(dist(P,central),dist(wp,central));minimum=min(minimum,cl);bminimum=min(bminimum,bd);exmax=max(exmax,ex)
     if m['routeEscapeLeg'] and j==0:
      n,e=offset(P,C);wn,we=offset(wp,C);test('escape_outward',(wn-n)*n+(we-e)*e>=-1e-4 and dist(wp,C)>dist(P,C),detail)
     else:test('planned_leg_clearance',cl>=m['requiredPlanningClearanceMetres']-.001,detail)
     test('planned_leg_boundary',bd>=-.001,detail);test('planned_leg_excursion',ex<=m['excursionStopDistanceM']+.001,detail);P=wp
    routes.append({'file':filename,'time':m['timestamp'],'journeyId':m['journeyId'],'replan':not initial,'kind':m['routeKind'],'direction':m['routeDirection'],'waypoints':len(m['routeWaypoints']),'min_clearance_m':minimum,'min_boundary_m':bminimum,'max_excursion_m':exmax,'attempt_ms':m['attemptElapsedMs']})
   if m['translationPurpose']=='approach' and speed>1e-8:
    nonzero+=1;P=(a['aircraftLatitude'],a['aircraftLongitude']);wp=(m['routeWaypointLatitude'],m['routeWaypointLongitude']);cl=seg(P,wp,C)
    if m['routeEscapeLeg']:
     n,e=offset(P,C);wn,we=offset(wp,C);test('observed_escape_outward',n*(wn-n)+e*(we-e)>=-.02,detail)
    else:test('observed_active_leg_clearance',cl>=radius-.02,detail)
    test('observed_active_boundary',boundary(P,anchor,bearing)>=-.02,detail)
   if m['routeStatus']=='blocked' and (i==0 or moves[i-1]['reason']!=m['reason']):episodes.append({'file':filename,'time':m['timestamp'],'reason':m['reason'],'journeyId':m['journeyId'],'waypoint':m['routeWaypointIndex']+1,'attempt_ms':m['attemptElapsedMs'],'clearance':m['failedSegmentClearanceM'],'boundary':m['failedSegmentBoundaryM'],'recovery':m['routeRecoveryStatus'],'recoveryAttempts':m['routeRecoveryAttempts']})
   maxattempt=max(maxattempt,m['attemptElapsedMs'])
  summaries.append({'file':filename,'rows':len(rows),'angle':cfg['positioningAngleDegrees'] if cfg else None,'plans':plans,'replans':repairs,'outcomes':dict(collections.Counter(r['outcome']+':'+r['reason'] for r in os)),'submitted_positioning_cycles':nonzero,'max_recovery_attempts':max((m['routeRecoveryAttempts'] for m in moves),default=0),'active_reason_seconds':dict(duration),'max_attempt_ms':maxattempt,'retreat_starts':sum(r['event']=='retreat_started' for r in rows),'pause_reasons':dict(collections.Counter(r.get('detail',r.get('reason')) for r in rows if r['event']=='pause'))})
report={'archive':str(Z),'checks':dict(counts),'failures':fails,'sessions':summaries,'routes':routes,'block_episodes':episodes,'outcomes':outcomes}
(out/'results.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
for name,data in [('routes.csv',routes),('blocks.csv',episodes)]:
 with (out/name).open('w',newline='',encoding='utf-8') as f:
  fields=list(dict.fromkeys(k for d in data for k in d));w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(data)
print('CHECKS',dict(counts),'FAILURES',len(fails));print('SAMPLES',json.dumps(fails[:12],indent=2));print('SESSIONS',json.dumps(summaries,indent=2));print('ROUTES',json.dumps(routes,indent=2));print('BLOCKS',json.dumps(episodes,indent=2))


