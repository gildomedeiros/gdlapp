import zipfile,json,math,csv
from pathlib import Path
z=zipfile.ZipFile(r'C:/Users/gildo/temp/vt404test/cam3_full_2026-10-05_220638_442_05e26c81.zip');rows=[json.loads(x) for x in z.read('cam3_full_2026-10-05_220208_476_1f1cb1c6.jsonl').splitlines()]; aa={(r.get('session'),r.get('cycleId')):r for r in rows if r['event']=='aiming_cycle'}
R=6371000
def off(p,c):return math.radians(p[0]-c[0])*R,math.radians(p[1]-c[1])*R*math.cos(math.radians(c[0]))
def dist(p,c):n,e=off(p,c);return math.hypot(n,e)
def seg(p,w,c):n,e=off(p,c);a,b=off(w,c);dn,de=a-n,b-e;d=dn*dn+de*de;t=max(0,min(1,-(n*dn+e*de)/d)) if d else 0;return math.hypot(n+t*dn,e+t*de)
result=[]
for m in rows:
 if m['event']!='movement_cycle' or m.get('positioningMode')!='diagonal' or m['cycleId'] not in [436,437,438,484,485,486,755,1342,1343]:continue
 a=aa[(m['session'],m['cycleId'])];p=(a['aircraftLatitude'],a['aircraftLongitude']);c=(m.get('routeSurferLatitude'),m.get('routeSurferLongitude'));w=(m.get('routeWaypointLatitude'),m.get('routeWaypointLongitude'));bearing=math.radians(m['seawardBearingDeg']);angle=None;ds=None;cl=None;wd=None
 if c[0] is not None:
  n,e=off(p,c);angle=math.degrees(math.atan2(-n*math.sin(bearing)+e*math.cos(bearing),-n*math.cos(bearing)-e*math.sin(bearing)));ds=dist(p,c);cl=seg(p,w,c);wd=dist(p,w)
 f=m.get('submittedForwardMps');r=m.get('submittedRightMps');speed=None if f is None or r is None else math.hypot(f,r)
 d={'time':m['timestamp'][11:23],'cycle':m['cycleId'],'elapsed_s':round(m['attemptElapsedMs']/1000,3),'status':m['reason'],'direct_to_current_surfer_m':m['surferDistanceM'],'direct_to_snapshot_m':ds,'angle_to_snapshot_deg':angle,'endpoint_radius_m':m['filmingEndpointDirectDistanceM'],'leg_min_direct_to_snapshot_m':cl,'distance_to_waypoint_m':wd,'waypoint':None if c[0] is None else m['routeWaypointIndex']+1,'boundary_seaward_m':m['retreatBoundarySeawardDistanceM'],'submitted_speed_mps':speed,'retreat_active':m['retreatActive']};result.append(d)
print(json.dumps(result,indent=2))
with open('exports/vt404/stuck_route_timeline.csv','w',newline='') as f:
 wr=csv.DictWriter(f,fieldnames=list(result[0]));wr.writeheader();wr.writerows(result)
