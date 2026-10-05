from pathlib import Path
import zipfile,json,math,datetime,xml.etree.ElementTree as ET
out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt403/run_maps');out.mkdir(parents=True,exist_ok=True)
NS='http://www.opengis.net/kml/2.2';GX='http://www.google.com/kml/ext/2.2'
ET.register_namespace('',NS);ET.register_namespace('gx',GX)
def add(p,n,t=None,**attrs):
 e=ET.SubElement(p,'{'+NS+'}'+n,attrs)
 if t is not None:e.text=str(t)
 return e
def point(p,name,lat,lon,desc='',style='event'):
 q=add(p,'Placemark');add(q,'name',name);add(q,'description',desc);add(q,'styleUrl','#'+style);g=add(q,'Point');add(g,'altitudeMode','clampToGround');add(g,'coordinates',f'{lon:.10f},{lat:.10f},0');return q
def line(p,name,coords,style,desc='',hidden=False):
 q=add(p,'Placemark');add(q,'name',name);add(q,'description',desc);add(q,'styleUrl','#'+style)
 if hidden:add(q,'visibility',0)
 g=add(q,'LineString');add(g,'tessellate',1);add(g,'altitudeMode','clampToGround');add(g,'coordinates',' '.join(f'{lon:.10f},{lat:.10f},0' for lat,lon in coords));return q
def shifted(lat,lon,n,e):return lat+math.degrees(n/6371000),lon+math.degrees(e/(6371000*math.cos(math.radians(lat))))
def bearing(a,b):
 lat1,lat2=map(math.radians,[a['latitude'],b['latitude']]);d=math.radians(b['longitude']-a['longitude']);return math.atan2(math.sin(d)*math.cos(lat2),math.cos(lat1)*math.sin(lat2)-math.sin(lat1)*math.cos(lat2)*math.cos(d))
