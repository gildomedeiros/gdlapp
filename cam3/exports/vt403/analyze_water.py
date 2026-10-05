import zipfile,json,collections,math
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip')
for name in sorted(z.namelist()):
 if not name.endswith('.jsonl'):continue
 events=collections.Counter();sessions={};errors=collections.Counter();stops=[];config=[];version=None;first=None;last=None
 for l in z.open(name):
  r=json.loads(l);e=r['event'];events[e]+=1;first=first or r['timestamp'];last=r['timestamp']
  if e=='session_start':version=r.get('version')
  if e=='vt40_configuration':
   cfg=json.loads(r['effectiveSettingsJson']);lib=json.loads(r['effectiveShorelinesJson']);shore=next(p for p in lib['shorelines'] if p['id']==cfg['shorelineId']);config.append(dict(time=r['timestamp'],nextSession=r.get('nextSession'),settings=cfg,shoreline=shore))
  if e=='retreat_settings_config':config.append(dict(retreat=json.loads(r['effectiveJson'])))
  if e in ['pause','stop','loop_exception']:stops.append({k:r.get(k) for k in ['timestamp','event','detail','reason']})
  if e=='movement_cycle' and r.get('session',0)>0 and r.get('phase')!='OFF':
   sid=r['session'];s=sessions.setdefault(sid,dict(reasons=collections.Counter(),plans=collections.Counter(),arrivals=0,rides=[],boundary=collections.Counter(),path=collections.Counter(),retreatStarts=0,retreatRepeats=0,maxSpeed=0,minDirect=1e9,minBoundary=1e9,anchors=set(),dest={},endpointChanges=0,blockedNonzero=0,rideMax=0,returnStarts=[],returnArrivals=[]))
   s['reasons'][r.get('reason')]+=1;s['boundary'][r.get('retreatBoundaryStatus')]+=1;s['path'][r.get('pathBlockReason')]+=1
   if r.get('plannerEvent')=='fixed_destination_planned':s['plans'][r.get('journeyKind')]+=1
   if r.get('reason')=='fixed_destination_arrived':s['arrivals']+=1
   if r.get('rideEvent')=='ride_started':s['rides'].append({k:r.get(k) for k in ['timestamp','speedKmh','rideRemainingMs','rideDurationMs']})
   s['rideMax']=max(s['rideMax'],r.get('speedKmh') or 0)
   if r.get('reason')=='return_arrival_tolerance':s['returnArrivals'].append({k:r.get(k) for k in ['timestamp','returnReason','returnCompletionCentralDistanceM']})
   if r.get('phase')=='RETURNING' and not s.get('returning'):s['returnStarts'].append({k:r.get(k) for k in ['timestamp','returnReason','returnPlannedTravelM']})
   s['returning']=r.get('phase')=='RETURNING'
   d=r.get('surferDistanceM');bd=r.get('retreatBoundarySeawardDistanceM');s['minDirect']=min(s['minDirect'],d) if d is not None else s['minDirect'];s['minBoundary']=min(s['minBoundary'],bd) if bd is not None else s['minBoundary']
   s['anchors'].add((r.get('retreatBoundaryLatitude'),r.get('retreatBoundaryLongitude')))
   f=r.get('submittedForwardMps');right=r.get('submittedRightMps')
   if f is not None:s['maxSpeed']=max(s['maxSpeed'],math.hypot(f,right or 0))
   if r.get('retreatBoundaryBlocked') and r.get('translationPurpose')=='retreat' and f not in [None,0]:s['blockedNonzero']+=1
   key=r.get('fixedDestinationFixTime');dest=(r.get('approachTargetLatitude'),r.get('approachTargetLongitude'))
   if key is not None and key>=0 and dest[0] is not None:
    s['endpointChanges']+=int(key in s['dest'] and s['dest'][key]!=dest);s['dest'][key]=dest
  if e in ['retreat_started','retreat_repeated'] and r.get('session') in sessions:sessions[r['session']]['retreatStarts' if e=='retreat_started' else 'retreatRepeats']+=1
 print('\nLOG',name,version,first,last);print('CONFIG',json.dumps(config));print('EVENT_COUNTS',json.dumps({k:v for k,v in events.items() if k in ['lora_packet','lora_gap','retreat_boundary_blocked','retreat_boundary_allowed','loop_exception','user_start','pause']}))
 for sid,s in sessions.items():
  del s['dest'];s['anchors']=list(s['anchors']);print('SESSION',sid,json.dumps(s))
 print('STOPS',json.dumps(stops))
