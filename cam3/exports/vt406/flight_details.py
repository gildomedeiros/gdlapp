import json,collections
from pathlib import Path
rep=json.loads(Path('exports/vt406/flight-2026-10-07/results.json').read_text());print('MIN PLANNED',min(r['min_clearance_m'] for r in rep['routes']));print('ROUTE KINDS',dict(collections.Counter(r['kind'] for r in rep['routes'])))
for name in ['cam3_full_2026-10-07_080514_499_042b2a4f.jsonl','cam3_full_2026-10-07_090028_041_a2dcd7d5.jsonl']:
 rows=[json.loads(x) for x in (Path('C:/Users/gildo/temp')/name).read_text().splitlines()];print('\nFILE',name)
 print('EVENTS',dict(collections.Counter(r['event'] for r in rows)))
 for r in rows:
  if r['event']=='vt40_configuration':
   c=json.loads(r['effectiveSettingsJson']);print('CONFIG',r['timestamp'],r.get('nextSession'),c)
  elif r['event']=='retreat_settings_config':print('RETREAT CONFIG',r)
  elif r['event']=='journey_outcome':print('OUTCOME',r['timestamp'],r['journeyId'],r['outcome'],r['reason'],r['elapsedMs'],r['lastBlockReason'])
  elif r['event'] in ['stop','stop_request','input_event','pause','loop_exception','retreat_started','retreat_start_blocked','retreat_boundary_blocked','retreat_cancelled','log_overflow'] or 'error' in r['event'] or 'failed' in r['event']:print('EVENT',r)
 ms=[r for r in rows if r['event']=='movement_cycle'];prev=None;shown=collections.Counter()
 for m in ms:
  if m['reason']!=prev and m['reason'] in ['destination_inside_planning_clearance','return_alignment','moving_backward','return_arrival_tolerance','start_beachward_of_boundary']:
   print('STATE',{k:m.get(k) for k in ['timestamp','session','phase','reason','surferDistanceM','filmingDistanceM','requiredPlanningClearanceMetres','failedSegmentClearanceM','failedSegmentBoundaryM','routeRecoveryStatus','lastRouteRecoveryResult','routeRecoveryAttempts','journeyId','inactivityElapsedMs','attemptElapsedMs']})
  prev=m['reason']
