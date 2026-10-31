import json,math,collections
from pathlib import Path
out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408');summaries=[]
def distance(a,b):
 la,lo=map(math.radians,a);lb,lob=map(math.radians,b);return 6371000*2*math.asin(min(1,math.sqrt(math.sin((la-lb)/2)**2+math.cos(la)*math.cos(lb)*math.sin((lo-lob)/2)**2)))
def segment(a,b,s):
 def xy(v):return (math.radians(v[0]-s[0])*6371000,math.radians(s[1]-v[1])*6371000*math.cos(math.radians(s[0])))
 x,y=xy(a);u,v=xy(b);dx,dy=u-x,v-y;den=dx*dx+dy*dy;t=0 if not den else max(0,min(1,-(x*dx+y*dy)/den));return math.hypot(x+t*dx,y+t*dy)
for path in sorted(Path('C:/Users/gildo/temp/408').glob('*.jsonl')):
 rows=list(map(json.loads,path.open(encoding='utf-8-sig')));aim={x['cycleId']:x for x in rows if x.get('event')=='aiming_cycle'};ms=[x for x in rows if x.get('event')=='movement_cycle']
 failures=[];metrics=collections.defaultdict(list);outcomes=[x for x in rows if x.get('event')=='journey_outcome'];flow=collections.Counter();commands=collections.Counter();veto=collections.Counter();returns=[]
 for m in ms:
  a=aim.get(m['cycleId'],{});f=m.get('submittedForwardMps') or 0;rt=m.get('submittedRightMps') or 0;speed=math.hypot(f,rt);active=speed>1e-6
  if m['reason'] in ['moving_saved_route_aiming_surfer','moving_to_restart_target'] and not active:veto[m['reason']]+=1
  if m['phase']=='RETURNING':returns.append(m)
  if not active:continue
  purpose=m['translationPurpose'];commands[purpose]+=1;metrics['commandSpeed'].append(speed);metrics['currentSurferDistance'].append(m['surferDistanceM'])
  d=(a['aircraftLatitude'],a['aircraftLongitude']);h=math.radians(a['aircraftHeadingDeg']);sea=math.radians(m['seawardBearingDeg']);normal=f*math.cos(h-sea)+rt*math.sin(sea-h);bound=m['currentBoundaryDistanceM']
  if bound is not None and bound< -1e-6:
   flow[purpose]+=1;metrics['beachwardDistance'].append(bound)
   if normal<=0:failures.append([m['timestamp'],'beachward command fails sea-side recovery',normal])
  if purpose in ['approach','return']:
   if speed>m['maxMovementSpeedMps']+1e-6:failures.append([m['timestamp'],'speed exceeds cap',speed])
   if m['centralDistanceM']>=m['excursionStopDistanceM']+1e-6:failures.append([m['timestamp'],'outside excursion',m['centralDistanceM']])
  if purpose=='approach' and m.get('positioningMode')=='diagonal':
   s=(m['routeSurferLatitude'],m['routeSurferLongitude']);w=(m['routeWaypointLatitude'],m['routeWaypointLongitude']);clear=segment(d,w,s);metrics['executionS0Clearance'].append(clear)
   if not m['routeEscapeLeg'] and clear+1e-4<m['requiredPathClearanceMetres']:failures.append([m['timestamp'],'S0 leg clearance',clear])
   if not m['routeEscapeLeg'] and m['surferDistanceM']<19-1e-5:failures.append([m['timestamp'],'positioning submitted with current surfer inside retreat threshold',m['surferDistanceM']])
  if purpose=='return':metrics['returnCurrentSurferDistance'].append(m['surferDistanceM'])
 result={'file':path.name,'version':rows[0].get('version'),'commands':dict(commands),'beachwardCommands':dict(flow),'nonzeroCommandSafetyFailures':failures,'zeroSubmissionWhileMoving':dict(veto),'ranges':{k:[min(v),max(v)] for k,v in metrics.items() if v},'journeyOutcomes':dict(collections.Counter(x['outcome']+':'+x['reason'] for x in outcomes)),'returnFirst':returns[0]['timestamp'] if returns else None,'returnLast':returns[-1]['timestamp'] if returns else None,'returnTargetCount':len(set((x['returnTargetLatitude'],x['returnTargetLongitude']) for x in returns)),'configurationBlocks':[x for x in rows if x['event']=='configuration_start_blocked']}
 summaries.append(result);print(json.dumps(result))
(out/'flight_audit.json').write_text(json.dumps(summaries,indent=2),encoding='utf-8')
