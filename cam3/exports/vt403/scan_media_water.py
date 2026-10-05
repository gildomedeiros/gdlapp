import zipfile,json,collections
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');counts=collections.Counter();samples={}
for n in z.namelist():
 if not n.endswith('.jsonl'):continue
 for l in z.open(n):
  r=json.loads(l);e=r.get('event','')
  if any(x in e for x in ['record','camera','video','media','ride']):
   counts[e]+=1
   if e not in samples:samples[e]=r
print(counts)
for e,r in samples.items():print(e,json.dumps(r)[:2500])
