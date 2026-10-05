from pathlib import Path
p=Path('exports/vt403/make_kml.py');s=p.read_text()
s=s.replace("C:/Users/gildo/temp/4.0.3.zip","C:/Users/gildo/temp/v4 test 2.zip")
s=s.replace("if not filename.endswith('.jsonl'):continue","if '_061049_' not in filename:continue")
s=s.replace("else 'Sideways left'","else 'Sideways right'")
s=s.replace("('event','ff00aaff',2)","('blocked','ff0000ff',6),('event','ff00aaff',2)")
s=s.replace("('-left' if mode=='sideways' else '')","('-right-061109' if mode=='sideways' else '')")
marker=" path=out/"
insert=''' blockedFolder=add(doc,'Folder');add(blockedFolder,'name','RED — automatic movement blocked')
 blocks=[];current=None
 for r in movements:
  if r.get('phase')=='OFF':continue
  cause='wrong_filming_side' if r.get('reason')=='wrong_filming_side_hold' else r.get('pathBlockReason') if r.get('pathCheckStatus')=='blocked' else 'central_retreat_boundary' if r.get('retreatBoundaryBlocked') else None
  ar=aims.get(r['cycleId'],{});lat,lon=ar.get('aircraftLatitude'),ar.get('aircraftLongitude')
  if not cause or lat is None or lon is None:
   current=None;continue
  if current is None or current['cause']!=cause:
   current={'cause':cause,'rows':[]};blocks.append(current)
  current['rows'].append((r,lat,lon))
 for block in blocks:
  first,lat,lon=block['rows'][0];last=block['rows'][-1][0]
  description=f"Blocked from {first['timestamp']} to {last['timestamp']}. Reason: {block['cause']}. This marks an automatic movement guard, not lost aiming."
  point(blockedFolder,first['timestamp'][11:19]+' BLOCKED: '+block['cause'],lat,lon,description,'blocked')
  coords=[(x[1],x[2]) for x in block['rows']]
  if len(coords)>1:line(blockedFolder,'Blocked '+first['timestamp'][11:19]+'–'+last['timestamp'][11:19],coords,'blocked',description)
 frame=min(movements,key=lambda r:abs(r['epochMs']-1791058369824));ar=aims[frame['cycleId']]
 point(blockedFolder,'06:12:49.824 — supplied frame',ar['aircraftLatitude'],ar['aircraftLongitude'],f"Nearest log: {frame['timestamp']}. Block: {frame['pathBlockReason']}. Projected right-alongshore separation {frame['projectedSeparationM']:.2f} m; shore-normal alignment error {frame['comeToMeAlignmentErrorM']:.2f} m; direct horizontal distance {frame['surferDistanceM']:.2f} m. Alignment would preserve the projected separation and end inside the 18 m retreat radius.",'blocked')
'''
s=s.replace(marker,insert+marker)
Path('exports/vt403/make_right_kml.py').write_text(s)
