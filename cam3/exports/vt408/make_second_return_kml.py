from pathlib import Path
import json,math,xml.etree.ElementTree as E
src=Path('C:/Users/gildo/temp/408/cam3_full_2026-10-08_095211_213_da352ea1.jsonl')
rows=list(map(json.loads,src.open(encoding='utf-8-sig')))
ms=[x for x in rows if x['event']=='movement_cycle'];aa={x['cycleId']:x for x in rows if x['event']=='aiming_cycle'}
i=next(i for i,x in enumerate(ms) if x['phase']=='RETURNING');j=next(j for j in range(i,len(ms)) if ms[j]['reason']=='return_arrival_tolerance')
before,start,done=ms[i-1],ms[i],ms[j]
def drone(m):a=aa[m['cycleId']];return a['aircraftLatitude'],a['aircraftLongitude']
central=(start['centralLatitude'],start['centralLongitude']);anchor=(start['retreatBoundaryLatitude'],start['retreatBoundaryLongitude'])
d0,d1=drone(before),drone(done);target=(start['returnTargetLatitude'],start['returnTargetLongitude'])
R=6371000
def point(p,n,e):return p[0]+math.degrees(n/R),p[1]+math.degrees(e/(R*math.cos(math.radians(p[0]))))
def distance(a,b):
 n=math.radians(b[0]-a[0])*R;e=math.radians(b[1]-a[1])*R*math.cos(math.radians(a[0]));return math.hypot(n,e)
assert abs(distance(d1,target)-done['returnTargetRemainingM'])<.001
ns='http://www.opengis.net/kml/2.2';E.register_namespace('',ns)
def node(p,n,text=None):x=E.SubElement(p,'{'+ns+'}'+n);x.text=text;return x
root=E.Element('{'+ns+'}kml');doc=node(root,'Document');node(doc,'name','VT 4.0.8 — second soft return, 09:56:20–09:56:37')
node(doc,'description','Three primary pins: saved central, last reported drone position before VT return, and reported drone position at completion. Coordinates are from supplied logs. This is VT soft return, not aircraft RTH. White line is reported GPS track; it includes drift/GPS variation during yaw alignment. Optional context shows original boundary, 10 m stand-off line and saved return target.')
for name,color,scale in [('central','ff0000ff',1.15),('before','ff00ffff',1),('after','ff00ff00',1),('track','ffffffff',1),('target','ffff00ff',.8),('boundary','ff0000ff',1),('standOff','ffffff00',1)]:
 s=node(doc,'Style');s.set('id',name);icon=node(s,'IconStyle');node(icon,'color',color);node(icon,'scale',str(scale));node(node(icon,'Icon'),'href','https://maps.google.com/mapfiles/kml/paddle/wht-blank.png');line=node(s,'LineStyle');node(line,'color',color);node(line,'width','3');label=node(s,'LabelStyle');node(label,'scale','0.85')
def coords(points):return ' '.join(f'{p[1]:.12f},{p[0]:.12f},0' for p in points)
def pm(parent,name,style,description):p=node(parent,'Placemark');node(p,'name',name);node(p,'styleUrl','#'+style);node(p,'description',description);return p
def mark(parent,name,style,p,description):m=pm(parent,name,style,description);q=node(m,'Point');node(q,'altitudeMode','clampToGround');node(q,'coordinates',coords([p]));return m
def line(parent,name,style,points,description):m=pm(parent,name,style,description);q=node(m,'LineString');node(q,'tessellate','1');node(q,'altitudeMode','clampToGround');node(q,'coordinates',coords(points))
mark(doc,'CENTRAL — saved position','central',central,'Original central position and boundary anchor. Latitude %.12f, longitude %.12f. Return target is on the 10 m sea-side stand-off line, not at this pin.'%central)
mark(doc,'BEFORE return — 09:56:20.073','before',d0,f'Last movement cycle before return begins (cycle {before["cycleId"]}). Actual reported drone position. {distance(d0,central):.3f} m from central; {before["currentBoundaryDistanceM"]:.3f} m sea-side of boundary. Latitude {d0[0]:.12f}, longitude {d0[1]:.12f}.')
mark(doc,'AFTER return complete — 09:56:37.540','after',d1,f'Completion cycle {done["cycleId"]}: return_arrival_tolerance. Actual reported drone position, not target coordinates. {distance(d1,central):.3f} m from central; {done["currentBoundaryDistanceM"]:.3f} m sea-side of boundary. {done["returnTargetRemainingM"]:.3f} m from saved return target. Latitude {d1[0]:.12f}, longitude {d1[1]:.12f}.')
track=[]
for m in ms[i-1:j+1]:
 p=drone(m)
 if not track or p!=track[-1]:track.append(p)
line(doc,'Reported drone track during return','track',track,'Logged GPS positions, including initial yaw alignment. No route or position has been invented. Start and finish pins are only %.3f m apart; zoom closely to separate them.'%distance(d0,d1))
context=node(doc,'Folder');node(context,'name','Optional boundary and saved return target');node(context,'visibility','0')
b=math.radians(start['seawardBearingDeg']);sea=(math.cos(b),math.sin(b));along=(-sea[1],sea[0])
line(context,'Original central boundary','boundary',[point(anchor,along[0]*v,along[1]*v) for v in [-40,40]],'Boundary through original central, parallel to selected wave line.')
base=point(anchor,sea[0]*10,sea[1]*10)
line(context,'10 m sea-side stand-off line','standOff',[point(base,along[0]*v,along[1]*v) for v in [-40,40]],'Perpendicular boundary distance 10 m; this does not mean 10 m from the central pin.')
mark(context,'Saved return target — 10 m sea-side','target',target,f'Fixed target saved at {start["timestamp"]}. Planned starting distance {start["returnTargetRemainingM"]:.3f} m. Completion happens within existing 1 m arrival tolerance.')
look=node(doc,'LookAt');node(look,'latitude',str((central[0]+d0[0])/2));node(look,'longitude',str((central[1]+d0[1])/2));node(look,'range','110');node(look,'tilt','0');node(look,'heading','0')
dest=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408/VT-408-second-return-095620.kml')
E.indent(root);E.ElementTree(root).write(dest,encoding='utf-8',xml_declaration=True)
tree=E.parse(dest);assert len(tree.findall('.//{'+ns+'}Point'))==4
for x in tree.findall('.//{'+ns+'}coordinates'):
 for raw in x.text.split():
  lon,lat,alt=map(float,raw.split(','));assert -180<=lon<=180 and -90<=lat<=90
print(json.dumps({'kml':str(dest),'beforeAfterSeparationM':distance(d0,d1),'beforeCentralDistanceM':distance(d0,central),'afterCentralDistanceM':distance(d1,central),'afterTargetRemainingM':done['returnTargetRemainingM'],'reportedTrackSamples':len(track)},indent=2))
