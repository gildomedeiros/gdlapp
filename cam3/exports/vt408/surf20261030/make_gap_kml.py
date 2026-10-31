import json,math,xml.etree.ElementTree as E
from pathlib import Path
p=Path('C:/Users/gildo/temp/311026/cam3_full_2026-10-30_135752_696_d8a69d1a.jsonl');b=a=None
for l in p.open():
 x=json.loads(l)
 if x['event']!='aiming_cycle':continue
 if b is None and x.get('targetSequence')==5083:b=x
 if x.get('targetSequence')==5091:a=x;break
assert b and a
N='http://www.opengis.net/kml/2.2';E.register_namespace('',N)
def el(parent,name,text=None,**attrs):
 z=E.SubElement(parent,'{'+N+'}'+name,attrs)
 if text is not None:z.text=str(text)
 return z
root=E.Element('{'+N+'}kml');doc=el(root,'Document');el(doc,'name','VT 408 — before and after GPS gap — 14:17:13 to 14:17:18');el(doc,'description','30 October 2026, Brisbane UTC+10. Two logged snapshots. Toggle Before/After folders. Arrow is aircraft heading, NOT movement direction. Thin line joins drone to recorded surfer fix. Positions are logged GPS coordinates, not video-derived. No interpolated surfer route. Ground-clamped for readability.')
for key,color in [('before','ff00a5ff'),('after','ffffff00')]:
 for role,icon in [('drone','placemark_circle.png'),('surfer','man.png')]:
  st=el(doc,'Style',id=key+'_'+role);ic=el(st,'IconStyle');el(ic,'color',color);el(ic,'scale',1.1);el(el(ic,'Icon'),'href','https://maps.google.com/mapfiles/kml/shapes/'+icon)
 st=el(doc,'Style',id=key+'_heading');ls=el(st,'LineStyle');el(ls,'color',color);el(ls,'width',5)
 st=el(doc,'Style',id=key+'_sight');ls=el(st,'LineStyle');el(ls,'color',color);el(ls,'width',2)
look=el(doc,'LookAt');el(look,'longitude',153.43006);el(look,'latitude',-27.95123);el(look,'altitude',0);el(look,'heading',0);el(look,'tilt',0);el(look,'range',140)
def point(f,name,lat,lon,style,desc=''):
 z=el(f,'Placemark');el(z,'name',name);el(z,'description',desc);el(z,'styleUrl','#'+style);pt=el(z,'Point');el(pt,'coordinates',f'{lon},{lat},0')
def line(f,name,pts,style):
 z=el(f,'Placemark');el(z,'name',name);el(z,'styleUrl','#'+style);ls=el(z,'LineString');el(ls,'tessellate',1);el(ls,'coordinates',' '.join(f'{lo},{la},0' for la,lo in pts))
def dest(la,lo,bearing,m):
 q=math.radians(bearing);return la+math.degrees(m*math.cos(q)/6371000),lo+math.degrees(m*math.sin(q)/(6371000*math.cos(math.radians(la))))
for key,x in [('before',b),('after',a)]:
 f=el(doc,'Folder');label=key.upper();time=x['timestamp'][11:23];el(f,'name',f'{label} — {time} Brisbane');el(f,'visibility',1);la=x['aircraftLatitude'];lo=x['aircraftLongitude'];h=x['aircraftHeadingDeg'];tip=dest(la,lo,h,12)
 desc=f"Snapshot {x['timestamp']}; surfer packet #{x['targetSequence']}; fix age {x['targetAgeMs']} ms; aircraft heading {h:.1f} degrees true; surfer {x['relativeBearingDeg']:.1f} degrees right of aircraft; horizontal separation {x['distanceM']:.2f} m. Arrow length 12 m is illustrative."
 point(f,f'{label} DRONE — {time}',la,lo,key+'_drone',desc);point(f,f'{label} SURFER — packet {x["targetSequence"]}',x['targetLatitude'],x['targetLongitude'],key+'_surfer',desc)
 line(f,f'{label} heading {h:.1f}° — arrow points forward',[(la,lo),tip],key+'_heading');line(f,'Heading arrowhead',[dest(*tip,h+155,3),tip,dest(*tip,h-155,3)],key+'_heading');point(f,f'{label} FACING {h:.1f}°',*tip,key+'_drone')
 line(f,f'{label} drone to surfer: {x["distanceM"]:.2f} m; {x["relativeBearingDeg"]:.1f}° right',[(la,lo),(x['targetLatitude'],x['targetLongitude'])],key+'_sight')
out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408/surf20261030/VT408-GPS-gap-141713-before-after.kml');E.ElementTree(root).write(out,encoding='utf-8',xml_declaration=True);E.parse(out)
print(out);print('BEFORE',b['timestamp'],b['distanceM'],b['relativeBearingDeg']);print('AFTER',a['timestamp'],a['distanceM'],a['relativeBearingDeg'])
