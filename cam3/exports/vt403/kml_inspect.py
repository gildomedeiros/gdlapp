import zipfile,json
z=zipfile.ZipFile('C:/Users/gildo/temp/4.0.3.zip')
for n in z.namelist():
 if n.endswith('.jsonl'):
  print(n)
  for l in z.open(n):
   r=json.loads(l)
   if r['event'] in ('aiming_cycle','retreat_boundary_blocked','movement_cycle') and r.get('session')==1:
    print(json.dumps(r));
    if r['event']=='aiming_cycle':break
