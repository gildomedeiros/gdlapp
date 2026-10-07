from pathlib import Path
import json,math,xml.etree.ElementTree as E
rows=[json.loads(s) for s in Path('C:/Users/gildo/temp/vt 407/cam3_full_2026-10-08_065204_691_e7bc5a17.jsonl').read_text(encoding='utf-8-sig').splitlines()]
m=next(r for r in rows if r.get('event')=='movement_cycle' and r.get('reason')=='destination_central_boundary')
a=next(r for r in rows if r.get('event')=='aiming_cycle' and r.get('cycleId')==m['cycleId'])
c=next(r for r in rows if r.get('event')=='vt40_configuration')
settings=json.loads(c['effectiveSettingsJson']);library=json.loads(c['effectiveWaveLinesJson'])
profile=next(p for p in library['waveLines'] if p['id']==settings['waveLineId'])
R=6371000
def point(lat,lon,n,e):return lat+math.degrees(n/R),((lon+math.degrees(e/(R*math.cos(math.radians(lat))))+540)%360)-180
def offset(lat,lon,origin):return math.radians(lat-origin[0])*R,math.radians(lon-origin[1])*R*math.cos(math.radians(origin[0]))
origin=(m['retreatBoundaryLatitude'],m['retreatBoundaryLongitude'])
drone=(a['aircraftLatitude'],a['aircraftLongitude']);surfer=(a['targetLatitude'],a['targetLongitude'])
b=math.radians(m['seawardBearingDeg']);sea=(math.cos(b),math.sin(b));along=(-sea[1],sea[0])
angle=math.radians(settings['positioningAngleDegrees']);radius=settings['filmingSeparationMetres']
axis=(-sea[0]*math.cos(angle)-sea[1]*math.sin(angle),-sea[1]*math.cos(angle)+sea[0]*math.sin(angle))
target=point(*surfer,axis[0]*radius,axis[1]*radius)
def boundary_distance(p):n,e=offset(*p,origin);return n*sea[0]+e*sea[1]
assert abs(boundary_distance(target)-m['failedSegmentBoundaryM'])<.002
ns='http://www.opengis.net/kml/2.2';E.register_namespace('',ns)
def node(parent,tag,text=None):x=E.SubElement(parent,'{'+ns+'}'+tag);x.text=text;return x
root=E.Element('{'+ns+'}kml');doc=node(root,'Document');node(doc,'name','VT 4.0.7 — initial hold 06:52:08')
node(doc,'description','Measured snapshot at 2026-10-08 06:52:08.911 Australia/Brisbane. Boundary through original central, parallel to selected Wave line. Requested destination is blocked; connection lines are explanatory, not flown routes.')
styles={'boundary':'ff0000ff','wave':'ffffff00','drone':'ff00ffff','surfer':'ff00ff00','target':'ffff00ff','measure':'ffffffff','sea':'ff00aa00'}
for name,color in styles.items():
 st=node(doc,'Style');st.set('id',name);ls=node(st,'LineStyle');node(ls,'color',color);node(ls,'width','4' if name in ['boundary','wave'] else '2')
 icon=node(st,'IconStyle');node(icon,'color',color);node(icon,'scale','1.1');i=node(icon,'Icon');node(i,'href','https://maps.google.com/mapfiles/kml/paddle/wht-blank.png')
def placemark(name,style,description):p=node(doc,'Placemark');node(p,'name',name);node(p,'description',description);node(p,'styleUrl','#'+style);return p
def coords(points):return ' '.join(f'{lon:.12f},{lat:.12f},0' for lat,lon in points)
def mark(name,style,p,description):pm=placemark(name,style,description);g=node(pm,'Point');node(g,'altitudeMode','clampToGround');node(g,'coordinates',coords([p]))
def line(name,style,points,description):pm=placemark(name,style,description);g=node(pm,'LineString');node(g,'tessellate','1');node(g,'altitudeMode','clampToGround');node(g,'coordinates',coords(points))
boundary=[point(*origin,along[0]*d,along[1]*d) for d in [-130,130]]
line('Central boundary — red','boundary',boundary,'Original central boundary. Permitted side is indicated by the green sea-side arrow. It is not the physical shoreline.')
pa=(profile['pointA']['latitude'],profile['pointA']['longitude']);pb=(profile['pointB']['latitude'],profile['pointB']['longitude'])
line('Wave line / shoreline reference — ovrob2','wave',[pa,pb],'Actual configured A/B segment, not a surveyed beach edge. Selected seaSide: leftOfAToB.')
mark('Wave line A','wave',pa,'Saved point A');mark('Wave line B','wave',pb,'Saved point B')
mark('Drone / original central — boundary 0 m','drone',drone,'Actual reported drone position at initial hold. Central captured here; current surfer distance 61.00 m.')
mark('Surfer — 17.43 m sea-side; 61.00 m from drone','surfer',surfer,'Actual received surfer position at this snapshot. Along-boundary displacement from central is about 58.46 m.')
mark('Requested destination — BLOCKED, 3.78 m beachward','target',target,'Calculated −45° / 30 m filming destination. Surfer 17.43 m sea-side minus 21.21 m shoreward component gives −3.78 m. This is a requested point, not a reached position.')
line('Drone to surfer — 61.00 m measurement','measure',[drone,surfer],'Distance reference only; not a planned or flown route.')
line('Surfer to requested destination — 30 m at −45°','target',[surfer,target],'Configured filming offset; not a movement route.')
foot=point(*surfer,-boundary_distance(surfer)*sea[0],-boundary_distance(surfer)*sea[1])
line('Surfer to boundary — 17.43 m','surfer',[surfer,foot],'Perpendicular distance to original central boundary.')
foot_target=point(*target,-boundary_distance(target)*sea[0],-boundary_distance(target)*sea[1])
line('Blocked destination to boundary — 3.78 m','target',[target,foot_target],'Perpendicular beachward distance.')
arrow_start=point(*origin,along[0]*-90,along[1]*-90);arrow_end=point(*arrow_start,sea[0]*30,sea[1]*30)
line('Permitted sea-side direction','sea',[arrow_start,arrow_end],'Direction comes from saved Wave line orientation, independent of satellite imagery or actual water.')
mark('SEA SIDE — permitted','sea',arrow_end,'Positive boundary side')
look=node(doc,'LookAt');node(look,'longitude',str(origin[1]));node(look,'latitude',str(origin[0]));node(look,'range','350');node(look,'tilt','0');node(look,'heading','0')
out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt407/VT-407-initial-hold-065208.kml');E.indent(root);E.ElementTree(root).write(out,encoding='utf-8',xml_declaration=True)
E.parse(out)
print(str(out));print(f'Verified requested destination boundary distance {boundary_distance(target):.3f} m; {len(doc.findall("{"+ns+"}Placemark"))} placemarks')
