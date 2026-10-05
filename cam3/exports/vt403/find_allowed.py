import zipfile,json
z=zipfile.ZipFile('C:/Users/gildo/temp/v4 test 2.zip');n=next(n for n in z.namelist() if '_062100_' in n)
rows=[json.loads(l) for l in z.open(n)]
for r in rows:
 if r['event']=='movement_cycle' and r.get('plannerEvent')=='fixed_destination_planned':
  print(json.dumps({k:r.get(k) for k in ['epochMs','timestamp','journeyKind','projectedSeparationM','comeToMeAlignmentErrorM','pathCheckStatus','approachTargetLatitude','approachTargetLongitude']}))
  if r.get('journeyKind')=='alignment_only_preserve_projection':
   for q in rows:
    if q['event']=='movement_cycle' and q.get('fixedDestinationFixTime')==r.get('fixedDestinationFixTime') and q.get('submittedForwardMps') is not None and abs(q.get('submittedForwardMps') or 0)+abs(q.get('submittedRightMps') or 0)>0:
     print('MOVING',q['timestamp'],q['submittedForwardMps'],q['submittedRightMps']);break
   break
