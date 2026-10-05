import zipfile,json,math,collections,csv
from pathlib import Path
Z=Path(r'C:/Users/gildo/temp/vt404test/cam3_full_2026-10-05_220638_442_05e26c81.zip'); out=Path('exports/vt404'); R=6371000
checks=collections.Counter(); failures=[]; sessions=[]; plansout=[]; blocks=[];totalrows=0

def offset(p,c):return (math.radians(p[0]-c[0])*R,math.radians((p[1]-c[1]+180)%360-180)*R*math.cos(math.radians(c[0])))
def distance(p,c):
 a,b=map(math.radians,p); x,y=map(math.radians,c);v=math.sin((a-x)/2)**2+math.cos(a)*math.cos(x)*math.sin((b-y)/2)**2
 return R*2*math.asin(min(1,math.sqrt(v)))
def segment(a,b,c):
 an,ae=offset(a,c);bn,be=offset(b,c);n,e=bn-an,be-ae;den=n*n+e*e;t=max(0,min(1,-(an*n+ae*e)/den)) if den else 0
 return math.hypot(an+t*n,ae+t*e)
def test(name,ok,detail):
 checks[name]+=1
 if not ok:failures.append({'check':name,**detail})
def boundary(p,anchor,bearing):
 n,e=offset(p,anchor);a=math.radians(bearing);return n*math.cos(a)+e*math.sin(a)
