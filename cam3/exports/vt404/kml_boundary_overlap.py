import runpy
v=runpy.run_path('exports/vt404/update_route_kml.py')
globals().update({k:x for k,x in v.items() if not k.startswith('__')})
p2=point_named('P2');p3=point_named('P3 — destination')
for p in list(doc.findall(tag('Placemark'))):
    name=p.findtext(tag('name')) or ''
    if name.startswith(('MAGENTA:', 'R —', 'YELLOW:')):
        doc.remove(p)
original_anchor=anchor
anchor=geo(-20*math.cos(sea),-20*math.sin(sea))
# Functions inherited from runpy retain that module's global dictionary.
boundary.__globals__['anchor']=anchor
for pm in doc.findall(tag('Placemark')):
    if pm.findtext(tag('name'))=='Central boundary — beachward crossing prohibited':
        pm.find(tag('name')).text='Historical central boundary — hidden for comparison'
        ET.SubElement(pm,tag('visibility')).text='0'
mark('RED: hypothetical boundary 20 m from S0','Assumed boundary for overlap scenario, parallel to original shoreline; not the recorded boundary.',[geo(-20*math.cos(sea)+q*150*(-math.sin(sea)),-20*math.sin(sea)+q*150*math.cos(sea)) for q in [-1,1]],'boundary')
route=[d,p1,p2,p3]
results=[]
for i,(u,w) in enumerate(zip(route,route[1:])):
    cl=clearance(u,w);bd=min(boundary(u),boundary(w))
    assert cl>=25
    results.append({'leg':['D → P1','P1 → P2','P2 → P3'][i],'minimum_S0_clearance_m':cl,'minimum_boundary_seaward_m':bd})
mark('RED: original continuation is prohibited with assumed boundary',
     'P3 is beachward of the hypothetical boundary. No clockwise or anticlockwise route can reach unchanged P3. Red line is a prohibited illustration, not a proposed movement. '+str(results),route,'boundary')
mark('MAGENTA: 4.0.5 holds at D — P3 blocked',f'P3 is {-boundary(p3):.3f} m beachward of the hypothetical boundary. Hold and aim; log destination_central_boundary. No positioning route starts.',[d],'proposedRecovery',False)
# Show exact boundary/circle intersection points using the same local geometry.
an,ae=off(anchor);sn,se=math.cos(sea),math.sin(sea)
signed_anchor=an*sn+ae*se
assert abs(signed_anchor)<25, 'Original boundary does not intersect the 25 m circle'
tn,te=-se,sn
span=math.sqrt(25**2-signed_anchor**2)
cross=[geo(signed_anchor*sn+q*span*tn,signed_anchor*se+q*span*te) for q in [-1,1]]
for i,p in enumerate(cross):
    mark('Boundary / 25 m circle intersection '+str(i+1),'The hypothetical central boundary cuts through the proposed 25 m clearance circle. The route must satisfy both constraints.',[p],'anchor',False)
s=ET.SubElement(doc,tag('Style'),id='overlapArea')
ps=ET.SubElement(s,tag('PolyStyle'));ET.SubElement(ps,tag('color')).text='660000ff'
ls=ET.SubElement(s,tag('LineStyle'));ET.SubElement(ls,tag('color')).text='ff0000ff'
# Circle cap on the prohibited beachward side, closed along the boundary chord.
theta=math.atan2(se,sn);alpha=math.acos(signed_anchor/25)
cap=[geo(25*math.cos(theta+alpha+(2*math.pi-2*alpha)*i/180),25*math.sin(theta+alpha+(2*math.pi-2*alpha)*i/180)) for i in range(181)]
cap.append(cap[0])
p=ET.SubElement(doc,tag('Placemark'));ET.SubElement(p,tag('name')).text='RED SHADE: circle portion beachward of boundary'
ET.SubElement(p,tag('description')).text='Hypothetical boundary moved to 20 m from S0 to demonstrate overlap. Both this cap and the rest of the 25 m circle are excluded from ordinary positioning.'
ET.SubElement(p,tag('styleUrl')).text='#overlapArea'
g=ET.SubElement(p,tag('Polygon'));ET.SubElement(g,tag('tessellate')).text='1';ob=ET.SubElement(g,tag('outerBoundaryIs'));lr=ET.SubElement(ob,tag('LinearRing'));ET.SubElement(lr,tag('coordinates')).text=' '.join(f'{lon:.12f},{lat:.12f},0' for lat,lon in cap)
doc.find(tag('name')).text='VT 4.0.5 proposal — boundary / clearance overlap'
doc.find(tag('description')).text='Hypothetical boundary is 20 m from S0, intersecting the 25 m circle. Unchanged P3 is beyond this boundary, so neither direction is permitted. Magenta D shows hold; red route is prohibited. Original boundary is retained hidden. Yellow: proposed 25 m circle. Orange: historical 27 m circle. Blue: historical planned route. Red shade: beachward circle cap. Proposed illustration only, not flown or implemented. Horizontal ground-clamped geometry.'
out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt404/VT-route-220255-minus30-recovery-405-overlap.kml')
tree.write(out,encoding='utf-8',xml_declaration=True);ET.parse(out)
print(json.dumps({'output':str(out),'S0_boundary_signed_m':boundary(c),'legs':results},indent=2))

