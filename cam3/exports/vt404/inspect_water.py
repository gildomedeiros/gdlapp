import zipfile,json,collections
z=zipfile.ZipFile(r'C:/Users/gildo/temp/vt404test/cam3_full_2026-10-05_220638_442_05e26c81.zip')
for name in sorted(z.namelist()):
 counts=collections.Counter(); moves=collections.Counter(); configs=[]; samples={}; bad=0; times=[]
 with z.open(name) as f:
  for line in f:
   try:r=json.loads(line)
   except:bad+=1;continue
   e=r.get('event');counts[e]+=1
   if e not in samples:samples[e]=r
   if e=='vt40_configuration':configs.append(r)
   if e=='movement_cycle':moves[(r.get('session'),r.get('positioningMode'),r.get('reason'),r.get('routeKind'))]+=1
 print('\nFILE',name,'rows',sum(counts.values()),'bad',bad)
 print('events',dict(counts))
 print('moves',moves.most_common(12))
 for r in configs: print('CONFIG',r)
 if not configs:
  for e in ['session_start','app_start','full_log_open']: 
   if e in samples:print('sample',samples[e])
 if configs:
  for e in ['movement_cycle','aiming_cycle','movement_transition','retreat_cycle']:
   if e in samples:print('SCHEMA',e,samples[e])
