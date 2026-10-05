import runpy
v=runpy.run_path('exports/vt404/update_route_kml.py')
globals().update({k:x for k,x in v.items() if not k.startswith('__')})
p2=point_named('P2');p3=point_named('P3 — destination')
# Hypothetical east-west line 20 m south of S0; north is permitted.
# Rotating as well as shifting is necessary to preserve this saved P3.
sea=0.0;anchor=geo(-20,0)
boundary.__globals__.update(anchor=anchor,sea=sea)
for pm in list(doc.findall(tag('Placemark'))):
    name=pm.findtext(tag('name')) or ''
    if name.startswith(('MAGENTA:', 'R —', 'YELLOW:', 'Direction toward SEA','SEA side','Boundary anchor')):
        doc.remove(pm)
    elif name=='Central boundary — beachward crossing prohibited':
        pm.find(tag('name')).text='Historical boundary — hidden comparison'
        ET.SubElement(pm,tag('visibility')).text='0'
mark('RED: hypothetical boundary — overlaps circle, P3 safe',
     'Illustration only: east-west line 20 m south of S0, north side permitted. Original boundary orientation and location are replaced for this what-if. Saved S0, D and P1/P2/P3 are unchanged.',[geo(-20,-150),geo(-20,150)],'boundary')
mark('GREEN: permitted side of hypothetical boundary','North of the red line is permitted in this hypothetical geometry.',[anchor,geo(30,0)],'sea')
mark('Hypothetical boundary anchor','Boundary anchor for illustration, not a recorded central capture.',[anchor],'anchor',False)
route=[d,p1,p2,p3];checks=[]
for name,u,w in zip(['D → P1','P1 → P2','P2 → P3'],route,route[1:]):
    cl=clearance(u,w);bd=min(boundary(u),boundary(w));assert cl>=25 and bd>=0
    checks.append({'leg':name,'minimum_clearance_m':cl,'minimum_boundary_seaward_m':bd})
mark('MAGENTA: 4.0.5 permitted continuation D → P1 → P2 → P3',
     'Proposed 4.0.5 behaviour with zero extra buffer: each remaining saved leg passes 25 m clearance and the hypothetical boundary. Continue directly to P1, then P2 and unchanged P3; no extra escape is required here. Not flown or implemented. '+str(checks),route,'proposedRecovery')
for i,p in enumerate([geo(-20,-15),geo(-20,15)]):
    mark('Boundary / 25 m circle intersection '+str(i+1),'Hypothetical boundary crosses the clearance circle; the route avoids both prohibited regions.',[p],'anchor',False)
s=ET.SubElement(doc,tag('Style'),id='overlapArea');ps=ET.SubElement(s,tag('PolyStyle'));ET.SubElement(ps,tag('color')).text='660000ff'
ls=ET.SubElement(s,tag('LineStyle'));ET.SubElement(ls,tag('color')).text='ff0000ff'
alpha=math.acos(-20/25)
cap=[geo(25*math.cos(alpha+(2*math.pi-2*alpha)*i/180),25*math.sin(alpha+(2*math.pi-2*alpha)*i/180)) for i in range(181)];cap.append(cap[0])
pm=ET.SubElement(doc,tag('Placemark'));ET.SubElement(pm,tag('name')).text='RED SHADE: circle / beachward overlap'
ET.SubElement(pm,tag('styleUrl')).text='#overlapArea';pg=ET.SubElement(pm,tag('Polygon'));ob=ET.SubElement(pg,tag('outerBoundaryIs'));lr=ET.SubElement(ob,tag('LinearRing'));ET.SubElement(lr,tag('coordinates')).text=' '.join(f'{lon:.12f},{lat:.12f},0' for lat,lon in cap)
doc.find(tag('name')).text='VT 4.0.5 what-if — overlapping boundary, P3 safe'
doc.find(tag('description')).text='Hypothetical boundary shifted and rotated: east-west line 20 m south of captured S0, north permitted. Saved target and waypoints unchanged; angle labels refer to original recorded configuration. Magenta: permitted continuation D-P1-P2-P3. Yellow: 25 m circle. Orange: historical 27 m circle. Blue: historical planned route. Red shade: circle/beachward overlap. No extra leg needed because existing remaining legs pass. Illustration only, not actual flight or implemented 4.0.5.'
out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt404/VT-route-220255-minus30-recovery-405-overlap-safe-P3.kml');tree.write(out,encoding='utf-8',xml_declaration=True);ET.parse(out)
print(json.dumps({'output':str(out),'P3_boundary_clearance_m':boundary(p3),'legs':checks},indent=2))
