import math,xml.etree.ElementTree as E
from pathlib import Path
ns='http://www.opengis.net/kml/2.2';E.register_namespace('',ns)
def t(n):return '{'+ns+'}'+n
root=E.Element(t('kml'));doc=E.SubElement(root,t('Document'))
E.SubElement(doc,t('name')).text='VT 4.0.5 — route around blocked boundary passage'
E.SubElement(doc,t('description')).text='Hypothetical geometry matching the user sketch, not recorded flight coordinates. S0 is retained as map origin; drone and P3 are illustrative. Boundary runs north-south 24 m east of S0, west permitted. 25 m clearance circle overlaps boundary by 1 m. Magenta proposed route uses west side. Ground-clamped horizontal geometry; not an implemented or flown route.'
lat,lon=-28.08875,153.40753;R=6371000
def geo(p):n,e=p;return f'{lon+math.degrees(e/(R*math.cos(math.radians(lat)))):.12f},{lat+math.degrees(n/R):.12f},0'
for name,color,width in [('route','ffff00ff',5),('boundary','ff0000ff',4),('circle','ff00ffff',3),('green','ff00ff00',3)]:
 s=E.SubElement(doc,t('Style'),id=name);ls=E.SubElement(s,t('LineStyle'));E.SubElement(ls,t('color')).text=color;E.SubElement(ls,t('width')).text=str(width)
 ic=E.SubElement(s,t('IconStyle'));E.SubElement(ic,t('color')).text=color;icon=E.SubElement(ic,t('Icon'));E.SubElement(icon,t('href')).text='https://maps.google.com/mapfiles/kml/shapes/placemark_circle.png'
def mark(name,pts,style,desc='',line=True):
 p=E.SubElement(doc,t('Placemark'));E.SubElement(p,t('name')).text=name;E.SubElement(p,t('description')).text=desc;E.SubElement(p,t('styleUrl')).text='#'+style
 g=E.SubElement(p,t('LineString' if line else 'Point'))
 if line:E.SubElement(g,t('tessellate')).text='1'
 E.SubElement(g,t('coordinates')).text=' '.join(geo(x) for x in pts)
start=(35,5);end=(-35,5);radius=28
ring=[(radius*math.cos(math.radians(a)),radius*math.sin(math.radians(a))) for a in range(0,-181,-10)]
route=[start]+ring+[end]
def clearance(a,b):
 n,e=a;dn,de=b[0]-n,b[1]-e;q=max(0,min(1,-(n*dn+e*de)/(dn*dn+de*de)));return math.hypot(n+q*dn,e+q*de)
for a,b in zip(route,route[1:]):assert clearance(a,b)>=25 and max(a[1],b[1])<=24
mark('RED boundary — WEST / left side permitted',[(70,24),(-70,24)],'boundary','Hypothetical central boundary; circle overlaps it by 1 m. The complete magenta route stays west of this line.')
mark('Boundary anchor — hypothetical central point',[(55,24)],'boundary',line=False)
mark('Permitted direction',[(55,24),(55,-15)],'green')
mark('S0 — captured surfer',[(0,0)],'green',line=False)
mark('25 m clearance circle',[(25*math.cos(i*math.pi/90),25*math.sin(i*math.pi/90)) for i in range(181)],'circle','25 m retreat threshold, zero extra path buffer, centred on captured S0.')
mark('Drone — hypothetical start',[start],'route',line=False)
mark('P3 — safe saved destination',[end],'route','19 m on permitted side; 35.36 m from S0.',False)
mark('MAGENTA: proposed route around LEFT side',route,'route','Straight legs via illustrative intermediate waypoints, all outside the 25 m circle and west of boundary. 28 m waypoint radius is illustrative route geometry, not an additional blocking buffer. This is a valid candidate, not an exact output of the unimplemented 4.0.5 planner.')
for i,p in enumerate([ring[0],ring[9],ring[-1]]):mark(['Entry waypoint','Left-side waypoint','Exit waypoint'][i],[p],'route',line=False)
for n in [-7,7]:mark('Circle / boundary intersection',[(n,24)],'boundary',line=False)
out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt404/VT-405-sketch-left-around.kml');E.ElementTree(root).write(out,encoding='utf-8',xml_declaration=True);E.parse(out)
print(str(out));print('Minimum leg clearance: %.3f m'%min(clearance(a,b) for a,b in zip(route,route[1:])))
