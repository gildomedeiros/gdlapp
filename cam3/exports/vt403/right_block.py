import zipfile,json,collections
z=zipfile.ZipFile('C:/Users/gildo/temp/v4 test 2.zip');n=next(n for n in z.namelist() if '_061049_' in n)
rs=[json.loads(l) for l in z.open(n)];prev=None
for r in rs:
 if r['event']=='movement_cycle' and r.get('session')==1 and r.get('phase')!='OFF':
  key=(r.get('reason'),r.get('pathBlockReason'),r.get('retreatBoundaryStatus'))
  if key!=prev:
   print(json.dumps({k:r.get(k) for k in ['timestamp','reason','projectedSeparationM','comeToMeAlignmentErrorM','surferDistanceM','pathBlockReason','retreatBoundaryStatus','retreatBoundarySeawardDistanceM','submittedForwardMps','submittedRightMps']}));prev=key
cfg=next(r for r in rs if r['event']=='retreat_settings_config');print('RETREAT',cfg['effectiveJson'])
m=[r for r in rs if r['event']=='movement_cycle' and r.get('reason')=='alignment_path_or_destination_blocked'];print('BLOCKED projected range',min(r['projectedSeparationM'] for r in m),max(r['projectedSeparationM'] for r in m))
b=[r for r in rs if r['event']=='movement_cycle' and r.get('retreatBoundaryBlocked')];print('boundary blocked nonzero submitted',sum(r.get('submittedForwardMps') not in [None,0] for r in b))
