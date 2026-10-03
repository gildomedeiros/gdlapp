import json,csv,collections,statistics,math
from pathlib import Path
def stats(v):
 v=sorted(x for x in v if isinstance(x,(int,float)) and math.isfinite(x))
 return {k:round(x,2) for k,x in zip(['min','median','p90','max'],[v[0],statistics.median(v),v[min(len(v)-1,math.ceil(len(v)*.9)-1)],v[-1]])} if v else {}
data=json.loads(Path('exports/VT38_2026-10-03_analysis.json').read_text())
periods=[];details=[]
def t(r):return r['timestamp'][11:23]
def distance(a,b):
 la,lo,lb,ln=map(math.radians,[a['aircraftLatitude'],a['aircraftLongitude'],b['aircraftLatitude'],b['aircraftLongitude']])
 return 6371000*2*math.asin(min(1,math.sqrt(math.sin((lb-la)/2)**2+math.cos(la)*math.cos(lb)*math.sin((ln-lo)/2)**2)))
for res in data:
 if not res['counts'].get('retreat_started'):continue
 rows=[json.loads(l) for l in open(res['file'],encoding='utf-8-sig')]
 ev=lambda e:[r for r in rows if r['event']==e]
 a=[r for r in ev('aiming_cycle') if r['state']=='AIMING'];begin=a[0]['monoMs'];end=a[-1]['monoMs'];pack=[r for r in ev('lora_packet') if begin<=r['monoMs']<=end]
 gaps=[dict(start=t(x),end=t(y),seconds=round((y['monoMs']-x['monoMs'])/1000,3)) for x,y in zip(pack,pack[1:]) if y['monoMs']-x['monoMs']>3000]
 mov=[r for r in ev('movement_cycle') if r['cycleId']==a[0]['cycleId']][0]
 cfg={r['event']:json.loads(r.get('effectiveJson') or r.get('fileContents')) for r in res['configs'] if r['event'] in ['retreat_settings_config','rotation_speed_config','gimbal_band_config']}
 d=dict(file=Path(res['file']).name,activeStart=t(a[0]),activeEnd=t(a[-1]),settings={k:mov[k] for k in ['filmingDistanceM','maxMovementSpeedMps','reapproachMarginM','lineupWidthM','rideStartKmh','rideDurationMs','noRideTimeoutMs']},configs=cfg,gapsOver3s=gaps,activePacketGapsMs=stats([y['monoMs']-x['monoMs'] for x,y in zip(pack,pack[1:])]),surferYaw=res['yawGroups']['surfer'],retainedSeconds=sum(max(0,n['monoMs']-r['monoMs'])/1000 for r,n in zip(a,a[1:]) if r.get('retainedTargetAiming')),gimbal=dict(commands=len(ev('gimbal_pitch_command')),reached=len(ev('gimbal_pitch_reached')),results=dict(collections.Counter(r.get('result') for r in ev('gimbal_pitch_result'))),reachMs=stats([r.get('elapsedMs') for r in ev('gimbal_pitch_reached')])),rides=dict(collections.Counter(r.get('rideEvent') for r in res['rides'])),navOutcomes=dict(collections.Counter(s['end'].get('reason') if s['end'] else 'unfinished' for s in res['approaches'])),returns=[s for s in res['approaches'] if s['start']['phase']=='RETURNING'])
 pending=[]
 for r in rows:
  if r['event'] in ['retreat_started','retreat_repeated']:pending.append(r)
  if r['event'] in ['retreat_finished','retreat_cancelled']:
   for s,n in zip(pending,pending[1:]+[r]):
    tr=[q for q in ev('retreat_cycle') if s['cycleId']<=q['cycleId']<n['cycleId'] and q['active']]
    ac=[q for q in a if s['monoMs']<=q['monoMs']<n['monoMs']]
    periods.append(dict(file=d['file'],start=t(s),end=t(n),event=s['event'],period=s['period'],durationSeconds=round((n['monoMs']-s['monoMs'])/1000,3),gpsFresh=s['gpsFresh'],gpsAgeMs=s['gpsAgeMs'],staleStarts=s['startsWithoutFreshGps'],limit=s['maxStartsWithoutFreshGps'],startSeparationM=round(s['distanceM'],2),endSeparationM=round(n['distanceM'],2),netDroneDisplacementM=round(distance(s,n),2),endReason=n['reason'],desiredSpeedMps=s['speedMps'],submittedValues=str(dict(collections.Counter(str(q.get('submittedForwardMps')) for q in tr))),observedSpeed=stats([q.get('horizontalSpeedMps') for q in ac]),staleCycles=sum(not q.get('gpsFresh') for q in tr)))
   pending=[]
 details.append(d)
Path('exports/VT38_details.json').write_text(json.dumps(details,indent=2))
with open('exports/VT38_retreat_periods.csv','w',newline='') as f:
 w=csv.DictWriter(f,fieldnames=periods[0].keys());w.writeheader();w.writerows(periods)
for d in details:print(json.dumps(d))
print('PERIOD TOTAL',len(periods),'DURATION',stats([p['durationSeconds'] for p in periods]),'DISPLACEMENT',stats([p['netDroneDisplacementM'] for p in periods]),'SUBMITTED',collections.Counter(p['submittedValues'] for p in periods))
