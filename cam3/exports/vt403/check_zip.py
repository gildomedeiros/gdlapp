import zipfile,json,math
z=zipfile.ZipFile('C:/Users/gildo/temp/4.0.3.zip')
for n in z.namelist():
 if not n.endswith('.jsonl'):continue
 destinations={};changes=0;examples=[];mind=1e9;blockedfirst=None;blockedlast=None;minb=1e9;bad=0
 for line in z.open(n):
  r=json.loads(line)
  if r['event']=='movement_cycle' and r.get('session')==1:
   if r.get('surferDistanceM') is not None:mind=min(mind,r['surferDistanceM'])
   k=r.get('fixedDestinationFixTime');t=(r.get('approachTargetLatitude'),r.get('approachTargetLongitude'))
   if k is not None and k>=0 and t[0] is not None:
    changes+=int(k in destinations and destinations[k]!=t);destinations[k]=t
   d=r.get('retreatBoundarySeawardDistanceM')
   if d is not None:minb=min(minb,d)
   if r.get('translationPurpose')=='retreat' and r.get('submittedForwardMps') is not None:
    f=r['submittedForwardMps'];h=math.radians(r.get('approachHeadingDeg') or 0)
    if r.get('retreatBoundaryBlocked') and f!=0:bad+=1
   if r.get('plannerEvent')=='fixed_destination_planned' and r.get('journeyKind')=='projected_reapproach':examples.append({k:r.get(k) for k in ['timestamp','projectedSeparationM','comeToMeAlignmentErrorM','plannedTravelM']})
  if r['event']=='aiming_cycle' and r.get('submitted') and r.get('state')=='AIMING':
   pass
 print(n,'endpointChanges',changes,'minimumDirect',mind,'minimumBoundary',minb,'badBlockedCommands',bad,'reapproaches',examples)
 # Show actual commands after boundary release and first sideways interruption
 rs=[json.loads(l) for l in z.open(n)]
 for time in (['22:36:43','22:38:00'] if '223229' in n else ['22:42:28']):
  r=next((r for r in rs if r['event']=='movement_cycle' and time in r['timestamp'] and r.get('submittedForwardMps') is not None and r.get('submittedForwardMps')!=0),None)
  if r:print('COMMAND', {k:r.get(k) for k in ['timestamp','reason','retreatBoundaryStatus','retreatBoundarySeawardDistanceM','submittedForwardMps','submittedRightMps']})
 for r in rs:
  if r['event'] in ['retreat_approach_replaced','loop_exception']:print('EVENT',{k:r.get(k) for k in ['timestamp','event','reason','startDistanceM']})
