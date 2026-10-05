import zipfile,json,collections
z=zipfile.ZipFile(r'C:/Users/gildo/temp/vt404test/cam3_full_2026-10-05_220638_442_05e26c81.zip')
for name in sorted(z.namelist()):
 rows=[json.loads(l) for l in z.read(name).splitlines()]; cfg=[r for r in rows if r['event']=='vt40_configuration']; m=[r for r in rows if r['event']=='movement_cycle' and r.get('positioningMode')=='diagonal'];
 if not cfg:continue
 print('\n',name)
 for c in cfg:
  j=json.loads(c['effectiveSettingsJson']);print('cfg',c['timestamp'],{k:j.get(k) for k in ['positioningAngleDegrees','filmingSeparationMetres','positioningAngleToleranceDegrees','extraPathClearanceMetres','maxExcursionMetres']})
 for r in rows:
  if r['event']=='retreat_settings_config': print('retreatcfg',{k:v for k,v in r.items() if k not in ['fileContents','record','monoMs','epochMs']})
 print('reasons',collections.Counter(r['reason'] for r in m))
 print('plans',[(r['timestamp'],r['routeKind'],r['routeDirection'],r['journeyKind'],r['surferDistanceM'],r['filmingEndpointDirectDistanceM'],len(r['routeWaypoints'])) for r in m if r['plannerEvent']=='fixed_route_planned'])
 print('arrivals',[(r['timestamp'],r['routeKind'],r['surferDistanceM']) for r in m if r['reason']=='fixed_destination_arrived'])
 print('route blocks',[(r['timestamp'],r['reason'],r['routeStatus'],r['failedSegmentClearanceM'],r['requiredPathClearanceMetres'],r['routeWaypointIndex']) for i,r in enumerate(m) if r['reason'].startswith('route_') and (i==0 or m[i-1]['reason']!=r['reason'])][:20])
 print('pauses',[(r['timestamp'],r.get('detail'),r.get('reason')) for r in rows if r['event'] in ['pause','stop','loop_exception']])
