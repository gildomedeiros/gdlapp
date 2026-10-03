import json,math,collections
from pathlib import Path
rr=json.loads(Path('exports/VT38_2026-10-03_analysis.json').read_text())
def t(r):return r['timestamp'][11:23]
def pick(r,keys):return {k:r.get(k) for k in keys.split()}
def dist(a,b):
 if any(a.get(k) is None or b.get(k) is None for k in ['aircraftLatitude','aircraftLongitude']):return None
 la,lo,lb,ln=map(math.radians,[a['aircraftLatitude'],a['aircraftLongitude'],b['aircraftLatitude'],b['aircraftLongitude']])
 return round(6371000*2*math.asin(min(1,math.sqrt(math.sin((lb-la)/2)**2+math.cos(la)*math.cos(lb)*math.sin((ln-lo)/2)**2))),2)
for r in rr:
 print('\nRUN',Path(r['file']).name,r['header']['timestamp'],r['last']['timestamp'])
 if not r['counts'].get('user_start'):continue
 print('SCHEMA',json.dumps(r['schemas'].get('movement_cycle',{})))
 print('CONFIG',json.dumps(r['configs']))
 print('CONTROL',json.dumps([e for e in r['events'] if e['event'] in ['configuration_start_blocked','state','stop','pause','resumed','recovery_cancelled','movement_settings']]))
 print('RETREATS')
 starts=[]
 for e in r['retreatEvents']:
  if e['event'] in ['retreat_started','retreat_repeated']:starts.append(e)
  if e['event'] in ['retreat_finished','retreat_cancelled']:
   for s,n in zip(starts,starts[1:]+[e]):
    print(json.dumps(dict(start=t(s),end=t(n),duration=round((n['monoMs']-s['monoMs'])/1000,3),event=s['event'],period=s.get('period'),fresh=s.get('gpsFresh'),age=s.get('gpsAgeMs'),count=s.get('startsWithoutFreshGps'),limit=s.get('maxStartsWithoutFreshGps'),startM=s.get('distanceM'),endM=n.get('distanceM'),droneDisplacementM=dist(s,n),endReason=n.get('reason'))))
   starts=[]
  if e['event'] in ['retreat_gps_allowance_reset','retreat_start_blocked']:print(json.dumps(e))
 print('STALEAUDIT',len(r['retreatAudit']['staleStarts']),len(r['retreatAudit']['overLimit']))
 print('NAV',len(r['approaches']))
 for s in r['approaches']:
  print(json.dumps(dict(startTime=t(s['start']),end=t(s['end']) if s['end'] else None,phase=s['start']['phase'],start=pick(s['start'],'timestamp surferDistanceM filmingDistanceM plannedTravelM approachRemainingM returnRemainingM forwardProgressM returnProgressM reason yawPurpose'),last=pick(s['last'],'timestamp surferDistanceM plannedTravelM approachRemainingM returnRemainingM forwardProgressM returnProgressM submittedForwardMps reason'),endReason=s['end'].get('reason') if s['end'] else None)))
 print('RIDES',json.dumps([pick(e,'timestamp rideEvent speedKmh rideRemainingMs') for e in r['rides']]))
