import json,zipfile,collections
from pathlib import Path
rep=json.loads(Path('exports/vt405/water-test/results.json').read_text());print('TOTAL OUTCOMES',dict(collections.Counter(r['outcome']+':'+r['reason'] for r in rep['outcomes'])))
z=zipfile.ZipFile(rep['archive'])
for name in sorted(z.namelist()):
 rows=[json.loads(x) for x in z.read(name).splitlines() if x.strip()];moves=[r for r in rows if r['event']=='movement_cycle' and r.get('positioningMode')=='diagonal'];aa={(r.get('session'),r.get('cycleId')):r for r in rows if r['event']=='aiming_cycle'}
 print('\nFILE',name)
 for r in rows:
  if r['event']=='journey_outcome':print('OUTCOME',r['timestamp'],r['journeyId'],r['outcome'],r['reason'],r['elapsedMs'],r['lastBlockReason'])
 if '183437' in name or '182241' in name:
  shown=collections.Counter()
  for m in moves:
   if m['plannerEvent'] in ['fixed_route_planned','fixed_route_replanned'] or m['routeStatus']=='blocked' and shown[m['reason']]<2 or m['reason']=='moving_saved_route_aiming_surfer' and shown['moving']<1:
    if m['reason']=='moving_saved_route_aiming_surfer':shown['moving']+=1
    shown[m['reason']]+=1
    a=aa.get((m['session'],m['cycleId']),{})
    print('DETAIL',{k:m.get(k) for k in ['timestamp','cycleId','phase','reason','journeyId','routeStatus','routeWaypointIndex','routeRecoveryAttempts','routeRecoveryStatus','failedSegmentClearanceM','failedSegmentBoundaryM','retreatBoundarySeawardDistanceM','submittedForwardMps','submittedRightMps','paused','capturedSurferDirectDistanceM','surferDistanceM','plannerEvent']})
  pauses=collections.Counter(r.get('detail') for r in rows if r['event']=='pause');print('PAUSES',dict(pauses))
for r in rep['routes']:
 if r['replan']:print('REPLAN',r)