def iso(ms):return datetime.datetime.fromtimestamp(ms/1000,datetime.timezone(datetime.timedelta(hours=10))).isoformat(timespec='milliseconds')
z=zipfile.ZipFile('C:/Users/gildo/temp/4.0.3.zip')
for filename in z.namelist():
 if not filename.endswith('.jsonl'):continue
 rows=[json.loads(l) for l in z.open(filename)]
 cfg=next(r for r in rows if r['event']=='vt40_configuration');settings=json.loads(cfg['effectiveSettingsJson']);lib=json.loads(cfg['effectiveShorelinesJson']);profile=next(s for s in lib['shorelines'] if s['id']==settings['shorelineId'])
 mode=settings['mode'];label='Front' if mode=='front' else 'Sideways left'
 started=cfg['epochMs'];stop=next((r['epochMs'] for r in rows if r['event']=='stop' and r['epochMs']>started),rows[-1]['epochMs'])
 active=[r for r in rows if started<=r['epochMs']<=stop and r.get('session')==1]
 aims={r['cycleId']:r for r in active if r['event']=='aiming_cycle'}
 movements=[r for r in active if r['event']=='movement_cycle'];anchor=next(r for r in movements if r.get('retreatBoundaryLatitude') is not None)
 alat,alon=anchor['retreatBoundaryLatitude'],anchor['retreatBoundaryLongitude'];angle=bearing(profile['pointA'],profile['pointB']);an,ae=math.cos(angle),math.sin(angle);sign=1 if profile['seaSide']=='leftOfAToB' else -1;sn,se=sign*ae,-sign*an
 root=ET.Element('{'+NS+'}kml');doc=add(root,'Document');add(doc,'name','VT 4.0.3 — '+label);add(doc,'description',f'Source: {Path(filename).name}\nActive session only: {iso(started)} to {iso(stop)}. Drone track blue; surfer yellow; captured shoreline cyan; initial-central retreat boundary red. Tracks are ground-clamped GPS positions, not aircraft altitude. Track gaps over 5 seconds are split. Shoreline orientation is the configured direction, not independently verified. Boundary line is drawn 300 m each side of central for display; the control boundary is infinite. Settings: '+json.dumps(settings))
 for key,col,width in [('drone','ffff6600',4),('surfer','ff00ffff',4),('shore','ffffff00',3),('boundary','ff0000ff',3),('sea','ff00aa00',3),('plan','ff888888',2),('event','ff00aaff',2),('central','ff0000ff',2)]:
  st=add(doc,'Style',id=key);ls=add(st,'LineStyle');add(ls,'color',col);add(ls,'width',width);ic=add(st,'IconStyle');add(ic,'color',col);add(ic,'scale',.8)
 setup=add(doc,'Folder');add(setup,'name','Shoreline and fixed retreat boundary')
 a,b=profile['pointA'],profile['pointB'];line(setup,'Selected shoreline '+profile['name'],[(a['latitude'],a['longitude']),(b['latitude'],b['longitude'])],'shore',profile['seaSide']);point(setup,'Shoreline A',a['latitude'],a['longitude']);point(setup,'Shoreline B',b['latitude'],b['longitude'])
 point(setup,'Initial central / boundary anchor',alat,alon,'Fixed throughout the session; not a land or obstacle measurement.','central')
 line(setup,'Retreat shoreward boundary',[shifted(alat,alon,-300*an,-300*ae),shifted(alat,alon,300*an,300*ae)],'boundary','Only retreat uses this boundary; Come-to-me and return are unchanged.')
 point(setup,'Configured SEAWARD side',*shifted(alat,alon,40*sn,40*se),'Configured sea bearing %.1f degrees'%((math.degrees(math.atan2(se,sn))+360)%360))
 line(setup,'Configured seaward direction',[(alat,alon),shifted(alat,alon,40*sn,40*se)],'sea')
 tracks=add(doc,'Folder');add(tracks,'name','GPS tracks — use time slider');stats={}
 for kind,latkey,lonkey,timekey,agekey in [('drone','aircraftLatitude','aircraftLongitude','aircraftFixMs','aircraftAgeMs'),('surfer','targetLatitude','targetLongitude','targetFixMs','targetAgeMs')]:
  samples=[];seen=set()
  for r in aims.values():
   lat,lon=r.get(latkey),r.get(lonkey);age=r.get(agekey);fix=r.get(timekey)
   if lat is None or lon is None or age is None or age>3000 or fix in seen:continue
   seen.add(fix);samples.append((r['epochMs']-age,lat,lon))
  samples.sort();segments=[]
  for sample in samples:
   if not segments or sample[0]-segments[-1][-1][0]>5000:segments.append([])
   segments[-1].append(sample)
  for i,segment in enumerate(segments):
   q=add(tracks,'Placemark');add(q,'name',kind.title()+' GPS track'+(f' — segment {i+1}' if len(segments)>1 else ''));add(q,'styleUrl','#'+kind)
   g=ET.SubElement(q,'{'+GX+'}Track');add(g,'altitudeMode','clampToGround')
   for ms,lat,lon in segment:add(g,'when',iso(ms))
   for ms,lat,lon in segment:ET.SubElement(g,'{'+GX+'}coord').text=f'{lon:.10f} {lat:.10f} 0'
  if samples:
   point(tracks,kind.title()+' start',samples[0][1],samples[0][2],iso(samples[0][0]),kind);point(tracks,kind.title()+' end',samples[-1][1],samples[-1][2],iso(samples[-1][0]),kind)
  stats[kind]=len(samples)
 events=add(doc,'Folder');add(events,'name','Key events — timestamps in Brisbane time')
 plans=add(doc,'Folder');add(plans,'name','Fixed planned journeys (optional)');add(plans,'visibility',0)
 for r in active:
  e=r['event'];name=None;lat=lon=None
  if e=='movement_cycle' and r.get('plannerEvent')=='fixed_destination_planned':
   lat,lon=r.get('approachTargetLatitude'),r.get('approachTargetLongitude');name='Fixed destination: '+str(r.get('journeyKind'))
   line(plans,r['timestamp'][11:19]+' '+str(r.get('journeyKind')),[(r['approachStartLatitude'],r['approachStartLongitude']),(lat,lon)],'plan','Saved once; not continuously retargeted.')
  elif e in ['retreat_started','retreat_boundary_blocked','retreat_boundary_allowed','retreat_approach_replaced']:
   lat,lon=r.get('aircraftLatitude'),r.get('aircraftLongitude');name=e
  elif e=='movement_cycle' and r.get('reason')=='fixed_destination_arrived':
   arow=aims.get(r['cycleId'],{});lat,lon=arow.get('aircraftLatitude'),arow.get('aircraftLongitude');name='Fixed destination arrived'
  if name and lat is not None and lon is not None:
   desc=json.dumps({k:r.get(k) for k in ['event','reason','projectedSeparationM','comeToMeAlignmentErrorM','pathBlockReason','boundaryStatus','boundarySeawardDistanceM','distanceM','retreatBoundarySeawardDistanceM']},indent=2)
   q=point(events,r['timestamp'][11:19]+' '+name,lat,lon,desc);ts=add(q,'TimeStamp');add(ts,'when',r['timestamp'])
 path=out/('VT-4.0.3-'+mode+('-left' if mode=='sideways' else '')+'-run.kml');ET.indent(root);ET.ElementTree(root).write(path,encoding='utf-8',xml_declaration=True)
 # Independent parse checks: namespaces, track times and valid geographical coordinates.
 parsed=ET.parse(path)
 for g in parsed.findall('.//{'+GX+'}Track'):
  times=g.findall('{'+NS+'}when');coords=g.findall('{'+GX+'}coord');assert len(times)==len(coords)>0
  for c in coords:
   lon,lat,alt=map(float,c.text.split());assert -180<=lon<=180 and -90<=lat<=90
 print(path.name,stats,'bytes',path.stat().st_size,'XML verified')
