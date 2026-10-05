import zipfile,json,collections
z=zipfile.ZipFile('C:/Users/gildo/temp/v4 test 2.zip')
for name in z.namelist():
 if not name.endswith('.jsonl'):continue
 counts=collections.Counter();examples={};settings=[];events=[];plans=collections.Counter()
 for l in z.open(name):
  r=json.loads(l);e=r['event']
  if e=='vt40_configuration':settings.append((r['timestamp'],json.loads(r['effectiveSettingsJson'])))
  if e=='movement_cycle' and r.get('session',0)>0 and r.get('phase')!='OFF':
   counts[(r.get('reason'),r.get('pathBlockReason'))]+=1
   examples.setdefault(r.get('reason'),{k:r.get(k) for k in ['timestamp','projectedSeparationM','comeToMeAlignmentErrorM','surferDistanceM','filmingSideStatus','pathCheckStatus','pathBlockReason','retreatBoundaryStatus','retreatBoundarySeawardDistanceM','centralDistanceM']})
   if r.get('plannerEvent')=='fixed_destination_planned':plans[r.get('journeyKind')]+=1
  if e in ['stop','pause','loop_exception','retreat_boundary_blocked']:events.append({k:r.get(k) for k in ['timestamp','event','detail','reason']})
 print(name, '\nSETTINGS',json.dumps(settings),'\nCOUNTS',str(counts),'\nPLANS',str(plans),'\nEVENTS',json.dumps(events))
 if '060358' in name:print('FIRST EXAMPLES',json.dumps(examples))
