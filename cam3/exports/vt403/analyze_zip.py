import zipfile,json,collections,math
z=zipfile.ZipFile('C:/Users/gildo/temp/4.0.3.zip')
for name in z.namelist():
 if not name.endswith('.jsonl'):continue
 events=collections.Counter(); reasons=collections.Counter(); paths=collections.Counter(); bounds=collections.Counter(); plans=collections.Counter(); starts=[]; transitions=[]; stops=[]; example={}; maxspeed=0; active=0; anchors=set(); minbd=1e9; movingblocked=0; inwardbeyond=0; arrivals=0; rides=0
 for line in z.open(name):
  r=json.loads(line);e=r['event'];events[e]+=1
  if e=='session_start':print('\nLOG',name,'version',r.get('version'))
  if e=='vt40_configuration': print('EFFECTIVE',r['timestamp'],r.get('effectiveSettingsJson'))
  if e=='retreat_settings_config':print('RETREAT',r.get('effectiveJson'))
  if e in ['stop','pause','user_start','retreat_boundary_blocked','retreat_boundary_allowed','retreat_started','retreat_repeated']:
   if e.startswith('retreat_boundary'):transitions.append({k:r.get(k) for k in ['timestamp','event','reason','boundarySeawardDistanceM','boundaryStatus','headingDeg','distanceM']})
   elif e=='retreat_started':starts.append({k:r.get(k) for k in ['timestamp','gpsFresh','startDistanceM','boundarySeawardDistanceM']})
   else:stops.append({k:r.get(k) for k in ['timestamp','event','detail','reason']})
  if e=='movement_cycle' and r.get('session',0)>0:
   active+=1;reasons[r.get('reason')]+=1;paths[(r.get('filmingSideStatus'),r.get('pathCheckStatus'),r.get('pathBlockReason'))]+=1;bounds[r.get('retreatBoundaryStatus')]+=1
   anchors.add((r.get('retreatBoundaryLatitude'),r.get('retreatBoundaryLongitude')))
   d=r.get('retreatBoundarySeawardDistanceM');minbd=min(minbd,d) if d is not None else minbd
   if r.get('plannerEvent')=='fixed_destination_planned':plans[r.get('journeyKind')]+=1
   if r.get('reason')=='fixed_destination_arrived':arrivals+=1
   if r.get('rideEvent')=='ride_started':rides+=1
   f=r.get('submittedForwardMps');right=r.get('submittedRightMps');
   if f is not None:maxspeed=max(maxspeed,math.hypot(f,right or 0))
   if r.get('retreatBoundaryBlocked') and r.get('translationPurpose')=='retreat' and f is not None and f!=0:movingblocked+=1
   key=r.get('reason');example.setdefault(key,{k:r.get(k) for k in ['timestamp','reason','projectedSeparationM','comeToMeAlignmentErrorM','filmingSideStatus','pathCheckStatus','pathBlockReason','retreatBoundaryStatus','retreatBoundarySeawardDistanceM','submittedForwardMps','submittedRightMps']})
 print('SUMMARY',json.dumps(dict(activeCycles=active,events={k:v for k,v in events.items() if k in ['retreat_started','retreat_repeated','retreat_approach_replaced','retreat_boundary_blocked','retreat_boundary_allowed','lora_gap','lora_packet','loop_exception']},reasons=dict(reasons),paths={str(k):v for k,v in paths.items()},boundary=dict(bounds),plans=dict(plans),arrivals=arrivals,rides=rides,anchors=list(anchors),minBoundaryDistance=minbd,maxSubmittedSpeed=maxspeed,blockedRetreatNonzero=movingblocked)))
 print('TRANSITIONS',json.dumps(transitions));print('RETREAT_STARTS',json.dumps(starts));print('STOPS',json.dumps(stops));print('EXAMPLES',json.dumps(example))
