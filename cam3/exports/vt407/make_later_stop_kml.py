from pathlib import Path
import json,math,xml.etree.ElementTree as E
rows=[json.loads(s) for s in Path('C:/Users/gildo/temp/vt 407/cam3_full_2026-10-08_065204_691_e7bc5a17.jsonl').read_text(encoding='utf-8-sig').splitlines()]
ms=[r for r in rows if r.get('event')=='movement_cycle'];aa={r['cycleId']:r for r in rows if r.get('event')=='aiming_cycle'}
start=next(r for r in ms if r.get('journeyId')==2 and r.get('plannerEvent')=='fixed_route_planned')
stop=next(r for r in ms if r.get('journeyId')==2 and r.get('reason')=='start_beachward_of_boundary')
c=next(r for r in rows if r.get('event')=='vt40_configuration');settings=json.loads(c['effectiveSettingsJson'])
profile=next(p for p in json.loads(c['effectiveWaveLinesJson'])['waveLines'] if p['id']==settings['waveLineId'])
R=6371000
def point(lat,lon,n,e):return lat+math.degrees(n/R),((lon+math.degrees(e/(R*math.cos(math.radians(lat))))+540)%360)-180
def xy(lat,lon,base):return math.radians(lat-base[0])*R,math.radians(lon-base[1])*R*math.cos(math.radians(base[0]))
origin=(stop['retreatBoundaryLatitude'],stop['retreatBoundaryLongitude']);b=math.radians(stop['seawardBearingDeg']);sea=(math.cos(b),math.sin(b));along=(-sea[1],sea[0])
def bd(p):n,e=xy(*p,origin);return n*sea[0]+e*sea[1]
def aircraft(m):a=aa[m['cycleId']];return a['aircraftLatitude'],a['aircraftLongitude']
d0=aircraft(start);d1=aircraft(stop);s0=(stop['routeSurferLatitude'],stop['routeSurferLongitude']);s1=(aa[stop['cycleId']]['targetLatitude'],aa[stop['cycleId']]['targetLongitude']);p3=(stop['approachTargetLatitude'],stop['approachTargetLongitude'])
assert abs(bd(d1)-stop['failedSegmentBoundaryM'])<.002
ns='http://www.opengis.net/kml/2.2';E.register_namespace('',ns)
def node(parent,name,text=None):x=E.SubElement(parent,'{'+ns+'}'+name);x.text=text;return x
root=E.Element('{'+ns+'}kml');doc=node(root,'Document');node(doc,'name','VT 407 — journey 2 moved, then boundary lockout')
node(doc,'description','Snapshot 2026-10-08 06:53:24.640 Australia/Brisbane. Initial hold is accepted by design and is not the issue illustrated here. Journey 2 was planned at 06:53:23.166. The reported drone position crossed the original central boundary; remaining leg and recovery were rejected despite saved destination remaining sea-side. No route is invented.')
colors={'boundary':'ff0000ff','wave':'ffffff00','start':'ff00ffff','stop':'ffff00ff','target':'ffff6600','s0':'ff00aaff','s1':'ff00ff00','trace':'ffffffff','execution':'ff00aaff','planning':'ff00ffff','sea':'ff00aa00'}
for name,color in colors.items():
 st=node(doc,'Style');st.set('id',name);ls=node(st,'LineStyle');node(ls,'color',color);node(ls,'width','4' if name=='boundary' else '2')
 it=node(st,'IconStyle');node(it,'color',color);node(it,'scale','1.1');icon=node(it,'Icon');node(icon,'href','https://maps.google.com/mapfiles/kml/paddle/wht-blank.png')
