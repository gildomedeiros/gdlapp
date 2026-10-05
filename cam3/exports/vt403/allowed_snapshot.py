from pathlib import Path
import zipfile,json,math,xml.etree.ElementTree as ET
ns='http://www.opengis.net/kml/2.2';ET.register_namespace('',ns)
def add(p,n,t=None,**a):
 e=ET.SubElement(p,'{'+ns+'}'+n,a)
 if t is not None:e.text=str(t)
 return e
def pt(p,name,lat,lon,desc='',style='point'):
 q=add(p,'Placemark');add(q,'name',name);add(q,'description',desc);add(q,'styleUrl','#'+style);g=add(q,'Point');add(g,'coordinates',f'{lon},{lat},0')
def line(p,name,coords,style,desc=''):
 q=add(p,'Placemark');add(q,'name',name);add(q,'description',desc);add(q,'styleUrl','#'+style);g=add(q,'LineString');add(g,'tessellate',1);add(g,'coordinates',' '.join(f'{lon},{lat},0' for lat,lon in coords))
def shift(lat,lon,n,e):return lat+math.degrees(n/6371000),lon+math.degrees(e/(6371000*math.cos(math.radians(lat))))
z=zipfile.ZipFile('C:/Users/gildo/temp/v4 test 2.zip');name=next(n for n in z.namelist() if '_062100_' in n);rs=[json.loads(l) for l in z.open(name)]
m=min((r for r in rs if r['event']=='movement_cycle'),key=lambda r:abs(r['epochMs']-1791058913293));a=next(r for r in rs if r['event']=='aiming_cycle' and r['cycleId']==m['cycleId']);cfg=next(r for r in rs if r['event']=='vt40_configuration');settings=json.loads(cfg['effectiveSettingsJson']);profile=next(p for p in json.loads(cfg['effectiveShorelinesJson'])['shorelines'] if p['id']==settings['shorelineId'])
slat,slon=a['targetLatitude'],a['targetLongitude'];dlat,dlon=a['aircraftLatitude'],a['aircraftLongitude'];sea=math.radians(m['seawardBearingDeg']);sn,se=math.cos(sea),math.sin(sea)
# Right axis looking seaward: clockwise 90 degrees.
axisn,axise=-se,sn
plat,plon=shift(slat,slon,axisn*m['projectedSeparationM'],axise*m['projectedSeparationM'])
root=ET.Element('{'+ns+'}kml');doc=add(root,'Document');add(doc,'name','06:21:53.293 — Sideways-right ALLOWED alignment geometry');add(doc,'description','Requested frame 2026-10-04 06:21:53.293 Brisbane. Nearest log snapshot 06:21:53.293 (exact planning cycle). Actual GPS positions; computed P is the saved alignment-only destination, movement permitted. No movement is simulated. Ground-clamped geometry; altitude excluded.')
for key,col,width in [('direct','ffff6600',3),('projected','ff00ffff',5),('blocked','ff00aa00',5),('clearance','ff0000ff',3),('shore','ffffff00',3),('boundary','ff0000aa',2),('axis','ffaaaaaa',2),('point','ffffffff',2),('sea','ff00aa00',3)]:
 st=add(doc,'Style',id=key);ls=add(st,'LineStyle');add(ls,'color',col);add(ls,'width',width);ic=add(st,'IconStyle');add(ic,'color',col);add(ic,'scale',1.1)
