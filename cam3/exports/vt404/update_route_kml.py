import json, math, zipfile, xml.etree.ElementTree as ET
from pathlib import Path
ns='http://www.opengis.net/kml/2.2'
ET.register_namespace('',ns)
def tag(n): return '{'+ns+'}'+n
tree=ET.parse('C:/Users/gildo/Downloads/VT-route-220255-minus30.kml')
doc=tree.getroot().find(tag('Document'))
def point_named(name):
    for p in doc.findall(tag('Placemark')):
        if p.findtext(tag('name'))==name:
            lon,lat,_=map(float,p.find('.//'+tag('coordinates')).text.split(','));return (lat,lon)
c=point_named('Surfer — planning snapshot');p1=point_named('P1');anchor=point_named('Boundary anchor — first central capture')
z=zipfile.ZipFile('C:/Users/gildo/temp/vt404test/cam3_full_2026-10-05_220638_442_05e26c81.zip')
rows=[json.loads(x) for x in z.read('cam3_full_2026-10-05_220208_476_1f1cb1c6.jsonl').splitlines()]
a=next(r for r in rows if r['event']=='aiming_cycle' and r.get('cycleId')==486)
m=next(r for r in rows if r['event']=='movement_cycle' and r.get('cycleId')==486)
d=(a['aircraftLatitude'],a['aircraftLongitude'])
R=6371000
def off(p):return (math.radians(p[0]-c[0])*R,math.radians(p[1]-c[1])*R*math.cos(math.radians(c[0])))
def geo(n,e):return (c[0]+math.degrees(n/R),c[1]+math.degrees(e/(R*math.cos(math.radians(c[0])))))
def distance(p):return math.hypot(*off(p))
def clearance(a,b):
    n,e=off(a);bn,be=off(b);dn,de=bn-n,be-e;t=max(0,min(1,-(n*dn+e*de)/(dn*dn+de*de))) if dn*dn+de*de else 0
    return math.hypot(n+t*dn,e+t*de)
sea=math.radians(m['seawardBearingDeg'])
def boundary(p):
    n=(p[0]-anchor[0])*math.pi/180*R;e=(p[1]-anchor[1])*math.pi/180*R*math.cos(math.radians(anchor[0]));return n*math.cos(sea)+e*math.sin(sea)
n,e=off(d);radius=distance(p1);recovery=geo(n*radius/distance(d),e*radius/distance(d))
def style(name,color,width=4):
    s=ET.SubElement(doc,tag('Style'),id=name)
    for typ in ['LineStyle','IconStyle']:
        t=ET.SubElement(s,tag(typ));ET.SubElement(t,tag('color')).text=color
        if typ=='LineStyle':ET.SubElement(t,tag('width')).text=str(width)
style('proposedRecovery','ffff00ff',5);style('noBuffer','ff00ffff',2)
def mark(name,desc,points,sty,line=True):
    p=ET.SubElement(doc,tag('Placemark'));ET.SubElement(p,tag('name')).text=name;ET.SubElement(p,tag('description')).text=desc;ET.SubElement(p,tag('styleUrl')).text='#'+sty
    g=ET.SubElement(p,tag('LineString' if line else 'Point'))
    if line:ET.SubElement(g,tag('tessellate')).text='1'
    ET.SubElement(g,tag('coordinates')).text=' '.join(f'{lon:.12f},{lat:.12f},0' for lat,lon in points)
for p in doc.findall(tag('Placemark')):
    if p.findtext(tag('name'))=='27 m planned surfer-clearance circle':p.find(tag('name')).text='Historical 27 m clearance — original test setting'
mark('25 m clearance — proposed zero extra buffer','Proposed assumption: 25 m retreat threshold + 0 m extra path clearance. Centred on captured S0.',[geo(25*math.cos(i*math.pi/60),25*math.sin(i*math.pi/60)) for i in range(121)],'noBuffer')
mark('D — actual reported stop position, 22:03:00.831',f'{distance(d):.3f} m from S0. D to P1 minimum clearance {clearance(d,p1):.3f} m: passes the proposed 25 m rule.','' if False else [d],'drone',False)
mark('R — illustrative recovery waypoint',f'Radially outward from D to the existing P1 ring radius of {radius:.3f} m. Illustration only; this extra leg is not necessary at a 25 m minimum.',[recovery],'proposedRecovery',False)
mark('MAGENTA: illustrative extra leg D → R',f'Only increases distance from S0. Length {radius-distance(d):.3f} m; minimum clearance {clearance(d,recovery):.3f} m. Not flown.',[d,recovery],'proposedRecovery')
mark('MAGENTA: reconnect R → P1',f'Minimum S0 clearance {clearance(recovery,p1):.3f} m. Then follow the original blue P1 → P2 → P3 route. Not flown.',[recovery,p1],'proposedRecovery')
mark('YELLOW: D → P1 already allowed without 2 m buffer',f'Minimum clearance {clearance(d,p1):.3f} m exceeds 25 m. Therefore this recorded stop would no longer require an extra leg under the proposed rule.',[d,p1],'noBuffer')
for u,v in [(d,recovery),(recovery,p1),(d,p1)]:
    assert clearance(u,v)>=25 and min(boundary(u),boundary(v))>=0
doc.find(tag('description')).text+=' Overlay: zero extra buffer, yellow 25 m circle and permitted D–P1 connector; magenta optional recovery illustration, not a recorded or implemented route. Original blue route and historical 27 m circle retained.'
out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt404/VT-route-220255-minus30-recovery.kml');tree.write(out,encoding='utf-8',xml_declaration=True);ET.parse(out)
print(json.dumps({'output':str(out),'extra_leg_m':radius-distance(d),'D_R_clearance':clearance(d,recovery),'R_P1_clearance':clearance(recovery,p1),'D_P1_clearance':clearance(d,p1),'R_boundary_seaward_m':boundary(recovery)},indent=2))