with zipfile.ZipFile(Z) as z:
 for filename in sorted(z.namelist()):
  rows=[]
  for i,line in enumerate(z.read(filename).splitlines(),1):
   try:rows.append(json.loads(line));checks['valid_json']+=1
   except Exception as ex:failures.append({'check':'valid_json','file':filename,'line':i,'error':str(ex)})
  totalrows+=len(rows)
  cfg=[r for r in rows if r['event']=='vt40_configuration']
  if not cfg:continue
  for r in rows:
   if r['event']=='retreat_cycle' and r.get('enabled') and r.get('active') and r.get('submittedForwardMps') is not None:
    f=r['submittedForwardMps'];test('active_retreat_command',f<=1e-6 and abs(f)<=r['speedMps']+1e-6 and (not r.get('boundaryBlocked') or abs(f)<=1e-6),{'file':filename,'time':r['timestamp']})
  setting=json.loads(cfg[-1]['effectiveSettingsJson']); moves=[r for r in rows if r['event']=='movement_cycle' and r.get('positioningMode')=='diagonal']
  aiming={(r.get('session'),r.get('cycleId')):r for r in rows if r['event']=='aiming_cycle'}
  plans=[r for r in moves if r['plannerEvent']=='fixed_route_planned']; arrivals=[r for r in moves if r['reason']=='fixed_destination_arrived']; nonzero=0; versions=set(r['version'] for r in rows if r['event']=='session_start'); firsts={}; lasttime=None; durations=collections.Counter();minplanned=1e9;minboundary=1e9;maxexcursion=0
  for i,m in enumerate(moves):
   detail={'file':filename,'time':m['timestamp'],'cycle':m['cycleId']};key=(m['session'],m['cycleId']);a=aiming.get(key)
   test('excursion_config',m['maxExcursionM']==300 and m['excursionStopDistanceM']==299,detail)
   test('distance_label',m['distanceMeaning']=='direct_horizontal' and m['projectedSeparationM'] is None,detail)
   dt=(moves[i+1]['epochMs']-m['epochMs'])/1000 if i+1<len(moves) else 0
   if 0<=dt<=1:durations[m['reason']]+=dt
   f=m.get('submittedForwardMps');r=m.get('submittedRightMps')
   if f is not None and r is not None and m['translationPurpose']!='retreat':test('positioning_command_cap',math.hypot(f,r)<=4+1e-6,detail)
   if m['reason'].startswith(('route_','destination_','no_valid_','start_')) and m['translationPurpose']!='retreat':
    test('blocked_positioning_zero',f in (None,0) and r in (None,0),detail)
   if m.get('routeWaypoints') and m['fixedDestinationFixTime']>0:
    k=(m['session'],m['fixedDestinationFixTime']);frozen=(m['routeSurferLatitude'],m['routeSurferLongitude'],m['approachTargetLatitude'],m['approachTargetLongitude'],json.dumps(m['routeWaypoints']))
    if k in firsts:test('snapshot_frozen',firsts[k]==frozen,detail)
    else:firsts[k]=frozen
   if m['plannerEvent']=='fixed_route_planned':
    C=(m['routeSurferLatitude'],m['routeSurferLongitude']);E=(m['approachTargetLatitude'],m['approachTargetLongitude']);P=(m['approachStartLatitude'],m['approachStartLongitude']);anchor=(m['retreatBoundaryLatitude'],m['retreatBoundaryLongitude']);central=(m['centralLatitude'],m['centralLongitude']);bearing=m['seawardBearingDeg'];radius=m['requiredPathClearanceMetres'];targetradius=m['filmingDistanceM'] if m['journeyKind']=='direct_distance_reapproach' else m['surferDistanceM']
    test('endpoint_radius',abs(distance(E,C)-targetradius)<.03,detail)
    n,e=offset(E,C);sea=math.radians(bearing);angle=math.degrees(math.atan2(-n*math.sin(sea)+e*math.cos(sea),-n*math.cos(sea)-e*math.sin(sea)));err=(angle-m['positioningAngleDegrees']+180)%360-180
    test('endpoint_angle',abs(err)<.01,detail)
    close=m['surferDistanceM']>m['approachStartThresholdM']+1e-6
    test('planning_trigger',close==(m['journeyKind']=='direct_distance_reapproach') and (close or abs(m['positioningAngleErrorDegrees'])>m['positioningAngleToleranceDegrees']+1e-6),detail)
    minimum=1e9;bd=1e9;ex=0
    for wp in m['routeWaypoints']:
     wp=tuple(wp);clear=segment(P,wp,C);bmin=min(boundary(P,anchor,bearing),boundary(wp,anchor,bearing));exc=max(distance(P,central),distance(wp,central));minimum=min(minimum,clear);bd=min(bd,bmin);ex=max(ex,exc)
     test('planned_segment_clearance',clear>=radius-1e-4,detail);test('planned_segment_boundary',bmin>=-1e-4,detail);test('planned_segment_excursion',exc<=299+.001,detail);P=wp
    minplanned=min(minplanned,minimum);minboundary=min(minboundary,bd);maxexcursion=max(maxexcursion,ex)
    plansout.append({'file':filename,'time':m['timestamp'],'angle':m['positioningAngleDegrees'],'kind':m['routeKind'],'journey':m['journeyKind'],'start_distance':m['surferDistanceM'],'endpoint_radius':distance(E,C),'waypoints':len(m['routeWaypoints']),'min_path_clearance':minimum,'min_boundary':bd,'max_excursion':ex})
   if m['translationPurpose']=='approach' and f is not None and r is not None and math.hypot(f,r)>1e-8:
    nonzero+=1
    if a and a.get('aircraftLatitude') is not None:
     P=(a['aircraftLatitude'],a['aircraftLongitude']);C=(m['routeSurferLatitude'],m['routeSurferLongitude']);wp=(m['routeWaypointLatitude'],m['routeWaypointLongitude']);anchor=(m['retreatBoundaryLatitude'],m['retreatBoundaryLongitude']);bd=boundary(P,anchor,m['seawardBearingDeg']);cl=segment(P,wp,C)
     test('observed_active_segment_clearance',cl>=m['requiredPathClearanceMetres']-.02,detail);test('observed_active_boundary',bd>=-.02,detail)
   if m['reason'].startswith('route_') and (i==0 or moves[i-1]['reason']!=m['reason']):
    b={'file':filename,'time':m['timestamp'],'reason':m['reason'],'leg':m['routeWaypointIndex'],'clearance':m['failedSegmentClearanceM'],'boundary':m['failedSegmentBoundaryM'],'live_distance':m['surferDistanceM']}
    if a and m.get('routeSurferLatitude') is not None:
     P=(a['aircraftLatitude'],a['aircraftLongitude']);C=(m['routeSurferLatitude'],m['routeSurferLongitude']);b['drone_distance_from_snapshot']=distance(P,C)
    blocks.append(b)
  sessions.append({'file':filename,'angle':setting['positioningAngleDegrees'],'plans':len(plans),'arrivals':len(arrivals),'submitted_positioning_cycles':nonzero,'clearance_block_seconds':round(durations['route_surfer_clearance'],1),'boundary_block_seconds':round(durations['route_central_boundary'],1),'destination_block_seconds':round(durations['destination_central_boundary'],1),'timeout_seconds':round(durations['movement_timeout'],1),'minimum_planned_path_clearance':minplanned,'minimum_planned_boundary_distance':minboundary,'maximum_planned_excursion':maxexcursion,'log_version':sorted(versions)})
report={'archive':str(Z),'rows':totalrows,'checks':dict(checks),'failures':failures,'sessions':sessions,'plans':plansout,'block_episodes':blocks}
(out/'water_test_results.json').write_text(json.dumps(report,indent=2))
for name,data in [('water_test_routes.csv',plansout),('water_test_blocks.csv',blocks)]:
 with (out/name).open('w',newline='') as f:
  fields=list(dict.fromkeys(k for d in data for k in d));w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(data)
print('rows',totalrows,'checks',dict(checks),'failures',len(failures));print(json.dumps(sessions,indent=2));print('failure samples',json.dumps(failures[:10],indent=2));print('blocks',json.dumps(blocks,indent=2))

import sys
sys.exit(1 if failures else 0)