def coords(ps):return ' '.join(f'{lo:.12f},{la:.12f},0' for la,lo in ps)
def pm(name,style,desc):x=node(doc,'Placemark');node(x,'name',name);node(x,'description',desc);node(x,'styleUrl','#'+style);return x
def mark(name,style,p,desc):x=node(pm(name,style,desc),'Point');node(x,'altitudeMode','clampToGround');node(x,'coordinates',coords([p]))
def line(name,style,ps,desc):x=node(pm(name,style,desc),'LineString');node(x,'tessellate','1');node(x,'altitudeMode','clampToGround');node(x,'coordinates',coords(ps))
line('RED — original central boundary','boundary',[point(*origin,along[0]*d,along[1]*d) for d in [-130,130]],'Unmoved boundary, parallel to the saved Wave line. Positive distance is the permitted sea side. Satellite imagery does not define the side.')
mark('Original central / boundary anchor','boundary',origin,'Captured at 06:52:08; not the start of journey 2.')
pa=(profile['pointA']['latitude'],profile['pointA']['longitude']);pb=(profile['pointB']['latitude'],profile['pointB']['longitude'])
line('CYAN — Wave line / shoreline reference, ovrob2','wave',[pa,pb],'Actual selected A/B segment; sea side leftOfAToB. Not a physical beach outline.')
mark('Wave line A','wave',pa,'Saved A');mark('Wave line B','wave',pb,'Saved B')
mark(f'D0 — journey starts, {bd(d0):+.3f} m sea-side','start',d0,'Reported aircraft at 06:53:23.166. Route passed planning. Direct surfer distance 50.386 m. A new direct positioning journey started here.')
mark(f'D1 — STOPPED, {abs(bd(d1)):.3f} m beachward','stop',d1,'Reported aircraft at 06:53:24.640. VT commands zero forward/right. Failed rule: route_central_boundary. Recovery result: start_beachward_of_boundary. GPS data alone cannot separate physical drift from positioning noise.')
mark(f'P3 — saved destination, {bd(p3):+.3f} m sea-side','target',p3,'Same frozen destination planned at 06:53:23.166: 30 m from S0 at −45°. Destination is permitted by the actual boundary; the reported D1 starting position is what blocks the remaining leg.')
mark('S0 — captured surfer for this journey','s0',s0,'Frozen routing reference. Required execution clearance 19 m, new planning clearance 21 m. D1 distance from S0 is 48.396 m.')
mark('S1 — current surfer, 50.495 m from D1','s1',s1,'Live surfer at 06:53:24.640. Retreat threshold 19 m, so retreat is not triggered. S1 does not replace S0/P3 of this active journey.')
line('BLUE — original permitted planned leg D0 to P3','target',[d0,p3],'Initially accepted straight movement leg. The full leg was not completed.')
trace=[aircraft(m) for m in ms if start['cycleId']<=m['cycleId']<=stop['cycleId'] and m.get('journeyId')==2]
line('WHITE — reported drone track before stop','trace',trace,'Actual logged aircraft positions from route planning to first boundary stop; not a simulated trajectory.')
line('MAGENTA — remaining D1 to P3, BLOCKED','stop',[d1,p3],'Explanatory remaining leg; not flown after the stop. It points toward a permitted destination, but current implementation rejects a beachward start before permitting recovery.')
line('GREEN — current surfer separation 50.495 m','s1',[d1,s1],'Measurement only, not a route. Shows current surfer is outside the 19 m retreat trigger.')
for radius,style,label in [(19,'execution','S0 routing execution clearance — 19 m'),(21,'planning','S0 routing planning clearance — 21 m')]:
 circle=[point(*s0,radius*math.cos(math.radians(i)),radius*math.sin(math.radians(i))) for i in range(0,361,3)]
 line(label,style,circle,'Circle around captured S0, not current S1. This is surfer clearance, not a reserve from the central boundary.')
foot=point(*d1,-bd(d1)*sea[0],-bd(d1)*sea[1]);line('D1 boundary overrun — 0.284 m','stop',[d1,foot],'Small perpendicular reported overrun. Drawn at true scale; labels explain it when too small to see.')
arrow_start=point(*origin,along[0]*-100,along[1]*-100);arrow_end=point(*arrow_start,sea[0]*25,sea[1]*25)
line('Direction toward permitted sea side','sea',[arrow_start,arrow_end],'Direction marker, not proposed or executed recovery movement.');mark('SEA SIDE — permitted','sea',arrow_end,'Positive boundary side')
look=node(doc,'LookAt');center=((d1[0]+s0[0]+origin[0])/3,(d1[1]+s0[1]+origin[1])/3);node(look,'latitude',str(center[0]));node(look,'longitude',str(center[1]));node(look,'range','340');node(look,'tilt','0');node(look,'heading','0')
out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt407/VT-407-journey2-boundary-stop-065324.kml');E.indent(root);E.ElementTree(root).write(out,encoding='utf-8',xml_declaration=True);E.parse(out)
print(out);print(f'D0={bd(d0):+.3f} m; D1={bd(d1):+.3f} m; P3={bd(p3):+.3f} m; track has {len(trace)} reported samples')
