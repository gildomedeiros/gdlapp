import zipfile,json,collections
z=zipfile.ZipFile('C:/Users/gildo/temp/405 test/cam3_full_2026-10-06_183437_420_649a64eb.zip')
for name in z.namelist():
 if not name.endswith('.jsonl'):continue
 rows=[json.loads(x) for x in z.read(name).splitlines() if x.strip()]
 ev=collections.Counter(r['event'] for r in rows);ms=[r for r in rows if r['event']=='movement_cycle'];configs=[r for r in rows if r['event']=='vt40_configuration']
 print(name,len(rows),'versions',[r.get('version') for r in rows if r['event']=='session_start'])
 print('events',dict(ev));print('reasons',dict(collections.Counter(r.get('reason') for r in ms)))
 for r in configs: print('config',r)
 for r in rows:
  if r['event']=='journey_outcome' or r['event']=='movement_cycle' and r.get('plannerEvent') in ['fixed_route_planned','fixed_route_replanned']:
   print('ROUTE',json.dumps(r))
