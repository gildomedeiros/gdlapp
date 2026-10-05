import zipfile,json,datetime,pathlib,xml.etree.ElementTree as E,math
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_095812_' in n);times=['10:15:52.678','10:15:57.682'];targets={s:datetime.datetime.fromisoformat('2026-10-04T'+s+'+10:00').timestamp()*1000 for s in times};best={};conf=None
for l in z.open(n):
 r=json.loads(l)
 if r.get('event')=='vt40_configuration':conf=r
 if r.get('event')=='aiming_cycle':
  for s,t in targets.items():
   d=abs(r['epochMs']-t)
   if s not in best or d<best[s][0]:best[s]=(d,r)
settings=json.loads(conf['effectiveSettingsJson']);shore=next(s for s in json.loads(conf['effectiveShorelinesJson'])['shorelines'] if s['id']==settings['shorelineId'])
ns='http://www.opengis.net/kml/2.2';E.register_namespace('',ns)
def tag(p,n,v=None):
 q=E.SubElement(p,'{'+ns+'}'+n)
 if v is not None:q.text=str(v)
 return q
root=E.Element('{'+ns+'}kml');doc=tag(root,'Document');tag(doc,'name','Sideways-left snapshots 10:15:52.678 and 10:15:57.682')
for name,col in [('shore','ffffff00'),('first','ff00ffff'),('second','ff0000ff'),('drone','ffff8000')]:
 st=tag(doc,'Style');st.set('id',name);ls=tag(st,'LineStyle');tag(ls,'color',col);tag(ls,'width',3);ic=tag(st,'IconStyle');tag(ic,'color',col);tag(ic,'scale',1.1)
def pm(parent,name,style,desc=''):
 p=tag(parent,'Placemark');tag(p,'name',name);tag(p,'styleUrl','#'+style);tag(p,'description',desc);return p
def point(parent,name,lat,lon,style,desc=''):
 p=pm(parent,name,style,desc);g=tag(p,'Point');tag(g,'coordinates',f'{lon},{lat},0')
def line(parent,name,coords,style,desc=''):
 p=pm(parent,name,style,desc);g=tag(p,'LineString');tag(g,'tessellate',1);tag(g,'coordinates',' '.join(f'{lon},{lat},0' for lat,lon in coords))
def offset(lat,lon,bearing,m):
 b=math.radians(bearing);return lat+math.degrees(m*math.cos(b)/6371000),lon+math.degrees(m*math.sin(b)/(6371000*math.cos(math.radians(lat))))
a=tuple(shore['pointA'][k] for k in ['latitude','longitude']);b=tuple(shore['pointB'][k] for k in ['latitude','longitude']);point(doc,'Shoreline A',*a,'shore');point(doc,'Shoreline B',*b,'shore');line(doc,'Selected shoreline '+shore['name'],[a,b],'shore','Configured sea side: '+shore['seaSide']+'. Orientation not independently verified.')
for i,s in enumerate(times):
 dlt,r=best[s];style=['first','second'][i];folder=tag(doc,'Folder');tag(folder,'name',s+' snapshot');d=(r['aircraftLatitude'],r['aircraftLongitude']);target=(r['targetLatitude'],r['targetLongitude']);desc=f"Frame 2026-10-04 {s}; telemetry {r['timestamp']}; offset {dlt} ms. GPS age {r['targetAgeMs']} ms. Ground-projected points; visible surfer position not measured."
 point(folder,'Drone '+s,*d,'drone',desc);point(folder,'Surfer GPS '+s,*target,style,desc)
 line(folder,f"Separation {r['distanceM']:.2f} m - {s}",[d,target],style,desc)
 line(folder,f"Drone heading {r['aircraftHeadingDeg']:.1f} deg - {s}",[d,offset(*d,r['aircraftHeadingDeg'],40)],'drone',f"GPS bearing {r['subjectBearingDeg']:.2f} deg; relative bearing {r['relativeBearingDeg']:.2f} deg")
p=pathlib.Path('exports/vt403/run_maps/VT-4.0.3-sideways-left-101552-101557.kml');E.ElementTree(root).write(p,encoding='utf-8',xml_declaration=True);E.parse(p);print(p.resolve());print('Validated XML: shoreline A/B, two drone/surfer pairs, separation and heading lines.')
