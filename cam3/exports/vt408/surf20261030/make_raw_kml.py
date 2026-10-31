import json,math,csv,xml.etree.ElementTree as E
from pathlib import Path
src=Path('C:/Users/gildo/temp/311026/cam3_full_2026-10-30_135752_696_d8a69d1a.jsonl');out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408/surf20261030');gps=[];aim=[];orientation={}
for l in src.open():
 x=json.loads(l);t=x['timestamp']
 if not ('2026-10-30T14:16:00'<=t<'2026-10-30T14:18:00'):continue
 if x['event']=='lora_packet':gps.append(x)
 if x['event']=='aiming_cycle':aim.append(x)
 if x['event']=='orientation_cycle':orientation[x['cycleId']]=x
assert gps and aim
N='http://www.opengis.net/kml/2.2';E.register_namespace('',N)
def el(p,n,t=None,**kw):
 z=E.SubElement(p,'{'+N+'}'+n,kw)
 if t is not None:z.text=str(t)
 return z
root=E.Element('{'+N+'}kml');doc=el(root,'Document');el(doc,'name','VT408 recorded GPS and heading 14:16–14:18 Brisbane');el(doc,'description','30 October 2026 UTC+10. Source: '+src.name+'. Green points: EVERY received lora_packet in interval, including sequence/acceptance metadata. Blue points: EVERY aiming_cycle aircraft position snapshot; repetitions retained. No positions interpolated, no path lines across GPS gaps. Heading arrows are calculated graphical indicators of LOGGED aircraftHeadingDeg, length 8m for display only, not measured travel or destinations. Arrow folders initially hidden to avoid clutter; turn on an individual timestamp arrow. Phone reception/log time is not GPS acquisition time. aircraftFixMs/aircraftAgeMs retained exactly. Coordinates plotted at ground level solely for map display. Surfer=reported transmitter GPS, not independently verified physical surfer position. No clock offsets applied.')
for key,color,icon in [('surfer','ff00ff00','placemark_circle.png'),('drone','ffff8000','placemark_circle.png')]:
 st=el(doc,'Style',id=key);ic=el(st,'IconStyle');el(ic,'color',color);el(ic,'scale',.45);el(el(ic,'Icon'),'href','https://maps.google.com/mapfiles/kml/shapes/'+icon);el(el(st,'LabelStyle'),'scale',0)
st=el(doc,'Style',id='heading');ls=el(st,'LineStyle');el(ls,'color','ff00ffff');el(ls,'width',3)
look=el(doc,'LookAt');el(look,'longitude',aim[0]['aircraftLongitude']);el(look,'latitude',aim[0]['aircraftLatitude']);el(look,'range',240);el(look,'tilt',0)
def folder(p,name,vis=1):
 f=el(p,'Folder');el(f,'name',name);el(f,'visibility',vis);return f
sf=folder(doc,f'SURFER — all {len(gps)} received GPS packets (green)');df=folder(doc,f'DRONE — all {len(aim)} logged snapshots (blue)');hf=folder(doc,'DRONE HEADING — every changed logged position/heading; select individual arrows',0)
def placemark(f,name,x,style):
 p=el(f,'Placemark');el(p,'name',name);el(p,'description',json.dumps(x,indent=2));el(p,'styleUrl','#'+style);ed=el(p,'ExtendedData')
 for k,v in x.items():
  if v is not None:el(el(ed,'Data',name=k),'value',v)
 el(el(p,'TimeStamp'),'when',x['timestamp']);return p
def pt(f,name,x,la,lo,style):
 p=placemark(f,name,x,style);el(el(p,'Point'),'coordinates',f'{lo},{la},0')
for x in gps:pt(sf,f'GPS #{x["sequence"]} — {x["timestamp"][11:23]}',x,x['latitude'],x['longitude'],'surfer')
seen=set();headingcount=0
for x in aim:
 if x.get('aircraftLatitude') is None or x.get('aircraftLongitude') is None:continue
 pt(df,f'DRONE {x["timestamp"][11:23]} — heading {x["aircraftHeadingDeg"]}°',x,x['aircraftLatitude'],x['aircraftLongitude'],'drone')
 key=(x['aircraftFixMs'],x['aircraftLatitude'],x['aircraftLongitude'],x['aircraftHeadingDeg'])
 if key in seen:continue
 seen.add(key);headingcount+=1
 f=folder(hf,f'{x["timestamp"][11:23]} — heading {x["aircraftHeadingDeg"]}°',1);p=placemark(f,f'Heading {x["aircraftHeadingDeg"]}° at {x["timestamp"][11:23]}',x,'heading');la=x['aircraftLatitude'];lo=x['aircraftLongitude'];h=x['aircraftHeadingDeg']
 def dest(la,lo,b,m):
  b=math.radians(b);return (la+math.degrees(m*math.cos(b)/6371000),lo+math.degrees(m*math.sin(b)/(6371000*math.cos(math.radians(la)))))
 tip=dest(la,lo,h,8);left=dest(*tip,h+155,2);right=dest(*tip,h-155,2)
 mg=el(p,'MultiGeometry')
 for pts in [[(la,lo),tip],[left,tip,right]]:el(el(mg,'LineString'),'coordinates',' '.join(f'{b},{a},0' for a,b in pts))
# Sequence gaps are text-only events, with no invented locations or connecting lines.
gf=folder(doc,'RECEPTION GAPS — text only, no positions')
gaps=[]
for a,b in zip(gps,gps[1:]):
 if b['sequence']-a['sequence']>1:
  z={'timestamp':b['timestamp'],'previousPacket':a['sequence'],'nextPacket':b['sequence'],'previousReceived':a['timestamp'],'nextReceived':b['timestamp'],'missingBefore':b.get('missingBefore'),'note':'No coordinates supplied for missing packets; none interpolated.'};gaps.append(z);placemark(gf,f'#{a["sequence"]} → #{b["sequence"]}: {a["timestamp"][11:23]}–{b["timestamp"][11:23]}',z,'surfer')
path=out/'VT408-recorded-GPS-heading-141600-141800.kml';E.ElementTree(root).write(path,encoding='utf-8',xml_declaration=True);E.parse(path)
for name,rows in [('surfer-packets-141600-141800.csv',gps),('drone-snapshots-141600-141800.csv',aim)]:
 keys=list(dict.fromkeys(k for x in rows for k in x))
 with (out/name).open('w',newline='',encoding='utf-8-sig') as f:w=csv.DictWriter(f,fieldnames=keys);w.writeheader();w.writerows(rows)
(out/'GPS-heading-141600-141800-verification.json').write_text(json.dumps({'source':str(src),'startInclusive':'2026-10-30T14:16:00+10:00','endExclusive':'2026-10-30T14:18:00+10:00','surferPackets':len(gps),'droneSnapshots':len(aim),'changedHeadingPositionSamples':headingcount,'sequenceGapEvents':len(gaps),'firstSurferPacket':gps[0]['sequence'],'lastSurferPacket':gps[-1]['sequence'],'kml':str(path),'interpolatedPositions':0,'clockOffsetApplied':0},indent=2))
print(path);print('GPS',len(gps),'drone',len(aim),'heading',headingcount,'gap events',len(gaps))