pt(doc,'D — DRONE at this timestamp',dlat,dlon,'Actual aircraft GPS. Automatic translation zero; aiming active.','direct')
pt(doc,'S — SURFER at this timestamp',slat,slon,'Actual LoRa GPS; fresh.','projected')
pt(doc,'P — ALLOWED fixed alignment destination',plat,plon,'Computed destination: same perpendicular distance from shoreline as surfer, preserving current 20.75 m projected right-alongshore separation. Outside the 18 m retreat radius. VT started moving toward this fixed destination at 06:21:53.396.','blocked')
line(doc,'D–S: DIRECT horizontal distance 22.46 m',[(dlat,dlon),(slat,slon)],'direct')
line(doc,'S–P: PROJECTED right-alongshore separation 20.75 m',[(slat,slon),(plat,plon)],'projected','Projection of the drone–surfer displacement onto the selected alongshore axis. P is the foot of that projection and the allowed alignment-only destination.')
line(doc,'D–P: SHORE-NORMAL alignment correction 8.60 m — ALLOWED',[(dlat,dlon),(plat,plon)],'blocked','Removing this offset would reduce direct separation from 22.46 m to 20.75 m, above 18 m retreat clearance.')
for label,p1,p2,style in [('20.75 m projected alongshore',(slat,slon),(plat,plon),'projected'),('8.60 m alignment correction',(dlat,dlon),(plat,plon),'blocked'),('22.46 m direct',(dlat,dlon),(slat,slon),'direct')]:pt(doc,label,(p1[0]+p2[0])/2,(p1[1]+p2[1])/2,'',style)
line(doc,'18 m retreat clearance around surfer',[shift(slat,slon,18*math.cos(math.radians(i)),18*math.sin(math.radians(i))) for i in range(0,361,5)],'clearance','P lies outside this circle. The drone currently lies outside it.')
# Configured shoreline and projections to it. Distances are signed relative to the selected straight infinite line.
pa,pb=profile['pointA'],profile['pointB'];alat,alon=pa['latitude'],pa['longitude'];an,ae=se,-sn
line(doc,'Selected shoreline vc — extended straight line',[shift(alat,alon,-180*an,-180*ae),shift(alat,alon,180*an,180*ae)],'shore','Based on saved A/B; not a visually verified physical shore.')
line(doc,'Captured A–B',[(pa['latitude'],pa['longitude']),(pb['latitude'],pb['longitude'])],'shore')
for label,lat,lon in [('Surfer',slat,slon),('Drone',dlat,dlon)]:
 n=math.radians(lat-alat)*6371000;e=math.radians(lon-alon)*6371000*math.cos(math.radians(alat));distance=n*sn+e*se;foot=shift(lat,lon,-distance*sn,-distance*se)
 line(doc,f'{label}: {distance:.2f} m signed seaward from configured shoreline',[(lat,lon),foot],'axis','This is distinct from projected alongshore separation. Positive = configured seaward.')
blat,blon=m['retreatBoundaryLatitude'],m['retreatBoundaryLongitude'];line(doc,'Initial central retreat boundary — INACTIVE at this timestamp',[shift(blat,blon,-100*an,-100*ae),shift(blat,blon,100*an,100*ae)],'boundary','The retreat boundary does not constrain this Come-to-me journey.')
pt(doc,'Initial central',blat,blon,'','boundary');line(doc,'Configured seaward direction',[(alat,alon),shift(alat,alon,35*sn,35*se)],'sea')
look=add(doc,'LookAt');add(look,'longitude',(slon+dlon)/2);add(look,'latitude',(slat+dlat)/2);add(look,'altitude',0);add(look,'heading',0);add(look,'tilt',0);add(look,'range',180);add(look,'altitudeMode','clampToGround')
path=Path('C:/Users/gildo/gdlapp/cam3/exports/vt403/run_maps/VT-4.0.3-right-062153-allowed-projection.kml');ET.indent(root);ET.ElementTree(root).write(path,encoding='utf-8',xml_declaration=True);ET.parse(path)
# Verify constructed destination has the logged perpendicular alignment travel.
n=math.radians(plat-dlat)*6371000;e=math.radians(plon-dlon)*6371000*math.cos(math.radians(slat));assert abs(math.hypot(n,e)-abs(m['comeToMeAlignmentErrorM']))<.02
print(path.name,'XML and projected geometry verified')
