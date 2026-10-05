import json,math,pathlib,xml.etree.ElementTree as E
r=json.loads(pathlib.Path('exports/vt403/snapshot100416_data.json').read_text());ns='http://www.opengis.net/kml/2.2';E.register_namespace('',ns)
def tag(p,n,v=None):
 q=E.SubElement(p,'{'+ns+'}'+n)
 if v is not None:q.text=str(v)
 return q
root=E.Element('{'+ns+'}kml');doc=tag(root,'Document');tag(doc,'name','VT sideways-left - 10:04:16.487')
for name,col in [('shore','ffffff00'),('drone','ffff8000'),('surfer','ff00ffff'),('heading','ff00ff00'),('bearing','ff00ffff')]:
 s=tag(doc,'Style');s.set('id',name);ls=tag(s,'LineStyle');tag(ls,'color',col);tag(ls,'width',3);ic=tag(s,'IconStyle');tag(ic,'color',col);tag(ic,'scale',1.2)
def pm(name,style,description=''):
 p=tag(doc,'Placemark');tag(p,'name',name);tag(p,'styleUrl','#'+style);tag(p,'description',description);return p
def point(name,lat,lon,style,desc=''):
 p=pm(name,style,desc);g=tag(p,'Point');tag(g,'coordinates',f'{lon},{lat},0')
def line(name,coords,style,desc=''):
 p=pm(name,style,desc);g=tag(p,'LineString');tag(g,'tessellate',1);tag(g,'coordinates',' '.join(f'{lon},{lat},0' for lat,lon in coords))
def offset(lat,lon,bearing,metres):
 b=math.radians(bearing);return lat+math.degrees(metres*math.cos(b)/6371000),lon+math.degrees(metres*math.sin(b)/(6371000*math.cos(math.radians(lat))))
a=(-27.95246,153.42909);b=(-27.9531,153.42911);d=(r['aircraftLatitude'],r['aircraftLongitude']);s=(r['targetLatitude'],r['targetLongitude'])
point('Shoreline A',*a,'shore');point('Shoreline B',*b,'shore');line('Selected shoreline mainb', [a,b],'shore','Configured sea side: left of A to B; east. Orientation not independently verified.')
point('Drone 10:04:16.463',*d,'drone',f"Heading {r['aircraftHeadingDeg']} deg; telemetry 24 ms before video frame. Ground-projected position.")
point('Surfer LoRa GPS',*s,'surfer',f"GPS age {r['targetAgeMs']} ms. GPS position, not a visually measured surfer position.")
line('Drone to surfer GPS: 33.23 m',[d,s],'bearing',f"Bearing {r['subjectBearingDeg']:.2f} deg; relative bearing {r['relativeBearingDeg']:.2f} deg.")
line('Drone heading 170.90 deg',[d,offset(*d,r['aircraftHeadingDeg'],45)],'heading')
mid=((a[0]+b[0])/2,(a[1]+b[1])/2);line('Configured seaward direction 88.42 deg',[mid,offset(*mid,88.418802413,50)],'heading')
p=pathlib.Path('exports/vt403/run_maps/VT-4.0.3-sideways-left-100416-snapshot.kml');p.parent.mkdir(parents=True,exist_ok=True);E.ElementTree(root).write(p,encoding='utf-8',xml_declaration=True);E.parse(p);print(p.resolve());print('Validated XML; 4 points and 4 lines')
